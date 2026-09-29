"""Person detection and tracking inside the court ROI, and the per-window person features derived from it.

Frames are decoded at a low rate (2.5 fps by default; a person moves little in 0.4 s and the detector is by far
the most expensive step), cropped to the expanded court ROI and passed to a torchvision COCO detector. Only
detections whose foot point (bottom center of the box) lies inside the ROI, grown by a small margin, are kept, so
players on neighbouring courts that merely overlap the crop are dropped. A greedy tracker (IoU, then foot-point
distance for fast movers) links the detections of consecutive frames into tracks, which is what makes a
per-person speed possible.

All coordinates handed out by this module are normalized to the full video frame (origin top left). Distances
and speeds are measured in units of the frame height, so they are isotropic even though the frame is wider than
it is tall; a speed is therefore "frame heights per second".

The detector weights are downloaded on first use into a cache directory and are never part of the repository.
No Ultralytics code or weights are used: the model comes from torchvision (BSD).
"""

from __future__ import annotations

import math
import os
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import ClassVar, Protocol

import av
import numpy as np
import pandas as pd

from snipnet_ml.features import bounded_duration, decode_frames, expanded_pixel_box
from snipnet_ml.labels import Court, Roi

# Bump whenever the meaning of a person feature column changes.
PERSON_FEATURE_VERSION = 1

# Index of the "person" class in the COCO label set used by torchvision detection models.
COCO_PERSON = 1

MODEL_CACHE_ENV = "SNIPNET_MODEL_CACHE"


@dataclass(frozen=True)
class PersonConfig:
    """Tunable parameters of the person pipeline."""

    fps: float = 2.5
    window_s: float = 0.5
    # Fraction of the ROI size added on every side before cropping, so a player stepping out of the marked
    # rectangle is still seen in full.
    roi_expand: float = 0.15
    # Fraction of the ROI size the foot point may lie outside the ROI and still count as "in the court".
    foot_margin: float = 0.05
    score_threshold: float = 0.5
    # Minimum IoU between a track's last box and a detection to link them.
    match_iou: float = 0.2
    # Second association pass for tracks the IoU pass left unmatched: a detection whose foot point lies within
    # this many box heights of the track's last foot point still continues the track. At 2.5 fps a sprinting
    # player moves further than their own width between frames, so the boxes no longer overlap. 0 disables it.
    foot_gate: float = 1.0
    # A track that missed n frames may have moved for (n + 1) frame intervals, so its foot gate grows by that
    # factor, capped here so a long-lost track does not swallow a nearby other player. 1 keeps the gate fixed.
    foot_gate_max_scale: float = 2.0
    # A track survives this many analysis frames without a matching detection.
    max_missed: int = 2

    def __post_init__(self) -> None:
        if self.fps <= 0 or self.window_s <= 0:
            raise ValueError("fps and window_s must be positive")
        if self.roi_expand < 0 or self.foot_margin < 0:
            raise ValueError("roi_expand and foot_margin must not be negative")
        if not 0 <= self.score_threshold <= 1 or not 0 <= self.match_iou <= 1:
            raise ValueError("score_threshold and match_iou must lie in [0, 1]")
        if self.foot_gate < 0:
            raise ValueError("foot_gate must not be negative")
        if self.foot_gate_max_scale < 1:
            raise ValueError("foot_gate_max_scale must be at least 1")
        if self.max_missed < 0:
            raise ValueError("max_missed must not be negative")


@dataclass(frozen=True)
class Detection:
    """One person box `(x0, y0, x1, y1)` in pixels of the image it was found in."""

    box: tuple[float, float, float, float]
    score: float


class PersonDetector(Protocol):
    """Anything that finds persons in an RGB `uint8` image of shape `(height, width, 3)`."""

    def detect(self, image: np.ndarray) -> list[Detection]: ...


def select_device() -> str:
    """Best available torch device: CUDA, then Apple MPS, then CPU."""
    import torch

    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def default_cache_dir() -> Path:
    return Path(os.environ.get(MODEL_CACHE_ENV) or Path.home() / ".cache" / "snipnet" / "models")


