# Step 2: SDK design (public API contract)

How a host app sees the TrackMe SDK. Everything here is designed to **never crash the host app**: calls made in the wrong state, a denied permission, an expired token or a dropped socket all come back as values the app can handle.

## 1. Principles

| Rule | What it means |
|---|---|
| **The app owns the token** | The SDK never logs in and never contains the API secret. The app gets an `access_token` (ideally its backend calls `POST /api/v1/auth/login`) and hands it to `initialize`. |
| **Explicit lifecycle** | `initialize` → use → `dispose`. Both are idempotent, and the pair can be repeated (logout → login). |
| **No exceptions cross the API** | Every operation returns `TrackMeResult<T>`: `Success(data)` or `Failure(TrackMeError)`. Swift never sees an unexpected Kotlin exception. |
| **The app owns the permission UI** | The SDK *checks* and *reports* permission state. It never shows the system prompt itself. |
| **Background is optional** | Sharing works with "While in use" permission. If the app has background permission, it keeps working with the screen off. |

## 1a. The SDK implements the contract, nothing more

The SDK is built only on documented sources: `openapi.json`, `Using_Trackme.md`, and the socket server's event definitions from the backend team. The public share page is a separate client, not a spec, so nothing is taken from its HTML, JavaScript or URLs.

- **Backend gaps or bugs aren't worked around in the SDK.** Field-name guessing, polling an undocumented endpoint, or reshaping odd payloads would hide the problem and make the SDK depend on today's quirks. Instead, the SDK reports the mismatch (`TrackMe.debugLogger`) and the gap is raised with the backend team.
- **A host app can still work around something if it must,** for example polling `trips.detail`, or taking a view token out of a link. The samples do that in app code, marked as app-side.

## 2. API shape

```kotlin
// ---- lifecycle ----
TrackMe.initialize(context, TrackMeConfig(
    token = initialAccessToken,
    tokenProvider = { /* suspend: fetch a fresh token from your backend, or null to give up */ },
    sharingUi = TrackMeSharingUi(/* notification / Live Activity look, see §6 */),
))
TrackMe.state            // StateFlow<TrackMeState>: NotInitialized | Ready | Disposed
TrackMe.dispose()        // stops sharing, closes socket + HTTP, cancels all work

// ---- REST (all return TrackMeResult<T>) ----
TrackMe.trips.create(name, durationMin)          TrackMe.trips.list(limit)
TrackMe.trips.preview(code)                      TrackMe.trips.join(code)
TrackMe.trips.detail(tripId)                     TrackMe.trips.rename(tripId, name)
TrackMe.trips.end(tripId)                        TrackMe.trips.leave(tripId)
TrackMe.trips.removeMember(tripId, userId)       TrackMe.trips.delete(tripId)
TrackMe.trips.viewLink(tripId)                   TrackMe.trips.rotateViewLink(tripId)
TrackMe.trips.history(tripId, from, to)
TrackMe.notifications.list(unreadOnly, tripId, cursor, limit)
TrackMe.notifications.markRead(id)               TrackMe.notifications.markAllRead()

// ---- location sharing (step 4/5) ----
TrackMe.location.permissionStatus()   // Denied | NotDetermined | WhileInUse | Always (+ precise/approximate)
TrackMe.sharing.start(options)        // checks permission and GPS first → Failure(LocationPermissionDenied) etc.
                                      // no tripId: the server fans each upload out to every live trip the user is in
TrackMe.sharing.pause() / resume() / stop()
TrackMe.sharing.state                 // StateFlow: Idle | Starting | Sharing(tripIds, lastLocation, lastSentAt, retrying) | Paused | Stopped(reason)

// ---- realtime (step 6) ----
TrackMe.realtime.watchTrip(tripId)    // suspend → TrackMeResult<Unit>; one trip per connection
TrackMe.realtime.watchShare(token)    // follow a public view link by its view token
TrackMe.realtime.stopWatchingTrip() / stopWatchingShare() / stop()
TrackMe.realtime.state                // StateFlow: Idle | Connecting | Connected(tripId, watchingShare) | Reconnecting(attempt)
TrackMe.events                        // SharedFlow<TrackMeEvent>: TripPosition, MemberChanged, TripEnded, Evicted,
                                      // SharePosition, ShareEnded, SharingStopped, TokenRefreshed, AuthenticationFailed
```

On iOS the same API is reached through `TrackMe.shared`. `suspend` functions are exported as `async` functions in Swift.

## 3. Errors

