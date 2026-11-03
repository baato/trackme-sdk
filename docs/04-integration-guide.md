# TrackMe SDK: Integration guide

For developers adding live location sharing to an **Android** or **iOS** app. Each step shows Kotlin and Swift side by side. Working examples of everything here are in `examples/android` and `examples/ios`, which use the published packages exactly as below (`sample-android/` and `sample-ios/` are the same apps built from the SDK source); [06-sample-apps.md](06-sample-apps.md) maps each SDK call to its screen.

---

## 0. What you get

| | Android | iOS |
|---|---|---|
| Package | `com.github.baato.trackme-sdk:trackme-sdk-android` (AAR via JitPack) | SwiftPM: `TrackMeSDK` (XCFramework) + `TrackMeLiveActivity` |
| Minimum OS | Android 7.0 (API 24) | iOS 15 (Live Activity 16.1+, interactive 17+) |
| Build requirement | `compileSdk` ≥ 37 | Xcode 16+ |
| Entry point | `TrackMe` | `TrackMe.shared` |

Features: trips (create / join by code / preview / list / detail / rename / end / leave / remove member / delete), public view links, notifications inbox, live location sharing with background support, **live updates** (members' positions, joins, trip end) over the TrackMe socket, and lifecycle and error handling that never crashes your app.

---

## 1. Install

### Android

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // JitPack builds artifacts from any GitHub repo: only let it serve the TrackMe SDK.
        maven("https://jitpack.io") { content { includeGroup("com.github.baato.trackme-sdk") } }
    }
}

// app/build.gradle.kts
android { compileSdk = 37 }
dependencies { implementation("com.github.baato.trackme-sdk:trackme-sdk-android:0.2.0") }
```

The version is the GitHub release tag. Use the `-android` artifact: it's the plain AAR. (`com.github.baato.trackme-sdk:trackme-sdk` is the Kotlin Multiplatform root, for KMP projects.)

**Unreleased local builds:** `./gradlew :trackme-sdk:publishToMavenLocal` in the SDK repo, add `mavenLocal()` to your repositories, and use `io.baato.trackme:trackme-sdk-android:<version>`.

The SDK's manifest merges in the location, foreground-service and notification permissions, and the `TrackMeLocationService` declaration. You add nothing unless you want "Allow all the time" (see §4).

### iOS

**SwiftPM (recommended).** *File → Add Package Dependencies…* → `https://github.com/baato/trackme-sdk` → the release version (`0.2.0`, *Up to Next Major*):
- your **app** target: `TrackMeSDK` + `TrackMeLiveActivity`
- your **Widget Extension** target (only for the Live Activity, §8): `TrackMeLiveActivity`

**Manual / unreleased builds:**
1. `./gradlew :trackme-sdk:assembleTrackMeSDKXCFramework`
2. Drag `trackme-sdk/build/XCFrameworks/release/TrackMeSDK.xcframework` into your target's **Frameworks, Libraries, and Embedded Content** and choose **Do Not Embed** (it's a static framework).
3. Add `TrackMeLiveActivity/` as a local package (*Add Package Dependencies… → Add Local…*).

Then `import TrackMeSDK` (and `import TrackMeLiveActivity`). How releases are produced: [05-releasing.md](05-releasing.md).

---

## 2. Get an access token (your backend)

The SDK **never logs in and never holds the API secret.** Your backend calls TrackMe and gives the app a token:

```
App ──(your auth)──► Your backend ──POST /api/v1/auth/login {email, full_name, api_secret}──► TrackMe
App ◄──────────── access_token ────────────────────────────────────────────────────────────┘
App ──► TrackMe.initialize(token)
```

If the token expires mid-session, the SDK asks you for a new one through a **token provider** (§3). The samples call login directly with a secret in a git-ignored file. That's for demos only; don't ship it.

---

## 3. Initialize and dispose

Initialize after your user signs in, and dispose when they sign out. Both calls are safe to repeat. Calling anything before `initialize` or after `dispose` returns `NotInitialized`; it never crashes.

