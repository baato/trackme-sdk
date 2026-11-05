#if canImport(ActivityKit)
import ActivityKit
import Foundation

/// The Live Activity type. Both the app (to start/update/end) and the Widget Extension (to draw it) use it,
/// which is why it lives in this shared package.
@available(iOS 16.1, *)
public struct TrackMeActivityAttributes: ActivityAttributes {
    public typealias ContentState = TrackMeActivityContent

    /// Fixed for the activity's lifetime. Kept empty on purpose: everything that changes is in ContentState.
    public init() {}
}
#endif
