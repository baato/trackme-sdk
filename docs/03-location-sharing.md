# Steps 4 + 5: Location permission and live sharing

## 1. What the host app calls

```kotlin
// 1. Check before showing a "Share my location" button
val status = TrackMe.location.permissionStatus().getOrNull()
// status.level: NotDetermined | Denied | WhileInUse | Always, status.precise, status.servicesEnabled

// 2. The app asks for permission with its own UI (the SDK never prompts), then:
when (val r = TrackMe.sharing.start()) {          // or start(SharingOptions(intervalMillis = 3_000, …))
    is TrackMeResult.Success -> Unit
    is TrackMeResult.Failure -> when (val e = r.error) {
        is TrackMeError.LocationPermissionDenied -> if (e.canAskAgain) requestPermission() else openAppSettings()
        TrackMeError.LocationServicesDisabled -> askToTurnOnLocation()
        is TrackMeError.BackgroundNotAllowed -> Unit   // start while the app is visible
        else -> show(e.message)
    }
}

// 3. Observe
TrackMe.sharing.state.collect { s -> /* Idle | Starting | Sharing(tripIds, lastLocation, lastSentAt, retrying) | Paused | Stopped(reason) */ }

// 4. Control (also triggered by the notification / Live Activity buttons)
TrackMe.sharing.pause(); TrackMe.sharing.resume(); TrackMe.sharing.stop()
```

**There's no `tripId` in `start()`.** `POST /v1/ingest/live` carries no trip id; the server forwards each fix to every live trip the user belongs to. So the flow is: create or join a trip, then `start()`. Sharing **stops by itself** with `StopReason.NoActiveTrips` once the server returns empty `trip_ids` (trip ended or expired, or the user left or was removed).

## 2. Behaviour in every situation

| Situation | Result |
|---|---|
| Not initialized / after dispose | `Failure(NotInitialized)`. `stop()` is always `Success` |
| Permission never asked (iOS) / not granted (Android) | `Failure(LocationPermissionDenied(canAskAgain = true))` |
| Permission denied (iOS) | `Failure(LocationPermissionDenied(canAskAgain = false))` → open Settings |
| Device location off | `Failure(LocationServicesDisabled)` |
| Started while app in background without "Always" | `Failure(BackgroundNotAllowed)` (Android would crash otherwise) |
| Android refuses the foreground service | `Failure(BackgroundNotAllowed)`, nothing left running |
| Approximate location only | Works; `status.precise == false` |
| App goes to background with "While in use" | Keeps sharing (Android foreground service; iOS blue location pill, needs `UIBackgroundModes`) |
| Offline / server 5xx | Keeps sharing, `retrying = true`, backoff 2×interval … 60 s, always sends the **newest** fix (no backlog) |
| A fix rejected with 422 | That fix is skipped, sharing continues |
| Token expired | `tokenProvider` is asked; if that fails → `Stopped(Unauthorized)` |
| Permission revoked / GPS switched off mid-share | `Stopped(PermissionRevoked)` / `Stopped(ServicesDisabled)` |
| Trip ended / user removed | `Stopped(NoActiveTrips)` |
| Paused | No uploads; GPS stays on so Resume works instantly, even from the lock screen |
| Stationary | Re-sends the last fix every `heartbeatMillis` (30 s) so members see you're online |
| `dispose()` | `Stopped(Disposed)`, GPS off, notification / Live Activity removed, synchronously |

Every stop also emits `TrackMeEvent.SharingStopped(reason)` on `TrackMe.events`.

## 3. KMP pattern used: one interface + one `expect` factory

```
commonMain                                   androidMain                         iosMain
──────────                                   ───────────                         ───────
interface PlatformServices  ◄─ implements ─  AndroidPlatformServices             IosPlatformServices
  permissionStatus()                           ContextCompat.checkSelfPermission   CLLocationManager.authorizationStatus
  locationUpdates(): Flow                      LocationManagerCompat (fused)       CLLocationManagerDelegate → callbackFlow
  battery()                                    BatteryManager                      UIDevice.batteryLevel
  isAppInForeground()                          ActivityManager.getMyMemoryState    UIApplication.applicationState
  start/update/stopSharingHost()               TrackMeLocationService + notif.     TrackMeSharingPresenter (Swift)

expect fun createPlatformServices()          actual fun …                        actual fun …

SharingController (all the logic, 100% shared, tested with FakePlatform)
```

Why not many small `expect fun`s? Because with an interface, **common tests can swap in a fake OS**. `SharingTest` covers permission denial, revoke, GPS off, trip end, 401, offline retry, pause/resume and dispose without a device.

`callbackFlow` is the standard bridge from callback APIs (LocationListener, CLLocationManagerDelegate) to `Flow`. Collecting it starts GPS, and cancelling the collection runs `awaitClose { stop updates }`.

## 4. Host-app checklist

### Android

The SDK manifest already merges in `ACCESS_FINE/COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, and the `TrackMeLocationService` declaration.

1. Request `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` at runtime. Also request `POST_NOTIFICATIONS` on Android 13+: without it sharing still works, but the notification and its buttons are hidden.
2. **Optional "Allow all the time":** add `<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>` to *your* manifest and request it separately, after foreground permission. You only need it to start or resume sharing while the app is in the background.
3. **Google Play:** apps targeting Android 14+ must declare the *location* foreground-service use in Play Console (App content → Foreground service permissions). Location sharing that the user starts is an accepted use.
4. Customize the notification:
   ```kotlin
   TrackMeConfig(token = t, tokenProvider = p, sharingUi = TrackMeSharingUi(
       title = "Sharing with family", text = "Tap to open", accentColor = 0xFF1565C0,
       androidSmallIcon = R.drawable.ic_stat_location, pauseLabel = "Pause", stopLabel = "Stop"))
   ```
5. `compileSdk` ≥ 37 (OkHttp 5.5 via Ktor).

### iOS

1. Info.plist: add `NSLocationWhenInUseUsageDescription`, plus `NSLocationAlwaysAndWhenInUseUsageDescription` if you want "Always".
2. Background sharing: Signing & Capabilities → **Background Modes → Location updates**. This adds `UIBackgroundModes = location`. Without it, sharing works only while the app is open. The SDK checks for it, so a missing entry can't crash the app.
3. Ask for permission with `CLLocationManager().requestWhenInUseAuthorization()`, then call `TrackMe.shared.sharing.start()`.
4. Lock-screen UI: `TrackMeIos.shared.setSharingPresenter(presenter:)`. The ready-made presenter (Live Activity + iOS 15 notification) is the `TrackMeLiveActivity` Swift package, plus `sample-ios/TrackMeSample/LiveActivityPresenter.swift`.

## 5. Open questions for the backend

- ~~**`timestamp` unit**~~ ✅ Resolved: epoch **seconds** (integer), confirmed with the backend. `fix_age_s` is in seconds too.
- **`speed` limit 0–400**: assumed to be m/s (what both platforms report).
