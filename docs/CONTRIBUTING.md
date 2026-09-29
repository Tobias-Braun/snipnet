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
with the same settings, load the file first: `set -a; . infra/.env; set +a`. The API and worker services are
still commented out in the compose file; they are added by their own issues.

## API (`services/api`)

Fastify + TypeScript, tested with Vitest.

```sh
pnpm --filter @snipnet/api dev        # watch mode on API_PORT (default 3000)
curl localhost:3000/v1/health
pnpm --filter @snipnet/api test
pnpm --filter @snipnet/api lint       # ESLint + Prettier check
pnpm --filter @snipnet/api typecheck
pnpm --filter @snipnet/api build      # compiles to dist/, run with `pnpm --filter @snipnet/api start`
```

`pnpm --filter @snipnet/api format` fixes formatting.

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

## CI

GitHub Actions runs one workflow per area (`.github/workflows/api.yml`, `web.yml`, `python.yml`,
`client.yml`). Each is filtered on its own folders plus the root files it depends on, and runs lint and tests.

## Pull requests

Work is tracked as GitHub issues. Branch names follow `<type>/<issue>-<topic>`, PR bodies contain
`Closes #<issue>`. Changes to the API contract go into `docs/api.md` in the same PR as the code that needs them.
