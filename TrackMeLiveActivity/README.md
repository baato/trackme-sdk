# TrackMeLiveActivity

Lock-screen and Dynamic Island UI for TrackMe location sharing on iOS.

| iOS | What the user sees | Buttons |
|---|---|---|
| 17+ | Live Activity (lock screen + Dynamic Island) | Interactive Pause/Resume and Stop |
| 16.1–16.x | Live Activity | Tapping opens the app |
| 15, or Live Activities turned off | Quiet notification | Pause/Resume and Stop as notification actions |

This package doesn't depend on the Kotlin `TrackMeSDK`. Your app connects the two with one small presenter class; copy `sample-ios/TrackMeSample/LiveActivityPresenter.swift`.

## Setup (about 5 minutes)

1. **Add the package** to your app target and your widget extension target. Use *File → Add Package Dependencies… → Add Local…* during development, or the git URL once it's published.
2. **Widget Extension** (*File → New → Target → Widget Extension*, deployment target 16.1 or later). Its entry point:
   ```swift
   import SwiftUI
   import TrackMeLiveActivity

   @main
   struct MyWidgets: WidgetBundle {
       var body: some Widget { TrackMeLiveActivityWidget() }
   }
   ```
3. **App `Info.plist`:** add `NSSupportsLiveActivities = YES`.
4. **Wire it at app start**, after `TrackMe.shared.initialize(...)`:
   ```swift
   TrackMeIos.shared.setSharingPresenter(presenter: LiveActivityPresenter())
   TrackMeActivityController.shared.onAction = { action in
       switch action {
       case .pause:  _ = TrackMe.shared.sharing.pause()
       case .resume: _ = TrackMe.shared.sharing.resume()
       case .stop:   _ = TrackMe.shared.sharing.stop()
       }
   }
   // For the iOS 15 / Live-Activities-off fallback notification:
   UNUserNotificationCenter.current().delegate = TrackMeActivityController.shared.notificationDelegate
   ```
   If your app already has a notification delegate, call `TrackMeActivityController.shared.notificationDelegate.handle(response)` from it instead.
5. *(Fallback only)* Ask for notification permission once: `await TrackMeActivityController.shared.requestNotificationPermission()`.

## Notes

- **The look comes from the app:** `TrackMeSharingUi(title:text:accentColor:)` in `TrackMeConfig`, plus `iosSymbolName` and the button labels.
- **Live Activities can only *start* while the app is in the foreground.** This matches how sharing starts. If starting fails, the controller falls back to the notification.
- **Updates are throttled:** state changes (paused, offline, trip count) go out right away, anything else at most every 15 seconds (`minimumUpdateInterval`). The "updated X ago" text ticks on its own.
- **Ending:** when sharing stops for any reason (user, trip ended, permission revoked, `dispose()`), the SDK calls `sharingEnded()`, and the activity or notification is removed.
