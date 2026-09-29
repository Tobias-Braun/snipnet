import math
import shutil
import subprocess

import numpy as np
import pytest

from snipnet_ml.court_detect import (
    HIGH_CONFIDENCE,
    detect_court,
    detect_net,
    fit_ellipse,
    suggest_from_frames,
    suggested_roi,
)
from snipnet_ml.labels import Point
from snipnet_ml.model import InvalidInputError

WIDTH, HEIGHT = 480, 270
YELLOW = (240, 220, 20)


def grass() -> np.ndarray:
    """A noisy green background so thresholds are not tested against perfectly flat colors."""
    rng = np.random.default_rng(0)
    image = np.empty((HEIGHT, WIDTH, 3), dtype=np.uint8)
    image[:] = (70, 120, 60)
    noise = rng.integers(-12, 12, size=image.shape)
    return np.clip(image + noise, 0, 255).astype(np.uint8)


def draw_ring(
    image: np.ndarray,
    center: tuple[float, float],
    radii: tuple[float, float],
    angle: float = 0.0,
    thickness: int = 3,
    skip: tuple[float, float] | None = None,
) -> None:
    """Draw an elliptical rim; `skip` is an angle range (radians) left empty, like a player hiding the rim."""
    for t in np.linspace(0, 2 * math.pi, 1500, endpoint=False):
        if skip and skip[0] <= t <= skip[1]:
            continue
        px = radii[0] * math.cos(t)
        py = radii[1] * math.sin(t)
        x = center[0] + px * math.cos(angle) - py * math.sin(angle)
        y = center[1] + px * math.sin(angle) + py * math.cos(angle)
        image[
            int(y) - thickness // 2 : int(y) + thickness // 2 + 1, int(x) - thickness // 2 : int(x) + thickness // 2 + 1
        ] = YELLOW


def draw_disc(image: np.ndarray, center: tuple[int, int], radius: int) -> None:
    yy, xx = np.ogrid[:HEIGHT, :WIDTH]
    image[(xx - center[0]) ** 2 + (yy - center[1]) ** 2 <= radius**2] = YELLOW


def test_fit_ellipse_recovers_a_rotated_ellipse() -> None:
    t = np.linspace(0, 2 * math.pi, 200, endpoint=False)
    angle = 0.5
    px, py = 40 * np.cos(t), 15 * np.sin(t)
    x = 100 + px * math.cos(angle) - py * math.sin(angle)
    y = 60 + px * math.sin(angle) + py * math.cos(angle)
    cx, cy, major, minor, fitted_angle = fit_ellipse(x, y)
    assert (cx, cy) == pytest.approx((100, 60), abs=1e-6)
    assert (major, minor) == pytest.approx((40, 15), abs=1e-6)
    assert math.cos(fitted_angle - angle) ** 2 == pytest.approx(1, abs=1e-6)


def test_fit_ellipse_rejects_collinear_points() -> None:
    line = np.arange(30, dtype=float)
    assert fit_ellipse(line, 2 * line + 1) is None


def test_detects_a_centered_net_with_high_confidence() -> None:
    image = grass()
    draw_ring(image, (240, 135), (28, 12), angle=0.1)
    detection = detect_net(image)
    assert detection is not None
    assert detection.net_point.x == pytest.approx(0.5, abs=0.01)
    assert detection.net_point.y == pytest.approx(0.5, abs=0.02)
    assert detection.major == pytest.approx(56 / WIDTH, rel=0.15)
    assert detection.confidence >= HIGH_CONFIDENCE


def test_detects_a_circular_net_seen_from_above() -> None:
    image = grass()
    draw_ring(image, (250, 120), (22, 22))
    detection = detect_net(image)
    assert detection is not None
    assert detection.net_point.x == pytest.approx(250 / WIDTH, abs=0.02)
    assert detection.net_point.y == pytest.approx(120 / HEIGHT, abs=0.02)
    assert detection.minor / detection.major == pytest.approx(1.0, abs=0.1)


def test_a_partly_hidden_rim_is_still_found_but_less_certain() -> None:
    full, hidden = grass(), grass()
    draw_ring(full, (240, 135), (28, 14))
    draw_ring(hidden, (240, 135), (28, 14), skip=(0.5, 2.5))
    complete, partial = detect_net(full), detect_net(hidden)
    assert complete is not None
    assert partial is not None
    assert partial.net_point.x == pytest.approx(0.5, abs=0.03)
    assert partial.confidence < complete.confidence


