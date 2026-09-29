"""Automatic net and court detection with classical computer vision, used to pre-fill the court selection.

The roundnet net is a small trampoline whose rim is bright yellow and, seen by a tripod camera, projects to a thin
ring: a circle when filmed from above, a flat ellipse from a low angle. The detector therefore looks for exactly
that and nothing else:

1. Threshold "yellow" pixels in HSV space on a downscaled frame.
2. Group them into connected components (after closing small gaps that players and the net cords cut into the rim).
3. Fit an ellipse to every candidate component with the direct least-squares method of Fitzgibbon et al.
4. Score the candidate by how well its pixels hug the fitted ellipse (a yellow shirt is a filled blob and fails),
   how much of the ellipse's circumference is covered (players may hide part of it) and how close it is to the
   image center (the camera is set up with the net roughly in the middle).

The net point is the ellipse center; the suggested ROI is a box around it scaled with the apparent net size, using
the same proportions as the desktop app's default court box. Coordinates are normalized to the frame (docs/api.md).
Detection over a video samples a handful of frames and only reports what the samples agree on, because a single
frame can contain a player in a yellow shirt standing in the middle of the image.

This is a first, deliberately simple version. Thresholds are tuned on synthetic images; real footage will tell
whether a learned detector is needed.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy import ndimage

from snipnet_ml.model import Court, InvalidInputError, Point, Roi

# Suggestions at or above this confidence are safe to apply without asking the user; below it the client shows
# them as a hint at most. Part of the API contract (docs/api.md).
HIGH_CONFIDENCE = 0.7

# Frames are shrunk to at most this width before analysis; the rim stays several pixels thick at that size.
ANALYSIS_WIDTH = 320

_MIN_RING_PIXELS = 24
# Flattest ellipse (minor / major axis) still accepted, i.e. the lowest camera angle the detector copes with.
_MIN_AXIS_RATIO = 0.12
# Median distance of the pixels from the fitted ellipse, relative to its size, above which a blob is not a ring.
_MAX_RESIDUAL = 0.2
# The residual a clean rim of normal thickness produces on its own; only the excess above it lowers confidence.
_RIM_RESIDUAL = 0.06
_MIN_COVERAGE = 0.45
_ANGLE_BINS = 16
# Farthest a net center may lie from the image center (in frame widths/heights) before it scores zero.
_CENTER_TOLERANCE = 0.4
# Accepted apparent net diameter as a fraction of the frame width.
_MIN_DIAMETER = 0.03
_MAX_DIAMETER = 0.5
_VIDEO_SAMPLES = 7
# Positions of the per-frame detections (fraction of the frame) that may differ before confidence drops to zero.
_MAX_SAMPLE_SPREAD = 0.06


@dataclass(frozen=True)
class NetDetection:
    """One net found in a single frame: its center, the fitted ellipse and how sure the detector is."""

    net_point: Point
    # Full axes of the fitted ellipse as fractions of the frame width, major first.
    major: float
    minor: float
    confidence: float


@dataclass(frozen=True)
class CourtSuggestion:
    """A court proposal in the shape of `Court` from docs/api.md plus a confidence in `[0, 1]`."""

    court: Court
    confidence: float


def suggested_roi(net: Point, diameter: float) -> Roi:
    """The court box for a net at `net` whose rim measures `diameter` frame widths across.

    A roundnet net is about 0.9 m wide and the playing area around it a few meters across, so the box grows with
    the apparent net size. The result is clamped to the proportions of the app's default box and shifted (not
    shrunk) to stay inside the frame.
    """
    width = min(0.9, max(0.35, diameter * 5))
    height = min(0.95, width * 0.7 / 0.6)
    return Roi(
        x=min(max(net.x - width / 2, 0.0), 1.0 - width),
        y=min(max(net.y - height / 2, 0.0), 1.0 - height),
        width=width,
        height=height,
    )


def yellow_mask(image: np.ndarray) -> np.ndarray:
    """Boolean mask of saturated, bright yellow pixels in an RGB `uint8` image."""
    rgb = image.astype(np.float32) / 255.0
    red, green, blue = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    value = rgb.max(axis=-1)
    chroma = value - rgb.min(axis=-1)
    saturation = np.divide(chroma, value, out=np.zeros_like(value), where=value > 0)
    # Yellow sits at 60 degrees hue: red and green are both high and about equal while blue is far below them.
    balanced = np.abs(red - green) <= 0.3 * np.maximum(red, green)
    return (saturation > 0.5) & (value > 0.45) & balanced & (blue < 0.6 * np.minimum(red, green))


def _ellipse_conic(u: np.ndarray, v: np.ndarray) -> np.ndarray | None:
    """Coefficients `(a, b, c, d, e, f)` of the ellipse `a u^2 + b uv + c v^2 + d u + e v + f = 0` fitted to points."""
    design = np.column_stack([u * u, u * v, v * v, u, v, np.ones_like(u)])
    constraint = np.zeros((6, 6))
    constraint[0, 2] = constraint[2, 0] = 2
    constraint[1, 1] = -1
    try:
        eigenvalues, eigenvectors = np.linalg.eig(np.linalg.solve(design.T @ design, constraint))
    except np.linalg.LinAlgError:
        return None
    # The ellipse is the eigenvector satisfying 4ac - b^2 > 0; for a valid fit exactly one eigenvalue is positive.
    for value, vector in zip(eigenvalues, eigenvectors.T, strict=True):
        real = vector.real
        if abs(value.imag) < 1e-9 and value.real > 0 and 4 * real[0] * real[2] - real[1] ** 2 > 0:
            return real
    return None


def fit_ellipse(x: np.ndarray, y: np.ndarray) -> tuple[float, float, float, float, float] | None:
    """Direct least-squares ellipse fit; returns `(cx, cy, semi_major, semi_minor, angle)` or None.

    The points are centered and scaled first because the raw scatter matrix is badly conditioned for pixel
    coordinates. `None` means the points are degenerate (for example collinear).
    """
    if len(x) < 6:
        return None
    mx, my = x.mean(), y.mean()
    scale = max(float(np.sqrt(((x - mx) ** 2 + (y - my) ** 2).mean())), 1e-9)
    conic = _ellipse_conic((x - mx) / scale, (y - my) / scale)
    if conic is None:
        return None
    a, b, c, d, e, f = conic
    determinant = 4 * a * c - b * b
    cu = (b * e - 2 * c * d) / determinant
    cv = (b * d - 2 * a * e) / determinant
    # At the center the equation reads (p - center)^T M (p - center) = -constant.
    constant = f + (d * cu + e * cv) / 2
    lambdas, vectors = np.linalg.eigh(np.array([[a, b / 2], [b / 2, c]]))
    if constant == 0 or np.any(-constant / lambdas <= 0):
        return None
    # The conic's overall sign is arbitrary, so which eigenvalue belongs to the longer axis is decided by size.
    semi = np.sqrt(-constant / lambdas)
    long_axis = int(np.argmax(semi))
    angle = math.atan2(vectors[1, long_axis], vectors[0, long_axis])
    major, minor = float(semi[long_axis]), float(semi[1 - long_axis])
    return float(cu * scale + mx), float(cv * scale + my), major * scale, minor * scale, angle


def _ring_score(x: np.ndarray, y: np.ndarray, ellipse: tuple[float, float, float, float, float]) -> tuple[float, float]:
    """Return `(residual, coverage)` of `(x, y)` against `ellipse`.

    The residual is the median distance of the pixels from the ellipse in units of the ellipse's own size (0 for
    a perfect thin ring, about 0.5 for a filled disc). Coverage is the fraction of angular bins around the center
    that contain any pixels.
    """
    cx, cy, semi_major, semi_minor, angle = ellipse
    cos, sin = math.cos(angle), math.sin(angle)
    dx, dy = x - cx, y - cy
    along = (dx * cos + dy * sin) / semi_major
    across = (-dx * sin + dy * cos) / semi_minor
    residual = float(np.median(np.abs(np.hypot(along, across) - 1.0)))
    bins = ((np.arctan2(across, along) + math.pi) / (2 * math.pi) * _ANGLE_BINS).astype(int) % _ANGLE_BINS
    coverage = float(np.count_nonzero(np.bincount(bins, minlength=_ANGLE_BINS)) / _ANGLE_BINS)
    return residual, coverage


def _clamp01(value: float) -> float:
    return min(1.0, max(0.0, value))


def _score_component(xs: np.ndarray, ys: np.ndarray, size: tuple[int, int]) -> NetDetection | None:
    """Turn one yellow component (pixel coordinates in a `(width, height)` frame) into a detection, if ring-like."""
    width, height = size
    ellipse = fit_ellipse(xs, ys)
    if ellipse is None:
        return None
    cx, cy, semi_major, semi_minor, _ = ellipse
    diameter = 2 * semi_major / width
    if semi_minor / semi_major < _MIN_AXIS_RATIO or not _MIN_DIAMETER <= diameter <= _MAX_DIAMETER:
        return None
    residual, coverage = _ring_score(xs, ys, ellipse)
    nx, ny = (cx + 0.5) / width, (cy + 0.5) / height
    if residual > _MAX_RESIDUAL or coverage < _MIN_COVERAGE or not (0 <= nx <= 1 and 0 <= ny <= 1):
        return None
    confidence = (
        _clamp01(1 - max(0.0, residual - _RIM_RESIDUAL) / (_MAX_RESIDUAL - _RIM_RESIDUAL))
        * _clamp01((coverage - 0.2) / 0.6)
        * _clamp01(1 - math.hypot(nx - 0.5, ny - 0.5) / _CENTER_TOLERANCE)
    )
    return NetDetection(Point(nx, ny), diameter, 2 * semi_minor / width, confidence)


def detect_net(image: np.ndarray) -> NetDetection | None:
    """Find the most plausible net in an RGB `uint8` frame of shape `(height, width, 3)`, or None."""
    step = max(1, math.ceil(image.shape[1] / ANALYSIS_WIDTH))
    small = image[::step, ::step]
    size = (small.shape[1], small.shape[0])

    # Players, the net cords and the frame edge break the thin rim into pieces; closing reconnects small gaps.
    neighbours = np.ones((3, 3), dtype=bool)
    mask = ndimage.binary_closing(yellow_mask(small), structure=neighbours, iterations=2)
    labels, _ = ndimage.label(mask, structure=neighbours)

    candidates = []
    for index, region in enumerate(ndimage.find_objects(labels), start=1):
        ys, xs = np.nonzero(labels[region] == index)
        if len(xs) < _MIN_RING_PIXELS:
            continue
        detection = _score_component(
            (xs + region[1].start).astype(np.float64), (ys + region[0].start).astype(np.float64), size
        )
        if detection is not None:
            candidates.append(detection)
    return max(candidates, key=lambda d: d.confidence, default=None)


def suggest_from_frames(frames: list[np.ndarray]) -> CourtSuggestion | None:
    """Combine detections from several frames of one video into a single suggestion.

    The suggestion uses the median net position and size of the frames that found a net. Its confidence is the
    median frame confidence, scaled by the share of frames that found a net and reduced when their positions
    disagree, so one stray yellow object cannot produce a confident answer.
    """
    if not frames:
        return None
    found = [d for d in (detect_net(frame) for frame in frames) if d is not None]
    if not found:
        return None
    xs = np.array([d.net_point.x for d in found])
    ys = np.array([d.net_point.y for d in found])
    net = Point(float(np.median(xs)), float(np.median(ys)))
    diameter = float(np.median([d.major for d in found]))
    spread = float(max(xs.max() - xs.min(), ys.max() - ys.min()))
    agreement = _clamp01(1 - spread / _MAX_SAMPLE_SPREAD)
    share = len(found) / len(frames)
    confidence = float(np.median([d.confidence for d in found])) * math.sqrt(share) * agreement
    return CourtSuggestion(Court(roi=suggested_roi(net, diameter), net_point=net), round(confidence, 4))


def sample_frames(path: str | Path, count: int = _VIDEO_SAMPLES) -> list[np.ndarray]:
    """Decode `count` RGB frames spread over the middle of the video (players are on the court there)."""
    import av

    frames: list[np.ndarray] = []
    try:
        with av.open(str(path)) as container:
            stream = container.streams.video[0]
            duration = float(container.duration / av.time_base) if container.duration else 0.0
            if duration <= 0:
                raise InvalidInputError("video has no duration")
            for i in range(count):
                target = duration * (0.1 + 0.6 * i / max(1, count - 1))
                container.seek(int(target / stream.time_base), stream=stream)
                # After a keyframe seek the first frames precede the target, so decode on until it is reached.
                for frame in container.decode(stream):
                    if frame.time is not None and frame.time >= target - 0.1:
                        frames.append(frame.to_ndarray(format="rgb24"))
                        break
    except av.error.FFmpegError as exc:
        raise InvalidInputError(f"cannot read video: {exc}") from exc
    return frames


def detect_court(path: str | Path) -> CourtSuggestion | None:
    """Suggest a court for the video at `path`, or None when no net was found."""
    return suggest_from_frames(sample_frames(path))


def main(argv: list[str] | None = None) -> None:
    """Print the suggestion for a video as the JSON shape of `Video.courtSuggestion` in docs/api.md."""
    import argparse
    import json

    parser = argparse.ArgumentParser(description="Suggest the net point and court ROI for a proxy video.")
    parser.add_argument("video", type=Path)
    suggestion = detect_court(parser.parse_args(argv).video)
    if suggestion is None:
        print("null")
        return
    roi, net = suggestion.court.roi, suggestion.court.net_point
    body = {
        "court": {
            "roi": {"x": roi.x, "y": roi.y, "width": roi.width, "height": roi.height},
            "netPoint": {"x": net.x, "y": net.y},
        },
        "confidence": suggestion.confidence,
    }
    print(json.dumps(body, indent=2))


if __name__ == "__main__":
    main()
