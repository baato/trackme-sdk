#!/usr/bin/env bash
# Prints the compileSdk floor (minCompileSdk in each AAR's aar-metadata.properties) for OkHttp 5.x releases. Used to pick versions that AGP 8.x apps (compileSdk 36) can build.
# Usage: scripts/check-aar-metadata.sh
set -euo pipefail
M=https://repo1.maven.org/maven2
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

echo "== okhttp-android: minCompileSdk"
for v in $(curl -fsS $M/com/squareup/okhttp3/okhttp-android/maven-metadata.xml | grep -o '<version>5\.[0-9.]*</version>' | sed 's/<[^>]*>//g'); do
  if curl -fsSo "$tmp/o.aar" "$M/com/squareup/okhttp3/okhttp-android/$v/okhttp-android-$v.aar"; then
    echo "  $v  $(unzip -p "$tmp/o.aar" META-INF/com/android/build/gradle/aar-metadata.properties 2>/dev/null | grep -E '^minCompileSdk=' || echo 'no metadata')"
  fi
done

