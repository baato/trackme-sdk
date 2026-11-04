# Flutter and React Native ports

**Status:** in progress on the `ports` branch. Steps 1 and 2, the bridge core and both packages, are written; the sample apps come next.

## The approach: wrap the KMP SDK, don't rewrite it

The hard parts of TrackMe are native on every platform:
- background location;
- Android's foreground service and its notification buttons;
- the iOS Live Activity;
- sharing that keeps running when the app is backgrounded or killed.

A Dart or TypeScript rewrite would still need native code for all of those. It would also have to re-learn every fix the SDK already has: rejoin after a reconnect, the duplicate-join guard, strict payload decoding, the trip countdown, sharing-state reporting. So the Flutter and React Native packages are **thin wrappers over the same Android and iOS SDK**:

```
 Dart / TypeScript API (typed models, Streams / listeners)
        │  strings only
 Flutter plugin  /  React Native Turbo Module   (Kotlin + Swift, ~100 lines each, pass-through)
        │
 TrackMeBridge (in the SDK, common Kotlin): method name + JSON args → JSON result; one event stream
        │
 TrackMe SDK (Android AAR / iOS XCFramework): the real implementation
```

**One bridge core:** all mapping between SDK types and JSON lives in `trackme-sdk/.../bridge/TrackMeBridge.kt`, tested once in `TrackMeBridgeTest`. The four platform glues (Flutter Android/iOS, React Native Android/iOS) only move strings, so they can't disagree. Any future wrapper (Capacitor, .NET MAUI, …) gets the same.

**Sharing doesn't depend on Dart or JS.** The Android service, the notification's Pause/Stop and the Live Activity are native. They keep working when the Flutter engine or the JS runtime is gone. Events reach Dart or JS whenever the app is running.

## Bridge protocol (v1)

`TrackMeBridge.PROTOCOL = 1`. JSON uses the API's snake_case names; server models (trips, members, notifications) are exactly as in [trackme-api.md](trackme-api.md).

### Calls

- **Start a session:** `start(context, configJson, tokenProvider)` (it runs `TrackMe.initialize`) returns an envelope synchronously. It's named `start` because Objective-C treats `init…` methods as initializers.
- **Everything else:** `call(method, argsJson, onResult)` calls `onResult` once, possibly on a background thread.
- **Envelopes:**
  - success: `{"ok":true,"value":…}`;
  - failure: `{"ok":false,"error":{"type":"NotFound","message":"…"}}`.
- **Error `type` values:** `NotInitialized`, `Unauthorized`, `Forbidden`, `NotFound`, `Conflict`, `Validation`, `Network`, `Server` (plus `status_code`), `LocationPermissionDenied` (plus `can_ask_again`), `LocationServicesDisabled`, `BackgroundNotAllowed`, `Unknown`.

| Method | Args | Value |
|---|---|---|
| `protocol`, `isInitialized`, `state` | – | `1`, bool, `{type}` |
| `updateToken` | `token` | null |
| `dispose` | – | null |
| `checkHealth` | – | `{status}` |
| `trips.create` | `name?`, `duration_min` | `TripCreateResponse` |
| `trips.list` | `limit` | `[TripSummary]` |
| `trips.preview` / `trips.join` | `code` | `TripPreview` / `TripSummary` |
| `trips.detail` | `trip_id` | `TripDetail` (members carry `sharing_state` when the server sends it) |
| `trips.rename` | `trip_id`, `name` | `TripSummary` |
| `trips.end` | `trip_id` | `TripSummary` |
| `trips.leave` / `trips.delete` | `trip_id` | null |
| `trips.removeMember` | `trip_id`, `user_id` | null |
| `trips.viewLink` / `trips.rotateViewLink` | `trip_id` | `{view_link}` |
| `trips.history` | `trip_id`, `from?`, `to?` | the server's history JSON |
| `sharing.start` | `options?: {interval_ms, min_distance_m, heartbeat_ms, high_accuracy}` | null |
| `sharing.pause` / `resume` / `stop` | – | null |
| `sharing.state` | – | sharing state (below) |
| `location.permissionStatus` | – | `{level, precise, services_enabled, background_capable}` |
| `realtime.watchTrip` | `trip_id` | null |
| `realtime.watchShare` | `view_token` | null |
| `realtime.stopWatchingTrip` / `stopWatchingShare` / `stop` | – | null |
| `realtime.state` | – | realtime state (below) |
| `notifications.list` | `unread_only?`, `trip_id?`, `cursor?`, `limit?` (default 20) | `NotificationPage` |
| `notifications.markRead` | `notification_id` | null |
| `notifications.markAllRead` | – | null |

**Config** (`start`): `{"token", "api_url"?, "socket_url"?, "request_timeout_ms"?, "token_refresh_timeout_ms"?, "sharing_ui"?}`. `sharing_ui` keys:
- `title`, `text`, `paused_text`;
- `accent_color` (an ARGB number);
- `show_pause_action`, `show_stop_action`;
- `pause_label`, `resume_label`, `stop_label`;
- `android_small_icon`: a resource id. The Android glue resolves a drawable *name* to that id.
- `android_channel_name`, `ios_symbol_name`.

### Event stream

`observe(onEvent)` sends `{"stream":…,"value":…}`. Each state stream sends its current value first.

