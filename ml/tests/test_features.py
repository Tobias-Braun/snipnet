import shutil
import subprocess
import time

import numpy as np
import pytest

from snipnet_ml import features, fixtures
from snipnet_ml.features import FEATURE_VERSION, FeatureConfig, FeatureFrame, extract_features
from snipnet_ml.labels import Labels, Roi

needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is not installed")


def rally_mask(labels: Labels, table: FeatureFrame, margin_s: float = 0.5) -> tuple[np.ndarray, np.ndarray]:
    """Windows fully inside a rally versus windows at least `margin_s` away from every rally."""
    inside = np.zeros(len(table), dtype=bool)
    dead = np.ones(len(table), dtype=bool)
    for rally in labels.rallies:
        start, end = rally.start_ms / 1000, rally.end_ms / 1000
        inside |= (table.t_start >= start) & (table.t_end <= end)
        dead &= (table.t_end < start - margin_s) | (table.t_start > end + margin_s)
    return inside, dead


@pytest.fixture(scope="module")
def synthetic(tmp_path_factory):
    directory = tmp_path_factory.mktemp("features")
    video = directory / "clip.mp4"
    labels = fixtures.generate_video(video, duration_s=45, seed=3)
    return video, labels


@needs_ffmpeg
def test_rally_windows_have_higher_motion_and_audio_than_dead_time(synthetic) -> None:
    video, labels = synthetic
    table = extract_features(video, labels.court.roi)
    inside, dead = rally_mask(labels, table)
    assert inside.sum() >= 10
    assert dead.sum() >= 10

    assert table.roi_motion[inside].mean() > 4 * table.roi_motion[dead].mean()
    assert table.onset_mean[inside].mean() > 3 * table.onset_mean[dead].mean()
    assert table.transient_count[inside].sum() > 5
    assert table.transient_count[dead].sum() <= 1
    # Distractors move all the time, so outside motion must not separate rallies from dead time.
    assert table.outside_motion[inside].mean() < 2 * table.outside_motion[dead].mean()
    assert table.outside_motion[dead].mean() > 0


@needs_ffmpeg
def test_table_shape_and_timestamps(synthetic) -> None:
    video, labels = synthetic
    table = extract_features(video, labels.court.roi)
    assert len(table) == 90
    assert table.t_start[0] == 0
    assert np.allclose(np.diff(table.t_start), 0.5)
    assert np.allclose(table.t_end - table.t_start, 0.5)
    assert list(table.to_dataframe().columns) == list(FeatureFrame.DTYPES)


@needs_ffmpeg
def test_cache_roundtrip_and_key_changes(synthetic, tmp_path, monkeypatch) -> None:
    video, labels = synthetic
    roi = labels.court.roi
    first = extract_features(video, roi, cache_dir=tmp_path)
    assert len(list(tmp_path.glob("*.npz"))) == 1

    # A second call must be served from the cache without decoding anything.
    monkeypatch.setattr(features, "compute_features", lambda *a, **k: pytest.fail("cache was not used"))
    second = extract_features(video, roi, cache_dir=tmp_path)
    for name in FeatureFrame.DTYPES:
        assert np.array_equal(getattr(first, name), getattr(second, name))

    video_hash = features.hash_video(video)
    base = features.cache_key(video_hash, roi, FeatureConfig())
    assert base != features.cache_key(video_hash, roi, FeatureConfig(fps=10))
    assert base != features.cache_key(video_hash, roi.model_copy(update={"x": 0.1}), FeatureConfig())
    assert base != features.cache_key("0" * 64, roi, FeatureConfig())
    assert f"-v{FEATURE_VERSION}-" in base


def test_stale_version_cache_is_rejected(tmp_path) -> None:
    empty = FeatureFrame.from_arrays(**{name: np.zeros(2) for name in FeatureFrame.DTYPES})
    path = tmp_path / "x.npz"
    empty.save(path)
    assert len(FeatureFrame.load(path)) == 2
    with np.load(path) as stored:
        data = dict(stored)
    data["version"] = np.asarray(FEATURE_VERSION + 1)
    np.savez(path, **data)
    with pytest.raises(ValueError, match="version"):
        FeatureFrame.load(path)


