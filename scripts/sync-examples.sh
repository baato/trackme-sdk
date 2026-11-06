#!/usr/bin/env bash
# Copies the sample apps' code into the standalone examples.
#
#   sample-android/, sample-ios/   build against the SDK *source* in this repo (for SDK development)
#   examples/android/, examples/ios/  build against the *published* SDK (JitPack, SwiftPM), like a customer app
#
# The app code is the same: edit it in the samples, then run this script. Build files (Gradle, project.yml)
# are separate and are not touched here. Android: the Kotlin package becomes io.baato.trackme.example.
#
#   scripts/sync-examples.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# ---------- Android ----------
src=sample-android/src/main
dst=examples/android/app/src/main
rm -rf "$dst/kotlin"
mkdir -p "$dst/kotlin/io/baato/trackme/example"
for f in "$src"/kotlin/io/baato/trackme/sample/*.kt; do
  sed -e 's/io\.baato\.trackme\.sample/io.baato.trackme.example/g' -e 's/"TrackMe Sample"/"LocationShare"/g' "$f" \
    > "$dst/kotlin/io/baato/trackme/example/$(basename "$f")"
done
sed 's/android:label="TrackMe Sample"/android:label="LocationShare"/' "$src/AndroidManifest.xml" > "$dst/AndroidManifest.xml"
# Launcher icon (LocationShare: a pin sending its position, teal to blue).
mkdir -p "$dst/res" && cp -R "$src/res/." "$dst/res/"

# ---------- iOS ----------
mkdir -p examples/ios/TrackMeExample examples/ios/TrackMeExampleWidget
rm -f examples/ios/TrackMeExample/*.swift examples/ios/TrackMeExampleWidget/*.swift   # keeps the generated Info.plist
for f in sample-ios/TrackMeSample/*.swift; do
  sed 's/"TrackMe Sample"/"LocationShare"/g' "$f" > "examples/ios/TrackMeExample/$(basename "$f")"
done
cp sample-ios/TrackMeSampleWidget/*.swift examples/ios/TrackMeExampleWidget/

echo "✔ examples/ synced from the samples. Re-run xcodegen in examples/ios if files were added or removed."
