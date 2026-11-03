# Sample apps: what's where, and how to test

Both samples (`sample-android/`, Compose; `sample-ios/`, SwiftUI) have two faces of the same SDK:

- **Live map:** the showcase. A ready-to-use "share my trip live" feature on a Baato map (MapLibre, `breeze` style). This is what an integrating app would ship.
- **Developer:** five sections (Trips, Share, Viewer, Inbox, SDK) plus a trip screen. **Every public SDK call has a button**, and the SDK section keeps an event log.

The same two apps also live in `examples/android` and `examples/ios`, built against the **published** SDK (JitPack / SwiftPM) instead of the source; `scripts/sync-examples.sh` copies the code across. Everything below applies to both; for the examples, the secrets files are `examples/android/local.properties` and `examples/ios/Secrets.xcconfig`.

All SDK calls go through one file per app: `SampleViewModel.kt` on Android, `SampleModel.swift` on iOS. Read those first; the screens only call into them.

## Setup: secrets and the dev-tools flag

Nothing secret is in the code. Each value lives in a git-ignored file:

| | Android: `local.properties` (repo root) | iOS: `sample-ios/Secrets.xcconfig` |
|---|---|---|
| TrackMe API secret (demo login only) | `trackme.apiSecret=…` | `TRACKME_API_SECRET = …` |
| Baato map key | `baato.accessToken=…` | `BAATO_ACCESS_TOKEN = …` |
| Developer screens | `trackme.devTools=true\|false` (empty: on for debug, off for release) | `TRACKME_DEV_TOOLS = YES\|NO` (empty: on for Debug, off for Release) |

The map loads `https://api.baato.io/api/v1/styles/breeze?key=<Baato key>`. Without a key the map area says so, and the rest still works.

- **Dev tools on:** a bottom bar switches between **Live map** and **Developer**.
- **Dev tools off:** the app is the Live map only, which is what a host app's users would see.

After changing `local.properties`, sync Gradle. After changing `Secrets.xcconfig` or adding files, re-run `xcodegen`.

## Live map (showcase)

The map starts centered on Kathmandu and moves to you once location is allowed; you're the blue dot. On Android the sample draws that dot itself, from the SDK's last fix while sharing or the phone's location otherwise. Every other member is a colored dot with their first name, and their color stays the same on the dot and in the member chips. The ◎ button flies back to you. The bottom panel changes with what you're doing:

| State | Panel | SDK calls behind it |
|---|---|---|
| No live trip | **Start a trip** (name; 30 min, 1 h, 2 h, 4 h or **Custom**: any whole number of minutes, digits only. The limits are the server's, 5–1440 today, and an out-of-range value shows the server's message), then "Start & share my location"; or **Join** | `trips.create`, then `sharing.start` and `realtime.watchTrip` |
| Join → paste an invite | Accepts the full invite message, a view link or a bare code. The trip's people appear on the map right away, with **Join**. Joining shows everyone; your own location is shared only when you tap **Share my location too** afterwards | `realtime.watchShare(token)` (the app takes the token out of the link), `trips.preview(code)`; then `trips.join` and `watchTrip` |
| In a live trip | Name, countdown and a time-left bar, then the **join code** in large type (Copy, Copy invite, Share), member chips (live / "2 min ago"), a share / pause / resume button, and **Leave trip**, or **End trip** for the owner | `trips.detail`, `TripPosition` / `MemberChanged` events, `sharing.pause` / `resume`, `trips.viewLink`, `trips.leave` / `end` |
| No location permission | "Allow location to share" (or "…in Settings" when denied for good); sharing starts by itself once granted | `location.permissionStatus`, `LocationPermissionDenied(canAskAgain)` |
| Location access, any time | The **location pill** under the top-left status: *No location access* (red), *While using* (orange), *Always* (green), plus *approx.* when precise location is off. Tapping it explains the three levels, marks yours, and offers the one useful action: Allow, Allow all the time, Turn on location, or Open Settings. It also warns when notifications are off (Android), since then the sharing notification can't show | `location.permissionStatus()`; the app asks |
| Trip ended (owner ended it, or time ran out) | "Trip ended" and **Done** | `TripEnded` event |
| Removed from the trip | Message, then back to "No live trip" | `Evicted` event |

With several live trips, chips in the panel switch between them.

