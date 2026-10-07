#!/usr/bin/env sh
set -eu
GRADLE_VERSION="9.8.0"
GRADLE_HOME="${GRADLE_HOME:-}"
if [ -n "$GRADLE_HOME" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then exec "$GRADLE_HOME/bin/gradle" "$@"; fi
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-$GRADLE_VERSION-bin"
DIST="$CACHE/gradle-$GRADLE_VERSION/bin/gradle"
if [ ! -x "$DIST" ]; then
  mkdir -p "$CACHE"
  TMP="$CACHE/gradle-$GRADLE_VERSION-bin.zip"
  echo "Gradle $GRADLE_VERSION non trovato; download..." >&2
  if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o "$TMP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"; elif command -v wget >/dev/null 2>&1; then wget -O "$TMP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"; else echo "Serve curl o wget." >&2; exit 1; fi
  rm -rf "$CACHE/gradle-$GRADLE_VERSION"
  mkdir -p "$CACHE"
  unzip -q "$TMP" -d "$CACHE"
fi
exec "$DIST" "$@"
