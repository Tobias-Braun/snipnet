# Build context is the repository root (see infra/docker-compose.yml), because the worker depends on the
# `ml` workspace member and the shared uv.lock.
FROM python:3.12-slim

# ffmpeg provides ffprobe, which the models use to read the proxy.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir "uv>=0.12,<0.13"

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy PYTHONUNBUFFERED=1
# librosa compiles helpers with numba's on-disk cache; the non-root worker cannot write next to the installed
# package and has no home directory, so without a writable cache dir importing librosa raises "no locator available".
ENV NUMBA_CACHE_DIR=/tmp/numba-cache

COPY pyproject.toml uv.lock .python-version ./
COPY ml ./ml
COPY services/inference ./services/inference

RUN uv sync --locked --no-dev --package snipnet-inference

# The visual embedder of the learned model downloads the ImageNet mobilenet_v3_small weights (about 10 MB) on first
# use. They are not baked into the image (the image stays weight-free and the build does not fetch them); the cache
# lives on the `model-cache` volume of the compose file instead, so the download happens once per volume. The
# directory is created and owned by the worker here so that a fresh named volume mounted on it inherits that ownership.
ENV SNIPNET_MODEL_CACHE=/cache/models

# The worker only needs to read the installed code and write to /tmp and the model cache, so it does not run as root.
RUN useradd --system --no-create-home worker \
    && mkdir -p /cache/models \
    && chown worker /cache/models
USER worker

# Python runs as PID 1 directly (no `uv run` wrapper) so that `docker stop` delivers SIGTERM to the worker's
# own handler, which finishes the current job before exiting.
CMD ["/app/.venv/bin/python", "-m", "snipnet_inference"]