**The locate button** (top right) shows where location stands. A blue gradient arrow means your position is known; tap to fly to you. A gray, pulsing arrow means access is allowed but there's no fix yet. A struck-through red arrow means no access, or location is off; tap to ask, or to see why. The icons are Material `near_me` / `near_me_disabled` on Android and SF Symbols `location.fill` / `location.slash.fill` on iOS.

**Asking for location, without nagging.** The map asks once by itself, the first time it opens without access (Android: location plus notifications on 13+; iOS: While Using). After that it asks only when you tap the locate button, "Allow location", or the pill. When the system won't show its dialog any more ("don't ask again" on Android, any decision on iOS), those buttons open Settings instead. On iOS the map's blue dot is switched on only once access is granted, because MapLibre would otherwise ask by itself at launch. "Always" is optional: sharing you start in the app keeps running in the background without it, and it's only needed to *start* sharing from the background.

**What "live" means on a member chip.** The server doesn't announce a pause: a paused or stopped phone simply stops uploading. So a member is *live* while their positions keep arriving, the newest no more than 45 s old (sharing re-sends at least every 30 s heartbeat, even when standing still). After that the chip ticks on as "50 s ago", "2 min ago" and so on. The dot and chip use whichever is newer, the socket position or the 5 s snapshot poll. An app that raises `heartbeatMillis` above ~40 s should widen that window to match. **Once the backend ships the proposed sharing state** ([trackme-api.md](trackme-api.md#proposed-sharing-state-pause-shows-at-once-on-other-phones)), a pause, resume or stop shows at once: the chip says **paused** or **not sharing** until that member's next position. The sample already handles it (`TrackMeEvent.MemberSharingChanged`, `TripMember.sharingState`).

**Joining never shares by itself.** Your location goes out only after **Share my location too** (or Start & share for a trip you create). One caveat comes from the API: the server sends each upload to *every* live trip you're in. So if you're already sharing when you open an invite, the panel warns that joining adds this trip. Per-trip sharing, and live positions for members who aren't sharing, are planned on the backend. Until then, the *app* (not the SDK) reloads the trip snapshot every 5 s whenever no live position has arrived for 10 s. That's an app-side workaround, which an integrating app may copy or drop.

**Debugging live updates.** Dev-tools builds set `TrackMe.debugLogger`, so the SDK tab's log (and logcat tag `TrackMe` / the Xcode console) shows the socket handshake, every join and its answer, and every live message with its raw payload. If positions don't move, that log shows whether `trip_position` arrives at all, and in what shape.

**Finding people.** Tap a member's chip in the panel, or their dot on the map, and the map flies to them; your own chip flies to you. **Owner:** long-press a member's chip to remove them from the trip (`trips.removeMember`), after a confirmation. They get `Evicted` and their map closes the trip. The sheet drops to its peek so they're visible. The ◎ button, member focus and "fit everyone" all center in the visible part of the map, between the top buttons and the sheet, so nothing lands behind the panel on small screens.

**The panel is a bottom sheet.** Drag it down to a peek (title and one status line) to see more of the map, and up for everything; on iOS, tapping the handle toggles it too. It opens fully whenever the panel changes (start, invite, trip, ended). The Invite text is the same shape the Join box reads back: `Follow me on TrackMe: <view link>` followed by `Join code: <code>`.

## Coverage

