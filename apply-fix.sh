#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
test -f "$ROOT/settings.gradle.kts"
test -f "$ROOT/app/build.gradle.kts"
test -f "$ROOT/gradlew"
echo "GassPlayer è già nella versione corrente; nessuna patch applicativa richiesta."
