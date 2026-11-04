# trackme_flutter

Live location sharing and trip groups for Flutter, on Baato's TrackMe. It wraps the native TrackMe SDK, so behaviour matches the Android and iOS SDKs exactly. Sharing (the background service, the notification's Pause/Stop, the Live Activity) runs natively, even while the Flutter engine isn't.

**Status:** development (`ports` branch). Not on pub.dev yet.

```dart
import 'package:trackme_flutter/trackme_flutter.dart';

await TrackMe.initialize(
  TrackMeConfig(token: accessToken, sharingUi: const TrackMeSharingUi(title: 'Sharing your trip')),
  tokenProvider: () => myBackend.freshToken(),   // asked on a 401; return null to give up
);

final trip = await TrackMe.trips.create(name: 'Ride home', durationMinutes: 60);
await TrackMe.sharing.start();                    // ask for location permission first (e.g. permission_handler)
await TrackMe.realtime.watchTrip(trip.id);

TrackMe.events.listen((e) {
  if (e is TripPositionEvent) moveMarker(e.position);
});
TrackMe.sharing.state.listen((s) => setState(() => sharing = s));
```

Every call throws `TrackMeException` (`type`, `message`) on failure. The whole API is in `lib/src/trackme.dart`, and it maps one to one onto the native SDK ([docs/04-integration-guide.md](../../docs/04-integration-guide.md)).

## App setup

- **Android:** `compileSdk 37`, `minSdk 24`. The plugin adds JitPack, limited to the TrackMe group, to your build's repositories.
- **iOS 15+:** in `Info.plist`, add `NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription` and `UIBackgroundModes` → `location`.
- **Permissions:** the SDK reports location permission (`TrackMe.location.permissionStatus()`) but never asks. Ask with your usual package.

## Developing against this repo's SDK

```bash
./gradlew :trackme-sdk:publishToMavenLocal -Ptrackme.version=0.3.0   # Android: SDK into ~/.m2
scripts/port-ios-sdk.sh local                                         # iOS: SDK into ios/Frameworks/
```
Then set `trackme.useLocalSdk=true` in the app's `android/gradle.properties`.

How it works: [docs/07-ports.md](../../docs/07-ports.md).
