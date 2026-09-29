"""Promotion report: does a learned model beat the heuristic v0 model on the held-out test split?

    python -m snipnet_ml.promote data/v1 models/learned-v1.3 --out report [--split test]

Both models are scored on the cached per-window features of the dataset's test videos with `snipnet_ml.eval` (segment
precision/recall/F1 at IoU >= 0.5, frame accuracy, boundary error), so the comparison is exactly the same for both
and needs no video decoding. The report is written as `report.json` and `report.md` and the exit status is 0 only when
the learned model is promoted, which lets a script gate the deployment of the model directory on it.

Rule: the learned model is promoted when its mean segment F1 is strictly higher than the heuristic's and its mean frame
accuracy is not lower. Means are taken per video, so a single long video does not dominate.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path

from snipnet_ml.dataset import Dataset
from snipnet_ml.eval import Metrics, evaluate
from snipnet_ml.heuristic import HEURISTIC_VERSION, HeuristicParams, predict_from_features
from snipnet_ml.labels import Rally
from snipnet_ml.learned import LearnedModel
from snipnet_ml.model import Prediction


@dataclass(frozen=True)
class ModelScore:
    version: str
    f1: float
    precision: float
    recall: float
    frame_accuracy: float
    boundary_mae_ms: float | None
    per_video_f1: dict[str, float]


@dataclass(frozen=True)
class PromotionReport:
    split: str
    videos: int
    heuristic: ModelScore
    learned: ModelScore
    promoted: bool
    reason: str


def _rallies(prediction: Prediction) -> list[Rally]:
    return [Rally(start_ms=s.start_ms, end_ms=s.end_ms) for s in prediction.segments]


def _mean(values: list[float]) -> float:
    return sum(values) / len(values)


def summarize(version: str, per_video: dict[str, Metrics]) -> ModelScore:
    metrics = list(per_video.values())
    boundaries = [m.boundary_mae_ms for m in metrics if m.boundary_mae_ms is not None]
    return ModelScore(
        version=version,
        f1=_mean([m.f1 for m in metrics]),
        precision=_mean([m.precision for m in metrics]),
        recall=_mean([m.recall for m in metrics]),
        frame_accuracy=_mean([m.frame_accuracy for m in metrics]),
        boundary_mae_ms=_mean(boundaries) if boundaries else None,
        per_video_f1={video: m.f1 for video, m in per_video.items()},
    )


def compare(dataset: Dataset, model: LearnedModel, split: str = "test") -> PromotionReport:
    """Score the learned model and the default heuristic on one split of the dataset."""
    examples = dataset.examples(split)
    if not examples:
        raise ValueError(f"the dataset has no videos in the {split!r} split")
    heuristic_metrics: dict[str, Metrics] = {}
    learned_metrics: dict[str, Metrics] = {}
    for example in examples:
        heuristic = predict_from_features(example.features.table, example.duration_ms, HeuristicParams())
        learned = model.predict_from_features(example.features, example.duration_ms)
        heuristic_metrics[example.video_id] = evaluate(_rallies(heuristic), example.rallies, example.duration_ms)
        learned_metrics[example.video_id] = evaluate(_rallies(learned), example.rallies, example.duration_ms)

    heuristic_score = summarize(HEURISTIC_VERSION, heuristic_metrics)
    learned_score = summarize(model.version, learned_metrics)
    better_f1 = learned_score.f1 > heuristic_score.f1
    not_worse_frames = learned_score.frame_accuracy >= heuristic_score.frame_accuracy
    if better_f1 and not_worse_frames:
        reason = "learned model has a higher mean segment F1 and no lower frame accuracy"
    elif not better_f1:
        reason = "learned model does not beat the heuristic on mean segment F1"
    else:
        reason = "learned model has a lower frame accuracy than the heuristic"
    return PromotionReport(
        split=split,
        videos=len(examples),
        heuristic=heuristic_score,
        learned=learned_score,
        promoted=better_f1 and not_worse_frames,
        reason=reason,
    )


def format_markdown(report: PromotionReport) -> str:
    def boundary(score: ModelScore) -> str:
        return "n/a" if score.boundary_mae_ms is None else f"{score.boundary_mae_ms / 1000:.3f}"

    rows = "\n".join(
        f"| {s.version} | {s.precision:.3f} | {s.recall:.3f} | {s.f1:.3f} | {s.frame_accuracy:.3f} | {boundary(s)} |"
        for s in (report.heuristic, report.learned)
    )
    verdict = "PROMOTE" if report.promoted else "DO NOT PROMOTE"
    return (
        f"# Promotion report: {report.learned.version}\n\n"
        f"Split `{report.split}`, {report.videos} videos, metrics averaged per video.\n\n"
        "| model | precision | recall | F1 | frame accuracy | boundary error (s) |\n"
        "|---|---|---|---|---|---|\n"
        f"{rows}\n\n"
        f"**{verdict}**: {report.reason}.\n"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Compare a learned model with the heuristic on a held-out split.")
    parser.add_argument("dataset", type=Path)
    parser.add_argument("model", type=Path, help="model directory or a folder of learned-v1.<n> directories")
    parser.add_argument("--out", type=Path, required=True, help="directory for report.json and report.md")
    parser.add_argument("--split", choices=("val", "test"), default="test")
    parser.add_argument("--device", choices=("cpu", "mps", "cuda"))
    args = parser.parse_args(argv)

    report = compare(Dataset(args.dataset), LearnedModel(args.model, device=args.device), args.split)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "report.json").write_text(json.dumps(asdict(report), indent=2) + "\n", encoding="utf-8")
    markdown = format_markdown(report)
    (args.out / "report.md").write_text(markdown, encoding="utf-8")
    print(markdown)
    return 0 if report.promoted else 1


if __name__ == "__main__":
    raise SystemExit(main())
