"""Command line entry point: run a model on a video and optionally score it against ground-truth labels.

    python -m snipnet_ml.predict video.mp4 --court court.json [--eval labels.json] [--params heuristic.yaml]

`court.json` holds a court as defined in docs/api.md (`{"roi": {...}, "netPoint": {...}}`); a label file is also
accepted there because it contains the same `court` object.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from snipnet_ml.eval import evaluate, format_table
from snipnet_ml.features import video_duration
from snipnet_ml.heuristic import HeuristicModel, HeuristicParams
from snipnet_ml.labels import Court, load_labels
from snipnet_ml.model import Prediction


def load_court(path: Path) -> Court:
    # The file is chosen by the operator on the command line, so reading an arbitrary path is intended.
    data = json.loads(path.read_text(encoding="utf-8"))  # NOSONAR
    return Court.model_validate(data.get("court", data))


def format_segments(prediction: Prediction) -> str:
    lines = [f"model {prediction.model_version}: {len(prediction.segments)} segments"]
    for index, segment in enumerate(prediction.segments, start=1):
        confidence = "" if segment.confidence is None else f"  confidence {segment.confidence:.2f}"
        lines.append(f"{index:3d}  {segment.start_ms / 1000:8.2f} s - {segment.end_ms / 1000:8.2f} s{confidence}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Detect rallies in a proxy video with the heuristic model.")
    parser.add_argument("video", type=Path)
    parser.add_argument("--court", type=Path, help="court JSON; without it the whole frame counts as the court")
    parser.add_argument("--eval", type=Path, dest="labels", help="label JSON to compute segment metrics against")
    parser.add_argument("--params", type=Path, help="YAML file overriding heuristic parameters")
    args = parser.parse_args(argv)

    params = HeuristicParams.from_yaml(args.params) if args.params else HeuristicParams()
    court = load_court(args.court) if args.court else None
    prediction = HeuristicModel(params).predict(args.video, court, lambda _: None)
    print(format_segments(prediction))

    if args.labels:
        labels = load_labels(args.labels)
        # The label file's duration is authoritative, but fall back to the real video length if it is shorter. The raw
        # container duration is not used: it is the absolute end time for proxies with a timestamp offset.
        duration_ms = max(labels.duration_ms, round(video_duration(args.video) * 1000))
        print()
        print(format_table(evaluate(prediction.segments, labels.rallies, duration_ms)))


if __name__ == "__main__":
    main()