**Kotlin**
```kotlin
val config = TrackMeConfig(
    token = accessToken,
    tokenProvider = tokenProvider { myBackend.fetchTrackMeToken() },   // suspend; return null to give up
    sharingUi = TrackMeSharingUi(title = "Sharing your location", text = "Your trip can see you", accentColor = 0xFF1565C0),
)
TrackMe.initialize(applicationContext, config)    // returns TrackMeResult<Unit>

// on sign-out
TrackMe.dispose()
```

**Swift**
```swift
final class TokenProvider: NSObject, TrackMeTokenProvider {
    func refreshToken(onResult: @escaping (String?) -> Void) {
        Task { onResult(try? await MyBackend.fetchTrackMeToken()) }   // call onResult exactly once
    }
}

let config = TrackMeConfig(token: accessToken,
                           tokenProvider: TokenProvider(),
                           sharingUi: TrackMeSharingUi(title: "Sharing your location",
                                                       text: "Your trip can see you", accentColor: 0xFF1565C0))
TrackMe.shared.initialize(context: PlatformContext(), config: config)

// on sign-out
TrackMe.shared.dispose()
```

If you refresh tokens yourself, call `TrackMe.updateToken(newToken)`.

---

## 4. Location permission (your UI, the SDK reports)

The SDK **checks** permission but never shows a prompt. Ask in your own UI, then call `TrackMe.location.permissionStatus()`:

| Field | Meaning |
|---|---|
| `level` | `NotDetermined`, `Denied`, `WhileInUse` or `Always` |
| `precise` | `false` = approximate only (sharing still works) |
| `servicesEnabled` | device-wide location switch |
| `backgroundCapable` | iOS: `UIBackgroundModes` contains `location` (always `true` on Android) |

### Android

```kotlin
val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    status = TrackMe.location.permissionStatus().getOrNull()
}
launcher.launch(buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)     // ask both, so Android offers "precise / approximate"
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)  // shows the sharing notification
}.toTypedArray())
```

"Allow all the time" is optional. You only need it to start or resume sharing while the app is *not* visible. To use it:
1. Add `<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>` to **your** manifest. The SDK deliberately doesn't declare it, so apps that don't need it avoid the extra Play review.
2. Request it **separately, after** foreground permission. On Android 11+ this opens Settings.

**Google Play:** if you target Android 14+, declare the *location* foreground-service use under Play Console → App content → Foreground service permissions.

### iOS

Add to `Info.plist`:
```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Share your live location with members of your trip.</string>
<!-- optional, for "Always" -->
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Keep sharing your location while the app is in the background.</string>
<!-- for background sharing: Signing & Capabilities → Background Modes → Location updates -->
<key>UIBackgroundModes</key>
<array><string>location</string></array>
```

```swift
let manager = CLLocationManager()      // keep a strong reference
manager.requestWhenInUseAuthorization()
// …later, optional:
manager.requestAlwaysAuthorization()
```

If `UIBackgroundModes` doesn't include `location`, the SDK doesn't turn on background updates (doing so would crash the app). Sharing then works only while the app is open.

---

## 5. Trips

Every call is `suspend` (Kotlin) or `async` (Swift) and returns `TrackMeResult`.

| Action | Kotlin | Swift | Who |
|---|---|---|---|
| Create | `TrackMe.trips.create(name, durationMin = 60)` → `CreatedTrip(joinCode, viewLink, …)` | `try await TrackMe.shared.trips.create(name:durationMin:)` | anyone |
| Preview by code | `trips.preview(code)` → name, owner, members | `trips.preview(code:)` | anyone |
| Join | `trips.join(code)` → `Trip` | `trips.join(code:)` | anyone |
| List mine | `trips.list(limit = 20)` | `trips.list(limit:)` | — |
| Detail + members' last positions | `trips.detail(tripId)` | `trips.detail(tripId:)` | member |
| Rename | `trips.rename(tripId, name)` | `trips.rename(tripId:name:)` | owner |
| End early | `trips.end(tripId)` | `trips.end(tripId:)` | owner |
| Leave | `trips.leave(tripId)` | `trips.leave(tripId:)` | member |
| Remove member | `trips.removeMember(tripId, userId)` | `trips.removeMember(tripId:userId:)` | owner |
| Delete (ended only) | `trips.delete(tripId)` → `Conflict` if live | `trips.delete(tripId:)` | owner |
| Public link | `trips.viewLink(tripId)` / `trips.rotateViewLink(tripId)` | `trips.viewLink(tripId:)` | member / owner |

