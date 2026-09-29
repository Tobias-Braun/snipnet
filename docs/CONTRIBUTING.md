# Contributing

How to run each part of Snipnet locally. The architecture is described in [PLAN.md](PLAN.md), the HTTP
contract in [api.md](api.md).

## Prerequisites

| Tool | Version | Used by |
|---|---|---|
| Node.js | 24 (see `.node-version`) | `apps/web`, `services/api` |
| pnpm | 12 (pinned via `packageManager`; `corepack enable` picks it up) | `apps/web`, `services/api` |
| uv | recent | `ml`, `services/inference` (installs Python 3.12 itself) |
| JDK | 21 | `apps/client` (Gradle downloads a toolchain if it is missing) |
| Docker | with Compose v2 | Postgres + MinIO |
| ffmpeg | recent | proxy transcoding, test fixtures |

Install all JavaScript and Python dependencies at once:

```sh
make install          # = pnpm install && uv sync
```

## Common tasks

The root `Makefile` wraps the per-area commands:

| Command | What it does |
|---|---|
| `make dev` | starts Postgres + MinIO, then the API and the landing page in watch mode |
| `make lint` | ESLint + Prettier + `tsc`, ruff, ktlint |
| `make test` | Vitest, pytest, Gradle tests |
| `make build` | API and landing page builds, desktop app assemble |
| `make infra-up` / `make infra-down` | start / stop the Docker stack |

Each area can also be run on its own, as described below.

## Backend stack (`infra/`)

```sh
sh infra/init-env.sh                  # creates infra/.env with random local secrets (once)
docker compose -f infra/docker-compose.yml up -d
```

This starts Postgres 16 and MinIO and creates the proxy bucket (`minio-init` exits once it is done). Ports are
bound to `127.0.0.1` and configurable in `infra/.env` through `POSTGRES_PORT`, `MINIO_API_PORT` and
`MINIO_CONSOLE_PORT`. The MinIO console is at <http://localhost:9001> (user/password from `MINIO_ROOT_USER` /
`MINIO_ROOT_PASSWORD` in `infra/.env`). `docker compose -f infra/docker-compose.yml down -v` removes the stack
including its data.

`infra/.env.example` documents every variable used by the stack, the API and the worker. Secrets have no
defaults: Compose refuses to start until `infra/.env` provides them. To run the API or worker outside Docker
with the same settings, load the file first: `set -a; . infra/.env; set +a`. The `api`, `worker` and `web` services
are part of the compose file too; `docker compose -f infra/docker-compose.yml up -d --build api` (re)builds and
starts only the API with its dependencies.

### Deploying a trained model to the worker

The worker serves `MODEL=learned` from a model directory that is mounted read-only at `/models` (compose sets
`MODEL_DIR=/models`). Weights are never committed or baked into the image. To deploy a model:

1. Check that it beats the heuristic: `python -m snipnet_ml.promote <dataset> <models>/learned-v1.<n> --out report`
   exits 0 only when the model is promoted, so a script can gate the copy on it.
2. Copy the `learned-v1.<n>` directory into the host directory `MODEL_HOST_DIR` (default `infra/models/`). The
   newest build in that folder wins.
3. Set `MODEL=learned` in `infra/.env` and run `docker compose -f infra/docker-compose.yml up -d worker`. The worker
   loads the model at startup, so restarting it is what picks up a new build.

The ImageNet backbone weights the embedder needs are downloaded on the first job into the `model-cache` volume
(`SNIPNET_MODEL_CACHE=/cache/models`), so the first learned run needs network access and later runs and rebuilds do not.

## API (`services/api`)

Fastify + TypeScript, Postgres through Kysely, tested with Vitest. The configuration is read from the
environment and validated at startup (missing variables abort with a list of what to fix). The server applies
pending SQL migrations from `services/api/migrations/` on startup; add a new numbered `.sql` file for every
schema change. The OpenAPI document is served at `/v1/openapi.json`.

