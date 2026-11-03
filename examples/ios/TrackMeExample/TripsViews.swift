import SwiftUI
import TrackMeSDK

// MARK: Trips tab: create, join, list

struct TripsTab: View {
    @EnvironmentObject var model: SampleModel
    @State private var tripName = ""
    @State private var minutes = 60
    @State private var code = ""

    var body: some View {
        Form {
            Section(header: Text("Create a trip · trips.create"), footer: Text("The trip ends by itself when the duration runs out.")) {
                TextField("Name (optional)", text: $tripName)
                DurationPicker(presets: [("5 min", 5), ("15 min", 15), ("1 h", 60), ("4 h", 240)], minutes: $minutes)
                Button("Create") { model.createTrip(name: tripName, minutes: minutes) }
                    .disabled(minutes < 0)
                if let c = model.created {
                    Text("Join code: \(c.joinCode)").font(.title2.monospaced())
                    Text("Ends in \(formatSeconds(c.remainingSeconds))").font(.caption)
                    HStack {
                        Button("Copy code") { UIPasteboard.general.string = c.joinCode }
                        Spacer()
                        Button("Share") { shareText("Follow me on TrackMe: \(c.viewLink)\nJoin code: \(c.joinCode)") }
                        Spacer()
                        Button("Open") { model.openTrip(c.id) }
                    }
                    .buttonStyle(.borderless)
                }
            }

            Section("Join with a code · trips.preview / join") {
                TextField("Join code", text: $code).textInputAutocapitalization(.characters).disableAutocorrection(true)
                HStack {
                    Button("Preview") { model.previewTrip(code: code) }
                    Spacer()
                    Button("Join") { model.joinTrip(code: code) }
                }
                .buttonStyle(.borderless)
                .disabled(code.trimmingCharacters(in: .whitespaces).isEmpty)
                if let p = model.preview {
                    Text("“\(p.name)” by \(p.ownerName ?? "?") · \(p.memberCount) members · ends \(shortDate(p.expiresAt))")
                }
            }

            Section(header: HStack { Text("My trips · trips.list"); Spacer(); Button("Refresh") { model.loadTrips() } },
                    footer: Text("Tap a trip for members, live positions, links, history, rename, end, leave and delete.")) {
                if model.trips.isEmpty { Text("No trips yet. Create one, or join with a code from another device.").font(.caption) }
                ForEach(model.trips, id: \.id) { trip in
                    Button { model.openTrip(trip.id) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack { Text(trip.name).bold(); Spacer(); StatusBadge(trip: trip) }
                            Text("\(trip.myRole) · \(trip.memberCount) members" + (trip.isLive ? " · \(formatSeconds(trip.remainingSeconds)) left" : ""))
                                .font(.caption)
                            if trip.isLive, let jc = trip.joinCode { Text("Code: \(jc)").font(.caption) }
                        }
                    }
                    .foregroundColor(.primary)
                }
            }
        }
    }
}

// MARK: Trip screen: everything about one trip

private enum TripAction: Identifiable {
    case end, leave, delete, rotate, remove(TripMember)
    var id: String {
        switch self {
        case .end: return "end"
        case .leave: return "leave"
        case .delete: return "delete"
        case .rotate: return "rotate"
        case .remove(let m): return "remove-\(m.userId)"
        }
    }
}

struct TripDetailView: View {
    @EnvironmentObject var model: SampleModel
    let detail: TripDetail
    @State private var confirm: TripAction?
    @State private var renaming = false
    @State private var newName = ""
    @State private var hint: String?
    @State private var now = Date()
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var trip: Trip { detail.trip }
    /// remainingSeconds is from load time; count down locally.
    private var remaining: Int64 { trip.remainingSeconds - Int64(now.timeIntervalSince(model.detailLoadedAt)) }

