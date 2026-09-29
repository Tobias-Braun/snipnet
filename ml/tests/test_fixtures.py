import array
import json
import shutil
import subprocess

import pytest

from snipnet_ml import fixtures
from snipnet_ml.labels import Court, Labels, Point, Roi, load_labels, save_labels

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None, reason="ffmpeg is not installed"
)


def test_plan_is_deterministic_and_valid() -> None:
    a = fixtures.plan_rallies(60, 1)
    assert a == fixtures.plan_rallies(60, 1)
    assert a != fixtures.plan_rallies(60, 2)
    assert len(a) >= 2
    assert all(x.end_ms <= 60_000 for x in a)
    assert all(a[i].end_ms < a[i + 1].start_ms for i in range(len(a) - 1))


def test_labels_roundtrip(tmp_path) -> None:
    labels = Labels(
        video="x.mp4", duration_ms=1000, court=fixtures.DEFAULT_COURT, rallies=[{"startMs": 0, "endMs": 500}]
    )
    save_labels(labels, tmp_path / "l.json")
    assert json.loads((tmp_path / "l.json").read_text())["durationMs"] == 1000
    assert load_labels(tmp_path / "l.json") == labels


def test_labels_reject_overlap_and_overrun() -> None:
    court = fixtures.DEFAULT_COURT
    overlapping = [{"startMs": 0, "endMs": 600}, {"startMs": 500, "endMs": 900}]
    with pytest.raises(ValueError, match="overlap"):
        Labels(video="x", duration_ms=1000, court=court, rallies=overlapping)
    with pytest.raises(ValueError, match="duration"):
        Labels(video="x", duration_ms=1000, court=court, rallies=[{"startMs": 0, "endMs": 1500}])


@needs_ffmpeg
def test_generated_video_matches_proxy_format(tmp_path) -> None:
    out = tmp_path / "f.mp4"
    labels = fixtures.generate_video(out, duration_s=20, seed=3)
    assert labels.rallies
    probe = subprocess.run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "stream=codec_name,r_frame_rate,sample_rate,channels",
            "-of", "json", str(out),
        ],
        capture_output=True, text=True, check=True,
    )  # fmt: skip
    streams = {s["codec_name"]: s for s in json.loads(probe.stdout)["streams"]}
    assert streams["h264"]["r_frame_rate"] == "15/1"
    assert streams["aac"]["sample_rate"] == "16000"
    assert streams["aac"]["channels"] == 1


def _decode(path, *args: str) -> bytes:
    command = ["ffmpeg", "-v", "error", "-i", str(path), *args, "-"]
    return subprocess.run(command, capture_output=True, check=True).stdout


