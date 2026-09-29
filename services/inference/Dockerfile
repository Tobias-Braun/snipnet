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

COPY pyproject.toml uv.lock .python-version ./
COPY ml ./ml
COPY services/inference ./services/inference

RUN uv sync --locked --no-dev --package snipnet-inference

CMD ["uv", "run", "--no-sync", "python", "-m", "snipnet_inference"]