```sh
pnpm --filter @snipnet/api dev        # watch mode on API_PORT (default 3000); needs the env loaded, see above
pnpm --filter @snipnet/api migrate    # apply migrations without starting the server
curl localhost:3000/v1/health
pnpm --filter @snipnet/api test       # integration tests need a Postgres, see below
pnpm --filter @snipnet/api lint       # ESLint + Prettier check
pnpm --filter @snipnet/api typecheck
pnpm --filter @snipnet/api build      # compiles to dist/, run with `pnpm --filter @snipnet/api start`
```

`pnpm --filter @snipnet/api format` fixes formatting.

Integration tests connect to the Postgres given by the standard `PG*` variables (defaults: `localhost:5432`,
user and database `snipnet`, which is what CI provides; `PGPASSWORD` must be set) and create a random schema
per test file that is dropped afterwards, so they can share the database of `make infra-up` without touching
its data: run them with `set -a; . infra/.env; set +a` loaded.

## Landing page (`apps/web`)

Vite + React + TypeScript.

```sh
pnpm --filter @snipnet/web dev        # dev server with hot reload
pnpm --filter @snipnet/web test
pnpm --filter @snipnet/web lint
pnpm --filter @snipnet/web build      # static site in apps/web/dist
```

## ML package and inference worker (`ml`, `services/inference`)

Both are members of the uv workspace defined in the root `pyproject.toml` and share one virtual environment
(`.venv`). `snipnet_inference` depends on `snipnet_ml`.

```sh
uv sync                               # creates .venv with both packages (editable) and dev tools
uv run pytest                         # tests of both packages
uv run pytest ml/tests                # one package only
uv run ruff check                     # lint
uv run ruff format                    # format
```

Add a dependency to one member with `uv add --package snipnet-ml <name>`. Only permissively licensed ML
dependencies are allowed (see PLAN.md). Footage and model weights are never committed.

## Desktop app (`apps/client`)

Kotlin Multiplatform + Compose Multiplatform, built with the Gradle wrapper. Modules: `shared` (KMP, currently
only the `desktop` JVM target) and `desktopApp` (Compose Desktop).

```sh
cd apps/client
./gradlew :desktopApp:run             # opens the "Snipnet" window
./gradlew check                       # ktlint + unit tests
./gradlew ktlintFormat                # fix Kotlin formatting
./gradlew :desktopApp:packageDistributionForCurrentOS   # native installer
```

On macOS with Homebrew, point Gradle at JDK 21 with `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

## End-to-end pipeline test (`infra/e2e`)

`infra/e2e/test_pipeline.py` acts as the desktop client against the real stack (Postgres, MinIO, API and the
worker container): it registers a user, generates a synthetic proxy with `snipnet_ml.fixtures`, creates the video,
uploads the proxy to the presigned URL, sets the court, starts the analysis, polls the job, fetches the
prediction, compares it with the fixture's ground truth (segment precision/recall and frame accuracy with
generous thresholds) and posts a final user segment set.

```sh
sh infra/e2e/run.sh
```

The script builds the images, starts the stack under its own Compose project name on free host ports (so it can
run next to `make infra-up`), runs the test and removes the stack with its volumes afterwards. It needs Docker,
uv and ffmpeg, and creates `infra/.env` if it is missing. Against an already running stack, set `E2E_API_URL`
and run `uv run pytest infra/e2e`; without it the test is skipped. The regular `uv run pytest` does not collect
this folder.

## CI

GitHub Actions runs one workflow per area (`.github/workflows/api.yml`, `web.yml`, `python.yml`,
`client.yml`). Each is filtered on its own folders plus the root files it depends on, and runs lint and tests.
`e2e.yml` runs the end-to-end pipeline test above on every change to `services/**`, `ml/**` or `infra/**`.

## Pull requests

Work is tracked as GitHub issues. Branch names follow `<type>/<issue>-<topic>`, PR bodies contain
`Closes #<issue>`. Changes to the API contract go into `docs/api.md` in the same PR as the code that needs them.
