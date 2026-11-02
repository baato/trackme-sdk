import SwiftUI

@main
struct TrackMeSampleApp: App {
    @StateObject private var model = SampleModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
        }
    }
}
