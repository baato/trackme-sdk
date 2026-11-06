# @baato/trackme-react-native example

The TrackMe showcase in React Native (0.88, new architecture), ported from the native samples (`sample-android/`, `sample-ios/`), on the module in this repo (`"@baato/trackme-react-native": "file:.."`).

- **Live map** on Baato's `breeze` style (`@maplibre/maplibre-react-native`):
  - start a trip, with a preset or custom length, or join one by code or by pasting an invite;
  - the trip panel: the countdown, the join code and Share, and members shown as live / paused / "2 min ago" (tap one to fly there; the owner long-presses to remove);
  - share / pause / resume, and end or leave the trip;
  - the location pill and dialog, and the locate button.
- **Developer** (dev builds): a button for every SDK call, with a log.
- **Lock screen:** the Android notification, plus the iOS Live Activity through the `TrackMeWidget` extension already in `ios/`.
- Light and dark theme.

Every SDK call is in `src/store.ts`. The screens (`src/LiveMap.tsx`, `src/Developer.tsx`) only call into it.

## Run

1. Secrets, in the git-ignored `src/secrets.ts`:
   ```bash
   cp src/secrets.example.ts src/secrets.ts   # then fill in TRACKME_API_SECRET and BAATO_ACCESS_TOKEN
   ```
   The API secret is **demo only**: the example logs in by itself. A real app gets its token from its own backend.
2. The SDK, built from this repo (from the repo root):
   ```bash
   ./gradlew :trackme-sdk:publishToMavenLocal -Ptrackme.version=0.3.2   # Android (android/gradle.properties: trackme.useLocalSdk=true)
   scripts/port-ios-sdk.sh local                                         # iOS
   ```
   To use the released SDK instead, set `trackme.useLocalSdk=false` for Android. For iOS, delete `../ios/Frameworks/TrackMeSDK.version`, and `pod install` then downloads the release.
3. Install and run:
   ```bash
   npm install
   (cd ios && pod install)
   npx react-native run-android
   npx react-native run-ios --list-devices     # pick a simulator or a connected iPhone
   ```
   On an iPhone, the first build needs a signing team: open `ios/TrackMeExample.xcworkspace` and set it for **TrackMeExample** and **TrackMeWidget** under *Signing & Capabilities*. Keep the phone on the same Wi-Fi as Metro.

- **Showcase only:** set `DEV_TOOLS` to `false` in `src/config.ts` to hide the developer screen, as a customer's app would.
- **Two-device test:** log in with a different email on each phone. Start a trip on one, then share or paste the invite on the other.
- **After changing the module's source:** restart Metro with `npx react-native start --reset-cache`.
- **Checks:** `npx tsc --noEmit`.
