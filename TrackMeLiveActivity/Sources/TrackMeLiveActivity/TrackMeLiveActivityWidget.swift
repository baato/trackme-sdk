#if canImport(ActivityKit) && canImport(WidgetKit)
import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// Add this to your Widget Extension's `WidgetBundle`:
/// ```swift
/// @main struct MyWidgets: WidgetBundle {
///     var body: some Widget { TrackMeLiveActivityWidget() }
/// }
/// ```
/// Colors, texts, icon and button labels come from `TrackMeSharingUi` in the app's TrackMeConfig.
@available(iOS 16.1, *)
public struct TrackMeLiveActivityWidget: Widget {
    public init() {}

    public var body: some WidgetConfiguration {
        ActivityConfiguration(for: TrackMeActivityAttributes.self) { context in
            TrackMeLockScreenView(content: context.state)
                .activitySystemActionForegroundColor(context.state.accentColor)
        } dynamicIsland: { context in
            let c = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    TrackMeIconBadge(content: c, size: 36).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    TrackMeStatusPill(content: c).padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(c.title).font(.headline).lineLimit(1).minimumScaleFactor(0.85)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 10) {
                        TrackMeSubtitle(content: c)
                        TrackMeTripBar(content: c)
                        TrackMeMetaLine(content: c)
                        TrackMeActionButtons(content: c)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                Image(systemName: c.symbolName).foregroundColor(c.paused ? .secondary : c.accentColor)
            } compactTrailing: {
                // Time left while the trip's end is known (iOS ticks it by itself), else the status word.
                if let range = c.tripInterval, range.upperBound > Date() {
                    Text(timerInterval: range, countsDown: true)
                        .font(.caption2.weight(.semibold).monospacedDigit())
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 52)
                        .foregroundColor(c.statusColor)
                } else {
                    Text(c.statusLabel).font(.caption2.weight(.semibold)).foregroundColor(c.statusColor)
                }
            } minimal: {
                Image(systemName: c.symbolName).foregroundColor(c.paused ? .secondary : c.accentColor)
            }
            .keylineTint(c.accentColor)
        }
    }
}

/// Lock screen / banner. iOS gives it at most ~160 pt of height and clips the rest, so it stays compact:
/// ```
/// (icon)  Sharing your location             ● LIVE
///         38:56 left · ends 16:43              ← or the text, until the trip's end is known
/// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━────────────     ← drains as the trip runs out
/// 1 trip · Updated 8 sec ago
/// [   Pause   ]  [   Stop   ]
/// ```
@available(iOS 16.1, *)
struct TrackMeLockScreenView: View {
    let content: TrackMeActivityContent

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 12) {
                TrackMeIconBadge(content: content, size: 38)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(content.title)
                            .font(.headline)
                            .lineLimit(1)
                            .minimumScaleFactor(0.85)
                            .layoutPriority(1)   // the title keeps its room; the pill is small and fixed
                        Spacer(minLength: 4)
                        TrackMeStatusPill(content: content)
                    }
                    TrackMeSubtitle(content: content)
                }
            }
            TrackMeTripBar(content: content)
            TrackMeMetaLine(content: content)
            TrackMeActionButtons(content: content)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

/// The app's symbol on a soft circle in the accent colour.
@available(iOS 16.1, *)
struct TrackMeIconBadge: View {
    let content: TrackMeActivityContent
    let size: CGFloat

    var body: some View {
        let tint = content.paused ? Color.secondary : content.accentColor
        Image(systemName: content.symbolName)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundColor(tint)
            .frame(width: size, height: size)
            .background(Circle().fill(tint.opacity(0.18)))
    }
}

/// "38:56 left · ends 16:43" while the trip's end is known (iOS ticks the timer by itself), else the text.
@available(iOS 16.1, *)
struct TrackMeSubtitle: View {
    let content: TrackMeActivityContent