There are two ways to share a trip:
- **Join code**, e.g. `C7FKK7`: the other person joins as a member and can share their own location.
- **View link**, e.g. `https://tracker.baato.io/trackme/s/…`: anyone with the link can watch in a browser, read-only. Rotate it if it leaks.

The trip duration is sent as given: its range is the server's (5–1440 minutes today), so it can change without an SDK update. Out of range, you get `Validation` with the server's message, such as "duration_min: Input should be greater than or equal to 5". Name (up to 100 characters) and code (1–16 characters) are still checked before anything is sent.

**Kotlin**
```kotlin
when (val r = TrackMe.trips.create("School run", durationMin = 45)) {
    is TrackMeResult.Success -> showCode(r.data.joinCode, r.data.viewLink)
    is TrackMeResult.Failure -> showError(r.error.message)
}
```

**Swift**
```swift
let r = try await TrackMe.shared.trips.create(name: "School run", durationMin: 45)
if let ok = (r as AnyObject) as? TrackMeResultSuccess<CreatedTrip>, let trip = ok.data {
    showCode(trip.joinCode, trip.viewLink)
} else if let failed = (r as AnyObject) as? TrackMeResultFailure {
    showError(failed.error.message)
}
```
The `unwrap()` helper in `sample-ios/TrackMeSample/SampleModel.swift` turns this into `let trip = try unwrap(r)`.

---

## 6. Live location sharing

Each upload goes to **every live trip the user is in**, so there's no trip id: create or join a trip, then start sharing.

**Kotlin**
```kotlin
when (val r = TrackMe.sharing.start()) {        // or start(SharingOptions(intervalMillis = 3_000))
    is TrackMeResult.Success -> Unit
    is TrackMeResult.Failure -> when (val e = r.error) {
        is TrackMeError.LocationPermissionDenied -> if (e.canAskAgain) askPermission() else openAppSettings()
        TrackMeError.LocationServicesDisabled -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        is TrackMeError.BackgroundNotAllowed -> Unit     // start while the app is visible
        else -> showError(e.message)
    }
}

TrackMe.sharing.state.collect { state -> render(state) }   // Compose: collectAsStateWithLifecycle()
TrackMe.sharing.pause(); TrackMe.sharing.resume(); TrackMe.sharing.stop()
```

**Swift**
```swift
if let failed = (TrackMe.shared.sharing.start() as AnyObject) as? TrackMeResultFailure {
    if let denied = failed.error as? TrackMeError.LocationPermissionDenied { denied.canAskAgain ? askPermission() : openSettings() }
}

let observer = TrackMeIos.shared.observeSharing { state in    // main thread; keep `observer`, call cancel() later
    if let s = state as? SharingState.Sharing { print("sharing to", s.tripIds) }
    if let s = state as? SharingState.Stopped { print("stopped:", s.reason.name) }
}

TrackMe.shared.sharing.pause(); TrackMe.shared.sharing.resume(); TrackMe.shared.sharing.stop()
```

### States and stop reasons

`Idle → Starting → Sharing(tripIds, lastLocation, lastSentAtMillis, retrying) ⇄ Paused → Stopped(reason)`

| `StopReason` | When | Suggested UI |
|---|---|---|
| `User` | you called `stop()` / the user tapped Stop | — |
| `NoActiveTrips` | trip ended or expired, or the user left or was removed | "Trip ended" |
| `PermissionRevoked` | permission turned off while sharing | ask again / Settings |
| `ServicesDisabled` | device location turned off | "Turn on location" |
| `Unauthorized` | token rejected and the provider gave no new one | sign in again |
| `BackgroundNotAllowed` | the OS wouldn't keep the background service | start while visible |
| `Disposed` | `dispose()` | — |

