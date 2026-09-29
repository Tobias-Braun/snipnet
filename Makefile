COMPOSE := docker compose -f infra/docker-compose.yml
GRADLE := cd apps/client && ./gradlew

.PHONY: help install dev infra-up infra-down lint test build client-run

help:
	@echo "install     install pnpm and uv dependencies"
	@echo "dev         start Postgres + MinIO, then the API and landing page in watch mode"
	@echo "infra-up    start Postgres + MinIO in the background"
	@echo "infra-down  stop the local stack (data volumes are kept)"
	@echo "lint        lint every area (TypeScript, Python, Kotlin)"
	@echo "test        run every area's tests"
	@echo "build       build the API, landing page and desktop app"
	@echo "client-run  launch the desktop app"

install:
	pnpm install
	uv sync

infra/.env:
	sh infra/init-env.sh

infra-up: infra/.env
	$(COMPOSE) up -d --wait postgres minio
	$(COMPOSE) up minio-init

infra-down:
	$(COMPOSE) down

dev: infra-up
	pnpm --parallel --filter @snipnet/api --filter @snipnet/web run dev

lint:
	pnpm lint
	pnpm typecheck
	uv run ruff check
	uv run ruff format --check
	$(GRADLE) ktlintCheck

test:
	pnpm test
	uv run pytest
	$(GRADLE) allTests test

build:
	pnpm build
	$(GRADLE) assemble

client-run:
	$(GRADLE) :desktopApp:run
