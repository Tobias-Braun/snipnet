import json

import pytest

from snipnet_ml.eval import evaluate, format_table, frame_accuracy, iou, load_predictions, main, match_segments
from snipnet_ml.labels import Rally


def r(start: int, end: int) -> Rally:
    return Rally(start_ms=start, end_ms=end)


def test_both_empty_is_perfect() -> None:
    m = evaluate([], [], 10_000)
    assert (m.precision, m.recall, m.f1, m.frame_accuracy) == (1.0, 1.0, 1.0, 1.0)
    assert m.boundary_mae_ms is None


def test_no_predictions_for_existing_rallies() -> None:
    m = evaluate([], [r(1000, 5000)], 10_000)
    assert (m.precision, m.recall, m.f1) == (0.0, 0.0, 0.0)
    assert m.frame_accuracy == pytest.approx(0.6)


def test_only_false_positives() -> None:
    m = evaluate([r(1000, 5000)], [], 10_000)
    assert (m.precision, m.recall, m.f1) == (0.0, 0.0, 0.0)


def test_full_overlap() -> None:
    m = evaluate([r(1000, 5000), r(7000, 9000)], [r(1000, 5000), r(7000, 9000)], 10_000)
    assert (m.precision, m.recall, m.f1, m.frame_accuracy, m.boundary_mae_ms) == (1.0, 1.0, 1.0, 1.0, 0.0)


def test_iou_threshold_is_inclusive() -> None:
    assert iou(r(0, 1000), r(500, 1500)) == pytest.approx(1 / 3)
    assert len(match_segments([r(0, 1000)], [r(0, 2000)])) == 1
    assert len(match_segments([r(0, 1000)], [r(0, 2001)])) == 0


def test_off_by_one_millisecond_still_matches() -> None:
    m = evaluate([r(1001, 5000)], [r(1000, 5001)], 10_000)
    assert m.f1 == 1.0
    assert m.boundary_mae_ms == 1.0


def test_split_rally_matches_only_the_larger_half() -> None:
    m = evaluate([r(0, 6000), r(6500, 10_000)], [r(0, 10_000)], 10_000)
    assert m.true_positives == 1
    assert m.precision == 0.5
    assert m.recall == 1.0


def test_split_rally_into_two_equal_halves_matches_nothing() -> None:
    m = evaluate([r(0, 4900), r(5100, 10_000)], [r(0, 10_000)], 10_000)
    assert m.true_positives == 0
    assert m.f1 == 0.0
    assert m.frame_accuracy == pytest.approx(0.98)


def test_matching_is_one_to_one_and_prefers_best_iou() -> None:
    label = r(0, 1000)
    matches = match_segments([r(100, 1000), r(0, 1000)], [label])
    assert matches == [(r(0, 1000), label)]


def test_frame_accuracy_counts_disagreeing_frames() -> None:
    assert frame_accuracy([r(0, 1000)], [r(0, 500)], 2000) == pytest.approx(0.75)
    assert frame_accuracy([], [], 0) == 1.0


def test_rally_validation() -> None:
    with pytest.raises(ValueError, match="greater"):
        r(5, 5)


def test_cli_prints_table(tmp_path, capsys) -> None:
    labels = {
        "video": "a.mp4",
        "durationMs": 10_000,
        "court": {"roi": {"x": 0.2, "y": 0.2, "width": 0.5, "height": 0.5}, "netPoint": {"x": 0.5, "y": 0.5}},
        "rallies": [{"startMs": 1000, "endMs": 5000}],
    }
    (tmp_path / "labels.json").write_text(json.dumps(labels))
    prediction = {"segments": [{"startMs": 1100, "endMs": 5000, "label": "rally"}]}
    (tmp_path / "pred.json").write_text(json.dumps(prediction))
    assert load_predictions(tmp_path / "pred.json") == [r(1100, 5000)]
    main([str(tmp_path / "labels.json"), str(tmp_path / "pred.json")])
    out = capsys.readouterr().out
    assert "segment F1" in out
    assert "0.050 s" in out
    assert "n/a" in format_table(evaluate([], [], 1000))
