import json
import shutil
from pathlib import Path

import numpy as np
import pytest

from snipnet_ml import fixtures, load_model
from snipnet_ml import labels as labels_module
from snipnet_ml.dataset import (
    Dataset,
    DatasetConfig,
    assign_split,
    build_dataset,
    parse_export,
    window_labels,
)
from snipnet_ml.embeddings import EmbeddingConfig
from snipnet_ml.features import FeatureConfig
from snipnet_ml.labels import Court, Point, Rally, Roi
from snipnet_ml.learned import LearnedModel, compute_window_features, next_version, resolve_model_dir
from snipnet_ml.promote import compare, format_markdown
from snipnet_ml.promote import main as promote_main
from snipnet_ml.train import TrainConfig, train
from snipnet_ml.train import main as train_main

needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is not installed")


def ids_for(split: str, count: int) -> list[str]:
    """Video ids that the deterministic split puts into `split`."""
    found = []
    candidate = 0
    while len(found) < count:
        name = f"video{candidate}"
        if assign_split(name) == split:
            found.append(name)
        candidate += 1
    return found


def test_assign_split_is_deterministic_and_roughly_proportional() -> None:
    names = [f"v{i}" for i in range(2000)]
    first = [assign_split(n) for n in names]
    assert first == [assign_split(n) for n in names]
    assert 0.6 < first.count("train") / len(names) < 0.8
    assert first.count("val") > 100
    assert first.count("test") > 100
    assert assign_split("v1", seed=1) in ("train", "val", "test")
    with pytest.raises(ValueError, match="summing to 1"):
        assign_split("v1", (0.5, 0.5, 0.5))


def test_window_labels_use_window_centers() -> None:
    labels = window_labels([Rally(start_ms=1000, end_ms=2000)], 6, 0.5)
    assert labels.tolist() == [0, 0, 1, 1, 0, 0]


def export_line(video_id: str, proxy: Path, labels, final_segments=None) -> str:
    roi = labels.court.roi
    segments = final_segments or [{"startMs": r.start_ms, "endMs": r.end_ms} for r in labels.rallies]
    return json.dumps(
        {
            "video": {
                "id": video_id,
                "durationMs": labels.duration_ms,
                "court": {"roi": roi.model_dump(by_alias=True), "netPoint": {"x": 0.5, "y": 0.5}},
            },
            "proxyUrl": proxy.as_uri(),
            "prediction": {"segments": []},
            "final": {"segments": segments},
        }
    )


def test_parse_export_skips_blank_lines_and_rejects_bad_ones() -> None:
    good = json.dumps(
        {
            "video": {"id": "a1", "durationMs": 1000, "court": {"roi": {"x": 0, "y": 0, "width": 1, "height": 1}}},
            "proxyUrl": "http://x",
            "final": {"segments": [{"startMs": 0, "endMs": 500}]},
        }
    )
    entries = parse_export(["", good, "  "])
    assert [e.video_id for e in entries] == ["a1"]
    assert entries[0].rallies == [Rally(start_ms=0, end_ms=500)]
    with pytest.raises(ValueError, match="line 1"):
        parse_export(["{}"])
    with pytest.raises(ValueError, match="video id"):
        parse_export([good.replace('"a1"', '"../evil"')])


@pytest.fixture(scope="module")
def pipeline(tmp_path_factory):
    """Synthetic export with 5 train, 1 validation and 2 test videos, the built dataset and a trained model."""
    root = tmp_path_factory.mktemp("learned")
    plan = {"train": ids_for("train", 5), "val": ids_for("val", 1), "test": ids_for("test", 2)}
    lines = []
    truth = {}
    seed = 10
    for split_ids in plan.values():
        for video_id in split_ids:
            video = root / "source" / f"{video_id}.mp4"
            video.parent.mkdir(exist_ok=True)
            labels = fixtures.generate_video(video, duration_s=45, seed=seed)
            seed += 1
            truth[video_id] = labels
            lines.append(export_line(video_id, video, labels))
    config = DatasetConfig(embeddings=EmbeddingConfig(weights="random", crop_size=64))
    dataset_path = build_dataset(parse_export(lines), root / "data", config)
    dataset = Dataset(dataset_path.parent)
    model_dir = train(
        dataset, root / "models", TrainConfig(epochs=25, hidden=16, blocks=3, crop_windows=64, seed=3), device="cpu"
    )
    return root, dataset, model_dir, plan, truth


@needs_ffmpeg
def test_dataset_splits_by_video_and_labels_from_final_set(pipeline) -> None:
    _, dataset, _, plan, truth = pipeline
    for split, expected in plan.items():
        assert sorted(dataset.video_ids(split)) == sorted(expected)
    example = dataset.examples("train")[0]
    assert len(example.labels) == len(example.features.table)
    assert 0.2 < example.labels.mean() < 0.9
    assert example.features.matrix().shape == (len(example.labels), 5 + 576 + 64)
    assert len(example.rallies) == len(truth[example.video_id].rallies)


@needs_ffmpeg
def test_incremental_build_merges_into_existing_dataset(pipeline, tmp_path) -> None:
    root, dataset, _, plan, truth = pipeline
    target = tmp_path / "data"
    shutil.copytree(dataset.directory, target)
    config = DatasetConfig(embeddings=EmbeddingConfig(weights="random", crop_size=64))
    updated = plan["train"][0]
    corrected = [{"startMs": 1000, "endMs": 4000}]
    line = export_line(updated, root / "source" / f"{updated}.mp4", truth[updated], corrected)

    merged = Dataset(build_dataset(parse_export([line]), target, config).parent)

    for split, expected in plan.items():
        assert sorted(merged.video_ids(split)) == sorted(expected)
    example = next(e for e in merged.examples("train") if e.video_id == updated)
    assert example.rallies == [Rally(start_ms=1000, end_ms=4000)]
    assert example.labels.sum() == 6
    with pytest.raises(ValueError, match="other feature or embedding settings"):
        build_dataset([], target, DatasetConfig(embeddings=EmbeddingConfig(weights="random", crop_size=96)))


