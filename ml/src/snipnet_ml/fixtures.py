"""Deterministic synthetic proxy-format videos for tests and model regression checks.

The video mimics what the heuristic model has to separate: a static "court" background, distractor motion at the
frame edges that never stops (other courts in the background) and, only during the ground-truth rally
intervals, moving blobs inside the central court ROI together with short click sounds (ball hits). Everything
is rendered by a single ffmpeg invocation from lavfi sources, so no footage is needed and the same seed always
yields the same rallies.
"""

from __future__ import annotations

import argparse
import random
import shutil
import subprocess
from pathlib import Path

from snipnet_ml.labels import Court, Labels, Point, Rally, Roi, save_labels

WIDTH = 480
HEIGHT = 270
FPS = 15
SAMPLE_RATE = 16000

DEFAULT_COURT = Court(roi=Roi(x=0.25, y=0.2, width=0.5, height=0.6), net_point=Point(x=0.5, y=0.5))

_MIN_RALLY_S = 5.0
_MAX_RALLY_S = 9.0
_MIN_GAP_S = 3.0
_CLICK_PERIOD_S = 0.7
_DISTRACTOR_SIZE = 20
# Free pixels required in a margin beside the ROI (on top of the distractor itself) so that neither the moving
# square nor the encoder's motion smear touches the ROI or the frame edge.
_MARGIN_CLEARANCE = 8


def ffmpeg_available() -> bool:
    return shutil.which("ffmpeg") is not None


def plan_rallies(duration_s: float, seed: int) -> list[Rally]:
    """Lay out non-overlapping rallies with random lengths and gaps, reproducibly for a seed."""
    rng = random.Random(seed)
    rallies: list[Rally] = []
    cursor = rng.uniform(_MIN_GAP_S, _MIN_GAP_S + 2)
    while True:
        length = rng.uniform(_MIN_RALLY_S, _MAX_RALLY_S)
        if cursor + length + _MIN_GAP_S > duration_s:
            break
        rallies.append(Rally(start_ms=round(cursor * 1000), end_ms=round((cursor + length) * 1000)))
        cursor += length + rng.uniform(_MIN_GAP_S, _MIN_GAP_S + 4)
    return rallies


def _active(rallies: list[Rally]) -> str:
    """ffmpeg expression that is non-zero while `t` lies inside any rally."""
    return "+".join(f"between(t,{r.start_ms / 1000:.3f},{r.end_ms / 1000:.3f})" for r in rallies) or "0"


def _click_expression(rallies: list[Rally]) -> str:
    """Audio expression: a decaying 2.5 kHz burst every `_CLICK_PERIOD_S` seconds inside each rally."""
    terms = []
    for rally in rallies:
        start = rally.start_ms / 1000
        phase = f"mod(t-{start:.3f},{_CLICK_PERIOD_S})"
        terms.append(f"between(t,{start:.3f},{rally.end_ms / 1000:.3f})*exp(-{phase}*120)*sin(2*PI*2500*t)*0.8")
    return "+".join(terms) or "0"


def _distractor_columns(court: Court) -> list[int]:
    """Left x pixel positions of the two edge distractors, both outside the court ROI.

    Each side margin (left of and right of the ROI) that is wide enough for a distractor plus clearance is used.
    With both margins available one distractor goes to each; with only one, both share it at different x
    positions. Raises ValueError for a court that leaves no usable margin, since the fixture could then not
    keep motion outside the rallies away from the ROI. The positions are whole pixels, exactly as they are passed
    to the overlay filter, so that a check on them covers what is actually rendered.
    """
    roi = court.roi
    right_start = (roi.x + roi.width) * WIDTH
    needed = _DISTRACTOR_SIZE + _MARGIN_CLEARANCE
    # (start, width) of each margin that fits a distractor.
    margins = [m for m in ((0.0, roi.x * WIDTH), (right_start, WIDTH - right_start)) if m[1] >= needed]
    if not margins:
        raise ValueError(
            f"court ROI leaves no margin of at least {needed}px left or right of it in the {WIDTH}px frame "
            "for the edge distractors"
        )
    first_start, first_width = margins[0]
    last_start, last_width = margins[-1]
    return [
        round(first_start + 0.1 * (first_width - _DISTRACTOR_SIZE)),
        round(last_start + 0.72 * (last_width - _DISTRACTOR_SIZE)),
    ]