def test_motion_ignores_global_brightness_shift() -> None:
    rng = np.random.default_rng(0)
    base = rng.integers(40, 200, size=(48, 64), dtype=np.uint8)
    brighter = np.clip(base.astype(int) + 30, 0, 255).astype(np.uint8)
    moved = base.copy()
    moved[10:30, 20:40] = 255 - moved[10:30, 20:40]
    times = [0.0, 0.2, 0.4]
    whole_frame = Roi(x=0, y=0, width=1, height=1)
    lit = features.motion_series(zip(times, [base, brighter, base], strict=True), whole_frame, 0.0)
    real = features.motion_series(zip(times, [base, moved, base], strict=True), whole_frame, 0.0)
    assert np.array_equal(lit.times, [0.2, 0.4])
    assert lit.roi.max() < 0.5
    assert real.roi.min() > 10 * lit.roi.max()


def test_motion_series_rejects_empty_video() -> None:
    with pytest.raises(ValueError, match="no video frames"):
        features.motion_series([], Roi(x=0, y=0, width=1, height=1), 0.0)


@needs_ffmpeg
def test_corrupt_cache_file_is_recomputed(synthetic, tmp_path) -> None:
    video, labels = synthetic
    roi = labels.court.roi
    expected = extract_features(video, roi, cache_dir=tmp_path)
    (cache_file,) = tmp_path.glob("*.npz")
    cache_file.write_bytes(cache_file.read_bytes()[:100])

    recovered = extract_features(video, roi, cache_dir=tmp_path)
    assert np.array_equal(recovered.roi_motion, expected.roi_motion)
    # The rewritten entry is complete again and no temporary files are left behind.
    assert len(FeatureFrame.load(cache_file)) == len(expected)
    assert list(tmp_path.iterdir()) == [cache_file]


def test_expanded_box_is_clipped_to_frame() -> None:
    box = features.expanded_pixel_box(Roi(x=0.0, y=0.5, width=0.5, height=0.5), 0.2, 100, 100)
    assert box == (0, 40, 60, 100)


@needs_ffmpeg
@pytest.mark.slow
def test_throughput_is_at_least_20x_realtime(tmp_path, capsys) -> None:
    video = tmp_path / "bench.mp4"
    labels = fixtures.generate_video(video, duration_s=120, seed=5)
    features.compute_features(video, labels.court.roi)  # warm up numba/librosa JIT and file caches
    started = time.perf_counter()
    features.compute_features(video, labels.court.roi)
    elapsed = time.perf_counter() - started
    speed = 120 / elapsed
    with capsys.disabled():
        print(f"\nfeature extraction throughput: {speed:.1f}x realtime ({elapsed:.2f}s for 120s of 480x270)")
    assert speed >= 20


@needs_ffmpeg
def test_wrappers_share_the_resampling_of_decode_frames(tmp_path) -> None:
    from snipnet_ml.persons import decode_rgb_frames

    video = tmp_path / "clip.mp4"
    fixtures.generate_video(video, duration_s=3, seed=2)
    gray = list(features.decode_gray_frames(video, 5.0))
    rgb = list(decode_rgb_frames(video, 5.0))
    assert [t for t, _ in gray] == [t for t, _ in rgb]
    assert len(gray) >= 14
    assert gray[0][1].ndim == 2
    assert rgb[0][1].ndim == 3
    assert rgb[0][1].shape[2] == 3
    direct = list(features.decode_frames(video, 5.0, "gray"))
    assert [t for t, _ in direct] == [t for t, _ in gray]
    assert all(np.array_equal(a[1], b[1]) for a, b in zip(direct, gray, strict=True))


def _ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *args], check=True)


@needs_ffmpeg
def test_offset_stream_start_keeps_rally_windows_aligned(synthetic, tmp_path) -> None:
    video, labels = synthetic
    shifted = tmp_path / "shifted.mkv"
    _ffmpeg("-i", str(video), "-c", "copy", "-output_ts_offset", "3.7", str(shifted))
    with features.av.open(str(shifted)) as container:
        assert features.stream_origin(container) == pytest.approx(3.7, abs=0.05)

    baseline = extract_features(video, labels.court.roi)
    table = extract_features(shifted, labels.court.roi)
    assert len(table) == len(baseline)
    assert table.t_start[0] == 0
    inside, dead = rally_mask(labels, table)
    assert table.roi_motion[inside].mean() > 4 * table.roi_motion[dead].mean()
    assert table.onset_mean[inside].mean() > 3 * table.onset_mean[dead].mean()
    # The shifted proxy carries the same content, so its windows must match the unshifted ones closely.
    assert np.corrcoef(table.roi_motion, baseline.roi_motion)[0, 1] > 0.98
    assert np.corrcoef(table.onset_mean, baseline.onset_mean)[0, 1] > 0.9


