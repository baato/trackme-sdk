// swift-tools-version: 5.9
// Lock-screen / Dynamic Island UI for TrackMe location sharing.
//
// Why a separate Swift package? ActivityKit, WidgetKit and App Intents are Swift-only, and a Live Activity's
// UI must live in a Widget Extension of the host app. Neither can ship inside the Kotlin TrackMeSDK.xcframework.
// This package has NO dependency on TrackMeSDK; the app connects the two with ~30 lines
// (see sample-ios/TrackMeSample/LiveActivityPresenter.swift).
import PackageDescription

let package = Package(
    name: "TrackMeLiveActivity",
    platforms: [.iOS(.v15)],   // Live Activity on 16.1+, interactive buttons on 17+, notification fallback on 15
    products: [
        .library(name: "TrackMeLiveActivity", targets: ["TrackMeLiveActivity"]),
    ],
    targets: [
        .target(name: "TrackMeLiveActivity"),
    ]
)
