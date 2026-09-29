"""Frozen embeddings that complement the hand-made v0 features for the learned model.

Two per-window embeddings are computed, both without any training:

- Visual: a small pretrained torchvision image backbone (`mobilenet_v3_small`, BSD) is run on the expanded court ROI
  crop of every analysis frame; the pooled feature vectors of the frames inside a 0.5 s window are averaged. The
  backbone is never fine-tuned, so its output only depends on the weights and the crop, which is what makes the
  embeddings cacheable. The ImageNet weights are downloaded on first use into the model cache directory and are never
  part of the repository. `weights="random"` builds the same network with a fixed random initialisation instead; it
  needs no download and is meant for tests and offline smoke runs, not for a model that is supposed to be good.
- Audio: mean and standard deviation of a 32-band log-mel spectrogram per window. It is a fixed signal transform, not
  a neural embedding; a pretrained audio network can replace it later by bumping `EMBEDDING_VERSION`.

Results are cached as `.npz` files keyed by video hash, ROI, the embedding settings and `EMBEDDING_VERSION`.
"""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path

import librosa
import numpy as np

from snipnet_ml.features import decode_audio, expanded_pixel_box, hash_video
from snipnet_ml.labels import Roi
from snipnet_ml.persons import decode_rgb_frames, default_cache_dir, select_device

# Bump whenever the meaning of an embedding column changes so cached results are not reused.
EMBEDDING_VERSION = 1

_MEL_BANDS = 32
_IMAGENET_MEAN = (0.485, 0.456, 0.406)
_IMAGENET_STD = (0.229, 0.224, 0.225)


@dataclass(frozen=True)
class EmbeddingConfig:
    """Everything that influences the embeddings; all fields take part in the cache key."""

    # "imagenet" downloads pretrained weights, "random" uses a seeded random initialisation (tests, offline runs).
    weights: str = "imagenet"
    crop_size: int = 112
    fps: float = 5.0
    window_s: float = 0.5
    roi_expand: float = 0.15
    sample_rate: int = 16000

    def __post_init__(self) -> None:
        if self.weights not in ("imagenet", "random"):
            raise ValueError("weights must be 'imagenet' or 'random'")
        if self.crop_size < 32 or self.fps <= 0 or self.window_s <= 0 or self.roi_expand < 0:
            raise ValueError("crop_size must be at least 32 and fps and window_s positive")


@dataclass(frozen=True)
class WindowEmbeddings:
    """Per-window embeddings; row `i` belongs to the same window `i` as the v0 feature table."""

    visual: np.ndarray
    audio: np.ndarray

    def __post_init__(self) -> None:
        if len(self.visual) != len(self.audio):
            raise ValueError("visual and audio embeddings must cover the same windows")


class VisualEmbedder:
    """Frozen `mobilenet_v3_small` feature extractor, built lazily so importing this module stays cheap."""

    def __init__(self, config: EmbeddingConfig, device: str | None = None, cache_dir: str | Path | None = None):
        self.config = config
        self._device = device
        self._cache_dir = Path(cache_dir) if cache_dir is not None else default_cache_dir()
        self._model = None

    @property
    def device(self) -> str:
        if self._device is None:
            self._device = select_device()
        return self._device

    def _load(self):
        import torch
        from torchvision.models import MobileNet_V3_Small_Weights, mobilenet_v3_small

        model = mobilenet_v3_small(weights=None)
        if self.config.weights == "imagenet":
            self._cache_dir.mkdir(parents=True, exist_ok=True)
            weights = MobileNet_V3_Small_Weights.IMAGENET1K_V1
            model.load_state_dict(weights.get_state_dict(model_dir=str(self._cache_dir), progress=False))
        else:
            # A local generator keeps the random initialisation identical on every run without touching the
            # global torch seed that training relies on.
            generator = torch.Generator().manual_seed(0)
            with torch.no_grad():
                for parameter in model.parameters():
                    parameter.copy_(torch.randn(parameter.shape, generator=generator) * 0.05)
        model.classifier = torch.nn.Identity()
        return model.to(self.device).eval()

    def embed(self, crops: np.ndarray) -> np.ndarray:
        """Embed a batch of RGB uint8 crops of shape `(n, size, size, 3)` into `(n, dim)` float32 vectors."""
        import torch

        if self._model is None:
            self._model = self._load()
        mean = torch.tensor(_IMAGENET_MEAN, device=self.device).view(1, 3, 1, 1)
        std = torch.tensor(_IMAGENET_STD, device=self.device).view(1, 3, 1, 1)
        batch = torch.from_numpy(crops).to(self.device).permute(0, 3, 1, 2).float().div(255)
        with torch.inference_mode():
            return self._model((batch - mean) / std).cpu().numpy().astype(np.float32)


def _resize_crop(frame: np.ndarray, box: tuple[int, int, int, int], size: int) -> np.ndarray:
    """Crop the box and nearest-neighbour resample it to `size` x `size`; cheap and deterministic."""
    x0, y0, x1, y1 = box
    crop = frame[y0:y1, x0:x1]
    rows = np.minimum((np.arange(size) * crop.shape[0] / size).astype(int), crop.shape[0] - 1)
    cols = np.minimum((np.arange(size) * crop.shape[1] / size).astype(int), crop.shape[1] - 1)
    return crop[rows][:, cols]