def _filter_graph(rallies: list[Rally], court: Court, rng: random.Random) -> str:
    roi = court.roi
    left, top = roi.x * WIDTH, roi.y * HEIGHT
    half_w, half_h = roi.width * WIDTH / 2, roi.height * HEIGHT / 2
    centre_x, centre_y = left + half_w, top + half_h
    active = _active(rallies)

    filters = [
        f"color=c=0x3f7f3f:s={WIDTH}x{HEIGHT}:r={FPS}[bg0]",
        f"[bg0]drawbox=x={left:.0f}:y={top:.0f}:w={2 * half_w:.0f}:h={2 * half_h:.0f}:c=0xdddddd:t=2[bg1]",
    ]
    last = "bg1"

    # Distractors keep moving for the whole video inside a side margin outside the ROI. Their colour
    # is dark so that they differ from the background in luma too: motion features typically work on grayscale,
    # and a mid-red of the same brightness as the green court would be invisible to them.
    for index, side_x in enumerate(_distractor_columns(court)):
        speed = rng.uniform(1.5, 3.0)
        phase = rng.uniform(0, 6.28)
        filters.append(f"color=c=0x501818:s={_DISTRACTOR_SIZE}x{_DISTRACTOR_SIZE}:r={FPS}[d{index}]")
        filters.append(
            f"[{last}][d{index}]overlay=x={side_x}:"
            f"y='{HEIGHT / 2 - 10:.0f}+{HEIGHT * 0.35:.0f}*sin({speed:.3f}*t+{phase:.3f})':eval=frame[dv{index}]"
        )
        last = f"dv{index}"

    # Player blobs only exist while a rally runs and never leave the ROI.
    for index in range(2):
        speed_x = rng.uniform(1.5, 3.0)
        speed_y = rng.uniform(1.0, 2.5)
        phase = rng.uniform(0, 6.28)
        amp_x, amp_y = half_w * 0.8, half_h * 0.7
        filters.append(f"color=c=0xf0e040:s=24x24:r={FPS}[p{index}]")
        filters.append(
            f"[{last}][p{index}]overlay=x='{centre_x - 12:.0f}+{amp_x:.0f}*sin({speed_x:.3f}*t+{phase:.3f})':"
            f"y='{centre_y - 12:.0f}+{amp_y:.0f}*cos({speed_y:.3f}*t+{phase:.3f})':"
            f"eval=frame:enable='{active}'[pv{index}]"
        )
        last = f"pv{index}"

    filters.append(f"[{last}]format=yuv420p[v]")
    return ";".join(filters)


def generate_video(
    output: str | Path,
    *,
    duration_s: float = 60.0,
    seed: int = 1,
    court: Court = DEFAULT_COURT,
) -> Labels:
    """Render the synthetic proxy video to `output` and return the ground-truth labels."""
    if duration_s <= 0:
        raise ValueError("duration_s must be positive")
    # Rejects a court without room for the distractors before ffmpeg is looked up or anything is rendered.
    _distractor_columns(court)
    if not ffmpeg_available():
        raise RuntimeError("ffmpeg is required to generate fixtures but was not found on PATH")
    output = Path(output)
    rallies = plan_rallies(duration_s, seed)
    rng = random.Random(seed + 1)
    graph = _filter_graph(rallies, court, rng)

    # The clip is trimmed with -t because the lavfi sources are endless.
    command = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-filter_complex", graph,
        "-f", "lavfi", "-i", f"aevalsrc='{_click_expression(rallies)}':s={SAMPLE_RATE}:c=mono",
        "-map", "[v]", "-map", "0:a",
        "-t", f"{duration_s:.3f}",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "28", "-pix_fmt", "yuv420p", "-r", str(FPS),
        "-c:a", "aac", "-ac", "1", "-ar", str(SAMPLE_RATE), "-b:a", "64k",
        "-movflags", "+faststart",
        str(output),
    ]  # fmt: skip
    subprocess.run(command, check=True, capture_output=True, text=True)
    return Labels(video=output.name, duration_ms=round(duration_s * 1000), court=court, rallies=rallies)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Generate a synthetic proxy-format rally video.")
    parser.add_argument("output", type=Path, help="output .mp4 path")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--duration", type=float, default=60.0, help="video length in seconds")
    parser.add_argument("--labels", type=Path, help="label JSON path (default: next to the video)")
    args = parser.parse_args(argv)

    labels = generate_video(args.output, duration_s=args.duration, seed=args.seed)
    labels_path = args.labels or args.output.with_suffix(".labels.json")
    save_labels(labels, labels_path)
    print(f"wrote {args.output} and {labels_path} ({len(labels.rallies)} rallies)")


if __name__ == "__main__":
    main()
