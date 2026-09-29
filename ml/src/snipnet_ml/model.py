"""Model interface shared by the inference worker and training code, plus the placeholder model."""

import json
import os
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
        # The path is passed as a list element (no shell) after -i, so it cannot be read as an ffprobe option.
        result = subprocess.run(  # NOSONAR
            ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", "-i", str(video_path)],
            capture_output=True,
            text=True,
            check=True,
        )
        duration_s = float(json.loads(result.stdout)["format"]["duration"])
    except subprocess.CalledProcessError as exc:
        # A negative return code means ffprobe was killed by a signal (e.g. Ctrl-C reaching the whole process
        # group), which says nothing about the file, so the job must stay retryable.
        if exc.returncode < 0:
            raise
        raise InvalidInputError(f"cannot read video duration from {video_path.name}") from exc
    except (KeyError, TypeError, ValueError) as exc:
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
        # For a video of a few milliseconds the middle third is empty, and docs/api.md requires startMs < endMs.
        if end_ms <= start_ms:
            raise InvalidInputError("video is too short to contain a rally")
        samples = max(1, round(duration_ms / 1000))
        values = [1.0 if start_ms <= (i + 0.5) * 1000 < end_ms else 0.0 for i in range(samples)]
        progress(1.0)
        return Prediction(
            segments=[Segment(start_ms=start_ms, end_ms=end_ms, confidence=None)],
            scores=ScoreCurve(hz=1.0, values=values),
            model_version=self.version,
        )


def load_model(name: str) -> RallyModel:
    """Resolve the ``MODEL`` setting (``heuristic``, ``dummy`` or ``learned``, or an exact model version).

    The heuristic parameters can be overridden with a YAML file named by ``HEURISTIC_PARAMS``. The learned model is
    loaded from the directory named by ``MODEL_DIR`` (a model directory, or a folder of ``learned-v1.<n>`` builds
    of which the newest is used); the directory is never part of the repository.
    """
    if name in ("dummy", DummyModel.version):
        return DummyModel()
    # Imported lazily: the heuristic module pulls in the feature stack (av, librosa), which the dummy model and
    # the interface itself do not need.
    from snipnet_ml.heuristic import HEURISTIC_VERSION, HeuristicModel, HeuristicParams

    if name in ("heuristic", HEURISTIC_VERSION):
        params_file = os.environ.get("HEURISTIC_PARAMS")
        return HeuristicModel(HeuristicParams.from_yaml(params_file) if params_file else None)
    if name == "learned" or name.startswith("learned-v1."):
        model_dir = os.environ.get("MODEL_DIR")
        if not model_dir:
            raise ValueError("MODEL_DIR must point at the trained model directory to use the learned model")
        # Imported lazily: the learned model pulls in torch, which the other models do not need at load time.
        from snipnet_ml.learned import LearnedModel

        model = LearnedModel(model_dir)
        if name != "learned" and name != model.version:
            raise ValueError(f"MODEL_DIR holds {model.version}, not {name}")
        return model
    raise ValueError(f"unknown model {name!r}")