class TorchvisionPersonDetector:
    """COCO person detector backed by torchvision's `fasterrcnn_mobilenet_v3_large_320_fpn`.

    The model is built and its weights are fetched lazily on the first `detect` call, so importing the module
    and constructing the detector stay cheap and offline.
    """

    def __init__(
        self, score_threshold: float = 0.5, device: str | None = None, cache_dir: str | Path | None = None
    ) -> None:
        self.score_threshold = score_threshold
        self._device = device
        self._cache_dir = Path(cache_dir) if cache_dir is not None else default_cache_dir()
        self._model = None

    @property
    def device(self) -> str:
        if self._device is None:
            self._device = select_device()
        return self._device

    def _load(self):
        from torchvision.models.detection import (
            FasterRCNN_MobileNet_V3_Large_320_FPN_Weights,
            fasterrcnn_mobilenet_v3_large_320_fpn,
        )

        weights = FasterRCNN_MobileNet_V3_Large_320_FPN_Weights.COCO_V1
        self._cache_dir.mkdir(parents=True, exist_ok=True)
        # Building without weights and loading the state dict ourselves is what lets the download land in our
        # cache directory instead of the global torch hub directory.
        model = fasterrcnn_mobilenet_v3_large_320_fpn(weights=None, weights_backbone=None)
        model.load_state_dict(weights.get_state_dict(model_dir=str(self._cache_dir), progress=False, check_hash=True))
        return model.to(self.device).eval()

    def detect(self, image: np.ndarray) -> list[Detection]:
        import torch

        if self._model is None:
            self._model = self._load()
        tensor = torch.from_numpy(np.ascontiguousarray(image)).permute(2, 0, 1).float().div(255).to(self.device)
        with torch.inference_mode():
            output = self._model([tensor])[0]
        found = []
        for box, label, score in zip(
            output["boxes"].cpu().tolist(),
            output["labels"].cpu().tolist(),
            output["scores"].cpu().tolist(),
            strict=True,
        ):
            if label == COCO_PERSON and score >= self.score_threshold:
                found.append(Detection(box=(box[0], box[1], box[2], box[3]), score=score))
        return found