@needs_ffmpeg
def test_audio_starting_after_video_is_padded_to_the_video_origin(synthetic, tmp_path) -> None:
    video, _ = synthetic
    delayed = tmp_path / "delayed.mkv"
    _ffmpeg(
        *("-i", str(video), "-itsoffset", "1.0", "-i", str(video)),
        *("-map", "0:v", "-map", "1:a", "-c", "copy", str(delayed)),
    )
    rate = 16000
    plain = features.decode_audio(video, rate)
    padded = features.decode_audio(delayed, rate)
    lead = rate
    assert len(padded) >= len(plain) + lead - rate // 10
    assert np.abs(padded[: lead - 800]).max() < 1e-3
    # Hits in the padded track sit one second later than in the plain one.
    plain_peak = int(np.argmax(np.abs(plain[: 5 * rate])))
    padded_peak = int(np.argmax(np.abs(padded[: 6 * rate])))
    assert padded_peak - plain_peak == pytest.approx(lead, abs=rate // 50)


def _click_train(gain: float, noise: float, seconds: int = 10, rate: int = 16000) -> np.ndarray:
    """Noise floor plus one broadband click every 0.4 s, all scaled by `gain` like a hotter or quieter recording."""
    rng = np.random.default_rng(0)
    samples = rng.normal(0.0, noise, seconds * rate)
    for start in range(rate // 2, (seconds - 1) * rate, int(0.4 * rate)):
        samples[start : start + 64] += rng.normal(0.0, 0.5, 64)
    return (samples * gain).astype(np.float32)


@pytest.mark.parametrize("gain", [0.05, 0.3, 1.0, 4.0])
def test_transient_count_is_independent_of_recording_gain(gain) -> None:
    # The onset envelope is a log-power flux, so a pure gain change must cancel out end to end.
    config = FeatureConfig()
    n_windows = 20
    reference = features.audio_features(_click_train(1.0, 0.002), config, n_windows)[2].sum()
    assert reference >= 20
    assert features.audio_features(_click_train(gain, 0.002), config, n_windows)[2].sum() == reference


def _hits_and_footsteps(frames: int, hit_every: int, hit: float, footstep: float) -> tuple[np.ndarray, np.ndarray]:
    """Onset envelope with a hit spike every `hit_every` frames and three weaker spikes (footsteps, voices) between.

    Returns the envelope and the hit frame indices.
    """
    envelope = np.zeros(frames)
    hits = np.arange(100, frames - hit_every, hit_every)
    envelope[hits] = hit
    for offset in (1, 2, 3):
        envelope[hits + offset * hit_every // 4] = footstep
    return envelope, hits


@pytest.mark.parametrize(("hit", "footstep"), [(20.0, 5.0), (8.0, 2.0)])
def test_transient_threshold_follows_the_videos_hit_level(hit, footstep) -> None:
    # A close microphone (hits 20, footsteps 5) and a distant one (both 2.5x weaker) must count the same events; an
    # absolute threshold between 2 and 5 would count the footsteps only on the close one.
    envelope, hits = _hits_and_footsteps(4000, 80, hit, footstep)
    np.testing.assert_array_equal(features.transient_peaks(envelope, FeatureConfig()), hits)


def test_transient_reference_is_not_diluted_by_long_quiet_stretches() -> None:
    # About 50 hits in 40000 frames (5 minutes) cover 0.1% of the frames, so any percentile of the raw frames is 0.
    envelope, hits = _hits_and_footsteps(40000, 800, 20.0, 5.0)
    np.testing.assert_array_equal(features.transient_peaks(envelope, FeatureConfig()), hits)


def test_transient_threshold_ignores_pure_noise() -> None:
    noise = np.random.default_rng(1).uniform(0.0, 1.0, 2000)
    assert len(features.transient_peaks(noise, FeatureConfig())) == 0


def test_transient_config_is_validated_and_part_of_the_cache_key() -> None:
    with pytest.raises(ValueError, match="transient_delta"):
        FeatureConfig(transient_delta=0)
    with pytest.raises(ValueError, match="percentile"):
        FeatureConfig(transient_reference_percentile=101)
    roi = Roi(x=0.1, y=0.1, width=0.5, height=0.5)
    assert features.cache_key("a" * 64, roi, FeatureConfig()) != features.cache_key(
        "a" * 64, roi, FeatureConfig(transient_delta=0.2)
    )
