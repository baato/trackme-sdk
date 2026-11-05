import Foundation
import UserNotifications
import TrackMeSDK

/// The ~30-line bridge between the Kotlin SDK and the Swift Live Activity package.
/// Copy it into your own app as-is.
///
/// The SDK calls these methods on the main thread whenever sharing starts, changes or ends.
final class LiveActivityPresenter: NSObject, TrackMeSharingPresenter {

    func sharingStarted(snapshot: SharingSnapshot) {
        let content = Self.content(from: snapshot)
        Task { @MainActor in TrackMeActivityController.shared.start(content) }
    }

    func sharingUpdated(snapshot: SharingSnapshot) {
        let content = Self.content(from: snapshot)
        Task { @MainActor in TrackMeActivityController.shared.update(content) }
    }

    func sharingEnded() {
        Task { @MainActor in TrackMeActivityController.shared.end() }
    }

    /// Kotlin `SharingSnapshot` → plain Swift content (Long → Int64, Int → Int32, millis → Date).
    private static func content(from s: SharingSnapshot) -> TrackMeActivityContent {
        TrackMeActivityContent(
            title: s.title,
            text: s.text,
            paused: s.paused,
            retrying: s.retrying,
            tripCount: Int(s.tripCount),
            lastSentAt: date(s.lastSentAtMillis),
            accentColorARGB: s.accentColor,
            symbolName: s.symbolName,
            showPauseAction: s.showPauseAction,
            showStopAction: s.showStopAction,
            pauseLabel: s.pauseLabel,
            resumeLabel: s.resumeLabel,
            stopLabel: s.stopLabel,
            tripStartedAt: date(s.tripStartedAtMillis),
            tripEndsAt: date(s.tripEndsAtMillis)
        )
    }

    /// Epoch millis → Date; 0 = not known.
    private static func date(_ millis: Int64) -> Date? {
        millis > 0 ? Date(timeIntervalSince1970: Double(millis) / 1000) : nil
    }

    /// Call once at app start: registers the presenter and routes lock-screen buttons back into the SDK.
    @MainActor
    static func install() {
        TrackMeIos.shared.setSharingPresenter(presenter: LiveActivityPresenter())
        TrackMeActivityController.shared.onAction = { action in
            switch action {
            case .pause: _ = TrackMe.shared.sharing.pause()
            case .resume: _ = TrackMe.shared.sharing.resume()
            case .stop: _ = TrackMe.shared.sharing.stop()
            }
        }
        // Fallback notification (iOS 15, Live Activities off, or no Widget Extension) delivers its button taps here.
        // An app with its own notification delegate keeps it, and calls notificationDelegate.handle(response) from it.
        if UNUserNotificationCenter.current().delegate == nil {
            UNUserNotificationCenter.current().delegate = TrackMeActivityController.shared.notificationDelegate
        }
    }
}
