import SwiftUI
import TrackMeLiveActivity
import WidgetKit

/// Widget Extension entry point. The whole Live Activity UI comes from the TrackMeLiveActivity package.
@main
struct TrackMeSampleWidgets: WidgetBundle {
    var body: some Widget {
        TrackMeLiveActivityWidget()
    }
}