def box_iou(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
    width = min(a[2], b[2]) - max(a[0], b[0])
    height = min(a[3], b[3]) - max(a[1], b[1])
    if width <= 0 or height <= 0:
        return 0.0
    intersection = width * height
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - intersection
    return intersection / union if union > 0 else 0.0


@dataclass(frozen=True)
class TrackedPerson:
    """A detection in one analysis frame together with the id of the track it belongs to.

    `box` is `(x0, y0, x1, y1)` normalized to the full frame; `foot` is its bottom center `(x, y)`.
    """

    track_id: int
    box: tuple[float, float, float, float]

    @property
    def foot(self) -> tuple[float, float]:
        return (self.box[0] + self.box[2]) / 2, self.box[3]


@dataclass
class _Track:
    track_id: int
    box: tuple[float, float, float, float]
    missed: int = 0


class IouTracker:
    """Greedy tracker: IoU pairs are matched first, then leftovers by foot-point distance.

    The IoU pass links slow movers. Because frames are 0.4 s apart, a fast player's boxes may not overlap at
    all, so a second greedy pass matches the still unmatched tracks and detections by the distance between
    their foot points, gated relative to the track's box height (`foot_gate`). The gate grows with the number of
    frames the track missed, by `missed + 1` up to `foot_gate_max_scale`. Unmatched detections start new
    tracks. No appearance features are used, which is enough for the coarse speed feature.

    `aspect` (frame width / height) converts the normalized x axis to the same unit as y, so the distance is
    isotropic; it is only relevant to the foot-distance pass.
    """

    def __init__(
        self,
        match_iou: float = 0.2,
        max_missed: int = 2,
        foot_gate: float = 1.0,
        aspect: float = 1.0,
        foot_gate_max_scale: float = 2.0,
    ) -> None:
        self.match_iou = match_iou
        self.max_missed = max_missed
        self.foot_gate = foot_gate
        self.foot_gate_max_scale = foot_gate_max_scale
        self.aspect = aspect
        self._tracks: list[_Track] = []
        self._next_id = 0

    def _foot_distance(self, a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
        dx = ((a[0] + a[2]) / 2 - (b[0] + b[2]) / 2) * self.aspect
        return math.hypot(dx, a[3] - b[3])

    def _iou_candidates(self, boxes: list[tuple[float, float, float, float]]) -> list[tuple[float, int, int]]:
        """`(-iou, track, box)` triples of all pairs overlapping enough, best overlap first."""
        return sorted(
            (-iou, ti, bi)
            for ti, track in enumerate(self._tracks)
            for bi, box in enumerate(boxes)
            if (iou := box_iou(track.box, box)) >= self.match_iou
        )

    def _foot_candidates(
        self, boxes: list[tuple[float, float, float, float]], free_tracks: set[int]
    ) -> list[tuple[float, int, int]]:
        """`(distance / track box height, track, box)` triples within the foot gate, closest first."""
        found = []
        for ti in free_tracks:
            track = self._tracks[ti]
            height = track.box[3] - track.box[1]
            if height <= 0:
                continue
            gate = self.foot_gate * min(track.missed + 1, self.foot_gate_max_scale)
            for bi, box in enumerate(boxes):
                relative = self._foot_distance(track.box, box) / height
                if relative <= gate:
                    found.append((relative, ti, bi))
        return sorted(found)

    def update(self, boxes: list[tuple[float, float, float, float]]) -> list[TrackedPerson]:
        """Assign track ids to `boxes` of the next frame, in the order the boxes were given."""
        used_tracks: set[int] = set()
        assigned: dict[int, _Track] = {}

        def match(candidates: list[tuple[float, int, int]]) -> None:
            for _, ti, bi in candidates:
                if ti not in used_tracks and bi not in assigned:
                    used_tracks.add(ti)
                    assigned[bi] = self._tracks[ti]

        match(self._iou_candidates(boxes))
        if self.foot_gate > 0:
            match(self._foot_candidates(boxes, set(range(len(self._tracks))) - used_tracks))

        for ti, track in enumerate(self._tracks):
            if ti not in used_tracks:
                track.missed += 1
        result = []
        for bi, box in enumerate(boxes):
            track = assigned.get(bi)
            if track is None:
                track = _Track(self._next_id, box)
                self._next_id += 1
                self._tracks.append(track)
            track.box = box
            track.missed = 0
            result.append(TrackedPerson(track.track_id, box))
        self._tracks = [track for track in self._tracks if track.missed <= self.max_missed]
        return result


@dataclass(frozen=True)
class PersonFrame:
    """Persons kept in one analysis frame."""

    time_s: float
    persons: list[TrackedPerson]


def foot_in_roi(foot: tuple[float, float], roi: Roi, margin: float) -> bool:
    """Whether a normalized foot point lies inside `roi` grown by `margin` of its size on every side."""
    return roi.x - roi.width * margin <= foot[0] <= roi.x + roi.width * (
        1 + margin
    ) and roi.y - roi.height * margin <= foot[1] <= roi.y + roi.height * (1 + margin)


def decode_rgb_frames(path: str | Path, fps: float) -> Iterator[tuple[float, np.ndarray]]:
    """Yield `(timestamp_s, rgb_frame)` pairs resampled to `fps`, one frame at a time."""
    return decode_frames(path, fps, "rgb24")


def track_persons(
    frames: Iterator[tuple[float, np.ndarray]],
    roi: Roi,
    detector: PersonDetector,
    config: PersonConfig | None = None,
    on_frame: Callable[[float], None] | None = None,
) -> list[PersonFrame]:
    """Detect, filter and track persons in a stream of full RGB frames.

    Each frame is cropped to the expanded ROI before detection; boxes are mapped back to normalized full-frame
    coordinates and dropped unless their foot point is inside the ROI.
    """
    config = config or PersonConfig()
    result: list[PersonFrame] = []
    tracker: IouTracker | None = None
    for time_s, frame in frames:
        height, width = frame.shape[:2]
        if tracker is None:
            tracker = IouTracker(
                config.match_iou,
                config.max_missed,
                config.foot_gate,
                width / height,
                config.foot_gate_max_scale,
            )
        x0, y0, x1, y1 = expanded_pixel_box(roi, config.roi_expand, width, height)
        boxes = []
        for detection in detector.detect(frame[y0:y1, x0:x1]):
            bx0, by0, bx1, by1 = detection.box
            box = ((bx0 + x0) / width, (by0 + y0) / height, (bx1 + x0) / width, (by1 + y0) / height)
            if foot_in_roi(((box[0] + box[2]) / 2, box[3]), roi, config.foot_margin):
                boxes.append(box)
        # The tracker is updated even for empty frames so lost tracks age out.
        result.append(PersonFrame(time_s, tracker.update(boxes)))
        if on_frame is not None:
            on_frame(time_s)
    return result


@dataclass(frozen=True)
class PersonFeatureFrame:
    """Column-oriented per-window person features; row `i` describes `[t_start[i], t_end[i])` seconds.

    - `person_count`: mean number of persons in the ROI per analysis frame of the window.
    - `mean_net_distance`: mean distance of the persons' foot points to the net point (frame heights).
    - `spread`: root of the summed variance of foot positions, i.e. the RMS distance from their centroid
      (frame heights); 0 with fewer than two persons.
    - `mean_speed`: mean foot-point speed of tracked persons (frame heights per second).

    Windows without any person or analysis frame get 0 in every column, which the model reads together with
    `person_count == 0`.
    """

    t_start: np.ndarray
    t_end: np.ndarray
    person_count: np.ndarray
    mean_net_distance: np.ndarray
    spread: np.ndarray
    mean_speed: np.ndarray

    DTYPES: ClassVar[dict[str, type]] = {
        "t_start": np.float64,
        "t_end": np.float64,
        "person_count": np.float32,
        "mean_net_distance": np.float32,
        "spread": np.float32,
        "mean_speed": np.float32,
    }

    def __post_init__(self) -> None:
        if len({len(getattr(self, name)) for name in self.DTYPES}) != 1:
            raise ValueError("all feature columns must have the same length")
        for name, dtype in self.DTYPES.items():
            if getattr(self, name).dtype != dtype:
                raise ValueError(f"column {name} must have dtype {np.dtype(dtype)}")

    def __len__(self) -> int:
        return len(self.t_start)

    def to_dataframe(self) -> pd.DataFrame:
        return pd.DataFrame({name: getattr(self, name) for name in self.DTYPES})

    @classmethod
    def from_arrays(cls, **columns: np.ndarray) -> PersonFeatureFrame:
        return cls(**{name: np.asarray(columns[name], dtype=dtype) for name, dtype in cls.DTYPES.items()})


def person_features(
    frames: list[PersonFrame], court: Court, aspect: float, duration_s: float, config: PersonConfig | None = None
) -> PersonFeatureFrame:
    """Aggregate tracked persons into windows of `config.window_s` covering `duration_s` seconds.

    `aspect` is frame width divided by frame height; x coordinates are scaled by it so that all distances are in
    frame heights.
    """
    config = config or PersonConfig()
    n_windows = max(1, math.ceil(duration_s / config.window_s - 1e-9))
    net = np.array([court.net_point.x * aspect, court.net_point.y])

    counts: list[list[int]] = [[] for _ in range(n_windows)]
    distances: list[list[float]] = [[] for _ in range(n_windows)]
    spreads: list[list[float]] = [[] for _ in range(n_windows)]
    speeds: list[list[float]] = [[] for _ in range(n_windows)]
    last_foot: dict[int, tuple[float, np.ndarray]] = {}

    for frame in frames:
        index = min(max(int(frame.time_s // config.window_s), 0), n_windows - 1)
        feet = np.array([[p.foot[0] * aspect, p.foot[1]] for p in frame.persons]).reshape(-1, 2)
        counts[index].append(len(feet))
        if len(feet):
            distances[index].extend(np.linalg.norm(feet - net, axis=1).tolist())
            spreads[index].append(float(np.sqrt(((feet - feet.mean(axis=0)) ** 2).sum(axis=1).mean())))
        for person, foot in zip(frame.persons, feet, strict=True):
            previous = last_foot.get(person.track_id)
            if previous is not None and frame.time_s > previous[0]:
                speeds[index].append(float(np.linalg.norm(foot - previous[1]) / (frame.time_s - previous[0])))
            last_foot[person.track_id] = (frame.time_s, foot)

    def mean_of(values: list[list[float]] | list[list[int]]) -> np.ndarray:
        return np.array([float(np.mean(v)) if v else 0.0 for v in values])

    starts = np.arange(n_windows) * config.window_s
    return PersonFeatureFrame.from_arrays(
        t_start=starts,
        t_end=starts + config.window_s,
        person_count=mean_of(counts),
        mean_net_distance=mean_of(distances),
        spread=mean_of(spreads),
        mean_speed=mean_of(speeds),
    )


def extract_person_features(
    path: str | Path,
    court: Court,
    detector: PersonDetector | None = None,
    config: PersonConfig | None = None,
) -> PersonFeatureFrame:
    """Decode `path`, detect and track persons in the court ROI and return the per-window features.

    Without an explicit `detector` the torchvision model is used on the best available device.
    """
    config = config or PersonConfig()
    detector = detector or TorchvisionPersonDetector(config.score_threshold)
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        aspect = stream.codec_context.width / stream.codec_context.height
    frames = track_persons(decode_rgb_frames(path, config.fps), court.roi, detector, config)
    if not frames:
        raise ValueError(f"no video frames decoded from {path}")
    duration = bounded_duration(path, frames[-1].time_s + 1.0 / config.fps)
    return person_features(frames, court, aspect, duration, config)


__all__ = [
    "PERSON_FEATURE_VERSION",
    "Detection",
    "IouTracker",
    "PersonConfig",
    "PersonDetector",
    "PersonFeatureFrame",
    "PersonFrame",
    "TorchvisionPersonDetector",
    "TrackedPerson",
    "extract_person_features",
    "person_features",
    "select_device",
    "track_persons",
]
