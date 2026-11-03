import SwiftUI
import TrackMeSDK

/// The showcase: a ready-to-use "share my trip live" feature on a Baato map.
/// No trip → start or join. Invite pasted → see who's there, then join. In a trip → members live, share/pause,
/// invite, leave (member) or end (owner). Built only from the same SDK calls as the developer screens.
struct LiveMapView: View {
    @EnvironmentObject var model: SampleModel
    @State private var recenter = 0
    @State private var panelExpanded = true
    @State private var panelHeight: CGFloat = 0
    @State private var showLocationSheet = false
    @State private var userLocated = false
    private let poll = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    private var granted: Bool { model.permission?.isGranted == true }

    var body: some View {
        ZStack(alignment: .bottom) {
            if Baato.isConfigured {
                BaatoMapView(markers: markers, fitKey: fitKey, recenterRequest: recenter,
                             focus: model.mapFocus, bottomInset: panelHeight,
                             onMarkerTap: { model.focusOnMember($0) }, showsUser: granted,
                             onUserLocated: { userLocated = $0 })
                    .ignoresSafeArea()
            } else {
                MissingBaatoKeyView()
            }

            VStack {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 8) {
                        if model.mapDetail?.trip.isLive == true || model.invite != nil { LivePill() }
                        LocationPill { showLocationSheet = true }
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 8) {
                        Button("Log out") { model.logout() }
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(.regularMaterial, in: Capsule())
                        // "Show me". Without location access it asks (or explains, when only Settings can help).
                        LocateButton(state: locateState) {
                            if granted { recenter += 1 }
                            else if model.locationCanAsk && model.permission?.servicesEnabled == true { model.askLocation() }
                            else { showLocationSheet = true }
                        }
                    }
                }
                .padding(12)
                Spacer()
            }

            MapPanel(expanded: $panelExpanded)
        }
        .onPreferenceChange(PanelRestingHeightKey.self) { panelHeight = $0 }
        // Tapping a member drops the panel to its peek, so they're visible.
        .onChange(of: model.mapFocus) { _ in panelExpanded = false }
        // Every time the map appears. Location is asked once by itself if never decided; after that only from
        // ◎, "Allow location" or the location pill. Never nags.
        .onAppear { model.autoAskLocationIfNeeded(); model.mapRefresh() }
        .sheet(isPresented: $showLocationSheet) { LocationAccessSheet().environmentObject(model).mediumDetent() }
        // Fallback next to the socket: while no live position arrives, refresh the trip snapshot every 5 s.
        .onReceive(poll) { _ in model.mapPoll() }
    }

    private var locateState: LocateState {
        if !granted || model.permission?.servicesEnabled == false { return .disabled }
        return userLocated ? .located : .searching
    }

    private var fitKey: String? {
        if let i = model.invite { return "invite:\(i.viewToken ?? ""):\(i.code ?? "")" }
        return model.mapDetail.map { "trip:\($0.trip.id)" }
    }

    private var markers: [MapMarker] {
        // Watching an invite: whatever the view link shows.
        if model.invite != nil {
            return model.viewerPositions.sorted(by: { $0.key < $1.key }).map { key, p in
                MapMarker(id: "v\(key)", label: firstName(p.fullName), latitude: p.latitude, longitude: p.longitude, color: markerColor(key))
            }
        }
        guard let d = model.mapDetail else { return [] }
        let myName = model.session?.fullName
        return d.members.compactMap { m in
            // You are the blue dot when location is allowed.
            if granted, let name = m.fullName, name == myName { return nil }
            // The newer of the live socket position and the snapshot from trips.detail().
            let live = model.newestLive(m, tripId: d.trip.id, loadedAt: model.mapLoadedAt, now: Date())
            guard let lat = live?.latitude ?? m.lat?.doubleValue, let lon = live?.longitude ?? m.lon?.doubleValue else { return nil }
            return MapMarker(id: "u\(m.userId)", label: firstName(m.fullName), latitude: lat, longitude: lon, color: markerColor("\(m.userId)"))
        }
    }
}

func firstName(_ name: String?) -> String {
    (name ?? "Member").split(separator: " ").first.map(String.init) ?? "Member"
}

private struct LivePill: View {
    @EnvironmentObject var model: SampleModel

