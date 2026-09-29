import json
import shutil
import subprocess
from pathlib import Path

import numpy as np
import pytest

from snipnet_ml import HeuristicModel, HeuristicParams, InvalidInputError, fixtures, load_model
from snipnet_ml.eval import evaluate
from snipnet_ml.features import FeatureFrame
from snipnet_ml.heuristic import build_segments, predict_from_features, viterbi_states
from snipnet_ml.labels import Court, Labels, Rally, save_labels
from snipnet_ml.model import DummyModel, Prediction
from snipnet_ml.predict import main as predict_main

needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is not installed")

# Seed 7 is the one the end-to-end test in infra/e2e renders, so its accuracy is checked here without a stack.
SEEDS = (1, 2, 3, 4, 7)


def to_rallies(prediction: Prediction) -> list[Rally]:
    return list(prediction.segments)


def model_court(labels: Labels) -> Court:
    return labels.court


@pytest.fixture(scope="module")
def clips(tmp_path_factory) -> dict[int, tuple[Path, Labels]]:
    directory = tmp_path_factory.mktemp("heuristic")
    result = {}
    for seed in SEEDS:
        video = directory / f"clip{seed}.mp4"
        result[seed] = (video, fixtures.generate_video(video, duration_s=60, seed=seed))
    return result


@needs_ffmpeg
@pytest.mark.parametrize("seed", SEEDS)
def test_synthetic_fixtures_meet_quality_targets(clips, seed: int) -> None:
    video, labels = clips[seed]
    prediction = HeuristicModel().predict(video, model_court(labels), lambda _: None)

    metrics = evaluate(to_rallies(prediction), labels.rallies, labels.duration_ms)
    assert metrics.f1 >= 0.9, metrics
    assert metrics.boundary_mae_ms is not None
    assert metrics.boundary_mae_ms <= 900, metrics
    assert metrics.frame_accuracy >= 0.85, metrics


@needs_ffmpeg
def test_prediction_shape_and_contract_invariants(clips) -> None:
    video, labels = clips[1]
    progress: list[float] = []
    prediction = HeuristicModel().predict(video, model_court(labels), progress.append)
    params = HeuristicParams()

    assert prediction.model_version == "heuristic-v0.1"
    assert progress[0] == 0.0
    assert progress[-1] == 1.0
    assert progress == sorted(progress)
    assert prediction.scores is not None
    assert prediction.scores.hz == 2.0
    assert len(prediction.scores.values) == 120
    assert all(0.0 <= v <= 1.0 for v in prediction.scores.values)

    previous_end = -1
    for segment in prediction.segments:
        assert 0 <= segment.start_ms < segment.end_ms <= labels.duration_ms
        # Padding is applied around detected rallies, so what remains is at least the minimum rally plus padding
        # unless it is clamped at the video edges.
        assert segment.end_ms - segment.start_ms >= params.min_rally_s * 1000
        assert segment.start_ms > previous_end
        assert segment.confidence is not None
        assert 0.0 <= segment.confidence <= 1.0
        previous_end = segment.end_ms


@needs_ffmpeg
def test_progress_is_reported_repeatedly_during_extraction_and_never_decreases(clips) -> None:
    video, labels = clips[1]
    progress: list[float] = []
    HeuristicModel().predict(video, model_court(labels), progress.append)

    # 0.0 at the start, 0.9 and 1.0 at the end, and many calls from the frame decode in between.
    assert len(progress) > 10
    assert progress == sorted(progress)
    assert progress[0] == 0.0
    assert progress[-1] == 1.0
    during_extraction = progress[1:-2]
    assert during_extraction
    assert all(0.0 < p <= 0.9 for p in during_extraction)
    assert during_extraction[-1] > 0.8


@needs_ffmpeg
def test_works_without_a_court(clips) -> None:
    video, _ = clips[1]
    prediction = HeuristicModel().predict(video, None, lambda _: None)
    assert prediction.scores is not None


@needs_ffmpeg
def test_footage_without_rallies_yields_no_segments(tmp_path: Path) -> None:
    import subprocess

    video = tmp_path / "quiet.mp4"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=gray:s=160x90:r=10:d=30", "-f", "lavfi", "-i",
         "anoisesrc=amplitude=0.002:d=30", "-shortest", "-pix_fmt", "yuv420p", str(video)],
        check=True,
    )  # fmt: skip
    assert HeuristicModel().predict(video, None, lambda _: None).segments == []