def visual_embeddings(
    path: str | Path, roi: Roi, config: EmbeddingConfig, n_windows: int, embedder: VisualEmbedder
) -> np.ndarray:
    """Window-averaged embeddings of the ROI crops, shape `(n_windows, dim)`; windows without frames get zeros."""
    times: list[float] = []
    vectors: list[np.ndarray] = []
    pending: list[np.ndarray] = []
    pending_times: list[float] = []

    def flush() -> None:
        if pending:
            vectors.extend(embedder.embed(np.stack(pending)))
            times.extend(pending_times)
            pending.clear()
            pending_times.clear()

    box: tuple[int, int, int, int] | None = None
    for time_s, frame in decode_rgb_frames(path, config.fps):
        if box is None:
            height, width = frame.shape[:2]
            box = expanded_pixel_box(roi, config.roi_expand, width, height)
        pending.append(_resize_crop(frame, box, config.crop_size))
        pending_times.append(time_s)
        if len(pending) == 32:
            flush()
    flush()
    if not vectors:
        raise ValueError(f"no video frames decoded from {path}")

    matrix = np.stack(vectors)
    index = np.clip((np.asarray(times) // config.window_s).astype(int), 0, n_windows - 1)
    sums = np.zeros((n_windows, matrix.shape[1]))
    np.add.at(sums, index, matrix)
    counts = np.bincount(index, minlength=n_windows)
    return np.divide(sums, counts[:, None], out=np.zeros_like(sums), where=counts[:, None] > 0).astype(np.float32)


def audio_embeddings(path: str | Path, config: EmbeddingConfig, n_windows: int) -> np.ndarray:
    """Mean and standard deviation of the log-mel spectrogram per window, shape `(n_windows, 2 * 32)`."""
    result = np.zeros((n_windows, 2 * _MEL_BANDS), dtype=np.float32)
    samples = decode_audio(path, config.sample_rate)
    if len(samples) < 2048:
        return result
    hop = 160
    mel = librosa.feature.melspectrogram(y=samples, sr=config.sample_rate, n_fft=512, hop_length=hop, n_mels=_MEL_BANDS)
    log_mel = np.log1p(mel * 10.0)
    frame_times = librosa.frames_to_time(np.arange(log_mel.shape[1]), sr=config.sample_rate, hop_length=hop)
    index = np.clip((frame_times // config.window_s).astype(int), 0, n_windows - 1)
    for window in np.unique(index):
        block = log_mel[:, index == window]
        result[window, :_MEL_BANDS] = block.mean(axis=1)
        result[window, _MEL_BANDS:] = block.std(axis=1)
    return result


def embedding_cache_key(video_hash: str, roi: Roi, config: EmbeddingConfig) -> str:
    params = json.dumps([asdict(config), roi.model_dump()], sort_keys=True)
    return f"{video_hash[:32]}-e{EMBEDDING_VERSION}-{hashlib.sha256(params.encode()).hexdigest()[:12]}"


def extract_embeddings(
    path: str | Path,
    roi: Roi,
    n_windows: int,
    config: EmbeddingConfig | None = None,
    cache_dir: str | Path | None = None,
    embedder: VisualEmbedder | None = None,
) -> WindowEmbeddings:
    """Return the per-window embeddings of a video, using the `.npz` cache when `cache_dir` is given.

    `n_windows` comes from the v0 feature table so both stay aligned. It is part of the cache validation: a cache
    file with a different window count (a video whose duration metadata changed) is recomputed.
    """
    config = config or EmbeddingConfig()
    cache_file = None
    if cache_dir is not None:
        cache_file = Path(cache_dir) / f"emb-{embedding_cache_key(hash_video(path), roi, config)}.npz"
        if cache_file.exists():
            try:
                with np.load(cache_file) as data:
                    if int(data["version"]) == EMBEDDING_VERSION and len(data["visual"]) == n_windows:
                        return WindowEmbeddings(visual=data["visual"], audio=data["audio"])
            except (ValueError, KeyError, OSError, EOFError, zipfile.BadZipFile):
                # A truncated or foreign cache file is simply recomputed and overwritten.
                pass

    embedder = embedder or VisualEmbedder(config)
    result = WindowEmbeddings(
        visual=visual_embeddings(path, roi, config, n_windows, embedder),
        audio=audio_embeddings(path, config, n_windows),
    )
    if cache_file is not None:
        cache_file.parent.mkdir(parents=True, exist_ok=True)
        # Unique temporary name plus rename, so a crash or a concurrent process never leaves a half-written file.
        handle, temporary = tempfile.mkstemp(dir=cache_file.parent, prefix=f".{cache_file.stem}-", suffix=".npz")
        os.close(handle)
        try:
            np.savez_compressed(temporary, version=EMBEDDING_VERSION, visual=result.visual, audio=result.audio)
            # The cache directory is chosen by the operator (or the worker's configuration), so it is trusted.
            os.replace(temporary, cache_file)  # NOSONAR
        except BaseException:
            Path(temporary).unlink(missing_ok=True)
            raise
    return result