```kotlin
sealed class TrackMeError {
    NotInitialized                    // called before initialize() or after dispose()
    Unauthorized                      // 401 and tokenProvider couldn't supply a working token
    Forbidden(message)                // 403, e.g. not the trip owner
    NotFound(message)                 // 404
    Conflict(message)                 // 409, e.g. delete a live trip
    Validation(message)               // 422 from the server, or caught client-side (duration 5–1440, code 1–16 chars)
    Network(message)                  // offline, DNS, timeout
    Server(statusCode, message)       // 5xx
    LocationPermissionDenied(canAskAgain)
    LocationServicesDisabled          // GPS switched off device-wide
    BackgroundNotAllowed              // requested background sharing but app lacks the capability
    Unknown(message)
}
```

## 4. Token handling

1. `initialize(token = …)` stores the token in memory only. The app decides where tokens persist.
2. Every request sends `Authorization: Bearer <token>`.
3. On **401**, the SDK calls `tokenProvider()` (one call at a time, even with parallel requests) and retries once with the new token. A connected socket keeps working (the server checks the token only when a socket connects); the next reconnect uses the new token, and a rejected reconnect asks `tokenProvider()` too.
4. If the provider returns `null`, or throws, the SDK returns `Failure(Unauthorized)`, emits `TokenRefreshFailed`, and **stops sharing**.
5. `TrackMe.updateToken(token)` is also available for apps that refresh on their own schedule.

## 5. Location permission matrix

| Situation | Android | iOS | SDK behaviour |
|---|---|---|---|
| Never asked | — | `notDetermined` | `start()` → `Failure(LocationPermissionDenied(canAskAgain = true))` |
| Denied | denied (maybe "don't ask again") | `denied` / `restricted` | `Failure(LocationPermissionDenied(canAskAgain))`; app shows rationale or a link to Settings |
| Approximate only | COARSE without FINE | reduced accuracy | Sharing works; `permissionStatus().precise = false` so the app can show a hint |
| While in use | FINE/COARSE | `authorizedWhenInUse` | Sharing works. **Continues in background** if started while the app is visible (Android foreground service, iOS background location indicator) |
| Always | + `ACCESS_BACKGROUND_LOCATION` | `authorizedAlways` | Sharing works, including starting or resuming from background |
| Revoked while sharing | callback / next fix fails | `locationManagerDidChangeAuthorization` | Stop cleanly, `sharing.state = Stopped(PermissionRevoked)`, emit `PermissionRevoked` |
| GPS off device-wide | `isLocationEnabled == false` | `locationServicesEnabled() == false` | `Failure(LocationServicesDisabled)` / stop with that reason |

The SDK **does not declare** `ACCESS_BACKGROUND_LOCATION` on Android. Declaring it triggers a Google Play policy review for every app using the SDK, so only apps that want "Always" add it to their own manifest. Likewise on iOS, the host app owns the `Info.plist` usage strings and `UIBackgroundModes`.

## 6. Background sharing UI

`TrackMeSharingUi` is one config shared by both platforms:

```kotlin
TrackMeSharingUi(
    title = "Sharing location",
    text  = "{tripName} · {memberCount} watching",   // template, updated live
    accentColor = 0xFF1565C0,                        // ARGB
    showPause = true,
    showStop = true,
    android = AndroidSharingUi(smallIcon = R.drawable.ic_trackme, channelName = "Location sharing"),
    ios     = IosSharingUi(symbolName = "location.fill"),
)
```

### Android: foreground service + notification (API 24+)

- `TrackMeLocationService`, `foregroundServiceType="location"`. The SDK manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, fine and coarse location, and `POST_NOTIFICATIONS`, so they merge into the app.
- An ongoing notification carries the app's icon, color, title and text, and **Pause/Resume** and **Stop** actions. Tapping it opens the app's launch activity, or a `contentIntent` the app supplies.
- **With "While in use" only:** the service is started while the app is visible, so Android allows location to continue after the app goes to the background.
- **Started from background** (no visible UI): this needs `ACCESS_BACKGROUND_LOCATION`. Without it, the result is `Failure(BackgroundNotAllowed)` and no crash. (Starting a foreground service from the background throws on Android 12+, so the SDK checks first.)
- **Android 13+ with notifications denied:** the service still runs and the notification appears only in the system's Task Manager. The SDK reports `notificationsVisible = false` in `sharing.state`.

### iOS: Live Activity (16.1+), notification fallback (15)

