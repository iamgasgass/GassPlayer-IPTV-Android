#!/usr/bin/env bash
set -euo pipefail
./gradlew lintDebug testDebugUnitTest assembleDebug --stacktrace
APK=$(find app/build/outputs/apk/debug -name '*.apk' -print -quit)
test -n "$APK"
echo "Build verificata: $APK"
