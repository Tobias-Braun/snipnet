#!/bin/sh
# Regenerates the Playwright baselines in apps/web/e2e/__screenshots__ inside the same Playwright image
# CI runs the e2e job in, so fonts and anti-aliasing match. The repository is copied into the container
# first because host node_modules hold native binaries for the wrong platform. Run from apps/web.
set -eu

version=$(node -p "require('@playwright/test/package.json').version")
repo_root=$(cd ../.. && pwd)

docker run --rm -v "$repo_root:/src" "mcr.microsoft.com/playwright:v${version}-noble" sh -c '
  set -eu
  mkdir /work
  tar -C /src --exclude=node_modules --exclude=dist --exclude=.git --exclude=.claude -cf - . | tar -C /work -xf -
  cd /work
  corepack enable
  pnpm install --frozen-lockfile --filter @snipnet/web...
  cd apps/web
  pnpm run build
  pnpm exec playwright test --update-snapshots
  mkdir -p /src/apps/web/e2e/__screenshots__
  cp e2e/__screenshots__/*.png /src/apps/web/e2e/__screenshots__/
  chown -R "$(stat -c %u /src):$(stat -c %g /src)" /src/apps/web/e2e/__screenshots__
'