@needs_ffmpeg
def test_unreadable_media_is_invalid_input(tmp_path: Path) -> None:
    broken = tmp_path / "broken.mp4"
    broken.write_bytes(b"not a video")
    with pytest.raises(InvalidInputError):
        HeuristicModel().predict(broken, None, lambda _: None)


def test_viterbi_removes_single_window_flicker() -> None:
    params = HeuristicParams()
    probability = np.array([0.05] * 10 + [0.95] + [0.05] * 10 + [0.9] * 10 + [0.05] * 5)
    states = viterbi_states(probability, params)
    assert states[:21].sum() == 0
    assert states[21:31].all()


def test_segment_cleanup_merges_gaps_drops_short_rallies_and_clamps() -> None:
    params = HeuristicParams()
    states = np.zeros(60, dtype=np.int8)
    states[0:6] = 1  # 3 s rally at the very start
    states[9:20] = 1  # 1.5 s gap to the previous run is bridged
    states[30:32] = 1  # 1 s blip is too short
    states[50:60] = 1  # touches the end of the video
    probability = states.astype(float)

    segments = build_segments(states, probability, 0.5, 30.0, params)

    assert [(s.start_ms, s.end_ms) for s in segments] == [(0, 10_500), (24_500, 30_000)]
    assert segments[0].confidence == pytest.approx(17 / 20)


def test_padding_that_makes_rallies_touch_merges_them() -> None:
    # Explicit padding: the gap of 1 s between the runs must be smaller than the two paddings together.
    params = HeuristicParams(min_gap_s=0.0, pad_before_s=1.0, pad_after_s=1.5)
    states = np.zeros(40, dtype=np.int8)
    states[4:12] = 1
    states[14:22] = 1
    segments = build_segments(states, states.astype(float), 0.5, 20.0, params)
    assert [(s.start_ms, s.end_ms) for s in segments] == [(1000, 12_500)]


def test_predict_from_features_on_a_hand_made_table() -> None:
    n = 60
    starts = np.arange(n) * 0.5
    active = (starts >= 10) & (starts < 20)
    table = FeatureFrame.from_arrays(
        t_start=starts,
        t_end=starts + 0.5,
        roi_motion=np.where(active, 4.0, 0.1),
        outside_motion=np.full(n, 1.0),
        onset_mean=np.where(active, 0.6, 0.02),
        onset_max=np.where(active, 20.0, 1.0),
        transient_count=np.where(active, 1, 0),
    )
    prediction = predict_from_features(table, 30_000, HeuristicParams())
    assert len(prediction.segments) == 1
    segment = prediction.segments[0]
    assert abs(segment.start_ms - 9000) <= 1500
    assert abs(segment.end_ms - 21_500) <= 1500


def test_clip_that_is_one_continuous_rally_yields_one_segment() -> None:
    n = 60
    starts = np.arange(n) * 0.5
    rng = np.random.default_rng(0)
    table = FeatureFrame.from_arrays(
        t_start=starts,
        t_end=starts + 0.5,
        roi_motion=4.0 + rng.uniform(-0.5, 0.5, n),
        outside_motion=np.full(n, 1.0),
        onset_mean=0.6 + rng.uniform(-0.1, 0.1, n),
        onset_max=np.full(n, 20.0),
        transient_count=rng.integers(1, 3, n),
    )
    prediction = predict_from_features(table, 30_000, HeuristicParams())
    assert len(prediction.segments) == 1
    segment = prediction.segments[0]
    assert segment.start_ms == 0
    assert segment.end_ms >= 28_000


def test_params_yaml_overrides_only_named_values(tmp_path: Path) -> None:
    path = tmp_path / "params.yaml"
    path.write_text("min_rally_s: 4.0\npad_after_s: 0\n", encoding="utf-8")
    params = HeuristicParams.from_yaml(path)
    assert params.min_rally_s == 4.0
    assert params.pad_after_s == 0
    assert params.min_gap_s == HeuristicParams().min_gap_s


