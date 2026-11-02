# Step 1: KMP build setup (TrackMe SDK)

By the end of this step you'll have **one Kotlin codebase** that builds:

| Output | File | Consumed by |
|---|---|---|
| Android library (AAR, published as Maven artifact) | `io.baato.trackme:trackme-sdk:0.1.0` | any Android app (`implementation(...)`) |
| iOS framework | `TrackMeSDK.xcframework` | any Xcode project (`import TrackMeSDK`) |

The only feature so far is `checkHealth()` (`GET /health`). It's small on purpose: it runs the whole pipeline (shared code, platform HTTP engines, JSON parsing, tests on both platforms, packaging) before you add the real features.

---

## 1. The mental model

```
                 ┌──────────────── commonMain ────────────────┐
                 │  TrackMe, TrackMeConfig, models, Ktor calls │   ← 95% of the SDK lives here
                 │  expect fun platformName()                  │
                 └───────────────┬───────────────┬────────────┘
                                 │               │
                 ┌── androidMain ┘               └ iosMain ──────────┐
                 │ actual fun platformName()   actual fun platformName()
                 │ Ktor OkHttp engine          Ktor Darwin engine     │
                 │ (later: FusedLocation)      (later: CLLocationManager)
                 └──────┬──────────────┘       └────────┬────────────┘
                        ▼                               ▼
                 Kotlin/JVM → AAR              Kotlin/Native → .framework ×2 → XCFramework
```

- A **target** is a platform you compile for: `android`, `iosArm64` (devices) and `iosSimulatorArm64` (simulator on an Apple-silicon Mac).
- A **source set** is a folder of code plus its dependencies. `commonMain` is compiled for *every* target, so it may only use pure Kotlin and multiplatform libraries: no `android.*` and no `platform.UIKit.*`. `androidMain` and `iosMain` can call their platform APIs directly.
- **`expect` / `actual`**: common code *declares* something (`expect fun platformName(): String`), and each platform source set *implements* it. See `Platform.kt`, `Platform.android.kt` and `Platform.ios.kt`. Later steps use the same pattern for GPS, battery state and secure token storage.

The Android plugin is AGP 9's **`com.android.kotlin.multiplatform.library`**, which is now the only supported way to add an Android target to a KMP module. Its config block is `kotlin { android { … } }`. Older tutorials show `com.android.library` with a top-level `android {}` block; that combination no longer works in the same module as the KMP plugin.

---

## 2. Prerequisites on your Mac

