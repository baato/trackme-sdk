#if canImport(AppIntents) && canImport(ActivityKit)
import ActivityKit
import AppIntents

// Buttons on the Live Activity (iOS 17+). `LiveActivityIntent` runs inside the APP's process, so it can reach
// TrackMeActivityController.shared.onAction → TrackMe.shared.sharing.pause()/resume()/stop().
// These types must be compiled into both the app and the widget extension. Because they live in this
// package, linking the package in both targets is enough.

@available(iOS 17.0, *)
public struct TrackMePauseSharingIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Pause location sharing"
    public init() {}
    public func perform() async throws -> some IntentResult {
        await TrackMeActivityController.shared.perform(.pause)
        return .result()
    }
}

@available(iOS 17.0, *)
public struct TrackMeResumeSharingIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Resume location sharing"
    public init() {}
    public func perform() async throws -> some IntentResult {
        await TrackMeActivityController.shared.perform(.resume)
        return .result()
    }
}

@available(iOS 17.0, *)
public struct TrackMeStopSharingIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Stop location sharing"
    public init() {}
    public func perform() async throws -> some IntentResult {
        await TrackMeActivityController.shared.perform(.stop)
        return .result()
    }
}
#endif
