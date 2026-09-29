import shutil
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
