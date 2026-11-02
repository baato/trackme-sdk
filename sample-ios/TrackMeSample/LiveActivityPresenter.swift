import Foundation
import UserNotifications
import TrackMeLiveActivity
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
            lastSentAt: s.lastSentAtMillis > 0 ? Date(timeIntervalSince1970: Double(s.lastSentAtMillis) / 1000) : nil,
            accentColorARGB: s.accentColor,
            symbolName: s.symbolName,
            showPauseAction: s.showPauseAction,
            showStopAction: s.showStopAction,
            pauseLabel: s.pauseLabel,
            resumeLabel: s.resumeLabel,
            stopLabel: s.stopLabel
        )
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
        // Fallback notification (iOS 15 / Live Activities off) delivers its button taps here.
        UNUserNotificationCenter.current().delegate = TrackMeActivityController.shared.notificationDelegate
    }
}
