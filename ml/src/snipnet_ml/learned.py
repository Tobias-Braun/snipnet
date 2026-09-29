"""The learned rally model (`learned-v1.<n>`): a temporal convolutional network over per-window features.

Every 0.5 s window is described by the v0 hand-made features, a frozen visual embedding of the court ROI and an
audio embedding (see `snipnet_ml.embeddings`). The network sees the whole sequence of windows of a video at once,
so a window is classified with the context of the seconds around it, and outputs one rally logit per window. The
probabilities go through the same Viterbi smoothing and segment cleanup as the heuristic model, so both models
share the segment semantics (minimum rally length, gap bridging, padding) and differ only in the score.

A trained model lives in a directory that is never committed:

    <MODEL_DIR>/learned-v1.3/model.pt     state dict, loaded with `weights_only=True`
    <MODEL_DIR>/learned-v1.3/meta.json    version, feature/embedding settings, network shape, normalisation stats

`MODEL_DIR` may point at one such directory or at a folder of them, in which case the highest build number wins.
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import torch
from torch import nn

from snipnet_ml.embeddings import EmbeddingConfig, VisualEmbedder, WindowEmbeddings, extract_embeddings
from snipnet_ml.features import FeatureConfig, FeatureFrame, extract_features
from snipnet_ml.heuristic import HeuristicParams, build_segments, viterbi_states
from snipnet_ml.labels import Roi
from snipnet_ml.model import (
    Court,
    InvalidInputError,
    Prediction,
    ProgressCallback,
    ScoreCurve,
    probe_duration_ms,
)
from snipnet_ml.persons import select_device

VERSION_PREFIX = "learned-v1."
_VERSION_PATTERN = re.compile(r"learned-v1\.(\d+)")
_FULL_FRAME = Roi(x=0.0, y=0.0, width=1.0, height=1.0)
_TABLE_COLUMNS = ("roi_motion", "outside_motion", "onset_mean", "onset_max", "transient_count")


@dataclass(frozen=True)
class WindowFeatures:
    """Everything the learned model knows about one video: the v0 table plus both embeddings, one row per window."""

    table: FeatureFrame
    embeddings: WindowEmbeddings

    def __post_init__(self) -> None:
        if len(self.table) != len(self.embeddings.visual):
            raise ValueError("feature table and embeddings must cover the same windows")

    def matrix(self) -> np.ndarray:
        """Raw `(windows, dim)` model input: log-scaled v0 columns followed by the visual and audio embeddings.

        The v0 columns are heavy-tailed (a transient count, a motion energy), so `log1p` brings them to a scale the
        per-feature standardisation in the model can handle.
        """
        v0 = np.stack([np.log1p(getattr(self.table, name).astype(np.float64)) for name in _TABLE_COLUMNS], axis=1)
        return np.concatenate([v0, self.embeddings.visual, self.embeddings.audio], axis=1).astype(np.float32)

    def save(self, path: str | Path) -> None:
        columns = {name: getattr(self.table, name) for name in FeatureFrame.DTYPES}
        np.savez_compressed(path, visual=self.embeddings.visual, audio=self.embeddings.audio, **columns)

    @classmethod
    def load(cls, path: str | Path) -> WindowFeatures:
        with np.load(path) as data:
            table = FeatureFrame.from_arrays(**{name: data[name] for name in FeatureFrame.DTYPES})
            return cls(table=table, embeddings=WindowEmbeddings(visual=data["visual"], audio=data["audio"]))


def compute_window_features(
    video_path: str | Path,
    roi: Roi,
    feature_config: FeatureConfig,
    embedding_config: EmbeddingConfig,
    cache_dir: str | Path | None = None,
    embedder: VisualEmbedder | None = None,
) -> WindowFeatures:
    """v0 table and embeddings of one video; both parts are cached separately in `cache_dir` when it is given."""
    table = extract_features(video_path, roi, feature_config, cache_dir)
    embeddings = extract_embeddings(video_path, roi, len(table), embedding_config, cache_dir, embedder)
    return WindowFeatures(table=table, embeddings=embeddings)


@dataclass(frozen=True)
class NetworkConfig:
    input_dim: int
    hidden: int = 64
    blocks: int = 4
    kernel: int = 3
    dropout: float = 0.2


class _Block(nn.Module):
    """Residual block of a non-causal dilated convolution; padding keeps the sequence length unchanged."""

    def __init__(self, hidden: int, kernel: int, dilation: int, dropout: float) -> None:
        super().__init__()
        self.conv = nn.Conv1d(hidden, hidden, kernel, dilation=dilation, padding=dilation * (kernel - 1) // 2)
        # GroupNorm with one group normalises per sample, so it behaves the same for a batch of crops during
        # training and for the single full-length sequence at inference (BatchNorm would not).
        self.norm = nn.GroupNorm(1, hidden)
        self.drop = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return x + self.drop(torch.relu(self.norm(self.conv(x))))


class TemporalConvNet(nn.Module):
    """`(batch, windows, input_dim)` features to `(batch, windows)` rally logits."""

    def __init__(self, config: NetworkConfig) -> None:
        super().__init__()
        if config.kernel % 2 == 0:
            raise ValueError("kernel must be odd so the convolutions keep the sequence length")
        self.project = nn.Conv1d(config.input_dim, config.hidden, 1)
        self.blocks = nn.Sequential(
            *[_Block(config.hidden, config.kernel, 2**i, config.dropout) for i in range(config.blocks)]
        )
        self.head = nn.Conv1d(config.hidden, 1, 1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        hidden = self.blocks(self.project(x.transpose(1, 2)))
        return self.head(hidden).squeeze(1)


@dataclass(frozen=True)
class ModelMeta:
    """Contents of `meta.json`: everything needed to rebuild the network and compute identical inputs."""

    version: str
    network: NetworkConfig
    features: FeatureConfig
    embeddings: EmbeddingConfig
    mean: list[float]
    std: list[float]
    smoothing: HeuristicParams

    def to_json(self) -> str:
        data = asdict(self)
        data["features"]["hit_band"] = list(self.features.hit_band)
        return json.dumps(data, indent=2)

    @classmethod
    def from_json(cls, text: str) -> ModelMeta:
        data = json.loads(text)
        features = dict(data["features"])
        features["hit_band"] = tuple(features["hit_band"])
        return cls(
            version=data["version"],
            network=NetworkConfig(**data["network"]),
            features=FeatureConfig(**features),
            embeddings=EmbeddingConfig(**data["embeddings"]),
            mean=data["mean"],
            std=data["std"],
            smoothing=HeuristicParams(**data["smoothing"]),
        )


def save_model(directory: str | Path, network: TemporalConvNet, meta: ModelMeta) -> Path:
    """Write `model.pt` and `meta.json` into `<directory>/<version>/` and return that path."""
    target = Path(directory) / meta.version
    target.mkdir(parents=True, exist_ok=True)
    torch.save({k: v.cpu() for k, v in network.state_dict().items()}, target / "model.pt")
    (target / "meta.json").write_text(meta.to_json() + "\n", encoding="utf-8")
    return target


def next_version(directory: str | Path) -> str:
    """`learned-v1.<n>` with `n` one above the highest build already present in `directory`."""
    builds = [
        int(m.group(1)) for p in Path(directory).glob("learned-v1.*") if (m := _VERSION_PATTERN.fullmatch(p.name))
    ]
    return f"{VERSION_PREFIX}{max(builds, default=0) + 1}"


def resolve_model_dir(path: str | Path) -> Path:
    """Accept a model directory or a folder of them (newest build wins) and return the model directory."""
    path = Path(path)
    if (path / "meta.json").is_file():
        return path
    builds = sorted(
        (
            (int(m.group(1)), p)
            for p in path.glob("learned-v1.*")
            if (m := _VERSION_PATTERN.fullmatch(p.name)) and p.is_dir()
        ),
        key=lambda item: item[0],
    )
    if not builds:
        raise FileNotFoundError(f"no learned model found in {path}; expected meta.json or learned-v1.<n> folders")
    return builds[-1][1]


class LearnedModel:
    """`RallyModel` backed by a trained temporal network; construct it from a model directory."""

    def __init__(
        self,
        model_dir: str | Path,
        cache_dir: str | Path | None = None,
        device: str | None = None,
        embedder: VisualEmbedder | None = None,
    ) -> None:
        directory = resolve_model_dir(model_dir)
        self.meta = ModelMeta.from_json((directory / "meta.json").read_text(encoding="utf-8"))
        self.version = self.meta.version
        self.cache_dir = cache_dir
        self.device = device or select_device()
        self._embedder = embedder or VisualEmbedder(self.meta.embeddings, device=self.device)
        self._network = TemporalConvNet(self.meta.network)
        self._network.load_state_dict(torch.load(directory / "model.pt", map_location="cpu", weights_only=True))
        self._network.to(self.device).eval()
        self._mean = np.asarray(self.meta.mean, dtype=np.float32)
        self._std = np.asarray(self.meta.std, dtype=np.float32)

    def probabilities(self, features: WindowFeatures) -> np.ndarray:
        """Rally probability per window."""
        inputs = (features.matrix() - self._mean) / self._std
        with torch.inference_mode():
            logits = self._network(torch.from_numpy(inputs).unsqueeze(0).to(self.device))
        return torch.sigmoid(logits)[0].cpu().numpy().astype(np.float64)

    def predict_from_features(self, features: WindowFeatures, duration_ms: int) -> Prediction:
        """Everything after feature extraction; separated so promotion can score cached features."""
        window_s = float(features.table.t_end[0] - features.table.t_start[0])
        probability = self.probabilities(features)
        states = viterbi_states(probability, self.meta.smoothing)
        segments = build_segments(states, probability, window_s, duration_ms / 1000, self.meta.smoothing)
        return Prediction(
            segments=segments,
            scores=ScoreCurve(hz=1 / window_s, values=[round(float(v), 4) for v in probability]),
            model_version=self.version,
        )

    def predict(self, video_path: Path, court: Court | None, progress: ProgressCallback) -> Prediction:
        progress(0.0)
        duration_ms = probe_duration_ms(video_path)
        roi = (
            Roi(x=court.roi.x, y=court.roi.y, width=court.roi.width, height=court.roi.height) if court else _FULL_FRAME
        )
        try:
            features = compute_window_features(
                video_path, roi, self.meta.features, self.meta.embeddings, self.cache_dir, self._embedder
            )
        except ValueError as exc:
            # Feature extraction raises ValueError only when nothing could be decoded (e.g. no video stream).
            raise InvalidInputError(str(exc)) from exc
        progress(0.9)
        prediction = self.predict_from_features(features, duration_ms)
        progress(1.0)
        return prediction
