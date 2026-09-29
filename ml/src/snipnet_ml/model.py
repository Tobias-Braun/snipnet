"""Model interface shared by the inference worker and training code, plus the placeholder model."""

import json
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

ProgressCallback = Callable[[float], None]


class InvalidInputError(Exception):
    """The video cannot be analysed no matter how often it is retried (unreadable or zero-length media)."""


@dataclass(frozen=True)
class Roi:
    x: float
    y: float
    width: float
    height: float


@dataclass(frozen=True)
class Point:
    x: float
    y: float


@dataclass(frozen=True)
class Court:
    """Court calibration in normalized image coordinates, as defined in docs/api.md."""

    roi: Roi
    net_point: Point


@dataclass(frozen=True)
class Segment:
    start_ms: int
    end_ms: int
    confidence: float | None = None


@dataclass(frozen=True)
class ScoreCurve:
    """Rally probability (0..1) sampled ``hz`` times per second."""

    hz: float
    values: list[float]


@dataclass(frozen=True)
class Prediction:
    segments: list[Segment]
    scores: ScoreCurve | None
    model_version: str


class RallyModel(Protocol):
    version: str

    def predict(self, video_path: Path, court: Court | None, progress: ProgressCallback) -> Prediction:
        """Detect rallies in the proxy at ``video_path``.

        ``progress`` takes a fraction in ``[0, 1]`` and may be called as often as the model likes; the caller
        throttles what is forwarded. Raises ``InvalidInputError`` for media that can never be analysed.
        """
        ...


def probe_duration_ms(video_path: Path) -> int:
    """Read the container duration with ffprobe, raising ``InvalidInputError`` if it is missing or unusable."""
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", str(video_path)],
            capture_output=True,
            text=True,
            check=True,
        )
        duration_s = float(json.loads(result.stdout)["format"]["duration"])
    except (subprocess.CalledProcessError, KeyError, ValueError) as exc:
        raise InvalidInputError(f"cannot read video duration from {video_path.name}") from exc
    duration_ms = int(duration_s * 1000)
    if duration_ms <= 0:
        raise InvalidInputError("video has no duration")
    return duration_ms


class DummyModel:
    """Reports one rally over the middle third of the video so the pipeline works before a real model exists."""

    version = "dummy-v0"

    def predict(self, video_path: Path, court: Court | None, progress: ProgressCallback) -> Prediction:
        progress(0.0)
        duration_ms = probe_duration_ms(video_path)
        start_ms = duration_ms // 3
        end_ms = duration_ms * 2 // 3
        samples = max(1, round(duration_ms / 1000))
        values = [1.0 if start_ms <= (i + 0.5) * 1000 < end_ms else 0.0 for i in range(samples)]
        progress(1.0)
        return Prediction(
            segments=[Segment(start_ms=start_ms, end_ms=end_ms, confidence=None)],
            scores=ScoreCurve(hz=1.0, values=values),
            model_version=self.version,
        )


def load_model(name: str) -> RallyModel:
    """Resolve the ``MODEL`` setting to an implementation."""
    if name == DummyModel.version:
        return DummyModel()
    raise ValueError(f"unknown model {name!r}")
