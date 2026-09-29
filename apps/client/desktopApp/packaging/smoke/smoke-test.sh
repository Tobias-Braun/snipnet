#!/usr/bin/env bash
# Smoke test of the packaged app image (what the installers wrap): builds it with createDistributable and runs a
# probe restricted to exactly the modules of the image's bundled jlink runtime, so a module missing from
# `modules(...)` in desktopApp/build.gradle.kts fails here instead of on a user's machine. Linux only; run from
# apps/client.
#
# jpackage strips bin/java from the bundled runtime (the app launcher loads libjvm directly), so the runtime cannot be
# executed on its own. Instead the host JDK, which is the same version as the one that produced the runtime, is run
# with --limit-modules set to the module list that jlink recorded in the runtime's `release` file.
set -euo pipefail

./gradlew :desktopApp:createDistributable --stacktrace

image=desktopApp/build/compose/binaries/main/app/Snipnet
release_file="$image/lib/runtime/release"
[ -f "$release_file" ] || { echo "bundled runtime not found at $release_file" >&2; exit 1; }

modules="$(sed -n 's/^MODULES="\(.*\)"$/\1/p' "$release_file" | tr ' ' ',')"
[ -n "$modules" ] || { echo "no MODULES entry in $release_file" >&2; exit 1; }
echo "bundled runtime modules: $modules"

probe_dir="$(mktemp -d)"
trap 'rm -rf "$probe_dir"' EXIT
javac --release 21 -d "$probe_dir" desktopApp/packaging/smoke/RuntimeProbe.java

java -Djava.awt.headless=true --limit-modules "$modules" -cp "$probe_dir:$image/lib/app/*" RuntimeProbe
