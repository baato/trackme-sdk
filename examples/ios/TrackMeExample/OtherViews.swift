import SwiftUI
import TrackMeSDK

// MARK: Share tab: permission + sharing + options

struct SharingTab: View {
    @EnvironmentObject var model: SampleModel
    @State private var hint: String?

    var body: some View {
        Form {
            Section(header: Text("Location permission · location.permissionStatus"),
                    footer: Text("The SDK never shows a prompt: the app asks, the SDK reports. Sharing needs at least While Using. Background sharing also needs the location background mode (backgroundCapable).")) {
                Text(permissionText)
                HStack {
                    Button("While using") { model.requestWhenInUse() }
                    Spacer()
                    Button("Always") { model.requestAlways() }
                    Spacer()
                    Button("Settings") { model.openSettings() }
                }
                .buttonStyle(.borderless)
            }

            Section("Live sharing · sharing.start / pause / resume / stop") {
                Text(sharingText)
                if let hint { Text(hint).foregroundColor(.red) }
                HStack {
                    if !model.sharing.isActive {
                        Button("Start") { hint = model.startSharing() }
                    } else {
                        if model.sharing is SharingState.Paused {
                            Button("Resume") { model.resumeSharing() }
                        } else {
                            Button("Pause") { model.pauseSharing() }
                        }
                        Spacer()
                        Button("Stop", role: .destructive) { model.stopSharing() }
                    }
                }
                .buttonStyle(.borderless)
            }

            Section(header: Text("SharingOptions"),
                    footer: Text(model.sharing.isActive ? "Applied when sharing starts. Stop to change them." : "Used by the next Start.")) {
                Picker("Upload at most every", selection: optionBinding(\.intervalMillis) { o, v in o.with(interval: v) }) {
                    Text("2 s").tag(Int64(2_000)); Text("5 s").tag(Int64(5_000)); Text("15 s").tag(Int64(15_000)); Text("60 s").tag(Int64(60_000))
                }
                Picker("Skip moves under", selection: optionBinding(\.minDistanceMeters) { o, v in o.with(distance: v) }) {
                    Text("0 m").tag(0.0); Text("5 m").tag(5.0); Text("25 m").tag(25.0); Text("100 m").tag(100.0)
                }
                Picker("Re-send at least every", selection: optionBinding(\.heartbeatMillis) { o, v in o.with(heartbeat: v) }) {
                    Text("15 s").tag(Int64(15_000)); Text("30 s").tag(Int64(30_000)); Text("1 min").tag(Int64(60_000)); Text("5 min").tag(Int64(300_000))
                }
                Toggle("High accuracy (GPS)", isOn: optionBinding(\.highAccuracy) { o, v in o.with(accuracy: v) })
            }
            .disabled(model.sharing.isActive)

            Section("Lock screen") {
                Text("Live Activity (iOS 16.1+, buttons on 17+) or a notification on iOS 15, styled by TrackMeSharingUi at login. Its Pause / Resume / Stop call the same SDK functions as the buttons above.")
                    .font(.caption)
            }
        }
    }

    /// Kotlin data classes are immutable in Swift: build a new SharingOptions for every change.
    private func optionBinding<V>(_ get: KeyPath<SharingOptions, V>, _ set: @escaping (SharingOptions, V) -> SharingOptions) -> Binding<V> {
        Binding(get: { model.options[keyPath: get] }, set: { model.options = set(model.options, $0) })
    }

    private var permissionText: String {
        guard let p = model.permission else { return "Unknown" }
        return "\(p.level.name) · \(p.precise ? "precise" : "approximate") · location \(p.servicesEnabled ? "on" : "OFF")"
            + (p.backgroundCapable ? " · background capable" : " · no background mode")
    }

    private var sharingText: String {
        switch model.sharing {
        case is SharingState.Idle: return "Not sharing. Create or join a trip, then start."
        case is SharingState.Starting: return "Starting… waiting for the first GPS fix"
        case let s as SharingState.Sharing:
            var t = "Sharing to trips \(s.tripIds.map { $0.int64Value })" + (s.retrying ? " · offline, retrying" : "")
            if let l = s.lastLocation {
                t += String(format: "\n%.5f, %.5f ±%.0f m", l.latitude, l.longitude, l.accuracyMeters?.doubleValue ?? 0)
                if let speed = l.speedMetersPerSecond { t += String(format: " · %.1f m/s", speed.doubleValue) }
            }
            if let sent = s.lastSentAtMillis {
                t += "\nlast sent \(formatSeconds(Int64(Date().timeIntervalSince1970 * 1000) / 1000 - sent.int64Value / 1000)) ago"
            }
            return t
        case is SharingState.Paused: return "Paused (GPS on, nothing sent)"
        case let s as SharingState.Stopped:
            switch s.reason.name {
            case "NoActiveTrips": return "Stopped: you're in no live trip any more (it ended, or you left / were removed)."
            case "PermissionRevoked": return "Stopped: location permission was turned off."
            case "ServicesDisabled": return "Stopped: Location Services were turned off."
            case "Unauthorized": return "Stopped: the token was rejected and the provider gave no new one."
            default: return "Stopped: \(s.reason.name)"
            }
        default: return "\(model.sharing)"
        }
    }
}