    var body: some View {
        if let range = content.tripInterval, range.upperBound > Date() {
            (Text(timerInterval: range, countsDown: true).bold().foregroundColor(.primary)
                + Text(" left · ends ") + Text(range.upperBound, style: .time))
                .font(.subheadline.monospacedDigit())
                .foregroundColor(.secondary)
                .lineLimit(1)
        } else {
            Text(content.paused ? "Location sharing is paused." : content.text)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .lineLimit(1)
        }
    }
}

/// Time left as a bar that drains as the trip runs out. Animated by iOS itself (`ProgressView(timerInterval:)`).
/// Nothing until the trip's end is known.
@available(iOS 16.1, *)
struct TrackMeTripBar: View {
    let content: TrackMeActivityContent

    var body: some View {
        if let range = content.tripInterval {
            ProgressView(timerInterval: range, countsDown: true) {
                EmptyView()
            } currentValueLabel: {
                EmptyView()
            }
            .progressViewStyle(.linear)
            .tint(content.paused ? .gray : content.accentColor)
        }
    }
}

/// "● LIVE" / "PAUSED" / "OFFLINE" / "STARTING".
@available(iOS 16.1, *)
struct TrackMeStatusPill: View {
    let content: TrackMeActivityContent

    var body: some View {
        HStack(spacing: 4) {
            Circle().fill(content.statusColor).frame(width: 6, height: 6)
            Text(content.statusLabel.uppercased())
                .font(.caption2.weight(.bold))
                .kerning(0.5)
        }
        .foregroundColor(content.statusColor)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Capsule().fill(content.statusColor.opacity(0.15)))
        .fixedSize()
    }
}

/// "1 trip · Updated 12 sec ago". iOS keeps the relative time ticking by itself, no updates needed.
@available(iOS 16.1, *)
struct TrackMeMetaLine: View {
    let content: TrackMeActivityContent

    var body: some View {
        let trips = content.tripLine.map { Text("\($0) · ") } ?? Text("")
        Group {
            if let sent = content.lastSentAt {
                trips + Text("Updated ") + Text(sent, style: .relative) + Text(" ago")
            } else {
                trips + Text("Waiting for GPS…")
            }
        }
        .font(.caption.monospacedDigit())
        .foregroundColor(content.retrying ? .orange : .secondary)
        .lineLimit(1)
    }
}

/// Interactive on iOS 17+: equal-width buttons. On 16.1–16.x buttons can't run code, so tapping the activity opens the app instead.
@available(iOS 16.1, *)
struct TrackMeActionButtons: View {
    let content: TrackMeActivityContent

    var body: some View {
        if #available(iOS 17.0, *) {
            if content.showPauseAction || content.showStopAction { buttons }
        }
    }

    @available(iOS 17.0, *)
    private var buttons: some View {
            HStack(spacing: 10) {
                if content.showPauseAction {
                    if content.paused {
                        Button(intent: TrackMeResumeSharingIntent()) {
                            Label(content.resumeLabel, systemImage: "play.fill").frame(maxWidth: .infinity)
                        }
                        .tint(content.accentColor)
                    } else {
                        Button(intent: TrackMePauseSharingIntent()) {
                            Label(content.pauseLabel, systemImage: "pause.fill").frame(maxWidth: .infinity)
                        }
                        .tint(.gray)
                    }
                }
                if content.showStopAction {
                    Button(intent: TrackMeStopSharingIntent()) {
                        Label(content.stopLabel, systemImage: "stop.fill").frame(maxWidth: .infinity)
                    }
                    .tint(.red)
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .font(.subheadline.weight(.semibold))
    }
}

extension TrackMeActivityContent {
    /// Pill / compact colour: green live, gray paused, orange offline, secondary while starting.
    var statusColor: Color {
        if paused { return .gray }
        if retrying { return .orange }
        if tripCount > 0 { return .green }
        return .secondary
    }
}
#endif
