import SwiftUI
import TrackMeSDK

/// Two faces of the same SDK:
/// - **Live map** (always): the ready-to-use showcase, see LiveMapView.swift.
/// - **Developer** (only when AppConfig.devTools: TRACKME_DEV_TOOLS, default on for Debug): a control for every SDK call.
///
/// Developer screen map (every public SDK call has a control somewhere):
/// - Login → TrackMe.shared.initialize (+ TrackMeSharingUi options)
/// - Trips tab → trips.create / preview / join / list ; tap a trip → TripDetailView
/// - TripDetailView → trips.detail / rename / end / leave / removeMember / delete / viewLink / rotateViewLink / history,
///   realtime.watchTrip / stopWatchingTrip, sharing.start / stop
/// - Share tab → location.permissionStatus, sharing.start(options:) / pause / resume / stop, TrackMeIos.observeSharing
/// - Viewer tab → realtime.watchShare / stopWatchingShare
/// - Inbox tab → notifications.list (paging, unread filter) / markRead / markAllRead
/// - SDK tab → state, checkHealth, updateToken, token-provider refresh, realtime.stop, initialize again, dispose, event log
struct ContentView: View {
    @EnvironmentObject var model: SampleModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if !model.loggedIn {
                NavigationView { LoginView() }.navigationViewStyle(.stack)
            } else if AppConfig.devTools {
                MainTabs()          // Live map | Developer
            } else {
                LiveMapView()       // showcase only
            }
        }
        .overlay(alignment: .top) { if model.busy { ProgressView().padding(8).background(.thinMaterial, in: Capsule()) } }
        .alert("TrackMe", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(model.message ?? "") }
        // Re-check permission when returning from Settings.
        .onChange(of: scenePhase) { phase in if phase == .active && model.loggedIn { model.refreshPermission() } }
    }
}

/// Dev-tools build: the showcase and the developer screens side by side.
private struct MainTabs: View {
    @EnvironmentObject var model: SampleModel

    var body: some View {
        TabView(selection: $model.section) {
            LiveMapView()
                .tabItem { Label("Live map", systemImage: "map") }
                .tag(SampleSection.map)
            DeveloperView()
                .tabItem { Label("Developer", systemImage: "hammer") }
                .tag(SampleSection.developer)
        }
        // The open trip (Trips list, Inbox "Open trip", after joining). Swipe down = close.
        .sheet(isPresented: Binding(get: { model.detail != nil }, set: { if !$0 { model.closeTrip() } })) {
            if let d = model.detail { TripDetailView(detail: d).environmentObject(model) }
        }
    }
}

private struct DeveloperView: View {
    @EnvironmentObject var model: SampleModel

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                Picker("Section", selection: $model.tab) {
                    Text("Trips").tag(SampleTab.trips)
                    Text("Share").tag(SampleTab.sharing)
                    Text("Viewer").tag(SampleTab.viewer)
                    Text("Inbox").tag(SampleTab.inbox)
                    Text("SDK").tag(SampleTab.sdk)
                }
                .pickerStyle(.segmented)
                .padding([.horizontal, .bottom])
                switch model.tab {
                case .trips: TripsTab()
                case .sharing: SharingTab()
                case .viewer: ViewerTab()
                case .inbox: InboxTab()
                case .sdk: SdkTab()
                }
            }
            .navigationTitle("Developer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Log out") { model.logout() } }
        }
        .navigationViewStyle(.stack)
    }
}

// MARK: login

private struct LoginView: View {
    @EnvironmentObject var model: SampleModel
    @State private var form = LoginForm(email: "ios.sample@trackme.dev", fullName: "iOS Sample")   // change per device

    private let accents: [(String, Int64)] = [("Blue", 0xFF1565C0), ("Green", 0xFF2E7D32), ("Orange", 0xFFEF6C00), ("Default", 0)]

    var body: some View {
        Form {
            Section(footer: Text("The sample logs in with the API secret from Secrets.xcconfig. A real app gets the token from its own backend and only calls TrackMe.shared.initialize(…). Use a different email on a second device to test trips together.")) {
                TextField("Email", text: $form.email).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                TextField("Full name", text: $form.fullName)
            }
            if AppConfig.devTools { Section(header: Text("Live Activity / notification (TrackMeSharingUi)")) {
                TextField("Title", text: $form.title)
                TextField("Text", text: $form.text)
                Picker("Accent", selection: $form.accentColor) {
                    ForEach(accents.indices, id: \.self) { i in Text(accents[i].0).tag(accents[i].1) }
                }
                Toggle("Pause button", isOn: $form.showPause)
                Toggle("Stop button", isOn: $form.showStop)
            } }
            Section {
                Button("Log in & initialize SDK") {
                    form.email = form.email.trimmingCharacters(in: .whitespaces)
                    model.login(form)
                }
                .disabled(!form.email.contains("@") || model.busy)
            }
        }
        .navigationTitle("TrackMe Example")
    }
}

// MARK: shared helpers

/// 3725 → "1 h 2 min", 125 → "2 min 5 s".
func formatSeconds(_ seconds: Int64) -> String {
    let s = max(0, seconds)
    if s >= 3600 { return "\(s / 3600) h \((s % 3600) / 60) min" }
    if s >= 60 { return "\(s / 60) min \(s % 60) s" }
    return "\(s) s"
}

/// "2026-10-29T06:40:37.5Z" → "2026-10-29 06:40"
func shortDate(_ iso: String?) -> String {
    guard let iso else { return "" }
    return String(iso.prefix(16)).replacingOccurrences(of: "T", with: " ")
}

struct StatusBadge: View {
    let trip: Trip
    var body: some View {
        if trip.isLive {
            Text("● LIVE").font(.caption.bold()).foregroundColor(.accentColor)
        } else {
            Text("ENDED" + (trip.endedReason.map { " (\($0))" } ?? "")).font(.caption.bold()).foregroundColor(.secondary)
        }
    }
}

/// UIActivityViewController for "Share…" buttons (ShareLink needs iOS 16).
func shareText(_ text: String) {
    let vc = UIActivityViewController(activityItems: [text], applicationActivities: nil)
    let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
    var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController
    while let presented = top?.presentedViewController { top = presented }
    top?.present(vc, animated: true)
}
