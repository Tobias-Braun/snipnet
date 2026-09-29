"""Training CLI for the learned rally model.

    python -m snipnet_ml.train data/v1 --out models [--config train.yaml] [--seed 1] [--device mps]

`data/v1` is a dataset directory from `snipnet_ml.dataset`. The run writes to `<out>/`:

- `learned-v1.<n>/`   the finished model (`model.pt` + `meta.json`), the checkpoint with the best validation loss
- `runs/learned-v1.<n>/last.pt` and `best.pt`   training checkpoints (network, optimizer, epoch) for resuming/inspection
- `runs/learned-v1.<n>/metrics.jsonl`   one JSON line per epoch: training loss, validation loss, window accuracy

Training samples random fixed-length crops of the window sequences, so a batch mixes videos and every epoch sees
different context boundaries. The seed fixes the crop sampling, the initialisation and the dropout masks; on the same
device and library versions a run is repeatable. Without a validation split (very few videos) the training loss
picks the best epoch.
"""

from __future__ import annotations

import argparse
import json
import random
from dataclasses import dataclass, fields
from pathlib import Path

import numpy as np
import torch
import yaml
from torch import nn

from snipnet_ml.dataset import Dataset, Example
from snipnet_ml.heuristic import HeuristicParams
from snipnet_ml.learned import ModelMeta, NetworkConfig, TemporalConvNet, next_version, save_model
from snipnet_ml.persons import select_device


@dataclass(frozen=True)
class TrainConfig:
    epochs: int = 30
    batch_size: int = 8
    # Windows per training crop (0.5 s each): 240 windows are two minutes of context.
    crop_windows: int = 240
    # Random crops drawn per epoch, per training video.
    crops_per_video: int = 8
    learning_rate: float = 1e-3
    weight_decay: float = 1e-2
    hidden: int = 64
    blocks: int = 4
    kernel: int = 3
    dropout: float = 0.2
    seed: int = 1

    def __post_init__(self) -> None:
        if min(self.epochs, self.batch_size, self.crop_windows, self.crops_per_video, self.hidden, self.blocks) < 1:
            raise ValueError("epochs, batch_size, crop_windows, crops_per_video, hidden and blocks must be at least 1")
        if self.learning_rate <= 0 or self.weight_decay < 0 or not 0 <= self.dropout < 1:
            raise ValueError("learning_rate must be positive, weight_decay >= 0 and dropout in [0, 1)")

    @classmethod
    def from_yaml(cls, path: str | Path) -> TrainConfig:
        """Defaults overridden by the top-level keys of a YAML mapping; unknown keys are rejected as typos."""
        # The file is chosen by the operator on the command line, so any path is intended.
        data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}  # NOSONAR
        if not isinstance(data, dict):
            raise ValueError(f"{path} must contain a YAML mapping of parameter names to values")
        unknown = sorted(set(data) - {f.name for f in fields(cls)})
        if unknown:
            raise ValueError(f"unknown training parameters in {path}: {', '.join(unknown)}")
        return cls(**data)


def feature_statistics(examples: list[Example]) -> tuple[np.ndarray, np.ndarray]:
    """Per-feature mean and standard deviation over all training windows; constant features get a std of 1."""
    stacked = np.concatenate([e.features.matrix() for e in examples])
    std = stacked.std(axis=0)
    return stacked.mean(axis=0), np.where(std < 1e-6, 1.0, std).astype(np.float32)


def _crop(inputs: np.ndarray, labels: np.ndarray, length: int, rng: np.random.Generator):
    """A random window of `length` rows; sequences shorter than that are zero-padded and masked out of the loss."""
    n = len(inputs)
    mask = np.zeros(length, dtype=np.float32)
    x = np.zeros((length, inputs.shape[1]), dtype=np.float32)
    y = np.zeros(length, dtype=np.float32)
    start = int(rng.integers(0, n - length + 1)) if n > length else 0
    take = min(n, length)
    x[:take], y[:take], mask[:take] = inputs[start : start + take], labels[start : start + take], 1.0
    return x, y, mask