| Tool | Version | Why |
|---|---|---|
| Android Studio | Quail 4 (2026.1.4) / Rabbit or newer | AGP 9.4 and compileSdk 37 (API 37 installs as `android-37.x`; older Studios can't sync it) |
| Kotlin Multiplatform plugin (Android Studio → Settings → Plugins) | latest | iOS run configs, environment check, `expect/actual` navigation |
| JDK | 17+ (Android Studio's bundled JBR 21 is fine) | AGP 9 needs ≥ 17 |
| Xcode + command line tools | current | Kotlin/Native links against the iOS SDK. Run `xcode-select -p` to check |

The first iOS build downloads the Kotlin/Native toolchain (~1 GB) into `~/.konan`. That happens once.

---

## 3. Project tour

```
TrackMe/
├── settings.gradle.kts          # repositories + which modules exist (":trackme-sdk")
├── build.gradle.kts             # declares plugin versions once (apply false)
├── gradle.properties            # JVM memory, caching, incremental Kotlin/Native
├── gradle/
│   ├── libs.versions.toml       # ALL versions (Kotlin 2.4.20, AGP 9.4.0, Ktor 3.6.0, …)
│   └── wrapper/                 # pins Gradle 9.6.0 so every machine/CI builds the same
├── local.properties             # NOT committed: sdk.dir + trackme.apiSecret
├── docs/
│   ├── 01-kmp-setup.md          # ← you are here
│   └── trackme-api.md           # the API notes (secret removed)
└── trackme-sdk/
    ├── build.gradle.kts         # targets, XCFramework, dependencies per source set
    └── src/
        ├── commonMain/kotlin/io/baato/trackme/
        │   ├── TrackMe.kt               # public entry point (what apps call)
        │   ├── TrackMeConfig.kt         # apiSecret + URLs supplied by the host app
        │   ├── Platform.kt              # expect fun platformName()
        │   ├── model/Health.kt          # @Serializable response
        │   └── network/HttpClientFactory.kt  # Ktor client: JSON, timeouts, base URL
        ├── commonTest/…/HealthCheckTest.kt   # runs on Android JVM AND iOS simulator
        ├── androidMain/
        │   ├── AndroidManifest.xml      # INTERNET permission (merged into host apps)
        │   └── kotlin/…/Platform.android.kt
        └── iosMain/kotlin/…/Platform.ios.kt
```

### Things worth noticing in `trackme-sdk/build.gradle.kts`

- **Version catalog accessors**: `libs.ktor.client.core` maps to `ktor-client-core` in `libs.versions.toml`. Dashes become dots.
- **Engines per platform**: `ktor-client-core` sits in `commonMain`, `ktor-client-okhttp` in `androidMain` and `ktor-client-darwin` in `iosMain`. Common code calls `HttpClient {}` and Ktor picks the engine available on each platform.
- **XCFramework**: both iOS targets add their framework to `XCFramework("TrackMeSDK")`, which produces one bundle containing device and simulator slices.
- **`isStatic = true`**: the framework is linked into the app binary. That's simpler to integrate, and in Xcode you choose *Do Not Embed*.
- **`objcExportSuspendFunctionLaunchThreadRestriction = none`**: without it, Swift may only call Kotlin `suspend` functions from the main thread.
- **`maven-publish`**: gives you `publishToMavenLocal` (and later a remote repo) for Android consumers.

### Things worth noticing in the Kotlin code

- `TrackMe` has a **public** constructor that apps use, plus an **internal** one that takes a Ktor `HttpClientEngine`. Tests pass a `MockEngine`, so they never touch the network. `internal` declarations are invisible to Android apps and to Swift.
- `@Throws(Exception::class)` on `checkHealth()`. If a Kotlin exception reaches Swift without `@Throws`, the **app crashes**. With it, Swift sees `func checkHealth() async throws -> HealthResponse`. Every public function that can fail needs this annotation.
- `expectSuccess = true` makes non-2xx responses throw (`ClientRequestException`/`ServerResponseException`) instead of failing later during JSON parsing.
- `ignoreUnknownKeys = true` means the backend can add fields without breaking SDK versions already shipped in apps.
- **The secret is not in the SDK.** `TrackMeConfig(apiSecret = …)` is supplied by each host app.

---

## 4. First build, step by step

1. **Open** `~/Documents/TrackMe` in Android Studio (*File → Open*, select the folder). Let Gradle sync finish. It downloads Gradle 9.6.0, AGP and the libraries.
   - If sync complains about the JDK, go to *Settings → Build Tools → Gradle → Gradle JDK* and choose the bundled JBR (21).
   - Android Studio adds `sdk.dir=…` to `local.properties` automatically.
2. **Run the tests on both platforms** (from the Android Studio terminal):
   ```bash
   ./gradlew :trackme-sdk:allTests
   ```
   This runs `HealthCheckTest` on the Android host JVM (`testAndroidHostTest`) and on an iOS simulator (`iosSimulatorArm64Test`). The same test runs on both platforms, which is the main payoff of KMP.
3. **Build the iOS framework**:
   ```bash
   ./gradlew :trackme-sdk:assembleTrackMeSDKXCFramework
   ```
   Output: `trackme-sdk/build/XCFrameworks/release/TrackMeSDK.xcframework` (and `/debug/`).
4. **Publish the Android library locally**:
   ```bash
   ./gradlew :trackme-sdk:publishToMavenLocal
   ```
   Output in `~/.m2/repository/io/baato/trackme/`: one folder per target. `trackme-sdk/` holds the root artifact (the `.module` file that tells Gradle which variant to use); the `.aar` is in `trackme-sdk-android/0.1.0/`; the iOS klibs are in `trackme-sdk-iosarm64/` and `trackme-sdk-iossimulatorarm64/`. Consumers only declare `io.baato.trackme:trackme-sdk:0.1.0`, and Gradle picks the right one.
5. `compileSdk` is **37**, because Ktor's OkHttp 5.5 engine requires it (`checkAndroidMainAarMetadata` fails on 36). Apps that use the SDK need compileSdk ≥ 37 too; `minSdk` stays 24.

Each command should end with `BUILD SUCCESSFUL`. If one fails, the first `e:` line in the output is the error to look at.

---

## 5. Consuming the SDK (smoke test)

> **Updated in step 2:** the API is now `TrackMe.initialize(context, TrackMeConfig(token = …))` + `TrackMeResult` values. The host app passes a token; the SDK never holds the API secret. See [02-sdk-design.md](02-sdk-design.md). The snippets below show the step-1 shape and are kept for reference.

### Android app

```kotlin
// settings.gradle.kts of the consumer app
dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }

// app/build.gradle.kts
dependencies { implementation("io.baato.trackme:trackme-sdk:0.1.0") }
```

```kotlin
val trackMe = TrackMe(TrackMeConfig(apiSecret = BuildConfig.TRACKME_SECRET))

lifecycleScope.launch {
    val health = runCatching { trackMe.checkHealth() }
    Log.d("TrackMe", "health = $health")   // Success(HealthResponse(status=ok))
}
```

### iOS app

1. Drag `TrackMeSDK.xcframework` into the Xcode project's *Frameworks, Libraries, and Embedded Content* → **Do Not Embed** (the framework is static).
2. Use it:

```swift
import TrackMeSDK

let trackMe = TrackMe(config: TrackMeConfig(
    apiSecret: Secrets.trackMe,                       // e.g. from an .xcconfig, not committed
    apiUrl: "https://tracker.baato.io/trackme/api",
    socketUrl: "https://tracker.baato.io/trackme"))   // Swift doesn't see Kotlin default args

Task {
    do {
        let health = try await trackMe.checkHealth()
        print("health ok:", health.isOk)
    } catch {
        print("health failed:", error)                // a Kotlin exception becomes an NSError
    }
}
```

> Later we'll switch iOS to **SwiftPM distribution** (a `Package.swift` pointing at a zipped XCFramework), so apps add the SDK by URL instead of dragging files in.

---

## 6. Secrets

- `local.properties` holds `trackme.apiSecret`. It is gitignored. The copy of the API notes in `docs/` has the secret removed.
- Anything compiled into a mobile app can be extracted, including an `api_secret`. That's acceptable for development and internal apps. For public distribution, ask the backend team whether login can be tied to a per-app key they can rotate.

---

## 7. Roadmap (next steps)

| Step | Topic | KMP concept you'll learn |
|---|---|---|
| 2 | Login + token storage + auto re-login on expiry | `expect/actual` for secure storage (Keychain / EncryptedSharedPreferences), Ktor `Auth` bearer plugin |
| 3 | Trips API: create, list, preview, join, detail, rename, end, leave, kick, delete, view links | `@Serializable` models, a sealed `TrackMeResult`/error type that is friendly to Swift |
| 4 | Device location | `expect/actual` with real platform APIs: FusedLocationProvider vs CLLocationManager, permissions |
| 5 | Live ingest loop (`POST /ingest/live`) | Coroutines + `Flow`, lifecycle, battery info per platform, background behavior |
| 6 | Real-time via socket.io (`member_changed`, `trip_ended`, `trip_position`) | Wrapping a KMP socket library (candidate: `com.piasy:kmp-socketio`) behind our own `Flow` API |
| 7 | Exposing `Flow` nicely to Swift | SKIE or KMP-NativeCoroutines → `AsyncSequence` |
| 8 | Distribution | Maven (GitHub Packages / Maven Central) for Android, SwiftPM for iOS, CI |