| Stream | Value |
|---|---|
| `state` | `{type: NotInitialized \| Ready \| Disposed}` |
| `sharing` | `{type: Idle \| Starting \| Paused}`, `{type: Stopped, reason}`, `{type: Sharing, trip_ids, last_location?, last_sent_at_ms?, retrying}` |
| `realtime` | `{type: Idle \| Connecting}`, `{type: Reconnecting, attempt}`, `{type: Connected, trip_id?, watching_share}` |
| `event` | `{type: TripPosition \| SharePosition, position: {trip_id?, user_id, display_name?, latitude, longitude, speed_mps?, fix_age_s?}}`, `{type: MemberChanged, trip_id, user_id, display_name?, change}`, `{type: MemberSharingChanged, trip_id, user_id, display_name?, state}`, `{type: TripEnded \| Evicted, trip_id?, reason?}`, `{type: SharingStopped, reason}`, `AuthenticationFailed`, `TokenRefreshed`, `ShareEnded` |

`last_location`: `{latitude, longitude, timestamp_ms, accuracy_m?, altitude_m?, speed_mps?, bearing_deg?}`.

### What each platform glue does

The same four duties on Flutter and React Native, on Android and iOS:

1. **`start(configJson)`:** build `PlatformContext` and call `TrackMeBridge.start`. On Android, first resolve a `sharing_ui.android_small_icon_name` to a resource id.
2. **`call(method, argsJson)`:** forward to `TrackMeBridge.call`, then answer the framework **on the main thread**.
3. **Events:** subscribe with `TrackMeBridge.observe` while Dart or JS listens. Forward each string on the main thread, and cancel when nobody listens.
4. **Token provider:** pass one that asks Dart or JS (Flutter: `invokeMethod("refreshToken")`; React Native: emit `refreshToken` with a request id and wait for `provideToken(id, token)`). If that side doesn't answer, return `null`; the SDK's own timeout applies.

Permissions stay the app's job, through the framework's usual package (`permission_handler`, `react-native-permissions`). The SDK only reports the status.

## Layout (same repo, `ports` branch)

```
flutter/trackme_flutter/          Flutter plugin (pub package): lib/ (Dart API + models), android/, ios/, example/
react-native/trackme-react-native/ React Native Turbo Module (npm package): src/ (TS API + models), android/, ios/,
                                   app.plugin.js (Expo config plugin), example/
```

JitPack and SwiftPM are unaffected. JitPack builds only `:trackme-sdk`, and the root `Package.swift` doesn't look in these folders.

## Getting the native SDK in

| | Development (in this repo) | Release (customers) |
|---|---|---|
| Android | `./gradlew :trackme-sdk:publishToMavenLocal`, plugin reads `io.baato.trackme:trackme-sdk-android` from `mavenLocal()` | `com.github.baato.trackme-sdk:trackme-sdk-android:<sdk version>` from JitPack. **The app adds** `maven("https://jitpack.io")`, because Gradle rejects repositories declared by a plugin |
| iOS | podspec vendors `trackme-sdk/build/XCFrameworks/release/TrackMeSDK.xcframework` | podspec downloads the release's `TrackMeSDK.xcframework.zip`. CocoaPods, because Flutter and React Native install native iOS code through it; SwiftPM support can follow |
| Live Activity | `TrackMeLiveActivity` sources in the repo | Host apps add a **Widget Extension** target and link `TrackMeLiveActivity` (documented step). Expo: the config plugin does the Info.plist part |

## Expo

Expo's autolinking links ordinary React Native modules as well as Expo modules, and any library with native code runs in an Expo **development build** (not in Expo Go). So a bare Turbo Module serves both kinds of app. The package also ships an Expo **config plugin** (`app.plugin.js`) that adds the location usage strings and background mode, because Expo regenerates the native projects.

## Order of work

1. ✅ **The bridge core** in the SDK, with tests. It ships in the next SDK release (0.3.0).
2. ✅ **The packages:** `flutter/trackme_flutter` and `react-native/trackme-react-native`. Each has typed models and API, the platform glues, and dev wiring to the local SDK (`trackme.useLocalSdk=true`, `scripts/port-ios-sdk.sh local`). The TypeScript type-checks, and the React Native spec was run through React Native's codegen, whose generated Java and Objective-C signatures match the native code. The Flutter side and all native builds are checked when the example apps build.
3. **Sample apps** in each package's `example/`: the Live map showcase plus developer screens, ported from the native samples. They use `maplibre_gl` on Flutter and `@maplibre/maplibre-react-native` on React Native, with the same `trackme.devTools` / secrets approach.
4. **Release:** SDK 0.3.0 → Flutter plugin 0.3.0 (pub.dev) and the React Native module 0.3.0 (npm), versioned with the SDK. Then customer-style examples that install the published packages.

## Risks to check in the first example builds

- **Kotlin version:** the SDK is compiled with Kotlin 2.4. A host app whose Kotlin Gradle plugin is too old to read that metadata fails to compile the plugin's Kotlin code ("incompatible version of Kotlin"). Either the app uses a recent enough Kotlin, or the SDK's Android target sets a lower `languageVersion` / `apiVersion`. That's decided when the Flutter and React Native examples first build.
- **compileSdk 37:** the SDK requires it, so the host app's Android Gradle Plugin must support API 37.
- **The XCFramework through CocoaPods:** the podspecs vendor `ios/Frameworks/TrackMeSDK.xcframework` (static, arm64 only), so `pod install` must find it there first.

Before publishing: npm allows a proprietary licence (`"license": "UNLICENSED"`). Check how pub.dev treats a non-open-source licence before the first Flutter publish.