def _masked_loss(logits: torch.Tensor, targets: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    per_window = nn.functional.binary_cross_entropy_with_logits(logits, targets, reduction="none")
    return (per_window * mask).sum() / mask.sum().clamp(min=1.0)


def evaluate_windows(
    network: TemporalConvNet, inputs: list[np.ndarray], labels: list[np.ndarray], device: str
) -> tuple[float, float]:
    """Mean loss and accuracy over all windows of full-length sequences."""
    network.eval()
    total_loss, correct, count = 0.0, 0, 0
    with torch.inference_mode():
        for x, y in zip(inputs, labels, strict=True):
            logits = network(torch.from_numpy(x).unsqueeze(0).to(device))[0].cpu()
            target = torch.from_numpy(y)
            total_loss += float(nn.functional.binary_cross_entropy_with_logits(logits, target, reduction="sum"))
            correct += int(((logits > 0).float() == target).sum())
            count += len(y)
    return total_loss / count, correct / count


def train(dataset: Dataset, out_dir: str | Path, config: TrainConfig | None = None, device: str | None = None) -> Path:
    """Train on the dataset's train split and return the directory of the finished model."""
    config = config or TrainConfig()
    device = device or select_device()
    train_examples = dataset.examples("train")
    if not train_examples:
        raise ValueError("the dataset has no training videos")
    val_examples = dataset.examples("val")

    random.seed(config.seed)
    np.random.seed(config.seed)
    torch.manual_seed(config.seed)
    rng = np.random.default_rng(config.seed)

    mean, std = feature_statistics(train_examples)
    train_inputs = [(e.features.matrix() - mean) / std for e in train_examples]
    train_labels = [e.labels for e in train_examples]
    val_inputs = [(e.features.matrix() - mean) / std for e in val_examples]
    val_labels = [e.labels for e in val_examples]

    network_config = NetworkConfig(
        input_dim=len(mean), hidden=config.hidden, blocks=config.blocks, kernel=config.kernel, dropout=config.dropout
    )
    network = TemporalConvNet(network_config).to(device)
    optimizer = torch.optim.AdamW(network.parameters(), lr=config.learning_rate, weight_decay=config.weight_decay)

    version = next_version(out_dir)
    run_dir = Path(out_dir) / "runs" / version
    run_dir.mkdir(parents=True, exist_ok=True)
    metrics_file = run_dir / "metrics.jsonl"
    metrics_file.write_text("", encoding="utf-8")

    meta = ModelMeta(
        version=version,
        network=network_config,
        features=dataset.feature_config,
        embeddings=dataset.embedding_config,
        mean=mean.tolist(),
        std=std.tolist(),
        smoothing=HeuristicParams(),
    )

    best_score = float("inf")
    best_state: dict[str, torch.Tensor] | None = None
    for epoch in range(1, config.epochs + 1):
        network.train()
        crops = [i for i in range(len(train_inputs)) for _ in range(config.crops_per_video)]
        rng.shuffle(crops)
        epoch_loss, batches = 0.0, 0
        for begin in range(0, len(crops), config.batch_size):
            batch = [
                _crop(train_inputs[i], train_labels[i], config.crop_windows, rng)
                for i in crops[begin : begin + config.batch_size]
            ]
            x, y, mask = (torch.from_numpy(np.stack(part)).to(device) for part in zip(*batch, strict=True))
            optimizer.zero_grad()
            loss = _masked_loss(network(x), y, mask)
            loss.backward()
            optimizer.step()
            epoch_loss += float(loss.detach())
            batches += 1

        record = {"epoch": epoch, "train_loss": epoch_loss / batches}
        if val_inputs:
            record["val_loss"], record["val_accuracy"] = evaluate_windows(network, val_inputs, val_labels, device)
        else:
            record["train_accuracy"] = evaluate_windows(network, train_inputs, train_labels, device)[1]
        with metrics_file.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record) + "\n")
        print(json.dumps(record))

        score = record.get("val_loss", record["train_loss"])
        checkpoint = {
            "epoch": epoch,
            "network": network.state_dict(),
            "optimizer": optimizer.state_dict(),
            "metrics": record,
        }
        torch.save(checkpoint, run_dir / "last.pt")
        if score < best_score:
            best_score = score
            best_state = {k: v.detach().cpu().clone() for k, v in network.state_dict().items()}
            torch.save(checkpoint, run_dir / "best.pt")

    assert best_state is not None
    network.load_state_dict(best_state)
    return save_model(out_dir, network, meta)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Train the learned rally model on a dataset directory.")
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--out", type=Path, required=True, help="model directory (MODEL_DIR)")
    parser.add_argument("--config", type=Path, help="YAML file overriding training parameters")
    parser.add_argument("--seed", type=int, help="overrides the seed of the config")
    parser.add_argument("--device", choices=("cpu", "mps", "cuda"), help="default: best available")
    args = parser.parse_args(argv)

    config = TrainConfig.from_yaml(args.config) if args.config else TrainConfig()
    if args.seed is not None:
        config = TrainConfig(**{**config.__dict__, "seed": args.seed})
    model_dir = train(Dataset(args.dataset), args.out, config, args.device)
    print(f"wrote {model_dir}")


if __name__ == "__main__":
    main()
