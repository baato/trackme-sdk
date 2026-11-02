import Foundation
import UserNotifications
#if canImport(ActivityKit)
import ActivityKit
#endif

/// Shows sharing on the lock screen:
/// - iOS 16.1+: a Live Activity (lock screen + Dynamic Island); interactive buttons on iOS 17+.
/// - iOS 15 (or Live Activities switched off by the user): a quiet local notification with Pause/Resume/Stop actions.
///
/// The app wires it once:
/// ```swift
/// TrackMeActivityController.shared.onAction = { action in /* call TrackMe.shared.sharing.pause/resume/stop */ }
/// UNUserNotificationCenter.current().delegate = TrackMeActivityController.shared.notificationDelegate
/// ```
@MainActor
public final class TrackMeActivityController {
    public static let shared = TrackMeActivityController()

    /// Called when the user taps Pause / Resume / Stop (Live Activity on iOS 17+, or the fallback notification).
    public var onAction: ((TrackMeActivityAction) -> Void)?

    /// Use the notification on iOS 15, and when Live Activities are disabled in Settings. Default true.
    public var useNotificationFallback = true

    /// Live Activity updates are rate-limited by iOS; don't push the same thing every few seconds.
    public var minimumUpdateInterval: TimeInterval = 15

    public let notificationDelegate = TrackMeNotificationDelegate()

    private var current: TrackMeActivityContent?
    private var lastPushed: Date = .distantPast
    private var usingNotification = false
    private var activityRef: AnyObject?   // Activity<TrackMeActivityAttributes>, type-erased for iOS 15 builds

    private init() {}

    // MARK: lifecycle (call from your TrackMeSharingPresenter)

    public func start(_ content: TrackMeActivityContent) {
        current = content
        lastPushed = .distantPast
        #if canImport(ActivityKit)
        if #available(iOS 16.1, *), ActivityAuthorizationInfo().areActivitiesEnabled {
            startActivity(content)
            return
        }
        #endif
        if useNotificationFallback { showNotification(content) }
    }

    public func update(_ content: TrackMeActivityContent) {
        guard let previous = current else { start(content); return }
        current = content
        // Push state changes immediately, otherwise at most every `minimumUpdateInterval`.
        let important = previous.paused != content.paused || previous.retrying != content.retrying ||
            previous.tripCount != content.tripCount || previous.title != content.title || previous.text != content.text
        guard important || Date().timeIntervalSince(lastPushed) >= minimumUpdateInterval else { return }

        #if canImport(ActivityKit)
        if #available(iOS 16.1, *), activityRef != nil {
            updateActivity(content)
            return
        }
        #endif
        if usingNotification { showNotification(content) }
    }

    public func end() {
        current = nil
        #if canImport(ActivityKit)
        if #available(iOS 16.1, *) { endActivities() }
        #endif
        if usingNotification {
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.notificationId])
            usingNotification = false
        }
    }

    /// Called by the intents / notification delegate.
    func perform(_ action: TrackMeActivityAction) {
        onAction?(action)
    }

    // MARK: Live Activity

    #if canImport(ActivityKit)
    @available(iOS 16.1, *)
    private func startActivity(_ content: TrackMeActivityContent) {
        endActivities()   // never leave two running (e.g. after an app restart)
        do {
            let activity: Activity<TrackMeActivityAttributes>
            if #available(iOS 16.2, *) {
                activity = try Activity.request(
                    attributes: TrackMeActivityAttributes(),
                    content: ActivityContent(state: content, staleDate: nil),
                    pushType: nil
                )
            } else {
                activity = try Activity.request(attributes: TrackMeActivityAttributes(), contentState: content, pushType: nil)
            }
            activityRef = activity
            lastPushed = Date()
        } catch {
            // e.g. the app is in background or the user disabled Live Activities: fall back.
            activityRef = nil
            if useNotificationFallback { showNotification(content) }
        }
    }

    @available(iOS 16.1, *)
    private func updateActivity(_ content: TrackMeActivityContent) {
        guard let activity = activityRef as? Activity<TrackMeActivityAttributes> else { return }
        lastPushed = Date()
        Task {
            if #available(iOS 16.2, *) {
                await activity.update(ActivityContent(state: content, staleDate: nil))
            } else {
                await activity.update(using: content)
            }
        }
    }

    @available(iOS 16.1, *)
    private func endActivities() {
        activityRef = nil
        let final = current
        for activity in Activity<TrackMeActivityAttributes>.activities {
            Task {
                if #available(iOS 16.2, *) {
                    let state = final ?? activity.content.state
                    await activity.end(ActivityContent(state: state, staleDate: nil), dismissalPolicy: .immediate)
                } else {
                    await activity.end(using: final, dismissalPolicy: .immediate)
                }
            }
        }
    }
    #endif

    // MARK: notification fallback (iOS 15+)

    nonisolated static let notificationId = "io.baato.trackme.sharing"
    nonisolated static let categoryId = "io.baato.trackme.sharing.category"

    /// Ask once (e.g. right before the first start) so the fallback notification can appear.
    public func requestNotificationPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert])) ?? false
    }

    private func showNotification(_ content: TrackMeActivityContent) {
        usingNotification = true
        lastPushed = Date()
        let center = UNUserNotificationCenter.current()

        var actions: [UNNotificationAction] = []
        if content.showPauseAction {
            actions.append(content.paused
                ? UNNotificationAction(identifier: TrackMeActivityAction.resume.rawValue, title: content.resumeLabel)
                : UNNotificationAction(identifier: TrackMeActivityAction.pause.rawValue, title: content.pauseLabel))
        }
        if content.showStopAction {
            actions.append(UNNotificationAction(identifier: TrackMeActivityAction.stop.rawValue,
                                                title: content.stopLabel, options: [.destructive]))
        }
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.categoryId, actions: actions, intentIdentifiers: []),
        ])

        let body = UNMutableNotificationContent()
        body.title = content.title
        body.body = "\(content.text)\n\(content.statusLine)"
        body.categoryIdentifier = Self.categoryId
        body.sound = nil
        if #available(iOS 15.0, *) { body.interruptionLevel = .passive }   // no sound/banner on updates

        // Same identifier → replaces the previous one instead of stacking.
        center.add(UNNotificationRequest(identifier: Self.notificationId, content: body, trigger: nil))
    }
}

/// Routes fallback-notification button taps to `TrackMeActivityController.onAction`.
/// If your app already has a notification delegate, call `handle(_:)` from it instead.
public final class TrackMeNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    /// Returns true if the response was a TrackMe action (and was handled).
    @discardableResult
    public func handle(_ response: UNNotificationResponse) -> Bool {
        guard response.notification.request.identifier == TrackMeActivityController.notificationId,
              let action = TrackMeActivityAction(rawValue: response.actionIdentifier) else { return false }
        Task { @MainActor in TrackMeActivityController.shared.perform(action) }
        return true
    }

    public func userNotificationCenter(_ center: UNUserNotificationCenter,
                                       didReceive response: UNNotificationResponse,
                                       withCompletionHandler completionHandler: @escaping () -> Void) {
        handle(response)
        completionHandler()
    }

    /// Show the sharing notification even while the app is open.
    public func userNotificationCenter(_ center: UNUserNotificationCenter,
                                       willPresent notification: UNNotification,
                                       withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.list])
    }
}
