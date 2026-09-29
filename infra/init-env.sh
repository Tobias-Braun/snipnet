#!/bin/sh
# Creates infra/.env from infra/.env.example and fills every empty *_PASSWORD, *_SECRET and *_TOKEN value with
# a fresh random one, so local stacks never share credentials that are published in the repository.
# An existing infra/.env is left untouched; delete it (and the Docker volumes) to start over.
set -eu

dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
target="$dir/.env"

if [ -e "$target" ]; then
  echo "$target already exists, leaving it untouched"
  exit 0
fi

umask 077
awk '
  /^[A-Z0-9_]*(PASSWORD|SECRET|TOKEN)=$/ {
    cmd = "openssl rand -hex 24"
    cmd | getline value
    close(cmd)
    print $0 value
    next
  }
  { print }
' "$dir/.env.example" > "$target"

echo "created $target"
