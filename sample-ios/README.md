# TrackMe iOS sample

A SwiftUI app (iOS 15+) that uses the Kotlin `TrackMeSDK` framework straight from this repo.

```bash
brew install xcodegen                      # once
cd sample-ios
cp Secrets.example.xcconfig Secrets.xcconfig   # DEMO ONLY: API secret + Baato map key (git-ignored)
xcodegen                                   # generates TrackMeSample.xcodeproj from project.yml (re-run after adding files)
open TrackMeSample.xcodeproj
```

In Xcode, pick your Team under *Signing & Capabilities*, choose a simulator or iPhone, and press Run. The first build runs Gradle (`embedAndSignAppleFrameworkForXcode`) and takes a few minutes.

**Testing on a real device** is what matters for location. In the simulator, use *Features → Location → City Run* to fake movement.

**Live Activity:** the `TrackMeSampleWidget` extension shows sharing on the lock screen and Dynamic Island (iOS 16.1+), with Pause/Resume/Stop buttons on iOS 17+. On iOS 15 a notification with the same actions appears instead. Start sharing, then lock the device. Live Activities are most reliable on a real iPhone.

Files:
- `SampleModel.swift`: every SDK call, so it doubles as a Swift usage reference
- `ContentView.swift`: Live map / Developer switch, login, shared helpers
- `LiveMapView.swift`: the showcase (map + start / join / invite / leave / end)
- `BaatoMap.swift`: MapLibre map with the Baato `breeze` style, member dots; the dev-tools flag (`AppConfig`)
- `TripsViews.swift`: Trips tab and the trip screen (rename, end, leave, remove member, delete, view link, history, live)
- `OtherViews.swift`: Share, Viewer, Inbox and SDK tabs
- `LocationAccess.swift`: the map's location pill and the "Location access" sheet (what each level means, one action to improve it)
- `DurationPicker.swift`: trip length presets plus Custom (any minutes; the server sets the limits)
- `LocateButton.swift`: the round "show me" button (located / searching / disabled)

What each screen covers, and a two-device test script: [docs/06-sample-apps.md](../docs/06-sample-apps.md).
- `DemoAuth.swift`: demo-only login, and a `TrackMeTokenProvider` written in Swift
- `LiveActivityPresenter.swift`: bridges the SDK to the `TrackMeLiveActivity` package (copy it into your app)
- `TrackMeSampleWidget/`: the Widget Extension, a one-liner using `TrackMeLiveActivityWidget()`
- `project.yml`: Info.plist keys (location usage strings, `UIBackgroundModes = location`) and the Gradle build phase
