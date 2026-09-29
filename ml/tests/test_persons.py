import shutil

import numpy as np
import pytest

from snipnet_ml import fixtures
from snipnet_ml.labels import Court, Point, Roi
from snipnet_ml.persons import (
    Detection,
    IouTracker,
    PersonConfig,
    PersonFrame,
    TorchvisionPersonDetector,
    TrackedPerson,
    box_iou,
    extract_person_features,
    person_features,
    select_device,
    track_persons,
)

needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is not installed")

COURT = Court(roi=Roi(x=0.25, y=0.25, width=0.5, height=0.5), net_point=Point(x=0.5, y=0.5))


class ScriptedDetector:
    """Stands in for the model: returns preset boxes in crop pixels, one list per call."""

    def __init__(self, script: list[list[tuple[float, float, float, float]]]) -> None:
        self.script = script
        self.calls = 0
        self.crop_shapes: list[tuple[int, ...]] = []

    def detect(self, image: np.ndarray) -> list[Detection]:
        self.crop_shapes.append(image.shape)
        boxes = self.script[min(self.calls, len(self.script) - 1)]
        self.calls += 1
        return [Detection(box=box, score=0.9) for box in boxes]


def blank_frames(count: int, fps: float, width: int = 200, height: int = 100):
    for i in range(count):
        yield i / fps, np.zeros((height, width, 3), dtype=np.uint8)


def test_tracker_keeps_ids_for_overlapping_boxes_and_starts_new_ones() -> None:
    tracker = IouTracker(match_iou=0.2, max_missed=1)
    first = tracker.update([(0.0, 0.0, 0.1, 0.2), (0.5, 0.5, 0.6, 0.7)])
    second = tracker.update([(0.51, 0.5, 0.61, 0.7), (0.01, 0.0, 0.11, 0.2), (0.9, 0.1, 1.0, 0.3)])
    assert [p.track_id for p in second[:2]] == [first[1].track_id, first[0].track_id]
    assert second[2].track_id not in {first[0].track_id, first[1].track_id}


def test_tracker_keeps_the_id_of_a_box_that_moves_more_than_its_width() -> None:
    tracker = IouTracker(match_iou=0.2, max_missed=1)
    # A 0.1 wide, 0.3 high box jumps 0.25 sideways: no overlap at all, but well inside one box height.
    first = tracker.update([(0.1, 0.1, 0.2, 0.4)])
    second = tracker.update([(0.35, 0.1, 0.45, 0.4)])
    assert box_iou((0.1, 0.1, 0.2, 0.4), (0.35, 0.1, 0.45, 0.4)) == 0.0
    assert second[0].track_id == first[0].track_id


def test_tracker_does_not_link_boxes_further_apart_than_the_foot_gate() -> None:
    tracker = IouTracker(match_iou=0.2, max_missed=1, foot_gate=1.0)
    first = tracker.update([(0.1, 0.1, 0.2, 0.4)])
    second = tracker.update([(0.6, 0.1, 0.7, 0.4)])
    assert second[0].track_id != first[0].track_id


def test_tracker_widens_the_foot_gate_for_a_track_that_missed_a_frame() -> None:
    # The 0.3 high box reappears 0.45 (1.5 box heights) away after one frame without any detection.
    reappeared = (0.55, 0.1, 0.65, 0.4)
    tracker = IouTracker(foot_gate=1.0)
    first = tracker.update([(0.1, 0.1, 0.2, 0.4)])
    tracker.update([])
    assert tracker.update([reappeared])[0].track_id == first[0].track_id
    # Without a miss the same jump is outside the gate, and a capped scale of 1 keeps the gate fixed.
    direct = IouTracker(foot_gate=1.0)
    first = direct.update([(0.1, 0.1, 0.2, 0.4)])
    assert direct.update([reappeared])[0].track_id != first[0].track_id
    fixed = IouTracker(foot_gate=1.0, foot_gate_max_scale=1.0)
    first = fixed.update([(0.1, 0.1, 0.2, 0.4)])
    fixed.update([])
    assert fixed.update([reappeared])[0].track_id != first[0].track_id


def test_tracker_caps_the_foot_gate_growth() -> None:
    # Two frames missed would allow a factor 3, but the default cap of 2 rejects a jump of 2.5 box heights.
    tracker = IouTracker(foot_gate=1.0, max_missed=2)
    first = tracker.update([(0.1, 0.1, 0.2, 0.4)])
    tracker.update([])
    tracker.update([])
    assert tracker.update([(0.85, 0.1, 0.95, 0.4)])[0].track_id != first[0].track_id


def test_tracker_foot_gate_uses_isotropic_distance_and_can_be_disabled() -> None:
    # A normalized x shift of 0.2 is 0.67 heights of the 0.3 high box on a square frame, but on a 2:1 frame it
    # equals 0.4 frame heights, which is more than one box height.
    moved = (0.3, 0.1, 0.4, 0.4)
    square = IouTracker(foot_gate=1.0, aspect=1.0)
    first = square.update([(0.1, 0.1, 0.2, 0.4)])
    assert square.update([moved])[0].track_id == first[0].track_id
    wide = IouTracker(foot_gate=1.0, aspect=2.0)
    first = wide.update([(0.1, 0.1, 0.2, 0.4)])
    assert wide.update([moved])[0].track_id != first[0].track_id
    off = IouTracker(foot_gate=0.0)
    first = off.update([(0.1, 0.1, 0.2, 0.4)])
    assert off.update([moved])[0].track_id != first[0].track_id


