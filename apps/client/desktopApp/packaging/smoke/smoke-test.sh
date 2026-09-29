#!/usr/bin/env bash
# Smoke test of the packaged app image (what the installers wrap): builds it with createDistributable and runs a
# probe with the image's bundled runtime, so a module missing from the jlink runtime (see `modules(...)` in
# desktopApp/build.gradle.kts) fails here instead of on a user's machine. Linux only; run from apps/client.
set -euo pipefail

./gradlew :desktopApp:createDistributable --stacktrace

image=desktopApp/build/compose/binaries/main/app/Snipnet
runtime_java="$image/lib/runtime/bin/java"
[ -x "$runtime_java" ] || { echo "bundled runtime not found at $runtime_java" >&2; exit 1; }

probe_dir="$(mktemp -d)"
trap 'rm -rf "$probe_dir"' EXIT
javac --release 21 -d "$probe_dir" desktopApp/packaging/smoke/RuntimeProbe.java

"$runtime_java" -Djava.awt.headless=true -cp "$probe_dir:$image/lib/app/*" RuntimeProbe