    var body: some View {
        let (text, color): (String, Color) = {
            switch model.realtimeState {
            case is RealtimeState.Connected: return (model.invite != nil ? "● Watching" : "● Live", .green)
            case is RealtimeState.Reconnecting: return ("Reconnecting…", .orange)
            case is RealtimeState.Connecting: return ("Connecting…", .orange)
            default: return ("Offline", .gray)
            }
        }()
        Text(text).font(.subheadline.weight(.semibold)).foregroundColor(color)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(.regularMaterial, in: Capsule())
    }
}

// MARK: bottom panel

/// The panel is a bottom sheet: drag it down to a small peek (title + status) to see more map, up for everything.
/// Hand-made because iOS 15 has no sheet detents, and this panel isn't a modal anyway.
private struct MapPanel: View {
    @EnvironmentObject var model: SampleModel
    @Binding var expanded: Bool
    @State private var contentHeight: CGFloat = 320
    @GestureState private var drag: CGFloat = 0

    /// Collapsed: drag handle + the panel's title and one line.
    private let peek: CGFloat = 92
    private let handleHeight: CGFloat = 25

    private var visibleHeight: CGFloat {
        let full = max(peek, contentHeight)
        let base = expanded ? full : peek
        return min(full, max(peek, base - drag))
    }

    /// A different panel (start → trip → ended, or an invite) opens fully, so its buttons are visible.
    private var panelKind: String {
        if model.invite != nil { return "invite" }
        if let d = model.mapDetail { return d.trip.isLive ? "trip:\(d.trip.id)" : "ended" }
        return "start"
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(Color.secondary.opacity(0.4)).frame(width: 40, height: 5)
                .padding(.top, 8).padding(.bottom, 12)
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
                .onTapGesture { expanded.toggle() }

            VStack(alignment: .leading, spacing: 12) {
                if let invite = model.invite {
                    InvitePanel(invite: invite)
                } else if let d = model.mapDetail, d.trip.isLive {
                    TripPanel(detail: d)
                } else if let d = model.mapDetail {
                    EndedPanel(detail: d)
                } else {
                    StartPanel()
                }
                if model.busy { ProgressView().frame(maxWidth: .infinity) }
            }
            .padding(.horizontal, 20).padding(.bottom, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)          // natural height…
            .background(GeometryReader { g in Color.clear.preference(key: PanelHeightKey.self, value: g.size.height) })
            .frame(height: visibleHeight, alignment: .top)          // …shown up to the current detent
            .clipped()
        }
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.15), radius: 12, y: -2)
                .ignoresSafeArea(edges: .bottom)
        )
        .onPreferenceChange(PanelHeightKey.self) { contentHeight = $0 }
        // Resting height (not the in-drag one): the map keeps its centre above this.
        .preference(key: PanelRestingHeightKey.self, value: (expanded ? max(peek, contentHeight) : peek) + handleHeight)
        .gesture(
            // .global: the panel changes height under the finger; measuring in its own (moving) space made the
            // translation jump every frame, which was the flicker.
            DragGesture(minimumDistance: 12, coordinateSpace: .global)
                .updating($drag) { value, state, _ in state = value.translation.height }
                .onEnded { value in
                    let h = value.predictedEndTranslation.height
                    if h > 60 { expanded = false } else if h < -60 { expanded = true }
                }
        )
        .animation(.interactiveSpring(response: 0.3, dampingFraction: 0.85), value: expanded)
        .onChange(of: panelKind) { _ in expanded = true }
    }
}

private struct PanelRestingHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct PanelHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct StartPanel: View {
    @EnvironmentObject var model: SampleModel
    @State private var mode = ""   // "", "start", "join"
    @State private var name = ""
    @State private var minutes = 60
    @State private var invite = ""