def test_tracker_prefers_iou_matches_over_foot_distance_matches() -> None:
    tracker = IouTracker(match_iou=0.2, max_missed=1)
    first = tracker.update([(0.3, 0.1, 0.4, 0.4)])
    # The small box has exactly the track's foot point but hardly overlaps it; the shifted full-size box has a
    # slightly larger foot distance but a high IoU. Foot distance alone would pick the small box.
    small = (0.34, 0.3, 0.36, 0.4)
    shifted = (0.33, 0.1, 0.43, 0.4)
    assert box_iou(first[0].box, small) < 0.2 <= box_iou(first[0].box, shifted)
    second = tracker.update([small, shifted])
    assert second[1].track_id == first[0].track_id
    assert second[0].track_id != first[0].track_id


def test_tracker_drops_tracks_after_too_many_missed_frames() -> None:
    tracker = IouTracker(match_iou=0.2, max_missed=1)
    original = tracker.update([(0.0, 0.0, 0.1, 0.2)])[0].track_id
    tracker.update([])
    assert tracker.update([(0.0, 0.0, 0.1, 0.2)])[0].track_id == original
    tracker.update([])
    tracker.update([])
    assert tracker.update([(0.0, 0.0, 0.1, 0.2)])[0].track_id != original


def test_track_persons_crops_to_roi_and_filters_by_foot_point() -> None:
    config = PersonConfig(roi_expand=0.2, foot_margin=0.0)
    # The frame is 200x100, so the expanded crop starts at pixel (30, 15) and is 140x70. Box A stands in the
    # ROI (foot at full-frame pixel (90, 55)); box B's foot is at pixel (40, 85), left of and below the ROI.
    detector = ScriptedDetector([[(50.0, 10.0, 70.0, 40.0), (5.0, 30.0, 15.0, 70.0)]])
    frames = track_persons(blank_frames(1, 2.5), COURT.roi, detector, config)
    assert detector.crop_shapes == [(70, 140, 3)]
    assert len(frames[0].persons) == 1
    box = frames[0].persons[0].box
    assert box == pytest.approx((0.4, 0.25, 0.5, 0.55))


def test_person_features_count_distance_spread_and_speed() -> None:
    def person(track_id: int, x: float, y: float) -> TrackedPerson:
        return TrackedPerson(track_id, (x - 0.01, y - 0.2, x + 0.01, y))

    frames = [
        PersonFrame(0.0, [person(0, 0.4, 0.5), person(1, 0.6, 0.5)]),
        PersonFrame(0.4, [person(0, 0.4, 0.6), person(1, 0.6, 0.6)]),
        PersonFrame(0.8, []),
    ]
    # Square frame (aspect 1). Frames at 0.0 s and 0.4 s share window 0, the empty frame at 0.8 s is window 1 and
    # window 2 has no frame. Feet start 0.1 from the net, then sqrt(0.02) after both moved 0.1 down.
    table = person_features(frames, COURT, aspect=1.0, duration_s=1.5)
    assert len(table) == 3
    assert table.person_count.tolist() == [2.0, 0.0, 0.0]
    assert table.mean_net_distance[0] == pytest.approx((0.1 + 0.02**0.5) / 2)
    assert table.spread[0] == pytest.approx(0.1)
    # Both persons moved 0.1 frame heights within 0.4 s.
    assert table.mean_speed[0] == pytest.approx(0.25)
    assert not table.mean_speed[1:].any()


def test_empty_windows_are_all_zero() -> None:
    table = person_features([], COURT, aspect=1.78, duration_s=2.0)
    assert len(table) == 4
    for name in ("person_count", "mean_net_distance", "spread", "mean_speed"):
        assert not getattr(table, name).any()


@needs_ffmpeg
def test_extract_person_features_on_synthetic_video_with_mocked_model(tmp_path) -> None:
    video = tmp_path / "clip.mp4"
    labels = fixtures.generate_video(video, duration_s=6, seed=1)
    # Two players walking right inside the crop and one bystander outside the ROI in every frame.
    script = [
        [(20.0 + 4 * i, 20.0, 40.0 + 4 * i, 60.0), (100.0 + 4 * i, 20.0, 120.0 + 4 * i, 60.0), (0.0, 0.0, 5.0, 10.0)]
        for i in range(15)
    ]
    detector = ScriptedDetector(script)
    table = extract_person_features(video, labels.court, detector)
    assert len(table) == 12
    assert detector.calls == 15
    assert table.person_count[2:10].min() == pytest.approx(2.0)
    assert (table.mean_speed[2:10] > 0).all()
    assert (table.spread[2:10] > 0).all()
    assert (table.mean_net_distance[2:10] > 0).all()


def test_select_device_prefers_cuda_then_mps_then_cpu(monkeypatch) -> None:
    import torch

    monkeypatch.setattr(torch.cuda, "is_available", lambda: True)
    assert select_device() == "cuda"
    monkeypatch.setattr(torch.cuda, "is_available", lambda: False)
    monkeypatch.setattr(torch.backends.mps, "is_available", lambda: True)
    assert select_device() == "mps"
    monkeypatch.setattr(torch.backends.mps, "is_available", lambda: False)
    assert select_device() == "cpu"


@pytest.mark.slow
def test_real_torchvision_model_runs_on_a_synthetic_frame(tmp_path) -> None:
    detector = TorchvisionPersonDetector(device="cpu", cache_dir=tmp_path / "models")
    try:
        detections = detector.detect(np.full((240, 320, 3), 127, dtype=np.uint8))
    except OSError as error:
        pytest.skip(f"model weights could not be downloaded: {error}")
    # A flat gray image contains no persons; the point is that the real weights load and inference runs.
    assert detections == []
    assert any((tmp_path / "models").glob("*.pth"))
