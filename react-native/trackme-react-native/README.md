# @baato/trackme-react-native

Live location sharing and trip groups for React Native, on Baato's TrackMe. It wraps the native TrackMe SDK as a Turbo Module, so behaviour matches the Android and iOS SDKs exactly. Sharing (the background service, the notification's Pause/Stop, the Live Activity) runs natively, even while JS isn't.

**Status:** development (`ports` branch). Not on npm yet. Requires React Native 0.76+ (the new architecture).

```ts
import TrackMe from '@baato/trackme-react-native';

await TrackMe.initialize(
  { token: accessToken, sharingUi: { title: 'Sharing your trip' } },
  { tokenProvider: () => myBackend.freshToken() },   // asked on a 401; resolve null to give up
);

const trip = await TrackMe.trips.create({ name: 'Ride home', durationMinutes: 60 });
await TrackMe.sharing.start();                       // ask for location permission first (e.g. react-native-permissions)
await TrackMe.realtime.watchTrip(trip.id);

const off = TrackMe.onEvent((e) => {
  if (e.type === 'TripPosition') moveMarker(e.position);
});
```

Every call rejects with a `TrackMeError` (`type`, `message`). The whole API is in `src/index.ts`, and it maps one to one onto the native SDK ([docs/04-integration-guide.md](../../docs/04-integration-guide.md)).

## App setup

- **Expo:** add the plugin in `app.json`, `"plugins": ["@baato/trackme-react-native"]`, then use a development build (not Expo Go). The plugin writes the iOS location strings, background mode and Live Activity flag.
- **Bare React Native, iOS:** add the same `Info.plist` keys yourself: `NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription`, `UIBackgroundModes` → `location`.
- **Android:** `compileSdk 37`, `minSdk 24`. The module adds JitPack, limited to the TrackMe group, to your build's repositories.

## Developing against this repo's SDK

```bash
./gradlew :trackme-sdk:publishToMavenLocal -Ptrackme.version=0.3.0   # Android: SDK into ~/.m2
scripts/port-ios-sdk.sh local                                         # iOS: SDK into ios/Frameworks/
```
Then set `trackme.useLocalSdk=true` in the app's `android/gradle.properties`. `npm run typecheck` checks the TypeScript.

How it works: [docs/07-ports.md](../../docs/07-ports.md).
