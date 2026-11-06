# trackme_flutter example

The TrackMe showcase in Flutter, ported from the native samples (`sample-android/`, `sample-ios/`), on the plugin in this repo.

- **Live map** on Baato's `breeze` style (`maplibre_gl`):
  - start a trip, with a preset or custom length, or join one by code or by pasting an invite;
  - the trip panel: the countdown, the join code with Copy and Share, and members shown as live / paused / "2 min ago" (tap one to fly there; the owner long-presses to remove);
  - share / pause / resume, and end or leave the trip;
  - the location pill and dialog, and the locate button.
- **Developer** (debug builds): a button for every SDK call, with a log.
- **Lock screen:** the Android notification, plus the iOS Live Activity through the `TrackMeWidget` extension already in `ios/`.
- Light and dark theme.

Every SDK call is in `lib/app_model.dart`. The screens only call into it.

## Run

1. Secrets, in the git-ignored `secrets.json`:
   ```bash
   cp secrets.example.json secrets.json   # then fill in TRACKME_API_SECRET and BAATO_ACCESS_TOKEN
   ```
   The API secret is **demo only**: the example logs in by itself. A real app gets its token from its own backend.
2. The SDK, built from this repo (from the repo root):
   ```bash
   ./gradlew :trackme-sdk:publishToMavenLocal -Ptrackme.version=0.3.2   # Android (android/gradle.properties: trackme.useLocalSdk=true)
   scripts/port-ios-sdk.sh local                                         # iOS
   ```
   To use the released SDK instead, set `trackme.useLocalSdk=false` for Android. For iOS, delete `../ios/Frameworks/TrackMeSDK.version`, and `pod install` then downloads the release.
3. Run:
   ```bash
   flutter pub get
   flutter run --dart-define-from-file=secrets.json            # add -d <device id> for a phone (flutter devices)
   ```
   On an iPhone, the first build needs a signing team: open `ios/Runner.xcworkspace` and set it for **Runner** and **TrackMeWidget** under *Signing & Capabilities*.

- **Showcase only:** add `--dart-define=TRACKME_DEV_TOOLS=false` to hide the developer screen, as a customer's app would.
- **Two-device test:** log in with a different email on each phone. Start a trip on one, then share or paste the invite on the other.
- **Checks:** `flutter analyze` and `flutter test` (the invite parser and the formatters).
