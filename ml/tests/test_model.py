import shutil
import subprocess
from pathlib import Path

import pytest

from snipnet_ml import DummyModel, InvalidInputError, load_model

needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is required to build test videos")


@pytest.fixture
def nine_second_video(tmp_path: Path) -> Path:
    path = tmp_path / "clip.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "color=c=black:s=64x64:r=15:d=9",
            "-pix_fmt",
            "yuv420p",
            str(path),
        ],
        check=True,
    )
    return path


@needs_ffmpeg
def test_dummy_model_uses_the_origin_relative_duration_of_an_offset_proxy(
    nine_second_video: Path, tmp_path: Path
) -> None:
    shifted = tmp_path / "shifted.mkv"
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-i",
            str(nine_second_video),
            "-c",
            "copy",
            "-output_ts_offset",
            "3.7",
            str(shifted),
        ],
        check=True,
    )

    prediction = DummyModel().predict(shifted, None, lambda _: None)

    # The declared duration is 12.7 s but the real content is 9 s, so the middle third is 3 s..6 s.
    [segment] = prediction.segments
    assert segment.start_ms == pytest.approx(3000, abs=300)
    assert segment.end_ms == pytest.approx(6000, abs=300)
    assert prediction.scores is not None
    assert len(prediction.scores.values) == pytest.approx(9, abs=1)


@needs_ffmpeg
def test_dummy_model_returns_one_rally_over_the_middle_third(nine_second_video: Path) -> None:
    reported: list[float] = []

    prediction = DummyModel().predict(nine_second_video, None, reported.append)

    assert prediction.model_version == "dummy-v0"
    [segment] = prediction.segments
    assert 2900 <= segment.start_ms <= 3100
    assert 5900 <= segment.end_ms <= 6100
    assert prediction.scores is not None
    assert prediction.scores.hz == 1.0
    assert prediction.scores.values == [0, 0, 0, 1, 1, 1, 0, 0, 0]
    assert reported[0] == 0.0
    assert reported[-1] == 1.0


@needs_ffmpeg
def test_dummy_model_rejects_unreadable_media(tmp_path: Path) -> None:
    broken = tmp_path / "broken.mp4"
    broken.write_bytes(b"not a video")

    with pytest.raises(InvalidInputError):
        DummyModel().predict(broken, None, lambda _: None)


def test_load_model_resolves_names() -> None:
    assert isinstance(load_model("dummy-v0"), DummyModel)
    with pytest.raises(ValueError, match="nope"):
        load_model("nope")
