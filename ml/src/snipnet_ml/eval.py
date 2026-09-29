"""Segment-level and frame-level metrics for comparing predicted rallies with labels.

All times are integer milliseconds, matching the API contract. Segments are half-open intervals
`[start_ms, end_ms)`.
"""

from __future__ import annotations

import argparse
import json
import math
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from snipnet_ml.labels import Labels, Rally, load_labels

IOU_THRESHOLD = 0.5
FRAME_HZ = 10


@dataclass(frozen=True)
class Metrics:
    precision: float
    recall: float
    f1: float
    frame_accuracy: float
    # None when no prediction was matched to a label, so there is no boundary to measure.
    boundary_mae_ms: float | None
    true_positives: int
    predicted: int
    labeled: int


def iou(a: Rally, b: Rally) -> float:
    intersection = max(0, min(a.end_ms, b.end_ms) - max(a.start_ms, b.start_ms))
    union = (a.end_ms - a.start_ms) + (b.end_ms - b.start_ms) - intersection
    return intersection / union if union else 0.0


def match_segments(
    predictions: Sequence[Rally], labels: Sequence[Rally], threshold: float = IOU_THRESHOLD
) -> list[tuple[Rally, Rally]]:
    """Greedy one-to-one matching: the highest-IoU pairs at or above the threshold win first."""
    candidates = sorted(
        (
            (score, pi, li)
            for pi, prediction in enumerate(predictions)
            for li, label in enumerate(labels)
            if (score := iou(prediction, label)) >= threshold
        ),
        key=lambda item: (-item[0], item[1], item[2]),
    )
    used_predictions: set[int] = set()
    used_labels: set[int] = set()
    matches = []
    for _, pi, li in candidates:
        if pi in used_predictions or li in used_labels:
            continue
        used_predictions.add(pi)
        used_labels.add(li)
        matches.append((predictions[pi], labels[li]))
    return matches


def _ratio(hits: int, total: int, other_total: int) -> float:
    """hits/total, where an empty side scores 1.0 only if the other side is empty too."""
    if total == 0:
        return 1.0 if other_total == 0 else 0.0
    return hits / total


def frame_accuracy(predictions: Sequence[Rally], labels: Sequence[Rally], duration_ms: int) -> float:
    """Share of 10 Hz frames, sampled at frame centers, whose rally/dead-time state agrees."""
    frames = math.ceil(duration_ms * FRAME_HZ / 1000)
    if frames == 0:
        return 1.0
    step = 1000 / FRAME_HZ
    agree = 0
    for index in range(frames):
        t = (index + 0.5) * step
        in_prediction = any(s.start_ms <= t < s.end_ms for s in predictions)
        in_label = any(s.start_ms <= t < s.end_ms for s in labels)
        agree += in_prediction == in_label
    return agree / frames


def evaluate(predictions: Sequence[Rally], labels: Sequence[Rally], duration_ms: int) -> Metrics:
    matches = match_segments(predictions, labels)
    tp = len(matches)
    precision = _ratio(tp, len(predictions), len(labels))
    recall = _ratio(tp, len(labels), len(predictions))
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    errors = [abs(p.start_ms - t.start_ms) for p, t in matches] + [abs(p.end_ms - t.end_ms) for p, t in matches]
    return Metrics(
        precision=precision,
        recall=recall,
        f1=f1,
        frame_accuracy=frame_accuracy(predictions, labels, duration_ms),
        boundary_mae_ms=sum(errors) / len(errors) if errors else None,
        true_positives=tp,
        predicted=len(predictions),
        labeled=len(labels),
    )


def load_predictions(path: str | Path) -> list[Rally]:
    """Read predictions from a label file, a segment set / result body (`segments`) or a bare list."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    items = data if isinstance(data, list) else data.get("segments", data.get("rallies"))
    if items is None:
        raise ValueError("prediction file needs a 'segments' or 'rallies' list")
    return [Rally.model_validate(item) for item in items]


def format_table(metrics: Metrics) -> str:
    boundary = "n/a" if metrics.boundary_mae_ms is None else f"{metrics.boundary_mae_ms / 1000:.3f} s"
    rows = [
        ("segment precision (IoU>=0.5)", f"{metrics.precision:.3f}"),
        ("segment recall (IoU>=0.5)", f"{metrics.recall:.3f}"),
        ("segment F1", f"{metrics.f1:.3f}"),
        (f"frame accuracy ({FRAME_HZ} Hz)", f"{metrics.frame_accuracy:.3f}"),
        ("mean abs. boundary error", boundary),
        ("matched / predicted / labeled", f"{metrics.true_positives} / {metrics.predicted} / {metrics.labeled}"),
    ]
    width = max(len(name) for name, _ in rows)
    return "\n".join(f"{name.ljust(width)}  {value}" for name, value in rows)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Compare predicted rallies with ground-truth labels.")
    parser.add_argument("labels", type=Path, help="label JSON (see snipnet_ml.labels)")
    parser.add_argument("predictions", type=Path, help="prediction JSON: segment list, segment set or label file")
    args = parser.parse_args(argv)

    labels: Labels = load_labels(args.labels)
    metrics = evaluate(load_predictions(args.predictions), labels.rallies, labels.duration_ms)
    print(format_table(metrics))


if __name__ == "__main__":
    main()
