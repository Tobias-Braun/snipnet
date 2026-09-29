"""Training dataset built from the API's training export.

    python -m snipnet_ml.dataset --export export.ndjson --out data/v1
    python -m snipnet_ml.dataset --api-url http://localhost:3000 --out data/v1     (token from ADMIN_TOKEN)

The export (`GET /v1/admin/training-export`, see docs/api.md) has one NDJSON line per video with the user's final
segment set. For each line the proxy is downloaded (once; existing files are reused), the per-window features of
`snipnet_ml.learned.compute_window_features` are computed and the label of every 0.5 s window is taken from the final
set: a window is a rally window when its center lies inside a final segment. The final set, not the prediction, is
the label because it carries the user's corrections.

The output directory holds `dataset.json` (index with split, duration and final rallies per video) and one
`examples/<video id>.npz` per video. Videos are split into train/validation/test by a hash of their id, so the
assignment is deterministic, independent of the export order and stable when videos are added later. The split is
by video: windows of one video never end up in two splits.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import urllib.parse
import urllib.request
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from snipnet_ml.embeddings import EmbeddingConfig, VisualEmbedder
from snipnet_ml.features import FeatureConfig
from snipnet_ml.labels import Rally, Roi
from snipnet_ml.learned import WindowFeatures, compute_window_features

SPLITS = ("train", "val", "test")
DEFAULT_FRACTIONS = (0.7, 0.15, 0.15)
DEFAULT_SPLIT_SEED = 0

# Video ids come from the API and end up in file names, so anything but a plain id is rejected.
_SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")

Downloader = Callable[[str, Path], None]


def assign_split(
    video_id: str, fractions: tuple[float, float, float] = DEFAULT_FRACTIONS, seed: int = DEFAULT_SPLIT_SEED
) -> str:
    """Deterministic train/val/test assignment of a video, from a hash of its id and the split seed."""
    if len(fractions) != 3 or min(fractions) < 0 or abs(sum(fractions) - 1.0) > 1e-6:
        raise ValueError("fractions must be three non-negative numbers summing to 1")
    digest = hashlib.sha256(f"{seed}:{video_id}".encode()).digest()
    position = int.from_bytes(digest[:8], "big") / 2**64
    if position < fractions[0]:
        return "train"
    return "val" if position < fractions[0] + fractions[1] else "test"


@dataclass(frozen=True)
class ExportEntry:
    """One line of the training export, reduced to what the pipeline needs."""

    video_id: str
    proxy_url: str
    duration_ms: int
    roi: Roi
    rallies: list[Rally]


def parse_export(lines: Iterable[str]) -> list[ExportEntry]:
    """Parse NDJSON lines; blank lines are skipped and a video repeated by an incremental export keeps its last line."""
    entries: dict[str, ExportEntry] = {}
    for number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
            video = record["video"]
            court = video.get("court")
            if not court:
                # Without a court there is no ROI to crop; the user never confirmed one, so the example is unusable.
                continue
            rallies = [Rally(start_ms=s["startMs"], end_ms=s["endMs"]) for s in record["final"]["segments"]]
            entry = ExportEntry(
                video_id=video["id"],
                proxy_url=record["proxyUrl"],
                duration_ms=int(video["durationMs"]),
                roi=Roi.model_validate(court["roi"]),
                rallies=rallies,
            )
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError(f"malformed training export line {number}: {error}") from error
        if not _SAFE_ID.fullmatch(entry.video_id):
            raise ValueError(f"training export line {number} has an unusable video id")
        entries[entry.video_id] = entry
    return list(entries.values())


def window_labels(rallies: list[Rally], n_windows: int, window_s: float) -> np.ndarray:
    """1 for every window whose center lies inside a rally, else 0."""
    centers_ms = (np.arange(n_windows) + 0.5) * window_s * 1000
    labels = np.zeros(n_windows, dtype=np.float32)
    for rally in rallies:
        labels[(centers_ms >= rally.start_ms) & (centers_ms < rally.end_ms)] = 1.0
    return labels


def download_proxy(url: str, target: Path) -> None:
    """Stream `url` into `target` through a temporary file, so an interrupted download is never mistaken for a proxy."""
    scheme = urllib.parse.urlparse(url).scheme
    if scheme not in ("http", "https", "file"):
        raise ValueError(f"unsupported proxy URL scheme {scheme!r}")
    temporary = target.with_name(target.name + ".part")
    try:
        # The scheme is restricted above; `file` exists so tests and offline exports can point at local proxies.
        with urllib.request.urlopen(url, timeout=60) as response, open(temporary, "wb") as out:  # NOSONAR
            shutil.copyfileobj(response, out)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


@dataclass(frozen=True)
class DatasetConfig:
    features: FeatureConfig = field(default_factory=FeatureConfig)
    embeddings: EmbeddingConfig = field(default_factory=EmbeddingConfig)
    fractions: tuple[float, float, float] = DEFAULT_FRACTIONS
    split_seed: int = DEFAULT_SPLIT_SEED


def build_dataset(
    entries: list[ExportEntry],
    out_dir: str | Path,
    config: DatasetConfig | None = None,
    downloader: Downloader = download_proxy,
    embedder: VisualEmbedder | None = None,
) -> Path:
    """Download proxies, compute features and labels and write the dataset; returns the path of `dataset.json`."""
    config = config or DatasetConfig()
    out = Path(out_dir)
    (out / "examples").mkdir(parents=True, exist_ok=True)
    (out / "proxies").mkdir(exist_ok=True)
    embedder = embedder or VisualEmbedder(config.embeddings)

    index = []
    for entry in entries:
        proxy = out / "proxies" / f"{entry.video_id}.mp4"
        if not proxy.exists():
            downloader(entry.proxy_url, proxy)
        features = compute_window_features(
            proxy, entry.roi, config.features, config.embeddings, out / "cache", embedder
        )
        example = out / "examples" / f"{entry.video_id}.npz"
        features.save(example)
        labels = window_labels(entry.rallies, len(features.table), config.features.window_s)
        np.save(out / "examples" / f"{entry.video_id}.labels.npy", labels)
        index.append(
            {
                "videoId": entry.video_id,
                "split": assign_split(entry.video_id, config.fractions, config.split_seed),
                "durationMs": entry.duration_ms,
                "windows": len(features.table),
                "rallies": [{"startMs": r.start_ms, "endMs": r.end_ms} for r in entry.rallies],
            }
        )
    document = {
        "features": config.features.__dict__ | {"hit_band": list(config.features.hit_band)},
        "embeddings": config.embeddings.__dict__,
        "videos": index,
    }
    path = out / "dataset.json"
    # The output directory is chosen by the operator on the command line, so writing below it is intended.
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")  # NOSONAR
    return path


@dataclass(frozen=True)
class Example:
    video_id: str
    split: str
    duration_ms: int
    rallies: list[Rally]
    features: WindowFeatures
    labels: np.ndarray


class Dataset:
    """Reader for a dataset directory written by `build_dataset`."""

    def __init__(self, directory: str | Path) -> None:
        self.directory = Path(directory)
        document = json.loads((self.directory / "dataset.json").read_text(encoding="utf-8"))
        document["features"]["hit_band"] = tuple(document["features"]["hit_band"])
        self.feature_config = FeatureConfig(**document["features"])
        self.embedding_config = EmbeddingConfig(**document["embeddings"])
        self._videos = document["videos"]

    def video_ids(self, split: str) -> list[str]:
        if split not in SPLITS:
            raise ValueError(f"split must be one of {SPLITS}")
        return [v["videoId"] for v in self._videos if v["split"] == split]

    def examples(self, split: str) -> list[Example]:
        result = []
        for video in (v for v in self._videos if v["split"] == split):
            name = video["videoId"]
            result.append(
                Example(
                    video_id=name,
                    split=split,
                    duration_ms=video["durationMs"],
                    rallies=[Rally(start_ms=r["startMs"], end_ms=r["endMs"]) for r in video["rallies"]],
                    features=WindowFeatures.load(self.directory / "examples" / f"{name}.npz"),
                    labels=np.load(self.directory / "examples" / f"{name}.labels.npy"),
                )
            )
        return result


def fetch_export(api_url: str, token: str, since: str | None = None) -> list[str]:
    """Download the NDJSON training export from the API with the admin token."""
    query = "?" + urllib.parse.urlencode({"since": since}) if since else ""
    parsed = urllib.parse.urlparse(api_url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError("api url must be http or https")
    request = urllib.request.Request(  # NOSONAR
        f"{api_url.rstrip('/')}/v1/admin/training-export{query}", headers={"Authorization": f"Bearer {token}"}
    )
    with urllib.request.urlopen(request, timeout=300) as response:  # NOSONAR
        return response.read().decode("utf-8").splitlines()


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Build a training dataset from the training export.")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--export", type=Path, help="NDJSON file saved from the training export endpoint")
    source.add_argument("--api-url", help="API base URL; the admin token is read from ADMIN_TOKEN")
    parser.add_argument("--since", help="ISO-8601 instant for an incremental export (with --api-url)")
    parser.add_argument("--out", type=Path, required=True, help="dataset directory")
    parser.add_argument("--weights", choices=("imagenet", "random"), default="imagenet")
    parser.add_argument("--split-seed", type=int, default=DEFAULT_SPLIT_SEED)
    args = parser.parse_args(argv)

    if args.export:
        lines = args.export.read_text(encoding="utf-8").splitlines()
    else:
        token = os.environ.get("ADMIN_TOKEN", "").strip()
        if not token:
            parser.error("ADMIN_TOKEN must be set to use --api-url")
        lines = fetch_export(args.api_url, token, args.since)
    config = DatasetConfig(embeddings=EmbeddingConfig(weights=args.weights), split_seed=args.split_seed)
    path = build_dataset(parse_export(lines), args.out, config)
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