| SDK call | Where in the sample |
|---|---|
| `TrackMe.initialize(context, config)` | Login screen ("Log in & initialize SDK") |
| `TrackMeConfig.tokenProvider` | Set at login; seen working via SDK tab → "Simulate expired token" |
| `TrackMeSharingUi` (title, text, accent, pause/stop buttons) | Login screen, "Sharing notification" / "Live Activity" section |
| `TrackMe.dispose()` | "Log out" (top bar) or SDK tab → "dispose() and log out" |
| `initialize` again (replace session) | SDK tab → "initialize() again" |
| `TrackMe.updateToken(token)` | SDK tab → "Fetch new token → updateToken()" |
| `TrackMe.checkHealth()` | SDK tab → "Check health" |
| `TrackMe.state`, `isInitialized` | SDK tab → State |
| `TrackMe.events` | handled in one place (`onEvent`), every event in the SDK tab's log |
| `trips.create(name, durationMin)` | Trips tab → Create (5 min / 15 min / 1 h / 4 h / Custom minutes) |
| `trips.preview(code)` / `trips.join(code)` | Trips tab → Join with a code |
| `trips.list(limit)` | Trips tab → My trips (Refresh) |
| `trips.detail(tripId)` | Tap a trip → trip screen (Refresh in the top bar) |
| `trips.rename` | Trip screen → Manage (owner) → Rename |
| `trips.end` | Trip screen → Manage (owner) → **End trip** |
| `trips.leave` | Trip screen → Manage → Leave trip (members) |
| `trips.removeMember` | Trip screen → Members → **Remove** next to a member (owner, live trip) |
| `trips.delete` | Trip screen → Manage (owner) → Delete trip (enabled once the trip has ended) |
| `trips.viewLink` / `rotateViewLink` | Trip screen → Public view link → Get link / Rotate (owner) |
| `trips.history(from, to)` | Trip screen → History → Last 15 min / Whole trip |
| `realtime.watchTrip` / `stopWatchingTrip` | Trip screen → Live updates switch (on by default for a live trip; off when you close the screen) |
| `realtime.watchShare` / `stopWatchingShare` | Viewer tab, or trip screen → Public view link → "Watch in app" |
| `realtime.stop()` / `realtime.state` | SDK tab |
| `location.permissionStatus()` | Share tab → Location permission (the app asks; the SDK reports) |
| `sharing.start(options)` / `pause` / `resume` / `stop` / `state` | Share tab (start/stop also on the trip screen) |
| `SharingOptions` | Share tab → SharingOptions (interval, min distance, heartbeat, accuracy) |
| `notifications.list(unreadOnly, cursor, limit)` | Inbox tab (Unread only, Refresh, Load more) |
| `notifications.markRead` / `markAllRead` | Inbox tab → Mark read / Mark all read |
| `TrackMeIos.observeSharing/Events/Realtime/State` (iOS) | `SampleModel.init` |

## How a trip ends

A trip ends in exactly two ways:

1. **The owner ends it:** trip screen → Manage (owner) → **End trip**.
2. **Its time runs out:** the duration chosen at create time. The trip screen counts it down ("Ends in …"). Pick **5 min** to see this quickly.

Either way, the server sends `trip_ended`. Members' sharing to that trip stops (`Stopped(NoActiveTrips)` if it was their only trip), the trip shows ENDED, and the owner can **Delete** it.

A member leaving (Leave trip) or being removed (Remove) doesn't end the trip. A removed member gets `Evicted` and their trip screen closes.

## Two-device test script (developer screens)

The quick version on the Live map: device A taps Start a trip, then Invite, and sends the message. Device B taps Join, pastes it, and sees A on the map before joining. B taps Join & share, and both see each other move. A taps End trip, and both see "Trip ended".


Use two devices (any mix of Android and iOS) and **log in with a different email on each**.

| Step | Device A (owner) | Device B (member) | Expected |
|---|---|---|---|
| 1 | Share tab: Request permission. Trips tab: create a **15 min** trip | | A sees the join code |
| 2 | | Trips tab: Preview, then Join with A's code | B lands on the trip screen; A's log shows "B joined" and A's member list refreshes |
| 3 | Trip screen: Start sharing | Trip screen: Live updates on | B sees A's row change to "· live" and move (emulator: Extended controls → Location; simulator: Features → Location → City Run) |
| 4 | | Start sharing | A sees B live too |
| 5 | Public view link → Get link → **Watch in app** | | Viewer tab shows A's and B's positions, like the web page |
| 6 | Rotate the link | | The old link's viewer shows "This link has ended" |
| 7 | History → Whole trip | | Raw JSON with both members' fixes |
| 8 | Rename the trip | Refresh | New name on both |
| 9 | Remove B | | B: "You're no longer in this trip", screen closes, sharing stops |
| 10 | | Join again with the code | Back in |
| 11 | **End trip** | | Both show ENDED; sharing stops on both; Inbox has a "trip ended" item |
| 12 | Delete trip | | Gone from both lists |
| 13 | Make a **5 min** trip and wait | | It ends by itself (same as step 11) |
| 14 | SDK tab: Simulate expired token | | Log: "Token refreshed by the token provider", then trips load |
| 15 | Log out while sharing | | The notification / Live Activity disappears at once |
