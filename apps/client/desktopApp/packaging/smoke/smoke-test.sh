#!/usr/bin/env bash
# Smoke test of the packaged app image (what the installers wrap): builds it with createDistributable and fails when
# its bundled jlink runtime lacks a module the app needs, so a stale `modules(...)` list in
# desktopApp/build.gradle.kts fails here instead of on a user's machine. Linux only; run from apps/client.
#
# Two checks, because neither covers everything on its own:
# 1. Every module that `suggestRuntimeModules` (jdeps over the app's jars) reports must be in the image. This catches
#    modules that are only touched on some code paths, such as jdk.unsupported or java.management.
# 2. A probe opens SQLite and loads the FFmpeg natives restricted to the image's modules. This catches what jdeps
#    cannot see, such as the JDBC driver being loaded through DriverManager.
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

# The task prints the suggestion as a Gradle snippet: modules("java.instrument", "java.sql", ...).
suggested="$(./gradlew -q :desktopApp:suggestRuntimeModules | sed -n 's/^modules(\(.*\))$/\1/p' | tr -d '" ')"
[ -n "$suggested" ] || { echo "suggestRuntimeModules printed no modules(...) line" >&2; exit 1; }
echo "suggested runtime modules: $suggested"
missing=()
for module in ${suggested//,/ }; do
  [[ ",$modules," == *",$module,"* ]] || missing+=("$module")
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "bundled runtime lacks modules suggested by suggestRuntimeModules: ${missing[*]}" >&2
  echo "add them to modules(...) in desktopApp/build.gradle.kts" >&2
  exit 1
fi

probe_dir="$(mktemp -d)"
trap 'rm -rf "$probe_dir"' EXIT
javac --release 21 -d "$probe_dir" desktopApp/packaging/smoke/RuntimeProbe.java

java -Djava.awt.headless=true --limit-modules "$modules" -cp "$probe_dir:$image/lib/app/*" RuntimeProbe
