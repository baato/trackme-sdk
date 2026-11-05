# @baato/trackme-react-native

Live location sharing and trip groups for React Native apps, on Baato's TrackMe.

The module wraps the native TrackMe SDK for Android and iOS as a Turbo Module, so it behaves exactly like it. Sharing runs natively: the background service, the notification's Pause / Stop and the iOS Live Activity keep working while JavaScript is paused or gone.

- **Trips:** create, join by code, preview, list, detail, rename, end, leave, remove a member, delete, view links, history.
- **Sharing:** start / pause / resume / stop, with background support and a countdown to the trip's end.
- **Live updates:** members' positions, joins and leaves, members' sharing state, trip end, view-link watching.
- **Also:** notifications inbox, location permission status, automatic token refresh, typed errors.

## Install

```bash
npm install @baato/trackme-react-native
cd ios && pod install
```

- **React Native:** 0.76+ with the new architecture (the default since 0.76).
- **Expo:** works in a development build, not in Expo Go. See the Expo section below.
- **Versions:** the package version is the SDK release it wraps. Android pulls that SDK version from JitPack. The iOS pod downloads that release's `TrackMeSDK.xcframework` on the first `pod install` and checks it against its SHA-256.

## App setup

### Android

| | Needed |
|---|---|
| `compileSdk` | 36 or newer (React Native 0.81+ templates already use 36+) |
| `minSdk` | 24 |
| Android Gradle Plugin | 8.11+ recommended; older 8.x builds with a warning |
| Kotlin | 2.4.20+, in `android/build.gradle`: `ext { kotlinVersion = "2.4.20" }` |

The module adds JitPack to your build's repositories, limited to the TrackMe group. The SDK's manifest brings the location, foreground-service and notification permissions and the sharing service, so you add nothing to `AndroidManifest.xml`.

### iOS (15.1+, React Native's minimum)

`ios/<App>/Info.plist`:
- `NSLocationWhenInUseUsageDescription`: why you share location;
- `NSLocationAlwaysAndWhenInUseUsageDescription`: why sharing continues in the background;
- `UIBackgroundModes` → `location`.

### Expo

```json
{ "expo": { "plugins": [["@baato/trackme-react-native", { "locationWhenInUse": "Share your live location with your trip." }]] } }
```

The config plugin writes the iOS location strings, the background mode and `NSSupportsLiveActivities`. Options are `locationWhenInUse`, `locationAlways`, and `liveActivities: false`. Then run `npx expo prebuild` / `eas build` for a development build.

### Permissions

The SDK reports location permission (`TrackMe.location.permissionStatus()`) but never asks. Ask with your usual package, for example [`react-native-permissions`](https://github.com/zoontek/react-native-permissions):
- location "while in use", then optionally "always";
- notifications, for the sharing notification on Android 13+ and on iOS.

## Use

```ts
import TrackMe, { TrackMeError } from '@baato/trackme-react-native';

// 1. Initialize with an access token from YOUR backend. It logs in to TrackMe with the API secret; the app never has it.
await TrackMe.initialize(
  { token: accessToken, sharingUi: { title: 'Sharing your trip', accentColor: 0xff1565c0 } },
  { tokenProvider: () => myBackend.freshToken() }, // asked when the token expires; resolve null to give up
);

// 2. A trip, sharing, live updates.
const trip = await TrackMe.trips.create({ name: 'Ride home', durationMinutes: 60 });
await TrackMe.sharing.start();
await TrackMe.realtime.watchTrip(trip.id);

const offEvents = TrackMe.onEvent((e) => {
  if (e.type === 'TripPosition') moveMarker(e.position);
  if (e.type === 'MemberSharingChanged') showSharingState(e.userId, e.state);
  if (e.type === 'TripEnded') showEnded();
});
const offSharing = TrackMe.sharing.onState((s) => setSharing(s));

// Others join with the code (or open the view link): TrackMe.trips.join(trip.joinCode)

// 3. On log out (and remove your listeners).
offEvents();
offSharing();
await TrackMe.dispose();
```

- **Errors:** every call rejects with a `TrackMeError`, with a `type` (`'NotFound'`, `'Validation'`, `'LocationPermissionDenied'`, …) and the server's `message`.
- **Limits:** trip duration and list sizes are set by the server. An out-of-range value comes back as a `Validation` error with the server's text, so show `e.message`.
- **Full API:** `src/index.ts` has `TrackMe.trips`, `.sharing`, `.location`, `.realtime` and `.notifications`, each state stream's `onState`, and `onEvent`. The models are in `src/models.ts`.
- **Behaviour, options and rules:** the same as the native SDK, described in [docs/04-integration-guide.md](../../docs/04-integration-guide.md).

## Background UI

`sharingUi` sets the look on both platforms:
- title, text and paused text;
- the accent colour (ARGB number);
- which buttons show, and their labels;
- the Android small icon (`androidSmallIcon`, a drawable *name*, monochrome) and the notification channel name;
- the iOS SF Symbol.

What each platform shows:
- **Android:** an ongoing notification with Pause / Resume, Stop and a countdown to the trip's end. No setup.
- **iOS, no setup:** while sharing, a notification with Pause / Resume / Stop.
- **iOS Live Activity** (lock screen + Dynamic Island, iOS 16.1+, buttons on 17+): Apple only allows this UI in the app's own Widget Extension, so it takes one step:
  1. In Xcode (`ios/<App>.xcworkspace`): *File → New → Target… → Widget Extension*, deployment target 16.1. Uncheck the Live Activity and Configuration options.
  2. *File → Add Package Dependencies…* → `https://github.com/baato/trackme-sdk`, the same version as this package. Add the product **`TrackMeLiveActivity` to the widget target only**.
  3. Replace the generated widget code with:
     ```swift
     import SwiftUI
     import TrackMeLiveActivity
     import WidgetKit

     @main struct TrackMeWidgets: WidgetBundle {
         var body: some Widget { TrackMeLiveActivityWidget() }
     }
     ```
  4. `Info.plist`: `NSSupportsLiveActivities` = `YES`. The Expo plugin already sets it.

  With a checkout of this repo, `ruby scripts/ios-add-widget-extension.rb ios/<App>.xcodeproj <App> <path to TrackMeLiveActivity/Sources/TrackMeLiveActivity>` does all four steps. While the extension is missing, the module shows the notification instead.
- **Your own lock-screen UI:** `sharingUi: { iosLiveActivity: false }` turns ours off on iOS.

## Example

[`example/`](example/) is the full showcase:
- a Live map on Baato's map: start or join a trip, invite people, see members live or paused, share or pause, end or leave;
- a developer screen with a button for every SDK call.

Its README says how to run it.

## Developing against this repo's SDK

```bash
./gradlew :trackme-sdk:publishToMavenLocal -Ptrackme.version=0.3.1   # Android: SDK into ~/.m2
scripts/port-ios-sdk.sh local                                         # iOS: SDK build into ios/Frameworks/, lock-screen code into ios/LiveActivity/
```
Then set `trackme.useLocalSdk=true` in the app's `android/gradle.properties`. `npm run typecheck` checks the TypeScript.

How the module works (the JSON bridge, the event stream, what each platform glue does): [docs/07-ports.md](../../docs/07-ports.md).
