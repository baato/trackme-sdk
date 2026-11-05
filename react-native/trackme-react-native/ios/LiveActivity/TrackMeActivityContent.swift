import Foundation
import SwiftUI

/// Everything the lock-screen UI shows. Plain Swift so this package doesn't depend on the Kotlin SDK.
/// Build it from the SDK's `SharingSnapshot` in your presenter.
public struct TrackMeActivityContent: Codable, Hashable, Sendable {
    public var title: String
    public var text: String
    public var paused: Bool
    public var retrying: Bool
    public var tripCount: Int
    /// nil = nothing sent yet
    public var lastSentAt: Date?
    /// ARGB like Android (0xFF1565C0). 0 = system accent.
    public var accentColorARGB: Int64
    /// SF Symbol name, e.g. "location.fill"
    public var symbolName: String
    public var showPauseAction: Bool
    public var showStopAction: Bool
    public var pauseLabel: String
    public var resumeLabel: String
    public var stopLabel: String
    /// When the shared trip started and ends (the one that ends last). nil = not known yet: no countdown.
    public var tripStartedAt: Date?
    public var tripEndsAt: Date?

    public init(
        title: String,
        text: String,
        paused: Bool = false,
        retrying: Bool = false,
        tripCount: Int = 0,
        lastSentAt: Date? = nil,
        accentColorARGB: Int64 = 0,
        symbolName: String = "location.fill",
        showPauseAction: Bool = true,
        showStopAction: Bool = true,
        pauseLabel: String = "Pause",
        resumeLabel: String = "Resume",
        stopLabel: String = "Stop",
        tripStartedAt: Date? = nil,
        tripEndsAt: Date? = nil
    ) {
        self.title = title
        self.text = text
        self.paused = paused
        self.retrying = retrying
        self.tripCount = tripCount
        self.lastSentAt = lastSentAt
        self.accentColorARGB = accentColorARGB
        self.symbolName = symbolName
        self.showPauseAction = showPauseAction
        self.showStopAction = showStopAction
        self.pauseLabel = pauseLabel
        self.resumeLabel = resumeLabel
        self.stopLabel = stopLabel
        self.tripStartedAt = tripStartedAt
        self.tripEndsAt = tripEndsAt
    }

    /// The trip's time span for the countdown bar, or nil when unknown or invalid.
    public var tripInterval: ClosedRange<Date>? {
        guard let start = tripStartedAt, let end = tripEndsAt, end > start else { return nil }
        return start...end
    }

    /// The accent as a SwiftUI color (falls back to the system accent for 0).
    public var accentColor: Color {
        guard accentColorARGB != 0 else { return .accentColor }
        let v = UInt64(bitPattern: accentColorARGB)
        return Color(
            .sRGB,
            red: Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8) & 0xFF) / 255,
            blue: Double(v & 0xFF) / 255,
            opacity: Double((v >> 24) & 0xFF) / 255
        )
    }

    /// One-word state for the status pill: Live, Paused, Offline or Starting.
    public var statusLabel: String {
        if paused { return "Paused" }
        if retrying { return "Offline" }
        if tripCount > 0 { return "Live" }
        return "Starting"
    }

    /// "1 trip" / "3 trips", or nil before the server has answered.
    public var tripLine: String? {
        guard tripCount > 0 else { return nil }
        return tripCount == 1 ? "1 trip" : "\(tripCount) trips"
    }

    /// One short line for the iOS 15 notification fallback: "Live · 1 trip · ends 17:45".
    public var statusLine: String {
        let ends = tripEndsAt.map { "ends " + DateFormatter.localizedString(from: $0, dateStyle: .none, timeStyle: .short) }
        return [statusLabel, tripLine, ends].compactMap { $0 }.joined(separator: " · ")
    }
}

/// What the user tapped on the Live Activity or notification.
public enum TrackMeActivityAction: String, Sendable {
    case pause, resume, stop
}