private extension SharingOptions {
    func with(interval: Int64? = nil, distance: Double? = nil, heartbeat: Int64? = nil, accuracy: Bool? = nil) -> SharingOptions {
        SharingOptions(intervalMillis: interval ?? intervalMillis, minDistanceMeters: distance ?? minDistanceMeters,
                       heartbeatMillis: heartbeat ?? heartbeatMillis, highAccuracy: accuracy ?? highAccuracy)
    }
}

// MARK: Viewer tab: watch a public view link

struct ViewerTab: View {
    @EnvironmentObject var model: SampleModel

    var body: some View {
        Form {
            Section(header: Text("Watch a view link · realtime.watchShare"),
                    footer: Text("What a non-member sees on the web page, live. Paste a link like …/trackme/s/<token> (or just the token). Get one from a trip's \"Public view link\".")) {
                TextField("View link or token", text: $model.viewerInput)
                    .textInputAutocapitalization(.never).disableAutocorrection(true)
                HStack {
                    Button("Watch") { model.watchShare() }.disabled(model.viewerInput.isEmpty)
                    Spacer()
                    Button("Stop") { model.stopWatchingShare() }.disabled(!model.viewerWatching)
                }
                .buttonStyle(.borderless)
                Text(status)
            }
            Section("Positions") {
                if model.viewerPositions.isEmpty { Text("None yet").font(.caption) }
                ForEach(model.viewerPositions.keys.sorted(), id: \.self) { key in
                    if let p = model.viewerPositions[key] {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.fullName ?? "Member \(key)").bold()
                            Text(String(format: "%.5f, %.5f", p.latitude, p.longitude))
                            Text(p.fixAgeSeconds.map { "fix \(formatSeconds($0.int64Value)) old" } ?? "")
                                .font(.caption)
                        }
                    }
                }
            }
        }
    }

    private var status: String {
        if model.viewerEnded { return "This link has ended (trip over, or the link was rotated)." }
        if model.viewerWatching {
            if let r = model.realtimeState as? RealtimeState.Reconnecting { return "Reconnecting (attempt \(r.attempt))…" }
            return "● Watching. Positions appear when members move."
        }
        return "Not watching"
    }
}

// MARK: Inbox tab: notifications

struct InboxTab: View {
    @EnvironmentObject var model: SampleModel

    var body: some View {
        Form {
            Section(header: Text("Inbox · notifications.list / markRead / markAllRead"),
                    footer: Text("Trip ended, member joined, trip deleted… created by the server.")) {
                Toggle("Unread only", isOn: Binding(get: { model.unreadOnly }, set: { model.setUnreadOnly($0) }))
                HStack {
                    Button("Refresh") { model.loadNotifications(reset: true) }
                    Spacer()
                    Button("Mark all read") { model.markAllRead() }.disabled(!model.notifications.contains { !$0.isRead })
                }
                .buttonStyle(.borderless)
            }
            Section {
                if model.notifications.isEmpty { Text("Nothing here yet.").font(.caption) }
                ForEach(Array(model.notifications.enumerated()), id: \.offset) { _, n in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(n.title).fontWeight(n.isRead ? .regular : .bold)
                        Text(n.body)
                        Text("\(shortDate(n.createdAt)) UTC").font(.caption)
                        HStack {
                            if !n.isRead { Button("Mark read") { model.markRead(n) } }
                            Spacer()
                            if n.tripId != nil { Button("Open trip") { model.openNotificationTrip(n) } }
                        }
                        .buttonStyle(.borderless)
                    }
                    .listRowBackground(n.isRead ? nil : Color.accentColor.opacity(0.12))
                }
                if model.hasMore { Button("Load more") { model.loadNotifications(reset: false) } }
            }
        }
    }
}

// MARK: SDK tab: lifecycle, token, health, log

struct SdkTab: View {
    @EnvironmentObject var model: SampleModel

    var body: some View {
        Form {
            Section("State") {
                Text("TrackMe.state: \(model.sdkState) · isInitialized \(TrackMe.shared.isInitialized ? "true" : "false")")
                Text("sharing.state: \(String(describing: type(of: model.sharing)))")
                Text("realtime.state: \(String(describing: model.realtimeState))")
            }
            Section("Server · checkHealth") {
                HStack {
                    Button("Check health") { model.checkHealth() }.buttonStyle(.borderless)
                    Spacer()
                    Text(model.health ?? "—").font(.caption)
                }
            }
            Section(header: Text("Token"),
                    footer: Text("\"Simulate\" puts a broken token in the SDK, then loads trips: the 401 makes the SDK ask the token provider, the log shows \"Token refreshed\", and the request still succeeds.")) {
                Button("Fetch new token → updateToken()") { model.refreshTokenNow() }
                Button("Simulate expired token") { model.simulateExpiredToken() }
            }
            Section(header: Text("Session"),
                    footer: Text("dispose() stops sharing, ends the Live Activity and closes the socket. Every call after it returns NotInitialized instead of crashing.")) {
                Button("realtime.stop() (close socket)") { model.stopRealtime() }
                Button("initialize() again (replaces session)") { model.reinitialize() }
                Button("dispose() and log out", role: .destructive) { model.logout() }
            }
            Section(header: HStack { Text("Event log"); Spacer(); Button("Clear") { model.clearLog() } }) {
                ForEach(Array(model.log.enumerated()), id: \.offset) { Text($0.element).font(.caption.monospaced()) }
            }
        }
    }
}