| iOS | Lock screen / Dynamic Island | Controls |
|---|---|---|
| 17+ | Live Activity | **Interactive** Pause / Stop buttons (App Intents) |
| 16.1–16.x | Live Activity | Not interactive; tapping opens the app (deep link) |
| 15 | Local notification with **Pause / Stop actions** + the blue location indicator in the status bar | Notification actions |

**A platform constraint shapes this design.** ActivityKit is a Swift-only API, and a Live Activity's UI must live in a **Widget Extension target inside the host app**. Neither can ship inside a Kotlin XCFramework. So iOS gets two pieces:

1. `TrackMeSDK.xcframework` (Kotlin). It holds all the logic, and calls a small protocol, `TrackMeSharingPresenter`, whenever sharing starts, updates, pauses or stops.
2. `TrackMeLiveActivity` (a small **Swift package** in this repo, with no dependency on the Kotlin framework). It provides `TrackMeActivityAttributes`, `TrackMeLiveActivityWidget` (a themeable lock screen and Dynamic Island), `TrackMePause/Resume/StopSharingIntent` (`LiveActivityIntent`, iOS 17), `TrackMeActivityController` (start/update/end with throttling, plus the iOS 15 notification fallback), and `TrackMeNotificationDelegate`.
3. A ~30-line `LiveActivityPresenter` in the app (copied from `sample-ios`) implements `TrackMeSharingPresenter`. It maps `SharingSnapshot` to `TrackMeActivityContent` and routes button taps back to `TrackMe.shared.sharing`. Keeping that glue in the app is what lets the Swift package build without the Kotlin framework.

Host-app setup on iOS (documented once, roughly 5 minutes):
- Add a Widget Extension, add `TrackMeLiveActivityWidget()` to its `WidgetBundle`, and set `NSSupportsLiveActivities = YES`.
- In `Info.plist`, add `NSLocationWhenInUseUsageDescription`, plus `NSLocationAlwaysAndWhenInUseUsageDescription` if the app wants "Always". Add `UIBackgroundModes` → `location` for background sharing.
- The SDK enables `allowsBackgroundLocationUpdates` **only if** the `location` background mode is present. Setting it without that mode crashes the app, which is a common mistake. Without it, sharing runs only while the app is open.
- `CLBackgroundActivitySession` (iOS 17+) is not used yet. `allowsBackgroundLocationUpdates` together with the blue indicator keeps updates running on iOS 15–26. Revisit if testing shows suspensions.

## 7. Implementation order

| Step | Delivers | Status |
|---|---|---|
| 2a | `initialize`/`dispose`/`state`, `TrackMeResult`/`TrackMeError`, token + `tokenProvider` with 401 retry, all trips and notifications endpoints | ✅ 16 tests passing on Android + iOS |
| 4 + 5 | `TrackMe.location` (permission status) and `TrackMe.sharing` (GPS → ingest loop, pause/resume/stop, stop reasons), Android foreground service + themed notification with actions, iOS CoreLocation + background flags + presenter hook | ✅ 35 tests passing on Android + iOS (19 sharing), see [03-location-sharing.md](03-location-sharing.md) |
| 5b | iOS `TrackMeLiveActivity` Swift package (Live Activity 16.1+/interactive 17+, iOS 15 notification fallback) and sample widget extension | ✅ written; verify on a real iPhone (see `TrackMeLiveActivity/README.md`) |
| 6 | Realtime: `TrackMe.realtime` + events, a small Socket.IO v4 client on Ktor WebSockets (no extra library), reconnect/backoff/rejoin, token refresh on rejected handshake. Strict contract from openapi.json and the socket server's event definitions: payloads are decoded as documented, never guessed; mismatches go to `TrackMe.debugLogger` (see [trackme-api.md](trackme-api.md#realtime-socketio)) | ✅ written with fake-server tests (`SocketIoCodecTest`, `RealtimeTest`); samples show live positions. Verify with two devices on one trip |
| 7 | Sample apps: Android Compose + iOS SwiftUI (create/join/preview/detail/end/leave, permissions, sharing controls, event log) | ✅ both build; Android sharing verified on device |
| 8 | Distribution: Maven publishing (local + any remote repo via `trackme.publish.*`), SwiftPM via `scripts/release-ios.sh` | ✅ see [05-releasing.md](05-releasing.md) |
| later | Swift ergonomics with SKIE (`Flow` → `AsyncSequence`, sealed → Swift enums); `CLBackgroundActivitySession` on iOS 17+ if field tests show suspensions | optional |
