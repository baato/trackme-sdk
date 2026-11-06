# trackme_flutter

Live location sharing and trip groups for Flutter apps, on Baato's TrackMe.

The plugin wraps the native TrackMe SDK for Android and iOS, so it behaves exactly like it. Sharing runs natively: the background service, the notification's Pause / Stop and the iOS Live Activity keep working while the Flutter engine is paused or gone.

- **Trips:** create, join by code, preview, list, detail, rename, end, leave, remove a member, delete, view links, history.
- **Sharing:** start / pause / resume / stop, with background support and a countdown to the trip's end.
- **Live updates:** members' positions, joins and leaves, members' sharing state, trip end, view-link watching.
- **Also:** notifications inbox, location permission status, automatic token refresh, typed errors.

## Install

From git: pub.dev only accepts open-source licences.

```yaml
dependencies:
  trackme_flutter:
    git:
      url: https://github.com/baato/trackme-sdk.git
      path: flutter/trackme_flutter
      ref: 0.3.2
```

`ref` is the SDK release tag. The plugin's Android side pulls the same SDK version from JitPack. Its iOS pod downloads that release's `TrackMeSDK.xcframework` on the first `pod install` and checks it against its SHA-256.

## App setup

### Android

| | Needed |
|---|---|
| `compileSdk` | 36 or newer |
| `minSdk` | 24 |
| Android Gradle Plugin | 8.11+ recommended; older 8.x builds with a warning |
| Kotlin Gradle plugin | 2.4.20+, in `android/settings.gradle.kts`: `id("org.jetbrains.kotlin.android") version "2.4.20" apply false` |

The plugin adds JitPack to your build's repositories, limited to the TrackMe group. The SDK's manifest brings the location, foreground-service and notification permissions and the sharing service, so you add nothing to `AndroidManifest.xml`.

### iOS (15+)

1. `ios/Podfile`: `platform :ios, '15.0'`.
2. `ios/Runner/Info.plist`:
   - `NSLocationWhenInUseUsageDescription`: why you share location;
   - `NSLocationAlwaysAndWhenInUseUsageDescription`: why sharing continues in the background;
   - `UIBackgroundModes` → `location`.
3. `pod install`, or just `flutter run`.

### Permissions

The SDK reports location permission (`TrackMe.location.permissionStatus()`) but never asks. Ask with your usual package, for example [`permission_handler`](https://pub.dev/packages/permission_handler):
- location "while in use", then optionally "always";
- notifications, for the sharing notification on Android 13+ and on iOS.

## Use

```dart
import 'package:trackme_flutter/trackme_flutter.dart';

// 1. Initialize with an access token from YOUR backend. It logs in to TrackMe with the API secret; the app never has it.
await TrackMe.initialize(
  TrackMeConfig(
    token: accessToken,
    sharingUi: const TrackMeSharingUi(title: 'Sharing your trip', accentColor: 0xFF1565C0),
  ),
  tokenProvider: () => myBackend.freshToken(), // asked when the token expires; return null to give up
);

// 2. A trip, sharing, live updates.
final trip = await TrackMe.trips.create(name: 'Ride home', durationMinutes: 60);
await TrackMe.sharing.start();
await TrackMe.realtime.watchTrip(trip.id);

TrackMe.events.listen((e) {
  switch (e) {
    case TripPositionEvent(:final position):
      moveMarker(position);
    case MemberSharingChanged(:final userId, :final state):
      showSharingState(userId, state);
    case TripEnded():
      showEnded();
    default:
  }
});
TrackMe.sharing.state.listen((s) => setState(() => sharing = s));

// Others join with the code (or open the view link): TrackMe.trips.join(trip.joinCode)

// 3. On log out.
await TrackMe.dispose();
```

- **Errors:** every call throws `TrackMeException`, with a `type` (`TrackMeErrorType.notFound`, `.validation`, `.locationPermissionDenied`, …) and the server's `message`.
- **Limits:** trip duration and list sizes are set by the server. An out-of-range value comes back as a `validation` error with the server's text, so show `e.message`.
- **Full API:** `lib/src/trackme.dart` has `TrackMe.trips`, `.sharing`, `.location`, `.realtime` and `.notifications`. The models are in `lib/src/models.dart`.
- **Behaviour, options and rules:** the same as the native SDK, described in [docs/04-integration-guide.md](../../docs/04-integration-guide.md).

## Background UI

`TrackMeSharingUi` sets the look on both platforms:
- title, text and paused text;
- the accent colour (ARGB);
- which buttons show, and their labels;
- the Android small icon (`androidSmallIcon`, a drawable *name*, monochrome) and the notification channel name;
- the iOS SF Symbol.

What each platform shows:
- **Android:** an ongoing notification with Pause / Resume, Stop and a countdown to the trip's end. No setup.
- **iOS, no setup:** while sharing, a notification with Pause / Resume / Stop.
- **iOS Live Activity** (lock screen + Dynamic Island, iOS 16.1+, buttons on 17+): Apple only allows this UI in the app's own Widget Extension, so it takes one step:
  1. In Xcode (`ios/Runner.xcworkspace`): *File → New → Target… → Widget Extension*, deployment target 16.1. Uncheck the Live Activity and Configuration options.
  2. *File → Add Package Dependencies…* → `https://github.com/baato/trackme-sdk`, version `0.3.2`. Add the product **`TrackMeLiveActivity` to the widget target only**.
  3. Replace the generated widget code with:
     ```swift
     import SwiftUI
     import TrackMeLiveActivity
     import WidgetKit

     @main struct TrackMeWidgets: WidgetBundle {
         var body: some Widget { TrackMeLiveActivityWidget() }
     }
     ```
  4. `ios/Runner/Info.plist`: `NSSupportsLiveActivities` = `YES`.

  With a checkout of this repo, `ruby scripts/ios-add-widget-extension.rb ios/Runner.xcodeproj Runner <path to TrackMeLiveActivity/Sources/TrackMeLiveActivity>` does all four steps. While the extension is missing, the plugin shows the notification instead.
- **Your own lock-screen UI:** `TrackMeSharingUi(iosLiveActivity: false)` turns ours off on iOS.

## Example

[`example/`](example/) is the full showcase:
- a Live map on Baato's map: start or join a trip, invite people, see members live or paused, share or pause, end or leave;
- a developer screen with a button for every SDK call.

Its README says how to run it.

## Developing against this repo's SDK

```bash
./gradlew :trackme-sdk:publishToMavenLocal -Ptrackme.version=0.3.2   # Android: SDK into ~/.m2
scripts/port-ios-sdk.sh local                                         # iOS: SDK build into ios/Frameworks/, lock-screen code into ios/LiveActivity/
```
Then set `trackme.useLocalSdk=true` in the app's `android/gradle.properties`.

How the plugin works (the JSON bridge, the event stream, what each platform glue does): [docs/07-ports.md](../../docs/07-ports.md).