- **Offline or server down:** sharing keeps going with `retrying = true`. It always sends the newest location, never a backlog, and backs off up to 60 seconds between attempts.
- **Paused:** nothing is sent, but GPS stays on so Resume (including from the notification) is instant.
- **Battery** level and charging state are sent with each fix automatically.

### Options

`SharingOptions(intervalMillis = 5_000, minDistanceMeters = 5.0, heartbeatMillis = 30_000, highAccuracy = true)`
- `intervalMillis`: the minimum time between uploads.
- `minDistanceMeters`: skip a fix unless you've moved at least this far…
- `heartbeatMillis`: …but re-send the last fix at least this often, so members see you're still online.

---

## 7. Live updates (watch a trip)

`TrackMe.sharing` **sends** your location. `TrackMe.realtime` **receives** everyone else's, live, over the TrackMe socket (Socket.IO). The SDK owns the connection: it authenticates with your access token, reconnects with backoff (1 s → 30 s), re-subscribes after a reconnect, answers the server's heartbeats, and closes on `dispose()`.

**Kotlin**
```kotlin
// When a trip screen opens (you must be a joined member of a live trip):
when (val r = TrackMe.realtime.watchTrip(trip.id)) {     // suspends until the server confirms
    is TrackMeResult.Success -> Unit
    is TrackMeResult.Failure -> when (r.error) {
        is TrackMeError.Conflict -> showTripEnded()        // "Trip has ended"
        is TrackMeError.Unauthorized -> signInAgain()
        is TrackMeError.Network -> showOffline()           // couldn't connect; call again later
        else -> showError(r.error.message)                 // Forbidden / NotFound: not a member
    }
}

TrackMe.events.collect { e ->
    when (e) {
        is TrackMeEvent.TripPosition -> map.move(e.position.userId, e.position.latitude, e.position.longitude)
        is TrackMeEvent.MemberChanged -> reloadMembers()                 // someone joined
        is TrackMeEvent.MemberSharingChanged -> showState(e.userId, e.state)   // paused / resumed / stopped
        is TrackMeEvent.TripEnded, is TrackMeEvent.Evicted -> closeTripScreen()
        else -> Unit
    }
}
TrackMe.realtime.state.collect { render(it) }   // Idle | Connecting | Connected(tripId, watchingShare) | Reconnecting(attempt)

// When the trip screen closes:
TrackMe.realtime.stopWatchingTrip()
```

**Swift**
```swift
Task {
    let r = try await TrackMe.shared.realtime.watchTrip(tripId: trip.id)
    if let f = (r as AnyObject) as? TrackMeResultFailure { print(f.error.message) }
}
let events = TrackMeIos.shared.observeEvents { e in               // main thread
    if let p = e as? TrackMeEvent.TripPosition {
        move(userId: p.position.userId?.int64Value, lat: p.position.latitude, lon: p.position.longitude)
    } else if e is TrackMeEvent.MemberChanged { reloadMembers() }
    else if e is TrackMeEvent.TripEnded || e is TrackMeEvent.Evicted { closeTripScreen() }
}
let live = TrackMeIos.shared.observeRealtime { state in
    connected = state is RealtimeState.Connected
}
_ = TrackMe.shared.realtime.stopWatchingTrip()
```

### What arrives