def test_a_solid_yellow_blob_is_not_a_net() -> None:
    image = grass()
    draw_disc(image, (240, 135), 20)
    assert detect_net(image) is None


def test_a_frame_without_yellow_has_no_net() -> None:
    assert detect_net(grass()) is None


def test_prefers_the_central_net_over_a_ring_on_a_background_court() -> None:
    image = grass()
    draw_ring(image, (235, 130), (26, 12))
    draw_ring(image, (60, 60), (26, 12))
    detection = detect_net(image)
    assert detection is not None
    assert detection.net_point.x == pytest.approx(235 / WIDTH, abs=0.02)


def test_a_net_far_from_the_center_scores_low() -> None:
    image = grass()
    draw_ring(image, (60, 60), (26, 12))
    detection = detect_net(image)
    assert detection is None or detection.confidence < HIGH_CONFIDENCE


def test_suggested_roi_is_centered_inside_the_frame_and_grows_with_the_net() -> None:
    small = suggested_roi(Point(x=0.5, y=0.5), 0.04)
    large = suggested_roi(Point(x=0.5, y=0.5), 0.08)
    assert large.width > small.width
    assert small.x + small.width / 2 == pytest.approx(0.5)
    edge = suggested_roi(Point(x=0.97, y=0.03), 0.08)
    assert 0 <= edge.x <= 1 - edge.width
    assert 0 <= edge.y <= 1 - edge.height


def test_video_suggestion_is_confident_when_frames_agree() -> None:
    frames = []
    for _ in range(5):
        frame = grass()
        draw_ring(frame, (240, 135), (28, 12))
        frames.append(frame)
    suggestion = suggest_from_frames(frames)
    assert suggestion is not None
    assert suggestion.confidence >= HIGH_CONFIDENCE
    assert suggestion.court.net_point.x == pytest.approx(0.5, abs=0.01)


def test_video_suggestion_is_not_confident_when_only_one_frame_finds_a_net() -> None:
    lucky = grass()
    draw_ring(lucky, (240, 135), (28, 12))
    suggestion = suggest_from_frames([lucky, grass(), grass(), grass(), grass()])
    assert suggestion is not None
    assert suggestion.confidence < HIGH_CONFIDENCE


def test_video_suggestion_is_not_confident_when_detections_disagree() -> None:
    frames = []
    for x in (200, 240, 280):
        frame = grass()
        draw_ring(frame, (x, 135), (28, 12))
        frames.append(frame)
    suggestion = suggest_from_frames(frames)
    assert suggestion is not None
    assert suggestion.confidence < HIGH_CONFIDENCE


def test_no_frames_or_no_net_yields_no_suggestion() -> None:
    assert suggest_from_frames([]) is None
    assert suggest_from_frames([grass(), grass()]) is None


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is not installed")
def test_detect_court_reads_frames_from_a_video(tmp_path) -> None:
    raw = tmp_path / "net.rgb"
    frame = grass()
    draw_ring(frame, (240, 135), (28, 12))
    raw.write_bytes(frame.tobytes() * 30)
    video = tmp_path / "net.mp4"
    subprocess.run(
        [
            "ffmpeg", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{WIDTH}x{HEIGHT}",
            "-r", "15", "-i", str(raw), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", str(video),
        ],
        check=True,
    )  # fmt: skip
    suggestion = detect_court(video)
    assert suggestion is not None
    assert suggestion.confidence >= HIGH_CONFIDENCE
    assert suggestion.court.net_point.x == pytest.approx(0.5, abs=0.02)


def test_detect_court_rejects_a_file_that_is_not_a_video(tmp_path) -> None:
    garbage = tmp_path / "broken.mp4"
    garbage.write_bytes(b"not a video at all")
    with pytest.raises(InvalidInputError):
        detect_court(garbage)


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is not installed")
def test_detect_court_rejects_audio_only_media(tmp_path) -> None:
    audio = tmp_path / "audio.mp4"
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=duration=1", "-c:a", "aac", str(audio)],
        check=True,
    )
    with pytest.raises(InvalidInputError):
        detect_court(audio)
