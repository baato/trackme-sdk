# Flutter and React Native ports

**Status:** in progress on the `ports` branch. Steps 1 and 2 (the bridge core and both packages) run on Android and iOS for both frameworks. Step 3, the Live map showcase plus a developer screen in each example, is written and being tried on devices.

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
| iOS lock screen | `scripts/port-ios-sdk.sh` also copies the `TrackMeLiveActivity` sources and the native sample's `LiveActivityPresenter` into each package's `ios/LiveActivity/`, and the glue installs the presenter on `start` | Same files, shipped inside the packages |

## iOS lock screen: notification out of the box, Live Activity with one step

On Android the SDK's own foreground-service notification shows sharing. iOS has nothing like it, so the packages bring
the `TrackMeLiveActivity` code with them and install its presenter when the session starts (no app code):

- **No extra setup:** a notification with Pause / Resume / Stop while sharing (it needs notification permission,
  which the examples ask for along with location). Its look comes from the same `sharingUi` as Android.
- **With a Widget Extension:** the Live Activity on the lock screen and Dynamic Island (iOS 16.1+, buttons on 17+).
  Apple only allows that UI in the app's own extension, so it's one step per app:
  `ruby scripts/ios-add-widget-extension.rb <App.xcodeproj> <app target> <TrackMeLiveActivity sources dir>` adds a
  `TrackMeWidget` target and `NSSupportsLiveActivities`. Both examples have it.
- `TrackMeActivityController.hasWidgetExtension` picks between the two, so an app without the extension never asks
  for a Live Activity that can't be drawn (this also protects native apps).
- **Opt out:** `sharingUi: iosLiveActivity: false` when the app shows its own lock-screen UI.
- An app with its own `UNUserNotificationCenter` delegate keeps it and forwards to
  `TrackMeActivityController.shared.notificationDelegate.handle(response)`.

## Expo

Expo's autolinking links ordinary React Native modules as well as Expo modules, and any library with native code runs in an Expo **development build** (not in Expo Go). So a bare Turbo Module serves both kinds of app. The package also ships an Expo **config plugin** (`app.plugin.js`) that adds the location usage strings and background mode, because Expo regenerates the native projects.

## Order of work

1. ✅ **The bridge core** in the SDK, with tests. It ships in the next SDK release (0.3.0).
2. ✅ **The packages:** `flutter/trackme_flutter` and `react-native/trackme-react-native`. Each has typed models and API, the platform glues, and dev wiring to the local SDK (`trackme.useLocalSdk=true`, `scripts/port-ios-sdk.sh local`). The TypeScript type-checks, and the React Native spec was run through React Native's codegen, whose generated Java and Objective-C signatures match the native code. The Flutter side and all native builds are checked when the example apps build.
3. **Sample apps** in each package's `example/`: the Live map showcase (start / join / invite, live members with "live / paused / 2 min ago", location pill and dialog, locate button, custom duration, countdown) and one developer screen with a control for every SDK call, ported from the native samples. Flutter uses `maplibre_gl` 0.27 (0.28 needs Flutter 3.44) and `share_plus`; React Native uses `@maplibre/maplibre-react-native` 11 and the built-in `Share`. Secrets: `secrets.json` / `src/secrets.ts`; the developer screen shows in debug builds (`TRACKME_DEV_TOOLS=false` / `DEV_TOOLS` hides it). Not ported yet: the Live Activity widget extension.
4. **Release:** SDK 0.3.0, then the Flutter plugin 0.3.0 (git dependency: pub.dev only takes open-source licences) and the React Native module 0.3.0 (npm), versioned with the SDK ([05-releasing.md](05-releasing.md) section 8). Then customer-style examples that install the published packages.

## Risks to check in the first example builds

- **Kotlin version:** the SDK is compiled with Kotlin 2.4. A host app whose Kotlin Gradle plugin is too old to read that metadata fails to compile the plugin's Kotlin code ("incompatible version of Kotlin"). Either the app uses a recent enough Kotlin, or the SDK's Android target sets a lower `languageVersion` / `apiVersion`. That's decided when the Flutter and React Native examples first build.
- **compileSdk and AGP:** found in the first Flutter build. SDK 0.2.0 depended on `androidx.core` 1.19.1, which needs AGP 9.1+ and compileSdk 37, and Flutter apps (and many native ones) are on AGP 8.x. From 0.3.0 the SDK pins `androidx.core` 1.16.0. The second build then failed on compileSdk: an AAR records the lowest compileSdk an app may use (`minCompileSdk`), and both the SDK (built against 37) and OkHttp 5.5.0 (pulled in by Ktor 3.6.0) asked for 37. The SDK now builds against 36 and holds OkHttp at 5.4.0 (strict), which asks for 36. So apps need **compileSdk 36+**; AGP 8.11+ is recommended, and older 8.x versions only warn. `scripts/check-aar-metadata.sh` prints each OkHttp release's floor.
- **The XCFramework through CocoaPods:** the podspecs vendor `ios/Frameworks/TrackMeSDK.xcframework` (static, arm64 only), so `pod install` must find it there first.

Publishing: npm allows a proprietary licence (`"license": "UNLICENSED"`); pub.dev requires an open-source one, so the Flutter plugin installs from git.