| Event | When | Fields |
|---|---|---|
| `TripPosition(position)` | a member of the watched trip uploaded a fix | `MemberPosition`: `tripId`, `userId`, `fullName`, `latitude`, `longitude`, `speedMetersPerSecond`, `fixAgeSeconds`. Decoded exactly per the contract in [trackme-api.md → Realtime](trackme-api.md#realtime-socketio); a payload that doesn't match is dropped and shows up in `TrackMe.debugLogger` |
| `MemberChanged(change)` | someone joined the trip (`change.joined`) | `tripId`, `userId`, `displayName`, `change` |
| `MemberSharingChanged(tripId, userId, displayName, state)` | a member started, paused, resumed or stopped sharing: show it at once | `state`: `MemberSharingState.Sharing / Paused / Stopped`. **Needs the backend's proposed `sharing_state` support** ([trackme-api.md → Proposed: sharing state](trackme-api.md#proposed-sharing-state-pause-shows-at-once-on-other-phones)); until then it never arrives. `TripMember.sharingState` carries the same in `trips.detail` |
| `TripEnded(tripId, reason)` | owner ended it, or time ran out | the SDK stops watching it |
| `Evicted(tripId, reason)` | you were removed, left on another device, or the trip ended | the SDK stops watching it and does **not** reconnect to it |
| `SharePosition(position)` / `ShareEnded` | only with `watchShare` (below) | same `MemberPosition` |

### Rules worth knowing

- **One trip at a time.** The server keeps each connection in one trip room; `watchTrip(other)` switches to the other trip.
- **Watching ≠ sharing.** You can watch without sharing and share without watching. Your own fixes come back to you as `TripPosition` too; filter by your user id if needed.
- **Show the snapshot first.** `trips.detail(id)` gives every member's last position immediately; live events then update it. Positions only flow while members are sharing.
- **View links:** `TrackMe.realtime.watchShare(viewToken)` follows a public view link by its **token**, and works for anyone. The API returns the link as a URL (`view_link`); if your app only has the URL, take the token out yourself, since its format is the backend's, not the SDK's.
- **Expired token:** if the server rejects the token on reconnect, the SDK asks your token provider once; if that fails you get `AuthenticationFailed` and watching stops.
- `stop()` closes the socket; `dispose()` does too. Every call is safe before `initialize`.
- **Sharing is all-or-nothing today.** The server sends each upload to every live trip you're in, so joining a trip while sharing elsewhere shares with it too. Members who aren't uploading currently don't get live positions. Per-trip sharing is planned on the backend; the SDK will add `sharing.start(tripId)` once it's available.
- **Debugging:** set `TrackMe.debugLogger = { Log.d("TrackMe", it) }` (Swift: `TrackMe.shared.debugLogger = { print($0) }`) in debug builds. You get the socket handshake, every join and its answer, and every live message's raw payload, including any the SDK couldn't read. It's called on a background thread. Leave it unset in release.

---

## 8. Background UI (notification / Live Activity)

Set the look once with `TrackMeConfig.sharingUi`:

| Field | Android | iOS |
|---|---|---|
| `title`, `text`, `pausedText` | notification title and text | Live Activity text |
| `accentColor` (ARGB) | notification color | tint |
| `showPauseAction`, `showStopAction`, `pauseLabel`, `resumeLabel`, `stopLabel` | notification buttons | Live Activity buttons (17+) |
| `androidSmallIcon` | status-bar icon (monochrome drawable) | — |
| `androidChannelName` | name shown in system notification settings | — |
| `iosSymbolName` | — | SF Symbol |

Both show **how long the trip has left**: a countdown and a bar that drains as it runs out, with "Live · 1 trip · ends 17:45". With several trips, it's the one that ends last, since sharing runs until then. When the set of trips changes, the SDK reads their end times with one `GET /v1/trips`. Until that answer arrives, the first seconds show "Updated … ago" instead.

- **Android:** an ongoing notification with **Pause/Resume** and **Stop** buttons. The header counts down by itself (a chronometer), and the progress bar updates with each upload. Tapping it opens your app. It works out of the box.
- **iOS:** the blue location indicator appears while sharing in the background. The `TrackMeLiveActivity` package adds the lock screen and Dynamic Island:

  | iOS | Shows | Buttons |
  |---|---|---|
  | 17+ | Live Activity | Interactive Pause/Resume, Stop |
  | 16.1–16.x | Live Activity | Tapping opens the app |
  | 15, or Live Activities turned off | Quiet notification | Notification actions |

  Setup (full walkthrough in `TrackMeLiveActivity/README.md`; working example in `sample-ios/`):
  1. Add a **Widget Extension** target (deployment target 16.1 or later) that links `TrackMeLiveActivity`:
     ```swift
     @main struct MyWidgets: WidgetBundle { var body: some Widget { TrackMeLiveActivityWidget() } }
     ```
  2. App `Info.plist`: `NSSupportsLiveActivities = YES`.
  3. Copy `sample-ios/TrackMeSample/LiveActivityPresenter.swift` into your app. It's about 30 lines that turn the SDK's `SharingSnapshot` into the Live Activity's content (including `tripStartedAtMillis` / `tripEndsAtMillis` for the countdown; 0 = not known yet). Call `LiveActivityPresenter.install()` once at launch. It registers the presenter, routes the Pause/Resume/Stop buttons back to `TrackMe.shared.sharing`, and sets the notification delegate for the iOS 15 fallback.
  4. For the iOS 15 fallback only, call `await TrackMeActivityController.shared.requestNotificationPermission()` once, e.g. next to your location permission request.

  A Live Activity can only start while the app is visible, which is when sharing starts anyway. If it can't start, the notification fallback is used. Updates are throttled to state changes plus one every 15 seconds. The countdown, its bar and the "updated … ago" text are animated by iOS itself (`Text(timerInterval:)`, `ProgressView(timerInterval:)`), so they need no updates.

---

## 9. Errors

| `TrackMeError` | Typical cause | What to do |
|---|---|---|
| `NotInitialized` | called before `initialize` / after `dispose` | initialize first |
| `Unauthorized` | token invalid and the provider gave none | sign in again |
| `Forbidden` / `NotFound` / `Conflict` | not owner / bad id or code / delete a live trip | show `message` |
| `Validation` | bad input (caught locally or by the server) | show `message` |
| `Network` | offline, timeout | retry |
| `Server(statusCode)` | 5xx | retry later |
| `LocationPermissionDenied(canAskAgain)` | no permission | prompt, or open Settings |
| `LocationServicesDisabled` | device location off | open location settings |
| `BackgroundNotAllowed` | starting from background without "Always" | start while visible |
| `Unknown` | unexpected | log `message` |

`message` is always safe to show or log.

---

## 10. Events

`TrackMe.events` (Kotlin `SharedFlow`; Swift `TrackMeIos.shared.observeEvents { }`):

- `SharingStopped(reason)`
- `TokenRefreshed`
- `AuthenticationFailed`
- Live updates (§7): `TripPosition`, `MemberChanged`, `TripEnded`, `Evicted`, `SharePosition`, `ShareEnded`

In Kotlin, a `when (event)` over `TrackMeEvent` should end with `else -> Unit` so future event types don't break your build.

---

## 11. Go-live checklist

- [ ] The token comes from **your backend**; no API secret in the app
- [ ] `initialize` after sign-in, `dispose` on sign-out
- [ ] Android: `compileSdk` 37, runtime location request (+ `POST_NOTIFICATIONS`), Play Console foreground-service declaration
- [ ] Android, optional: `ACCESS_BACKGROUND_LOCATION` in your manifest, requested separately
- [ ] iOS: location usage strings, `UIBackgroundModes = location` for background sharing
- [ ] iOS: Widget Extension with `TrackMeLiveActivityWidget()`, `NSSupportsLiveActivities = YES`, `LiveActivityPresenter.install()` at launch
- [ ] Handle `LocationPermissionDenied` / `LocationServicesDisabled` / `BackgroundNotAllowed` from `start()`
- [ ] Show `Stopped(reason)` to the user (especially `NoActiveTrips`, `PermissionRevoked`, `Unauthorized`)
- [ ] `watchTrip` when a trip screen opens, `stopWatchingTrip` when it closes; handle `TripEnded` / `Evicted`
- [ ] Tested on a real device with the screen off for more than 10 minutes
