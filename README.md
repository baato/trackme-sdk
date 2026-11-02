# TrackMe

A Kotlin Multiplatform (KMP) SDK that produces **Android** and **iOS** packages any host app can consume to:

- share live location with one another,
- form groups (trips), and
- join a group via a shareable link / join code.

The project doubles as a step-by-step KMP walkthrough:

1. [docs/01-kmp-setup.md](docs/01-kmp-setup.md): KMP build setup
2. [docs/02-sdk-design.md](docs/02-sdk-design.md): public API contract and roadmap
3. [docs/03-location-sharing.md](docs/03-location-sharing.md): location permission and the sharing engine
4. **[docs/04-integration-guide.md](docs/04-integration-guide.md): how an app integrates the SDK (Android + iOS)**
5. [docs/05-releasing.md](docs/05-releasing.md): publishing to Maven and SwiftPM
6. [docs/06-sample-apps.md](docs/06-sample-apps.md): which sample screen calls which SDK function, and a two-device test script

## Repository layout

| Path | What |
|---|---|
| `trackme-sdk/` | the KMP library → Android AAR + `TrackMeSDK.xcframework` |
| `sample-android/` | Compose demo: Live map showcase (MapLibre + Baato) and developer screens covering every SDK call (`./gradlew :sample-android:installDebug`) |
| `TrackMeLiveActivity/` | Swift package: iOS lock screen / Dynamic Island Live Activity + iOS 15 notification fallback |
| `sample-ios/` | SwiftUI demo: the same Live map and developer screens, plus the Live Activity widget (XcodeGen; see its README) |
| `scripts/release-ios.sh` | builds and zips the XCFramework, generates the SwiftPM `Package.swift` |

## Quick start

```bash
./gradlew :trackme-sdk:allTests                      # tests on Android JVM + iOS simulator
./gradlew :trackme-sdk:assembleTrackMeSDKXCFramework  # → trackme-sdk/build/XCFrameworks/
./gradlew :trackme-sdk:publishToMavenLocal           # → ~/.m2 (io.baato.trackme:trackme-sdk)
./gradlew :sample-android:installDebug               # demo app on a connected device / emulator
cd sample-ios && xcodegen && open TrackMeSample.xcodeproj   # iOS demo app
```

Samples need secrets in git-ignored files (TrackMe demo secret, Baato map key) and have a dev-tools flag: see [docs/06-sample-apps.md](docs/06-sample-apps.md#setup-secrets-and-the-dev-tools-flag).

## Stack

Kotlin 2.4.20 · AGP 9.4.0 (`com.android.kotlin.multiplatform.library`) · Gradle 9.6.0 · Ktor 3.6.0 · kotlinx.serialization / coroutines 1.11.0

## References

- TrackMe API docs: https://tracker.baato.io/trackme/docs#/
- API usage notes: [docs/trackme-api.md](docs/trackme-api.md)
- Realtime (Socket.IO) contract: [docs/trackme-api.md → Realtime](docs/trackme-api.md#realtime-socketio); SDK usage: [docs/04-integration-guide.md §7](docs/04-integration-guide.md)
