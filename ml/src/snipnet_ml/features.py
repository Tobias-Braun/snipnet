"""Per-window feature extraction for the heuristic rally model.

The proxy video is decoded with PyAV at a low analysis rate, the court region of interest (ROI) is expanded a bit
so players leaving the marked rectangle are still seen, and every 0.5 s window gets a row of features:

- ROI motion energy: mean absolute grayscale difference between consecutive analysis frames inside the expanded
  ROI, after removing the frame-wide median difference so global brightness changes (auto exposure, clouds)
  do not look like motion.
- Outside motion: the same measure on everything outside the expanded ROI (other courts, passers-by). It is
  used later to normalize the ROI motion.
- Audio: onset strength restricted to the hit frequency band (mean and max per window) and the number of sharp
  transients, since roundnet hits are short loud impulses.

Results are cached as `.npz` files keyed by the video content hash, the crop/analysis parameters and
`FEATURE_VERSION`, so bumping the version invalidates every stale cache entry.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import tempfile
import zipfile
from collections.abc import Iterable, Iterator
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import ClassVar

import av
import librosa
import numpy as np
import pandas as pd

from snipnet_ml.labels import Roi

# Bump whenever the meaning of any feature column changes so cached results are not reused.
FEATURE_VERSION = 3

_AUDIO_HOP = 128


@dataclass(frozen=True)
class FeatureConfig:
    """Tunable analysis parameters. All of them take part in the cache key."""

    fps: float = 5.0
    window_s: float = 0.5
    # Fraction of the ROI width/height added on every side before cropping.
    roi_expand: float = 0.15
    sample_rate: int = 16000
    # Hit band in Hz: ball hits are broadband clicks; wind and voices sit mostly below it.
    hit_band: tuple[float, float] = (1500.0, 7000.0)
    # Minimum onset-strength peak height that counts as a transient, as a fraction of the video's own hit level (the
    # `transient_reference_percentile` of its candidate peak heights, see `transient_peaks`), so the count does not
    # depend on microphone distance or background level. Untuned on real footage yet (M5).
    transient_delta: float = 0.4
    transient_reference_percentile: float = 90.0
    # Absolute lower bound (mel-flux units) for the threshold, so a video with only background noise does not have that
    # noise scaled up into transients.
    transient_min_delta: float = 2.0
    # Minimum spacing between two transients in seconds.
    transient_gap_s: float = 0.1

    def __post_init__(self) -> None:
        if self.fps <= 0 or self.window_s <= 0:
            raise ValueError("fps and window_s must be positive")
        if self.roi_expand < 0:
            raise ValueError("roi_expand must not be negative")
        if self.transient_delta <= 0 or self.transient_min_delta < 0:
            raise ValueError("transient_delta must be positive and transient_min_delta not negative")
        if not 0 < self.transient_reference_percentile <= 100:
            raise ValueError("transient_reference_percentile must be in (0, 100]")


@dataclass(frozen=True)
class FeatureFrame:
    """Column-oriented feature table; row `i` describes `[t_start[i], t_end[i])` seconds of the video."""

    t_start: np.ndarray
    t_end: np.ndarray
    roi_motion: np.ndarray
    outside_motion: np.ndarray
    onset_mean: np.ndarray
    onset_max: np.ndarray
    transient_count: np.ndarray

    DTYPES: ClassVar[dict[str, type]] = {
        "t_start": np.float64,
        "t_end": np.float64,
        "roi_motion": np.float32,
        "outside_motion": np.float32,
        "onset_mean": np.float32,
        "onset_max": np.float32,
        "transient_count": np.int32,
    }

    def __post_init__(self) -> None:
        lengths = {len(getattr(self, name)) for name in self.DTYPES}
        if len(lengths) != 1:
            raise ValueError("all feature columns must have the same length")
        for name, dtype in self.DTYPES.items():
            if getattr(self, name).dtype != dtype:
                raise ValueError(f"column {name} must have dtype {np.dtype(dtype)}")

    def __len__(self) -> int:
        return len(self.t_start)

    def to_dataframe(self) -> pd.DataFrame:
        return pd.DataFrame({name: getattr(self, name) for name in self.DTYPES})

    @classmethod
    def from_arrays(cls, **columns: np.ndarray) -> FeatureFrame:
        return cls(**{name: np.asarray(columns[name], dtype=dtype) for name, dtype in cls.DTYPES.items()})

    def save(self, path: str | Path) -> None:
        np.savez_compressed(path, version=FEATURE_VERSION, **{name: getattr(self, name) for name in self.DTYPES})

    @classmethod
    def load(cls, path: str | Path) -> FeatureFrame:
        with np.load(path) as data:
            if int(data["version"]) != FEATURE_VERSION:
                raise ValueError("feature cache was written by a different feature version")
            return cls.from_arrays(**{name: data[name] for name in cls.DTYPES})


def hash_video(path: str | Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while chunk := handle.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def cache_key(video_hash: str, roi: Roi, config: FeatureConfig) -> str:
    """Key over everything that influences the result: the content, the ROI, the parameters and the version."""
    params = json.dumps([asdict(config), roi.model_dump()], sort_keys=True)
    params_hash = hashlib.sha256(params.encode()).hexdigest()[:12]
    return f"{video_hash[:32]}-v{FEATURE_VERSION}-{params_hash}"


def expanded_pixel_box(roi: Roi, expand: float, width: int, height: int) -> tuple[int, int, int, int]:
    """Pixel box `(x0, y0, x1, y1)` of the ROI grown by `expand` of its size per side, clipped to the frame."""
    x0 = max(0.0, roi.x - roi.width * expand)
    y0 = max(0.0, roi.y - roi.height * expand)
    x1 = min(1.0, roi.x + roi.width * (1 + expand))
    y1 = min(1.0, roi.y + roi.height * (1 + expand))
    return (
        math.floor(x0 * width),
        math.floor(y0 * height),
        max(math.ceil(x1 * width), math.floor(x0 * width) + 1),
        max(math.ceil(y1 * height), math.floor(y0 * height) + 1),
    )


def probe_duration(path: str | Path) -> float:
    """Container duration in seconds, or 0 when the container does not declare one."""
    with av.open(str(path)) as container:
        return float(container.duration / av.time_base) if container.duration else 0.0


def bounded_duration(path: str | Path, content_end_s: float) -> float:
    """Video length in seconds, given where the decoded content (relative to the timeline origin) ends.

    Some containers (Matroska written with a timestamp offset) declare the absolute end time as their duration, which
    over-counts by the stream start time. The decoded content can never be longer than the real video, so the smaller
    of the two is used; a container without a declared duration falls back to the content end.
    """
    declared = probe_duration(path)
    return min(declared, content_end_s) if declared else content_end_s


def video_duration(path: str | Path) -> float:
    """Real video length in seconds without decoding: the container duration bounded by the last video packet's end.

    Demuxing only reads packet headers, so this is cheap even for long proxies. The packet end is taken relative to
    the timeline origin (see `stream_origin`), which removes the timestamp offset that inflates the declared duration
    of e.g. Matroska files written with `-output_ts_offset`. Returns 0 when the file has no video packets.
    """
    with av.open(str(path)) as container:
        if not container.streams.video:
            return 0.0
        stream = container.streams.video[0]
        origin = stream_origin(container)
        content_end = 0.0
        for packet in container.demux(stream):
            if packet.pts is None or packet.time_base is None:
                continue
            end = float((packet.pts + (packet.duration or 0)) * packet.time_base) - origin
            content_end = max(content_end, end)
    return bounded_duration(path, content_end) if content_end > 0 else 0.0


def stream_origin(container: av.container.InputContainer) -> float:
    """Timeline origin in seconds: the start time of the first video stream, else of the container, else 0.

    Proxies not produced by our own ffmpeg command (edit lists, `-output_ts_offset`, MPEG-TS) may start at a non-zero
    timestamp. All decoded timestamps are expressed relative to this origin so that video frames, audio samples and the
    0.5 s window grid share one zero.
    """
    stream = container.streams.video[0] if container.streams.video else None
    if stream is not None and stream.start_time is not None and stream.time_base is not None:
        return float(stream.start_time * stream.time_base)
    if container.start_time is not None:
        return float(container.start_time / av.time_base)
    return 0.0


def decode_gray_frames(path: str | Path, fps: float) -> Iterator[tuple[float, np.ndarray]]:
    """Yield `(timestamp_s, gray_frame)` pairs resampled to `fps`, timed like `decode_frames`."""
    return decode_frames(path, fps, "gray")


def decode_frames(path: str | Path, fps: float, pixel_format: str) -> Iterator[tuple[float, np.ndarray]]:
    """Yield `(timestamp_s, frame)` pairs resampled to `fps`, converted to the PyAV `pixel_format` (e.g. "gray").

    Timestamps are relative to the timeline origin (see `stream_origin`), so the first frame of a proxy with a
    non-zero start time is at 0 s; frames before the origin are skipped.

    This is a generator on purpose: an hour of 480p proxy at 5 fps is several GB of pixels, so frames are consumed one
    at a time instead of being stacked into a single array. It is the single home of the resampling logic.
    """
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        stream.thread_type = "AUTO"
        origin = stream_origin(container)
        next_time = 0.0
        step = 1.0 / fps
        for frame in container.decode(stream):
            if frame.time is None:
                continue
            time_s = frame.time - origin
            if time_s + 1e-6 < next_time:
                continue
            yield time_s, frame.to_ndarray(format=pixel_format)
            # Advance past the timestamp actually taken so a slow source does not cause a burst of catch-up frames.
            next_time = max(next_time + step, time_s + step / 2)


def decode_audio(path: str | Path, sample_rate: int) -> np.ndarray:
    """Decode the first audio stream to mono float32 at `sample_rate`; silent videos yield an empty array.

    Sample 0 of the result lies at the same timeline origin as the video frames (see `stream_origin`): when the audio
    starts later than the video the gap is padded with silence, when it starts earlier (e.g. AAC priming) the leading
    samples are dropped.
    """
    chunks: list[np.ndarray] = []
    lead_samples: int | None = None
    with av.open(str(path)) as container:
        if not container.streams.audio:
            return np.zeros(0, dtype=np.float32)
        origin = stream_origin(container)
        resampler = av.AudioResampler(format="fltp", layout="mono", rate=sample_rate)
        for frame in container.decode(container.streams.audio[0]):
            if lead_samples is None:
                lead_samples = round((frame.time - origin) * sample_rate) if frame.time is not None else 0
            for out in resampler.resample(frame):
                chunks.append(out.to_ndarray().reshape(-1))
        for out in resampler.resample(None):
            chunks.append(out.to_ndarray().reshape(-1))
    if not chunks:
        return np.zeros(0, dtype=np.float32)
    samples = np.concatenate(chunks).astype(np.float32)
    lead = lead_samples or 0
    if lead > 0:
        return np.concatenate([np.zeros(lead, dtype=np.float32), samples])
    return samples[-lead:]


def _window_mean(values: np.ndarray, times: np.ndarray, window_s: float, n_windows: int) -> np.ndarray:
    """Average `values` per window by their timestamps; windows without samples get 0."""
    index = np.clip((times // window_s).astype(int), 0, n_windows - 1)
    sums = np.bincount(index, weights=values, minlength=n_windows)
    counts = np.bincount(index, minlength=n_windows)
    return np.divide(sums, counts, out=np.zeros(n_windows), where=counts > 0)


@dataclass(frozen=True)
class MotionSeries:
    """Motion energy per consecutive frame pair, stamped with the time of the newer frame of the pair."""

    times: np.ndarray
    roi: np.ndarray
    outside: np.ndarray
    last_frame_time: float


def motion_series(frames: Iterable[tuple[float, np.ndarray]], roi: Roi, expand: float) -> MotionSeries:
    """ROI and outside motion energy of every frame pair, robust to global lighting changes.

    Only the previous frame is kept in memory, so arbitrarily long videos stream through in constant space.
    """
    previous: np.ndarray | None = None
    inside = np.zeros(0, dtype=bool)
    last_time = 0.0
    times: list[float] = []
    roi_values: list[float] = []
    outside_values: list[float] = []
    for time_s, frame in frames:
        current = frame.astype(np.float32)
        if previous is None:
            height, width = current.shape
            x0, y0, x1, y1 = expanded_pixel_box(roi, expand, width, height)
            inside = np.zeros(current.shape, dtype=bool)
            inside[y0:y1, x0:x1] = True
        else:
            diff = current - previous
            # A lighting change shifts every pixel by about the same amount; the median removes that shift while
            # real motion, which touches only a minority of pixels, survives.
            motion = np.abs(diff - np.median(diff))
            times.append(time_s)
            roi_values.append(float(motion[inside].mean()))
            outside_values.append(float(motion[~inside].mean()) if not inside.all() else 0.0)
        previous = current
        last_time = time_s
    if previous is None:
        raise ValueError("no video frames decoded")
    return MotionSeries(np.asarray(times), np.asarray(roi_values), np.asarray(outside_values), last_time)


def transient_peaks(envelope: np.ndarray, config: FeatureConfig) -> np.ndarray:
    """Frame indices of sharp onset-envelope peaks, with a threshold relative to the video's own hit level.

    The envelope is a flux of log-power mel bands, so a pure gain change cancels out, but how far a hit rises above
    the noise floor still depends on microphone distance, wind and the camera. An absolute peak height would therefore
    count every footstep on a close microphone and miss real hits on a distant one.

    Peaks are first picked at the absolute `transient_min_delta`. The reference level is the
    `transient_reference_percentile` of those candidate peak heights, i.e. the level this video's loud hits reach, and
    the final threshold is `transient_delta` times that reference, never below the floor. The percentile is taken over
    peaks rather than over all envelope frames: hits cover well under 1% of the frames of a long recording with breaks,
    so a frame percentile would sit at the noise level and the threshold would silently fall back to the floor.
    """
    wait = max(1, round(config.transient_gap_s * config.sample_rate / _AUDIO_HOP))

    def pick(delta: float) -> np.ndarray:
        return librosa.util.peak_pick(
            envelope, pre_max=wait, post_max=wait, pre_avg=wait, post_avg=wait, delta=delta, wait=wait
        )

    candidates = pick(config.transient_min_delta)
    if len(candidates) == 0:
        return candidates
    reference = float(np.percentile(envelope[candidates], config.transient_reference_percentile))
    delta = config.transient_delta * reference
    # Re-picking instead of filtering the candidates keeps the `wait` spacing consistent with the final threshold.
    return pick(delta) if delta > config.transient_min_delta else candidates


def audio_features(samples: np.ndarray, config: FeatureConfig, n_windows: int) -> tuple[np.ndarray, ...]:
    """Mean and max hit-band onset strength plus transient count per window."""
    mean = np.zeros(n_windows)
    peak = np.zeros(n_windows)
    count = np.zeros(n_windows, dtype=np.int64)
    if len(samples) < 2048:
        return mean, peak, count

    low, high = config.hit_band
    envelope = librosa.onset.onset_strength(
        y=samples,
        sr=config.sample_rate,
        hop_length=_AUDIO_HOP,
        n_fft=1024,
        fmin=low,
        fmax=min(high, config.sample_rate / 2),
    )
    frame_times = librosa.frames_to_time(np.arange(len(envelope)), sr=config.sample_rate, hop_length=_AUDIO_HOP)
    index = np.clip((frame_times // config.window_s).astype(int), 0, n_windows - 1)

    counts = np.bincount(index, minlength=n_windows)
    mean = np.divide(np.bincount(index, weights=envelope, minlength=n_windows), counts, out=mean, where=counts > 0)
    np.maximum.at(peak, index, envelope)

    peaks = transient_peaks(envelope, config)
    count = np.bincount(index[peaks], minlength=n_windows) if len(peaks) else count
    return mean, peak, count


def compute_features(path: str | Path, roi: Roi, config: FeatureConfig | None = None) -> FeatureFrame:
    """Decode `path` and compute the feature table without touching any cache."""
    config = config or FeatureConfig()
    try:
        motion = motion_series(decode_gray_frames(path, config.fps), roi, config.roi_expand)
    except ValueError as error:
        raise ValueError(f"{error} from {path}") from error
    samples = decode_audio(path, config.sample_rate)
    # Only the video bounds the duration: the audio track may run a little past the last frame (encoder padding).
    duration = bounded_duration(path, motion.last_frame_time + 1.0 / config.fps)
    n_windows = max(1, math.ceil(duration / config.window_s - 1e-9))

    roi_motion = _window_mean(motion.roi, motion.times, config.window_s, n_windows)
    outside_motion = _window_mean(motion.outside, motion.times, config.window_s, n_windows)
    onset_mean, onset_max, transients = audio_features(samples, config, n_windows)
    starts = np.arange(n_windows) * config.window_s
    return FeatureFrame.from_arrays(
        t_start=starts,
        t_end=starts + config.window_s,
        roi_motion=roi_motion,
        outside_motion=outside_motion,
        onset_mean=onset_mean,
        onset_max=onset_max,
        transient_count=transients,
    )


def extract_features(
    path: str | Path, roi: Roi, config: FeatureConfig | None = None, cache_dir: str | Path | None = None
) -> FeatureFrame:
    """Return the feature table for a video, reading and writing the `.npz` cache when `cache_dir` is given."""
    config = config or FeatureConfig()
    if cache_dir is None:
        return compute_features(path, roi, config)
    cache_file = Path(cache_dir) / f"{cache_key(hash_video(path), roi, config)}.npz"
    if cache_file.exists():
        try:
            return FeatureFrame.load(cache_file)
        except (ValueError, KeyError, OSError, EOFError, zipfile.BadZipFile):
            # A truncated or foreign cache file is simply recomputed and overwritten.
            pass
    result = compute_features(path, roi, config)
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    # Write to a unique temporary file and rename it into place, so a crash or a concurrent worker never leaves a
    # half-written file under the final name.
    handle, temporary = tempfile.mkstemp(dir=cache_file.parent, prefix=f".{cache_file.stem}-", suffix=".npz")
    os.close(handle)
    try:
        result.save(temporary)
        os.replace(temporary, cache_file)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise
    return result


__all__ = [
    "FEATURE_VERSION",
    "FeatureConfig",
    "FeatureFrame",
    "cache_key",
    "compute_features",
    "extract_features",
]