def _luma_motion_per_second(path, crop: str) -> list[int]:
    """Summed absolute grayscale difference between consecutive frames of a crop, bucketed per second."""
    width, height = (int(v) for v in crop.split(":")[:2])
    raw = _decode(path, "-vf", f"crop={crop},format=gray", "-f", "rawvideo")
    size = width * height
    frames = [raw[i : i + size] for i in range(0, len(raw) - size + 1, size)]
    diffs = [sum(abs(a - b) for a, b in zip(frames[i], frames[i - 1], strict=True)) for i in range(1, len(frames))]
    return [sum(diffs[s * fixtures.FPS : (s + 1) * fixtures.FPS]) for s in range(len(diffs) // fixtures.FPS)]


def _audio_peak_per_second(path) -> list[int]:
    pcm = array.array("h", _decode(path, "-f", "s16le", "-ac", "1", "-ar", str(fixtures.SAMPLE_RATE)))
    rate = fixtures.SAMPLE_RATE
    return [max(map(abs, pcm[s * rate : (s + 1) * rate])) for s in range(len(pcm) // rate)]


@needs_ffmpeg
def test_generated_video_content_follows_the_labels(tmp_path) -> None:
    out = tmp_path / "f.mp4"
    labels = fixtures.generate_video(out, duration_s=20, seed=3)

    # Seconds that lie entirely inside or entirely outside all rallies, with a margin for encoder smear.
    def inside(second: int) -> bool:
        return any(r.start_ms + 200 <= second * 1000 and (second + 1) * 1000 <= r.end_ms - 200 for r in labels.rallies)

    def outside(second: int) -> bool:
        return all((second + 1) * 1000 <= r.start_ms - 200 or second * 1000 >= r.end_ms + 200 for r in labels.rallies)

    # Inner ROI of the default court (x 120..360, y 54..216) without its border line, and the left margin strip.
    roi_motion = _luma_motion_per_second(out, "232:154:124:58")
    edge_motion = _luma_motion_per_second(out, "60:270:0:0")
    peaks = _audio_peak_per_second(out)
    seconds = range(min(len(roi_motion), len(peaks)))
    assert any(inside(s) for s in seconds)
    assert any(outside(s) for s in seconds)
    # A moving blob scores in the hundreds of thousands per second; static content stays far below 20k, which
    # leaves room for the slight re-quantization when the encoder inserts a keyframe.
    for s in seconds:
        assert edge_motion[s] > 50_000, f"edge distractor not visible in grayscale at {s}s"
        if inside(s):
            assert roi_motion[s] > 100_000, f"no ROI motion during rally at {s}s"
            assert peaks[s] > 10_000, f"no click sound during rally at {s}s"
        elif outside(s):
            assert roi_motion[s] < 20_000, f"ROI motion during dead time at {s}s"
            assert peaks[s] < 500, f"sound during dead time at {s}s"


def test_generate_video_rejects_non_positive_duration(tmp_path) -> None:
    with pytest.raises(ValueError, match="positive"):
        fixtures.generate_video(tmp_path / "f.mp4", duration_s=0)


@needs_ffmpeg
def test_cli_writes_video_and_labels(tmp_path) -> None:
    out = tmp_path / "f.mp4"
    fixtures.main([str(out), "--seed", "1", "--duration", "15"])
    labels = load_labels(tmp_path / "f.labels.json")
    assert labels.video == "f.mp4"
    assert out.stat().st_size > 0


def _court(x: float, width: float) -> Court:
    return Court(roi=Roi(x=x, y=0.2, width=width, height=0.6), net_point=Point(x=x + width / 2, y=0.5))


def test_distractors_stay_outside_the_roi_for_custom_courts() -> None:
    for court in (fixtures.DEFAULT_COURT, _court(0.0, 0.75), _court(0.3, 0.7), _court(0.1, 0.5)):
        roi = court.roi
        for x in fixtures._distractor_columns(court):
            end = x + fixtures._DISTRACTOR_SIZE
            assert end <= roi.x * fixtures.WIDTH or x >= (roi.x + roi.width) * fixtures.WIDTH
            assert x >= 0
            assert end <= fixtures.WIDTH


def test_court_without_margin_is_rejected(tmp_path) -> None:
    with pytest.raises(ValueError, match="no margin"):
        fixtures.generate_video(tmp_path / "f.mp4", duration_s=10, court=_court(0.02, 0.96))


@needs_ffmpeg
def test_no_distractor_motion_inside_a_wide_custom_court(tmp_path) -> None:
    # The ROI spans x 0..360, so only the right margin is free and both distractors must live there.
    out = tmp_path / "f.mp4"
    labels = fixtures.generate_video(out, duration_s=20, seed=3, court=_court(0.0, 0.75))

    def outside(second: int) -> bool:
        return all((second + 1) * 1000 <= r.start_ms - 200 or second * 1000 >= r.end_ms + 200 for r in labels.rallies)

    roi_motion = _luma_motion_per_second(out, "340:154:4:58")
    margin_motion = _luma_motion_per_second(out, "116:270:364:0")
    quiet = [s for s in range(len(roi_motion)) if outside(s)]
    assert quiet
    for s in quiet:
        assert roi_motion[s] < 20_000, f"distractor motion inside the ROI at {s}s"
        assert margin_motion[s] > 50_000, f"distractor not visible in the right margin at {s}s"
