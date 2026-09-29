"""Heuristic rally model (`heuristic-v0.1`): hand-tuned feature combination, HMM smoothing and segment cleanup.

The pipeline runs on the per-window feature table from `snipnet_ml.features` (0.5 s windows):

1. Every feature is normalized per video to roughly 0..1 with robust percentiles, so the same parameters work for
   quiet and loud recordings. A minimum range keeps a video without any rally from having its noise stretched
   into a fake signal. The low end is additionally capped by an absolute noise floor, so a clip that is almost
   entirely rally is not normalized to zero everywhere.
2. The normalized features are averaged with fixed weights into one activity score. Audio hits are sparse (a hit
   every second or so), so the audio features are averaged over a few seconds first.
3. A logistic function turns the score into a rally probability, which is also what is reported as the 2 Hz
   score curve.
4. A two-state (dead time / rally) HMM decoded with Viterbi removes flicker: switching state has to pay a penalty,
   so a single noisy window cannot start or end a rally.
5. The decoded runs are cleaned up: gaps shorter than `min_gap_s` are bridged, rallies shorter than `min_rally_s`
   are dropped, and the survivors are padded and clamped to the video.

Person detections (`snipnet_ml.persons`) are deliberately not used here: they need downloaded detector weights and
are by far the most expensive step, while motion and audio already separate rallies well enough for v0.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, fields
from pathlib import Path

import numpy as np
import yaml

from snipnet_ml.features import FeatureConfig, FeatureFrame, extract_features
from snipnet_ml.labels import Court, Roi
from snipnet_ml.model import (
    InvalidInputError,
    Prediction,
    ProgressCallback,
    ScoreCurve,
    Segment,
    probe_duration_ms,
)

HEURISTIC_VERSION = "heuristic-v0.1"

# Used when the caller did not provide a court: the whole frame is treated as the court.
_FULL_FRAME = Roi(x=0.0, y=0.0, width=1.0, height=1.0)


@dataclass(frozen=True)
class HeuristicParams:
    """Every tunable of the heuristic model. Any subset can be overridden from a YAML file with the same names."""

    # Weights of the three normalized signals in the activity score; they are rescaled to sum to one.
    motion_weight: float = 0.6
    onset_weight: float = 0.2
    transient_weight: float = 0.2

    # Motion in the court ROI is divided by `outside_motion_weight * outside_motion + motion_floor`, which cancels
    # camera shake and lighting changes that also show up outside the court.
    outside_motion_weight: float = 0.5
    motion_floor: float = 1.0

    # Moving-average lengths in seconds. Motion needs little smoothing; audio hits are sparse and need several
    # seconds to look like a continuous signal.
    motion_smooth_s: float = 1.0
    audio_smooth_s: float = 3.0

    # Percentiles of a video's values that map to 0 and 1 in normalization.
    low_percentile: float = 10.0
    high_percentile: float = 95.0
    # The percentile spread is never taken smaller than this (in the feature's own unit), see module docstring.
    min_motion_range: float = 0.5
    min_onset_range: float = 0.1
    min_transient_range: float = 0.2
    # Absolute noise floors (in the feature's own unit): the low end of the normalization is `min(low percentile,
    # floor)`. Without them a clip that is almost all rally has its low percentile at rally level and every window
    # normalizes to about 0. Dead-time noise stays below these values, so ordinary clips are not affected.
    motion_noise_floor: float = 0.3
    onset_noise_floor: float = 0.05
    transient_noise_floor: float = 0.1

    # Logistic mapping of the score to a probability: 0.5 at `score_midpoint`, steeper with `score_steepness`.
    score_midpoint: float = 0.4
    score_steepness: float = 12.0

    # HMM: probability of switching state per window (smaller = smoother) and how strongly the per-window
    # probability counts against the switching cost. Windows are far from independent, so this is below one.
    switch_probability: float = 0.03
    emission_weight: float = 0.7

    # Segment cleanup, in seconds.
    min_rally_s: float = 2.0
    min_gap_s: float = 3.0
    pad_before_s: float = 1.0
    pad_after_s: float = 1.5

    def __post_init__(self) -> None:
        self._check_numbers()
        if min(self.motion_weight, self.onset_weight, self.transient_weight) < 0:
            raise ValueError("signal weights must not be negative")
        if self.motion_weight + self.onset_weight + self.transient_weight <= 0:
            raise ValueError("at least one signal weight must be positive")
        if not 0 <= self.low_percentile < self.high_percentile <= 100:
            raise ValueError("percentiles must satisfy 0 <= low < high <= 100")
        if not 0 < self.switch_probability < 0.5:
            raise ValueError("switch_probability must be in (0, 0.5)")
        if self.emission_weight <= 0 or self.score_steepness <= 0:
            raise ValueError("emission_weight and score_steepness must be positive")
        for name in ("min_rally_s", "min_gap_s", "pad_before_s", "pad_after_s", "motion_smooth_s", "audio_smooth_s"):
            if getattr(self, name) < 0:
                raise ValueError(f"{name} must not be negative")

    def _check_numbers(self) -> None:
        """Reject non-numeric and non-finite values.

        YAML happily yields strings, booleans or `.nan` for a mistyped value; without this check they surface as a
        TypeError deep in the range checks or, for NaN, pass them silently and break the model.
        """
        for field in fields(self):
            value = getattr(self, field.name)
            if isinstance(value, bool) or not isinstance(value, int | float) or not math.isfinite(value):
                raise ValueError(f"{field.name} must be a finite number, got {value!r}")

    @classmethod
    def from_yaml(cls, path: str | Path) -> HeuristicParams:
        """Defaults overridden by the top-level keys of a YAML mapping; unknown keys are rejected as typos."""
        # The file is chosen by the operator on the command line or in the environment, so any path is intended.
        data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}  # NOSONAR
        if not isinstance(data, dict):
            raise ValueError(f"{path} must contain a YAML mapping of parameter names to values")
        known = {f.name for f in fields(cls)}
        unknown = sorted(set(data) - known)
        if unknown:
            raise ValueError(f"unknown heuristic parameters in {path}: {', '.join(unknown)}")
        return cls(**data)


def _moving_average(values: np.ndarray, seconds: float, window_s: float) -> np.ndarray:
    """Centered moving average over `seconds`, with edge windows averaged over the samples that exist."""
    width = max(1, round(seconds / window_s))
    if width == 1:
        return values
    kernel = np.ones(width)
    total = np.convolve(values, kernel, mode="same")
    counts = np.convolve(np.ones(len(values)), kernel, mode="same")
    return total / counts


def _normalize(values: np.ndarray, params: HeuristicParams, min_range: float, noise_floor: float) -> np.ndarray:
    low, high = np.percentile(values, [params.low_percentile, params.high_percentile])
    low = min(low, noise_floor)
    return np.clip((values - low) / max(high - low, min_range), 0.0, 1.0)


def activity_score(table: FeatureFrame, params: HeuristicParams) -> np.ndarray:
    """Weighted, per-video normalized combination of motion and audio features, per window, in 0..1."""
    window_s = float(table.t_end[0] - table.t_start[0])
    motion = table.roi_motion / (params.outside_motion_weight * table.outside_motion + params.motion_floor)
    motion = _moving_average(motion, params.motion_smooth_s, window_s)
    onset = _moving_average(table.onset_mean.astype(np.float64), params.audio_smooth_s, window_s)
    transients = _moving_average(table.transient_count.astype(np.float64), params.audio_smooth_s, window_s)

    weights = np.array([params.motion_weight, params.onset_weight, params.transient_weight])
    signals = np.stack(
        [
            _normalize(motion, params, params.min_motion_range, params.motion_noise_floor),
            _normalize(onset, params, params.min_onset_range, params.onset_noise_floor),
            _normalize(transients, params, params.min_transient_range, params.transient_noise_floor),
        ]
    )
    return (weights / weights.sum()) @ signals


def rally_probability(score: np.ndarray, params: HeuristicParams) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-params.score_steepness * (score - params.score_midpoint)))


def viterbi_states(probability: np.ndarray, params: HeuristicParams) -> np.ndarray:
    """Most likely dead(0)/rally(1) state per window. Both states are equally likely at the start."""
    eps = 1e-4
    p = np.clip(probability, eps, 1 - eps)
    emission = params.emission_weight * np.stack([np.log(1 - p), np.log(p)], axis=1)
    stay, switch = math.log(1 - params.switch_probability), math.log(params.switch_probability)

    n = len(p)
    best = np.zeros((n, 2))
    back = np.zeros((n, 2), dtype=np.int8)
    best[0] = emission[0]
    for i in range(1, n):
        for state in (0, 1):
            from_same = best[i - 1, state] + stay
            from_other = best[i - 1, 1 - state] + switch
            back[i, state] = state if from_same >= from_other else 1 - state
            best[i, state] = max(from_same, from_other) + emission[i, state]
    states = np.zeros(n, dtype=np.int8)
    states[-1] = int(np.argmax(best[-1]))
    for i in range(n - 1, 0, -1):
        states[i - 1] = back[i, states[i]]
    return states


def build_segments(
    states: np.ndarray, probability: np.ndarray, window_s: float, duration_s: float, params: HeuristicParams
) -> list[Segment]:
    """Turn decoded window states into padded, clamped, non-overlapping segments in milliseconds."""
    runs = _merge_close_runs(_state_runs(states), window_s, params.min_gap_s)
    long_runs = [run for run in runs if (run[1] - run[0]) * window_s >= params.min_rally_s]

    # Each entry is [first_window, last_window, begin_s, end_s]; padding can make neighbours touch or overlap, in
    # which case they form one rally that spans both.
    padded: list[list[float]] = []
    for first, last in long_runs:
        begin = max(0.0, first * window_s - params.pad_before_s)
        end = min(duration_s, last * window_s + params.pad_after_s)
        if padded and begin < padded[-1][3]:
            padded[-1][1], padded[-1][3] = last, end
        else:
            padded.append([first, last, begin, end])

    return [
        Segment(
            start_ms=round(begin * 1000),
            end_ms=round(end * 1000),
            confidence=round(float(probability[int(first) : int(last)].mean()), 3),
        )
        for first, last, begin, end in padded
        if round(end * 1000) > round(begin * 1000)
    ]


def _state_runs(states: np.ndarray) -> list[list[int]]:
    """Half-open `[first, last)` window ranges of consecutive rally windows."""
    edges = np.diff(np.concatenate([[0], states.astype(np.int8), [0]]))
    return [[int(a), int(b)] for a, b in zip(np.flatnonzero(edges == 1), np.flatnonzero(edges == -1), strict=True)]


def _merge_close_runs(runs: list[list[int]], window_s: float, min_gap_s: float) -> list[list[int]]:
    """Join runs separated by less than `min_gap_s`, since a pause that short is not a break between rallies.

    A run edge is only known to within one window, so a real pause of exactly `min_gap_s` can be decoded one window
    shorter; the threshold is lowered by one window so such a pause still counts as a break.
    """
    merged: list[list[int]] = []
    for first, last in runs:
        if merged and (first - merged[-1][1]) * window_s < min_gap_s - window_s:
            merged[-1][1] = last
        else:
            merged.append([first, last])
    return merged


def predict_from_features(table: FeatureFrame, duration_ms: int, params: HeuristicParams) -> Prediction:
    """Everything after feature extraction; separated so it can be tested and tuned on cached features."""
    window_s = float(table.t_end[0] - table.t_start[0])
    probability = rally_probability(activity_score(table, params), params)
    states = viterbi_states(probability, params)
    segments = build_segments(states, probability, window_s, duration_ms / 1000, params)
    return Prediction(
        segments=segments,
        scores=ScoreCurve(hz=1 / window_s, values=[round(float(v), 4) for v in probability]),
        model_version=HEURISTIC_VERSION,
    )


class HeuristicModel:
    version = HEURISTIC_VERSION

    def __init__(
        self,
        params: HeuristicParams | None = None,
        features: FeatureConfig | None = None,
        cache_dir: str | Path | None = None,
    ) -> None:
        self.params = params or HeuristicParams()
        self.features = features or FeatureConfig()
        self.cache_dir = cache_dir

    def predict(self, video_path: Path, court: Court | None, progress: ProgressCallback) -> Prediction:
        progress(0.0)
        duration_ms = probe_duration_ms(video_path)
        roi = court.roi if court else _FULL_FRAME
        try:
            table = extract_features(video_path, roi, self.features, self.cache_dir)
        except ValueError as exc:
            # extract_features raises ValueError only when nothing could be decoded (e.g. no video stream).
            raise InvalidInputError(str(exc)) from exc
        progress(0.9)
        prediction = predict_from_features(table, duration_ms, self.params)
        progress(1.0)
        return prediction
