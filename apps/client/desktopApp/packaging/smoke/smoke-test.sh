#!/usr/bin/env bash
# Smoke test of the packaged app image (what the installers wrap): builds it with createDistributable and runs a
# probe with the image's bundled runtime, so a module missing from the jlink runtime (see `modules(...)` in
# desktopApp/build.gradle.kts) fails here instead of on a user's machine. Linux only; run from apps/client.
set -euo pipefail

./gradlew :desktopApp:createDistributable --stacktrace

dist=desktopApp/build/compose/binaries/main/app
runtime_java="$(find "$dist" -path '*/runtime/bin/java' \( -type f -o -type l \) | head -n 1)"
[ -n "$runtime_java" ] || { echo "bundled runtime not found under $dist:" >&2; find "$dist" -maxdepth 5 >&2; exit 1; }
# The app jars sit in the app/ folder next to the runtime/ folder.
app_libs="$(dirname "$(dirname "$(dirname "$runtime_java")")")/app"
ls "$app_libs" > /dev/null

probe_dir="$(mktemp -d)"
trap 'rm -rf "$probe_dir"' EXIT
javac --release 21 -d "$probe_dir" desktopApp/packaging/smoke/RuntimeProbe.java

"$runtime_java" -Djava.awt.headless=true -cp "$probe_dir:$app_libs/*" RuntimeProbe
