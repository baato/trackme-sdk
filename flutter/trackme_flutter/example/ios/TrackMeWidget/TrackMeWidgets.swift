import SwiftUI
import WidgetKit

/// Widget Extension entry point. The whole Live Activity UI is TrackMeLiveActivity's (compiled into this target).
@main
struct TrackMeWidgets: WidgetBundle {
    var body: some Widget {
        TrackMeLiveActivityWidget()
    }
}
