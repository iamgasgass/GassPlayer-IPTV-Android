#!/usr/bin/env bash
set -euo pipefail
SOURCE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/app"
test -f settings.gradle.kts || { echo "Esegui questo script dalla root della repository Android." >&2; exit 1; }
cp -f "$SOURCE_DIR/src/main/java/com/iamgasgass/gassplayer/services/TraktService.kt" app/src/main/java/com/iamgasgass/gassplayer/services/TraktService.kt
cp -f "$SOURCE_DIR/src/main/java/com/iamgasgass/gassplayer/ui/GassPlayerApp.kt" app/src/main/java/com/iamgasgass/gassplayer/ui/GassPlayerApp.kt
cp -f "$SOURCE_DIR/src/main/java/com/iamgasgass/gassplayer/ui/screens/Common.kt" app/src/main/java/com/iamgasgass/gassplayer/ui/screens/Common.kt
cp -f "$SOURCE_DIR/src/main/java/com/iamgasgass/gassplayer/ui/theme/Theme.kt" app/src/main/java/com/iamgasgass/gassplayer/ui/theme/Theme.kt
echo "Patch applicata. Esegui la GitHub Action Build Android APK."