    var body: some View {
        Text("Share your trip, live").font(.title2.bold())
        Text("Start a trip and invite people, or join someone else's.").font(.subheadline).foregroundColor(.secondary)
        HStack(spacing: 12) {
            Button { mode = "start" } label: { Text("Start a trip").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
            Button { mode = "join" } label: { Text("Join").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
        }
        .controlSize(.large)

        if mode == "start" {
            TextField("Trip name (optional)", text: $name).textFieldStyle(PanelFieldStyle())
            DurationPicker(presets: [("30 min", 30), ("1 h", 60), ("2 h", 120), ("4 h", 240)], minutes: $minutes)
                .textFieldStyle(PanelFieldStyle())
            Button { model.mapCreate(name: name, minutes: minutes) } label: {
                Text("Start & share my location").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
            .disabled(minutes < 0)
        } else if mode == "join" {
            HStack {
                TextField("Join code or invite message", text: $invite)
                    .textFieldStyle(PanelFieldStyle()).textInputAutocapitalization(.never).disableAutocorrection(true)
                Button("Paste") { invite = UIPasteboard.general.string ?? invite }
            }
            Button { model.mapOpenInvite(invite) } label: { Text("Continue").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
                .disabled(invite.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }
}

private struct InvitePanel: View {
    @EnvironmentObject var model: SampleModel
    let invite: Invite
    @State private var code = ""

    var body: some View {
        Text(invite.preview?.name ?? "Shared trip").font(.title2.bold())
        Text(subtitle).font(.subheadline).foregroundColor(.secondary)
        if invite.viewToken != nil && !model.viewerEnded {
            Text(model.viewerPositions.isEmpty ? "Waiting for positions…" : "\(model.viewerPositions.count) on the map, live").font(.caption)
        }
        if invite.code == nil {
            TextField("Join code", text: $code).textFieldStyle(PanelFieldStyle()).textInputAutocapitalization(.characters)
        }
        // The server sends each upload to EVERY live trip you're in (the API has no per-trip choice yet), so joining
        // while you share elsewhere starts sharing to this trip too. Say so instead of doing it silently.
        if model.sharing.isActive {
            Text("You're sharing your location right now, so joining adds this trip to it. Pause or stop sharing first to join without sharing.")
                .font(.caption).foregroundColor(.red)
        }
        // Join shows everyone; sharing your own location is a separate button once you're in.
        HStack(spacing: 12) {
            Button { model.mapJoin(code: invite.code ?? code) } label: { Text("Join").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
                .disabled((invite.code ?? code).isEmpty || model.viewerEnded)
            Button { model.mapCancelInvite() } label: { Text("Close").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
        }
        .controlSize(.large)
    }

    private var subtitle: String {
        if model.viewerEnded { return "This trip has ended." }
        if let p = invite.preview { return "by \(p.ownerName ?? "someone") · \(p.memberCount) members" }
        return "Watching the shared link."
    }
}

private struct TripPanel: View {
    @EnvironmentObject var model: SampleModel
    let detail: TripDetail
    @State private var confirm = false
    @State private var removing: TripMember?
    @State private var now = Date()
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var trip: Trip { detail.trip }
    private var remaining: Int64 { trip.remainingSeconds - Int64(now.timeIntervalSince(model.mapLoadedAt)) }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(trip.name).font(.title2.bold()).lineLimit(1)
            Text("Ends in \(formatSeconds(remaining)) · \(detail.members.count) \(detail.members.count == 1 ? "person" : "people")")
                .font(.subheadline.monospacedDigit()).foregroundColor(.secondary)
            // Time left, draining: same as the Live Activity.
            ProgressView(value: Double(max(0, min(remaining, trip.plannedSeconds))), total: Double(max(1, trip.plannedSeconds)))
                .padding(.top, 6)
        }
        .onReceive(tick) { now = $0 }

        // More than one live trip: pick which one the map shows.
        if model.mapLiveTrips.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(model.mapLiveTrips, id: \.id) { t in
                        Button(t.name) { model.mapSelect(t.id) }
                            .buttonStyle(.bordered).tint(t.id == trip.id ? .accentColor : .gray)
                    }
                }
            }
        }

        // How others join: the code, big, with one-tap copy and share. (The share text also carries the view link.)
        InviteCard(joinCode: trip.joinCode)

        if trip.isOwner && detail.members.count > 1 {
            Text("Tap someone to find them · long-press to remove").font(.caption2).foregroundColor(.secondary)
        }
        // Who's here.
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(detail.members, id: \.userId) { m in
                    // Tap → the map flies to them (to you, for your own chip). Owner: long-press → remove them.
                    HStack(spacing: 6) {
                        Circle().fill(Color(markerColor("\(m.userId)"))).frame(width: 10, height: 10)
                        Text("\(firstName(m.fullName))\(m.isOwner ? " ★" : "") · \(status(m))").font(.caption.weight(.medium))
                    }
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Color(.secondarySystemBackground), in: Capsule())
                    .contentShape(Capsule())
                    .onTapGesture { model.focusOnMember("u\(m.userId)") }
                    .onLongPressGesture(minimumDuration: 0.5) {
                        guard trip.isOwner, !m.isOwner else { return }
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        removing = m
                    }
                }
            }
        }

        // Your location.
        if !model.sharing.isActive, let denied = model.mapSharingError as? TrackMeError.LocationPermissionDenied {
            Button { model.mapAllowLocation(canAskAgain: denied.canAskAgain) } label: {
                Text(denied.canAskAgain ? "Allow location to share" : "Allow location in Settings").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
        } else {
            Button { model.mapToggleSharing() } label: { Text(sharingLabel).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
                .tint(sharingActive ? .green : .accentColor)

            if let error = model.mapSharingError, !model.sharing.isActive, !(error is TrackMeError.LocationPermissionDenied) {
                Text(error.message).font(.caption).foregroundColor(.red)
            }
        }

        Button(role: .destructive) { confirm = true } label: { Text(trip.isOwner ? "End trip" : "Leave trip").frame(maxWidth: .infinity) }
            .buttonStyle(.bordered)
            .controlSize(.large)
        .alert("Remove \(removing?.fullName ?? "member")?",
               isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Remove", role: .destructive) { if let m = removing { model.mapRemoveMember(m.userId) } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("They're taken out of the trip and stop seeing it.") }
        .alert(trip.isOwner ? "End this trip?" : "Leave this trip?", isPresented: $confirm) {
            Button(trip.isOwner ? "End trip" : "Leave", role: .destructive) { trip.isOwner ? model.mapEnd() : model.mapLeave() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(trip.isOwner ? "Everyone stops sharing and the trip closes for all members."
                              : "You stop sharing with this trip. You can join again with the code.")
        }
    }

    /// "live" only while positions keep arriving: a paused member turns into "50 s ago" by itself (`now` ticks).
    private func status(_ m: TripMember) -> String {
        model.memberStatus(m, tripId: trip.id, loadedAt: model.mapLoadedAt, now: now)
    }

    private var sharingActive: Bool { model.sharing is SharingState.Sharing || model.sharing is SharingState.Starting }

    private var sharingLabel: String {
        switch model.sharing {
        case is SharingState.Sharing: return "● Sharing your location · tap to pause"
        case is SharingState.Starting: return "Starting…"
        case is SharingState.Paused: return "Paused · tap to resume"
        default: return "Share my location too"
        }
    }
}

/// The join code in large type, with Copy (code only), Copy invite (link + code) and Share.
private struct InviteCard: View {
    @EnvironmentObject var model: SampleModel
    let joinCode: String?
    @State private var copied: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(joinCode != nil ? "Join code" : "Invite people").font(.caption.weight(.medium)).foregroundColor(.secondary)
                    if let joinCode {
                        Text(joinCode).font(.system(.title, design: .monospaced).weight(.bold)).kerning(4).textSelection(.enabled)
                    }
                }
                Spacer()
                if let joinCode {
                    Button("Copy") { UIPasteboard.general.string = joinCode; flash("Code copied") }.buttonStyle(.borderless)
                }
            }
            HStack(spacing: 8) {
                Button { model.mapShareInvite(copyOnly: true); flash("Invite copied") } label: { Text("Copy invite").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
                Button { model.mapShareInvite() } label: { Label("Share", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
            }
            if let copied { Text(copied).font(.caption) }
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func flash(_ text: String) {
        copied = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { if copied == text { copied = nil } }
    }
}

private struct EndedPanel: View {
    @EnvironmentObject var model: SampleModel
    let detail: TripDetail

    var body: some View {
        Text("Trip ended").font(.title2.bold())
        Text("\(detail.trip.name) · \(detail.trip.endedReason ?? "ended") · lasted \(formatSeconds(detail.trip.actualSeconds))")
            .font(.subheadline).foregroundColor(.secondary)
        Button { model.mapDone() } label: { Text("Done").frame(maxWidth: .infinity) }
            .buttonStyle(.borderedProminent).controlSize(.large)
    }
}

/// A tall, filled text field that stays visible on the panel in light and dark mode
/// (`.roundedBorder` is small and nearly matches the panel's background in dark mode).
struct PanelFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .font(.body)
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Color(.separator), lineWidth: 0.5))
    }
}
