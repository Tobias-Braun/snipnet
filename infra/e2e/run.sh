#!/bin/sh
# Runs the end-to-end pipeline test (infra/e2e/test_pipeline.py) against a throwaway Docker Compose stack.
#
# The stack runs under its own project name on free host ports, so it never clashes with `make infra-up` or with
# other runs on the same machine, and it is removed together with its volumes on exit. infra/.env is created with
# random secrets when missing (the compose file reads it); the ports and endpoints are overridden through the
# environment, which takes precedence over the file.
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$root"

# Asks the kernel for an unused port; there is a small race until Docker binds it, which is acceptable here.
free_port() {
  uv run python -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'
}

sh infra/init-env.sh >/dev/null

POSTGRES_PORT=$(free_port)
MINIO_API_PORT=$(free_port)
MINIO_CONSOLE_PORT=$(free_port)
API_PORT=$(free_port)
S3_PUBLIC_ENDPOINT="http://localhost:${MINIO_API_PORT}"
export POSTGRES_PORT MINIO_API_PORT MINIO_CONSOLE_PORT API_PORT S3_PUBLIC_ENDPOINT

project="snipnet-e2e-$$"
compose="docker compose -p $project -f infra/docker-compose.yml"

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    $compose logs --no-color --tail 80 api worker minio || true
  fi
  $compose down -v --remove-orphans >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

$compose up -d --build --wait api worker
E2E_API_URL="http://localhost:${API_PORT}" uv run pytest infra/e2e -v -s -p no:cacheprovider