def test_window_features_require_matching_window_lengths(tmp_path) -> None:
    with pytest.raises(ValueError, match="window lengths"):
        compute_window_features(
            tmp_path / "missing.mp4",
            labels_module.Roi(x=0, y=0, width=1, height=1),
            FeatureConfig(window_s=0.5),
            EmbeddingConfig(weights="random", window_s=1.0),
        )


@needs_ffmpeg
def test_training_writes_checkpoints_metrics_and_versioned_model(pipeline) -> None:
    root, _, model_dir, _, _ = pipeline
    assert model_dir.name == "learned-v1.1"
    assert (model_dir / "model.pt").is_file()
    assert (model_dir / "meta.json").is_file()
    run = root / "models" / "runs" / "learned-v1.1"
    assert (run / "last.pt").is_file()
    assert (run / "best.pt").is_file()
    records = [json.loads(line) for line in (run / "metrics.jsonl").read_text().splitlines()]
    assert [r["epoch"] for r in records] == list(range(1, 26))
    assert records[-1]["train_loss"] < records[0]["train_loss"]
    assert "val_loss" in records[0]
    assert next_version(root / "models") == "learned-v1.2"
    assert resolve_model_dir(root / "models") == model_dir


@needs_ffmpeg
def test_learned_model_predicts_rallies_on_a_video(pipeline) -> None:
    root, _, model_dir, plan, truth = pipeline
    video_id = plan["test"][0]
    labels = truth[video_id]
    court = Court(roi=Roi(x=0.25, y=0.2, width=0.5, height=0.6), net_point=Point(x=0.5, y=0.5))
    model = LearnedModel(model_dir, cache_dir=root / "cache", device="cpu")
    assert model.version == "learned-v1.1"

    prediction = model.predict(root / "source" / f"{video_id}.mp4", court, lambda _: None)
    assert prediction.model_version == "learned-v1.1"
    assert prediction.scores is not None
    assert prediction.scores.hz == 2.0
    assert all(0 <= v <= 1 for v in prediction.scores.values)
    assert prediction.segments
    from snipnet_ml.eval import evaluate

    assert evaluate(prediction.segments, labels.rallies, labels.duration_ms).frame_accuracy > 0.8


@needs_ffmpeg
def test_load_model_reads_model_dir_from_environment(pipeline, monkeypatch) -> None:
    root, _, _, _, _ = pipeline
    monkeypatch.delenv("MODEL_DIR", raising=False)
    with pytest.raises(ValueError, match="MODEL_DIR"):
        load_model("learned")
    monkeypatch.setenv("MODEL_DIR", str(root / "models"))
    assert load_model("learned").version == "learned-v1.1"
    assert load_model("learned-v1.1").version == "learned-v1.1"
    with pytest.raises(ValueError, match=r"not learned-v1\.9"):
        load_model("learned-v1.9")


@needs_ffmpeg
def test_promotion_report_compares_against_heuristic(pipeline, tmp_path, capsys) -> None:
    _, dataset, model_dir, plan, _ = pipeline
    report = compare(dataset, LearnedModel(model_dir, device="cpu"))
    assert report.videos == 2
    assert report.split == "test"
    assert report.heuristic.version == "heuristic-v0.1"
    assert report.learned.version == "learned-v1.1"
    assert set(report.learned.per_video_f1) == set(plan["test"])
    assert 0 <= report.learned.f1 <= 1
    assert report.promoted == (
        report.learned.f1 > report.heuristic.f1 and report.learned.frame_accuracy >= report.heuristic.frame_accuracy
    )
    assert "heuristic-v0.1" in format_markdown(report)

    status = promote_main([str(dataset.directory), str(model_dir), "--out", str(tmp_path), "--device", "cpu"])
    assert status == (0 if report.promoted else 1)
    written = json.loads((tmp_path / "report.json").read_text())
    assert written["promoted"] == report.promoted
    assert (tmp_path / "report.md").is_file()


@needs_ffmpeg
def test_training_is_repeatable_for_a_seed(pipeline, tmp_path) -> None:
    _, dataset, model_dir, _, _ = pipeline
    config = TrainConfig(epochs=25, hidden=16, blocks=3, crop_windows=64, seed=3)
    again = train(dataset, tmp_path, config, device="cpu")
    first = LearnedModel(model_dir, device="cpu")
    second = LearnedModel(again, device="cpu")
    features = dataset.examples("test")[0].features
    np.testing.assert_allclose(first.probabilities(features), second.probabilities(features), atol=1e-4)


def test_train_config_validation_and_cli(tmp_path) -> None:
    config_file = tmp_path / "t.yaml"
    config_file.write_text("epochs: 3\nhidden: 8\n")
    assert TrainConfig.from_yaml(config_file).hidden == 8
    config_file.write_text("epochz: 3\n")
    with pytest.raises(ValueError, match="epochz"):
        TrainConfig.from_yaml(config_file)
    with pytest.raises(ValueError, match="at least 1"):
        TrainConfig(epochs=0)
    with pytest.raises(SystemExit):
        train_main([])
