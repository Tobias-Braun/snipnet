import json
import shutil
import subprocess

import pytest

from snipnet_ml import fixtures
from snipnet_ml.labels import Labels, load_labels, save_labels

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


@needs_ffmpeg
def test_cli_writes_video_and_labels(tmp_path) -> None:
    out = tmp_path / "f.mp4"
    fixtures.main([str(out), "--seed", "1", "--duration", "15"])
    labels = load_labels(tmp_path / "f.labels.json")
    assert labels.video == "f.mp4"
    assert out.stat().st_size > 0