    var body: some View {
        NavigationView {
            List {
                overview
                live
                members
                yourLocation
                viewLink
                historySection
                manage
            }
            .navigationTitle(trip.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { model.closeTrip() } }
                ToolbarItem(placement: .primaryAction) { Button("Refresh") { model.refreshTrip() } }
            }
            .onReceive(tick) { now = $0 }
            .alert(item: $confirm) { action in confirmAlert(action) }
            .sheet(isPresented: $renaming) { renameSheet }
        }
        .navigationViewStyle(.stack)
    }

    // MARK: sections

    private var overview: some View {
        Section("Trip · trips.detail") {
            HStack { Text("You are \(trip.myRole) (\(trip.myStatus))"); Spacer(); StatusBadge(trip: trip) }
            Text("\(trip.memberCount) members · started \(shortDate(trip.startedOn)) UTC").font(.caption)
            if trip.isLive {
                Text("Ends in \(formatSeconds(remaining))").font(.headline.monospacedDigit())
                Text("Planned \(formatSeconds(trip.plannedSeconds)). When it runs out the server ends the trip and everyone gets trip_ended.")
                    .font(.caption)
                if let code = trip.joinCode {
                    HStack {
                        Text("Join code: \(code)").bold()
                        Spacer()
                        Button("Copy") { UIPasteboard.general.string = code }
                        Button("Share") { shareText("Join my TrackMe trip “\(trip.name)” with code \(code)") }
                    }
                    .buttonStyle(.borderless)
                }
            } else {
                Text("Ended \(shortDate(trip.endedOn)) · \(trip.endedReason ?? "") · lasted \(formatSeconds(trip.actualSeconds))")
            }
        }
    }

    private var live: some View {
        Section(header: Text("Live updates · realtime.watchTrip"),
                footer: Text(trip.isLive
                             ? "Members' positions update as they move (only while they share). Joins refresh the list; the screen reacts to trip_ended and to being removed."
                             : "Live updates are only available while the trip is live.")) {
            Toggle(model.liveStatus, isOn: Binding(get: { model.watchingLive }, set: { model.setWatchLive($0) }))
                .disabled(!trip.isLive)
        }
    }

    private var members: some View {
        Section("Members (\(detail.members.count))") {
            ForEach(detail.members, id: \.userId) { m in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        let isMe = m.fullName != nil && m.fullName == model.session?.fullName
                        Text("\(m.fullName ?? "User \(m.userId)")\(isMe ? " (you)" : "")").bold()
                        Text("\(m.role) · id \(m.userId)").font(.caption)
                        // The newer of the live socket position and the snapshot; "live" only while positions keep coming.
                        let status = model.memberStatus(m, tripId: trip.id, loadedAt: model.detailLoadedAt, now: Date())
                        if let p = model.newestLive(m, tripId: trip.id, loadedAt: model.detailLoadedAt, now: Date()) {
                            Text(String(format: "%.5f, %.5f · ", p.latitude, p.longitude) + status
                                 + (p.speedMetersPerSecond.map { String(format: " · %.1f m/s", $0.doubleValue) } ?? ""))
                                .font(.caption)
                        } else if m.hasPosition, let lat = m.lat, let lon = m.lon {
                            Text(String(format: "%.5f, %.5f · ", lat.doubleValue, lon.doubleValue) + status).font(.caption)
                        } else {
                            Text("no position yet").font(.caption)
                        }
                    }
                    Spacer()
                    if trip.isOwner && trip.isLive && !m.isOwner {
                        Button("Remove", role: .destructive) { confirm = .remove(m) }.buttonStyle(.borderless)   // trips.removeMember
                    }
                }
            }
        }
    }

    @ViewBuilder private var yourLocation: some View {
        if trip.isLive {
            Section(header: Text("Your location · sharing"),
                    footer: Text("Sharing goes to every live trip you're in. Options and pause are on the Share tab.")) {
                Text(sharingLine)
                if let hint { Text(hint).foregroundColor(.red) }
                if model.sharing.isActive {
                    Button("Stop sharing", role: .destructive) { model.stopSharing() }
                } else {
                    Button("Start sharing") { hint = model.startSharing() }
                }
            }
        }
    }

    private var sharingLine: String {
        switch model.sharing {
        case let s as SharingState.Sharing:
            return s.tripIds.contains(where: { $0.int64Value == trip.id }) ? "Sharing to this trip" : "Sharing (waiting for the server to include this trip)"
        case is SharingState.Paused: return "Paused"
        case is SharingState.Starting: return "Starting…"
        default: return "Not sharing: members can't see you"
        }
    }

    private var viewLink: some View {
        Section(header: Text("Public view link · trips.viewLink / rotateViewLink"),
                footer: Text("Anyone with this link can watch in a browser, no login.")) {
            if let link = model.viewLink {
                Text(link).font(.caption).textSelection(.enabled)
                HStack {
                    Button("Copy") { UIPasteboard.general.string = link }
                    Spacer()
                    Button("Share") { shareText("Watch \(trip.name) live: \(link)") }
                    Spacer()
                    Button("Watch in app") { model.watchLinkInViewer() }
                    if trip.isOwner { Spacer(); Button("Rotate") { confirm = .rotate } }
                }
                .buttonStyle(.borderless)
            } else {
                Button("Get link") { model.loadViewLink() }
            }
        }
    }

    private var historySection: some View {
        Section("History · trips.history") {
            HStack {
                Button("Last 15 min") { model.loadHistory(lastMinutes: 15) }
                Spacer()
                Button("Whole trip") { model.loadHistory(lastMinutes: nil) }
                if model.history != nil { Spacer(); Button("Clear") { model.history = nil } }
            }
            .buttonStyle(.borderless)
            if let json = model.history {
                Text("\(json.count) characters of raw JSON (untyped until the format settles):").font(.caption)
                Text(String(json.prefix(2_000)) + (json.count > 2_000 ? "…" : "")).font(.caption2.monospaced())
            }
        }
    }

    private var manage: some View {
        Section(header: Text(trip.isOwner ? "Manage (owner)" : "Manage"),
                footer: Text(manageFooter)) {
            if trip.isOwner {
                Button("Rename") { newName = trip.name; renaming = true }                          // trips.rename
                if trip.isLive { Button("End trip", role: .destructive) { confirm = .end } }         // trips.end
                Button("Delete trip", role: .destructive) { confirm = .delete }.disabled(trip.isLive) // trips.delete
            } else if trip.isLive {
                Button("Leave trip", role: .destructive) { confirm = .leave }                         // trips.leave
            }
        }
    }

    private var manageFooter: String {
        if !trip.isOwner { return "Only the owner can rename, end, remove members or delete." }
        return trip.isLive ? "Delete is available once the trip has ended (end it, or wait for the time to run out)."
                           : "This trip has ended; you can delete it."
    }

    // MARK: dialogs

    private func confirmAlert(_ action: TripAction) -> Alert {
        let (title, text, button, run): (String, String, String, () -> Void) = {
            switch action {
            case .end: return ("End trip?", "Everyone stops sharing to this trip and members are notified.", "End trip", model.endTrip)
            case .leave: return ("Leave trip?", "You can join again with the code while it's live.", "Leave", model.leaveTrip)
            case .delete: return ("Delete trip?", "Removes the trip and its history for everyone.", "Delete", model.deleteTrip)
            case .rotate: return ("New public link?", "The current link stops working for everyone who has it.", "Rotate", model.rotateViewLink)
            case .remove(let m):
                return ("Remove \(m.fullName ?? "member")?", "They're taken out of the trip.", "Remove", { model.removeMember(m.userId) })
            }
        }()
        return Alert(title: Text(title), message: Text(text),
                     primaryButton: .destructive(Text(button), action: run), secondaryButton: .cancel())
    }

    private var renameSheet: some View {
        NavigationView {
            Form { TextField("Name", text: $newName) }
                .navigationTitle("Rename trip")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { renaming = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { renaming = false; model.renameTrip(newName) }
                            .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
        }
    }
}
