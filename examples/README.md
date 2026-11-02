# TrackMe examples: the published SDK, as a customer installs it

These two apps don't build the SDK. They download the **released** packages, the same way an integrating app does:

| | Gets the SDK from | Dependency |
|---|---|---|
| `android/` | JitPack | `com.github.baato.trackme-sdk:trackme-sdk-android:0.1.0` |
| `ios/` | SwiftPM | `https://github.com/baato/trackme-sdk`, from `0.1.0`: products `TrackMeSDK` + `TrackMeLiveActivity` |

The app code is the same as `sample-android/` and `sample-ios/`: the Live map showcase plus the Developer screens covering every SDK call ([docs/06-sample-apps.md](../docs/06-sample-apps.md)). The difference is only where the SDK comes from:

- **samples:** the SDK *source* in this repo. Use them while changing the SDK.
- **examples:** the *published* release. Use them to check a release, and as the copy-paste starting point for an app.

They install next to each other: `io.baato.trackme.example` vs `io.baato.trackme.sample`.

## Android

Open `examples/android` in Android Studio as its own project (not the repo root).

```bash
cd examples/android
cp local.properties.example local.properties   # DEMO ONLY: API secret + Baato map key (git-ignored)
./gradlew :app:installDebug
```

`settings.gradle.kts` adds JitPack, limited to the `com.github.baato.trackme-sdk` group. The version is `trackme` in `gradle/libs.versions.toml`. The first sync after a new tag can take a few minutes while JitPack builds it.

## iOS

```bash
cd examples/ios
cp Secrets.example.xcconfig Secrets.xcconfig   # DEMO ONLY: API secret + Baato map key (git-ignored)
xcodegen && open TrackMeExample.xcodeproj
```

Pick your Team under *Signing & Capabilities* for both targets, then Run. Xcode resolves the TrackMe package and downloads `TrackMeSDK.xcframework.zip` from the GitHub release, checking it against the checksum in `Package.swift`. No Gradle or JDK is needed.

Packages are pinned in `project.yml` (`from: 0.1.0`, so any 0.x ≥ 0.1.0). To try a new release: *File → Packages → Update to Latest Package Versions*, or *Reset Package Caches* if a release was re-uploaded.

## Keeping them in step

Change the app code in the samples, then copy it here:

```bash
scripts/sync-examples.sh
```

The script copies the Kotlin (with package `io.baato.trackme.example`) and Swift files, and leaves the build files alone. Sync after a release: if the samples already use SDK changes that aren't released yet, the examples won't compile against the published version. After a release, bump `trackme` in `examples/android/gradle/libs.versions.toml`. iOS picks up new 0.x versions by itself.