def test_params_yaml_rejects_unknown_and_invalid_values(tmp_path: Path) -> None:
    path = tmp_path / "params.yaml"
    path.write_text("min_rally: 4.0\n", encoding="utf-8")
    with pytest.raises(ValueError, match="min_rally"):
        HeuristicParams.from_yaml(path)
    path.write_text("switch_probability: 0.9\n", encoding="utf-8")
    with pytest.raises(ValueError, match="switch_probability"):
        HeuristicParams.from_yaml(path)
    path.write_text("- 1\n", encoding="utf-8")
    with pytest.raises(ValueError, match="mapping"):
        HeuristicParams.from_yaml(path)
    for bad_value in ("fast", "true", ".nan", ".inf"):
        path.write_text(f"min_gap_s: {bad_value}\n", encoding="utf-8")
        with pytest.raises(ValueError, match="min_gap_s must be a finite number"):
            HeuristicParams.from_yaml(path)


def test_load_model_selects_heuristic_and_dummy(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.delenv("HEURISTIC_PARAMS", raising=False)
    assert isinstance(load_model("dummy"), DummyModel)
    assert isinstance(load_model("dummy-v0"), DummyModel)
    model = load_model("heuristic")
    assert isinstance(model, HeuristicModel)
    assert model.params == HeuristicParams()

    path = tmp_path / "params.yaml"
    path.write_text("min_rally_s: 5\n", encoding="utf-8")
    monkeypatch.setenv("HEURISTIC_PARAMS", str(path))
    loaded = load_model("heuristic-v0.1")
    assert isinstance(loaded, HeuristicModel)
    assert loaded.params.min_rally_s == 5


@needs_ffmpeg
def test_predict_cli_prints_segments_and_metrics(clips, tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    video, labels = clips[2]
    labels_path = tmp_path / "labels.json"
    save_labels(labels, labels_path)
    court_path = tmp_path / "court.json"
    court_path.write_text(json.dumps(labels.court.model_dump(by_alias=True)), encoding="utf-8")

    predict_main([str(video), "--court", str(court_path), "--eval", str(labels_path)])

    output = capsys.readouterr().out
    assert "heuristic-v0.1" in output
    assert " s - " in output
    assert "segment F1" in output


def offset_proxy(video: Path, target: Path) -> Path:
    """Remux `video` into Matroska starting at 3.7 s, so the declared duration is the absolute end time."""
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(video), "-c", "copy", "-output_ts_offset", "3.7", str(target)],
        check=True,
    )
    return target


@needs_ffmpeg
def test_model_clamps_segments_to_the_real_length_of_an_offset_proxy(
    clips, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    video, labels = clips[2]
    shifted = offset_proxy(video, tmp_path / "shifted.mkv")
    seen: list[int] = []

    def recording_predict_from_features(table, duration_ms: int, params):
        seen.append(duration_ms)
        return predict_from_features(table, duration_ms, params)

    monkeypatch.setattr("snipnet_ml.heuristic.predict_from_features", recording_predict_from_features)

    prediction = HeuristicModel().predict(shifted, model_court(labels), lambda _: None)

    # Segment ends are clamped to this value, so it must be the real length, not the declared 63.7 s.
    assert seen == [pytest.approx(labels.duration_ms, abs=300)]
    assert all(segment.end_ms <= seen[0] for segment in prediction.segments)


@needs_ffmpeg
def test_predict_cli_evaluates_against_the_real_length_of_an_offset_proxy(
    clips, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    video, labels = clips[2]
    shifted = offset_proxy(video, tmp_path / "shifted.mkv")
    # A label file that under-reports the length makes the probed duration the one that counts.
    short_labels = labels.model_copy(update={"duration_ms": 1000, "rallies": []})
    labels_path = tmp_path / "labels.json"
    save_labels(short_labels, labels_path)
    seen: list[int] = []

    def fake_evaluate(_segments, _rallies, duration_ms: int):
        seen.append(duration_ms)
        return []

    monkeypatch.setattr("snipnet_ml.predict.evaluate", fake_evaluate)
    monkeypatch.setattr("snipnet_ml.predict.format_table", lambda _: "")

    predict_main([str(shifted), "--eval", str(labels_path)])

    # The container declares the absolute end (real length + 3.7 s); the evaluation must use the real length.
    assert seen == [pytest.approx(labels.duration_ms, abs=300)]
