#!/usr/bin/env bash
# Puts TrackMeSDK.xcframework into the Flutter plugin and the React Native module (their ios/Frameworks/,
# git-ignored). Their podspecs vendor it from there.
#
#   scripts/port-ios-sdk.sh local    # development: this repo's SDK build (builds it first)
#   scripts/port-ios-sdk.sh 0.3.0    # publishing: the released zip from GitHub, checked against that tag's Package.swift
set -euo pipefail
cd "$(dirname "$0")/.."

source="${1:?usage: scripts/port-ios-sdk.sh local|<version>}"
targets=(flutter/trackme_flutter/ios/Frameworks react-native/trackme-react-native/ios/Frameworks)
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [ "$source" = local ]; then
  ./gradlew :trackme-sdk:assembleTrackMeSDKReleaseXCFramework
  framework="trackme-sdk/build/XCFrameworks/release/TrackMeSDK.xcframework"
else
  url="https://github.com/baato/trackme-sdk/releases/download/$source/TrackMeSDK.xcframework.zip"
  curl -fsSL -o "$work/sdk.zip" "$url"
  expected="$(git show "$source:Package.swift" | sed -n 's/.*checksum: "\([0-9a-f]*\)".*/\1/p')"
  actual="$(shasum -a 256 "$work/sdk.zip" | cut -d' ' -f1)"
  [ -n "$expected" ] && [ "$expected" = "$actual" ] || { echo "✘ checksum mismatch for $url" >&2; exit 1; }
  unzip -q "$work/sdk.zip" -d "$work"
  framework="$work/TrackMeSDK.xcframework"
fi

for t in "${targets[@]}"; do
  mkdir -p "$t"
  rm -rf "$t/TrackMeSDK.xcframework"
  cp -R "$framework" "$t/"
  echo "✔ $t/TrackMeSDK.xcframework ($source)"
done
echo "Next: pod install in the example app(s)."
