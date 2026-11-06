import CoreLocation
import Foundation
import TrackMeSDK
import UIKit
import UserNotifications
import TrackMeLiveActivity

struct SampleError: Error { let message: String }

/// Unwraps a Kotlin `TrackMeResult<T>`: Success → value, Failure → throws with the SDK's message.
/// (Kotlin sealed classes appear in Swift as subclasses: TrackMeResultSuccess / TrackMeResultFailure.)
func unwrap<T: AnyObject>(_ result: TrackMeResult<T>) throws -> T {
    // Cast through AnyObject: Objective-C generics are erased at runtime, so the subclass check is what matters.
    if let success = (result as AnyObject) as? TrackMeResultSuccess<T>, let data = success.data { return data }
    if let failure = (result as AnyObject) as? TrackMeResultFailure { throw SampleError(message: failure.error.message) }
    throw SampleError(message: "Unexpected result")
}

enum SampleTab: Hashable { case trips, sharing, viewer, inbox, sdk }

/// Top level: the ready-to-use live map, and (dev-tools builds only) the developer screens.
enum SampleSection: Hashable { case map, developer }

/// An invite pasted into the live map: "Follow me on TrackMe: https://…/trackme/s/<token>\nJoin code: ABC123", a link or a bare code.
struct Invite: Equatable {
    var viewToken: String?
    var code: String?
    var preview: TripPreview?

    static func parse(_ text: String) -> Invite? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let token = firstMatch(#"/s/([A-Za-z0-9_\-]+)"#, in: t)
        let code = firstMatch(#"(?i)code[:\s]+([A-Za-z0-9]{4,16})"#, in: t)
            ?? (t.range(of: #"^[A-Za-z0-9]{4,16}$"#, options: .regularExpression) != nil ? t : nil)
        if token == nil && code == nil { return nil }
        return Invite(viewToken: token, code: code?.uppercased())
    }

    private static func firstMatch(_ pattern: String, in text: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let r = Range(m.range(at: 1), in: text) else { return nil }
        return String(text[r])
    }
}

/// The text the live map shares; the same shape Invite.parse reads back.
func inviteText(tripName: String, viewLink: String?, joinCode: String?) -> String {
    var t = "Follow me on TrackMe"
    if let viewLink { t += ": \(viewLink)" }
    if let joinCode { t += "\nJoin code: \(joinCode)" }
    if viewLink == nil && joinCode == nil { t += " (\(tripName))" }
    return t
}

/// What the login screen collects: who to log in as, and how the Live Activity / notification looks.
struct LoginForm: Codable {
    var email: String
    var fullName: String
    var title = "Sharing your location"
    var text = "Trip members can see where you are."
    var accentColor: Int64 = 0xFF1565C0
    var showPause = true
    var showStop = true
}

/// Every SDK call in the iOS sample goes through here, so this file doubles as a Swift usage reference.
@MainActor
final class SampleModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var session: LoginForm?   // who is logged in (nil = SDK not initialized)
    @Published var busy = false
    @Published var message: String?
    @Published var section: SampleSection = .map
    @Published var tab: SampleTab = .trips

    // live map (showcase)
    /// The trip the live map shows (the most recent live trip, or the one picked / just created / joined).
    @Published var mapDetail: TripDetail?
    @Published var mapLoadedAt = Date()
    @Published var mapLiveTrips: [Trip] = []
    @Published var mapViewLink: String?
    @Published var invite: Invite?
    /// Why sharing couldn't start from the map (e.g. no permission); the panel offers a fix.
    @Published var mapSharingError: TrackMeError?
    /// The trip TrackMe.realtime is watching; `livePositions` belong to it. Shared by the map and the trip screen.
    @Published var liveTripId: Int64?
    /// When the last live (socket) position arrived.
    private var lastLivePositionAt = Date.distantPast
    /// The member the live map should fly to (a tapped chip or dot).
    @Published var mapFocus: MapFocus?
    private var pendingMapShare = false

    // trips
    @Published var trips: [Trip] = []
    @Published var created: CreatedTrip?
    @Published var preview: TripPreview?

    // open trip
    @Published var detail: TripDetail?
    @Published var detailLoadedAt = Date()
    @Published var viewLink: String?
    @Published var watchingLive = false
    /// Latest live position per member of the open trip (TrackMe.realtime). Key = user id.
    @Published var livePositions: [Int64: MemberPosition] = [:]
    /// When each of `livePositions` arrived: a position only says "live" while new ones keep coming.
    @Published var livePositionAt: [Int64: Date] = [:]
    /// Sharing state each member reported (TrackMeEvent.MemberSharingChanged, Kotlin enum name) and when it arrived.
    @Published var memberSharing: [Int64: (state: String, at: Date)] = [:]
    @Published var history: String?

    // sharing
    @Published var permission: LocationPermissionStatus?
    /// The map asked for location once this session (it asks by itself only once, so it never nags).
    private var locationAutoAsked = false
    @Published var sharing: SharingState = SharingState.Idle.shared
    @Published var options = SharingOptions(intervalMillis: 5_000, minDistanceMeters: 5, heartbeatMillis: 30_000, highAccuracy: true)

    // viewer
    @Published var viewerInput = ""
    @Published var viewerWatching = false
    @Published var viewerEnded = false
    @Published var viewerPositions: [String: MemberPosition] = [:]

    // inbox
    @Published var notifications: [TrackMeNotification] = []
    @Published var nextCursor: String?
    @Published var hasMore = false
    @Published var unreadOnly = false

    // sdk
    @Published var sdkState = "NotInitialized"
    @Published var realtimeState: RealtimeState = RealtimeState.Idle.shared
    @Published var liveStatus = "Off"
    @Published var health: String?
    @Published var log: [String] = []

    var loggedIn: Bool { session != nil }

    private let locationManager = CLLocationManager()   // used ONLY to ask for permission: the app owns that UI
    private var observers: [TrackMeCancellable] = []

    override init() {
        super.init()
        locationManager.delegate = self
        LiveActivityPresenter.install()   // lock-screen Live Activity / iOS 15 notification + its buttons

        // Dev builds: SDK diagnostics (socket handshake, joins, every live message) in the console and the SDK log.
        if AppConfig.devTools {
            TrackMe.shared.debugLogger = { line in
                print("TrackMe:", line)
                DispatchQueue.main.async { [weak self] in self?.append("sdk  \(line)") }
            }
        }

        // Kotlin flows → callbacks on the main thread (see TrackMeIos in the SDK).
        observers.append(TrackMeIos.shared.observeSharing { [weak self] state in
            self?.sharing = state
            self?.append("Sharing: \(String(describing: type(of: state)))")
        })
        observers.append(TrackMeIos.shared.observeEvents { [weak self] event in self?.onEvent(event) })
        observers.append(TrackMeIos.shared.observeRealtime { [weak self] state in
            guard let self else { return }
            self.realtimeState = state
            switch state {
            case is RealtimeState.Connected: self.liveStatus = "● Live"
            case let r as RealtimeState.Reconnecting: self.liveStatus = "Reconnecting (attempt \(r.attempt))…"
            case is RealtimeState.Connecting: self.liveStatus = "Connecting…"
            default: self.liveStatus = "Off"
            }
            self.append("Live: \(state)")
        })
        observers.append(TrackMeIos.shared.observeState { [weak self] state in
            self?.sdkState = String(describing: type(of: state))
            self?.append("SDK: \(String(describing: type(of: state)))")
        })
        restoreSession()
    }

    /// Who is logged in (the login form, not the token), so reopening the app goes straight back in. A real app keeps
    /// its own user session the same way and gets a fresh TrackMe token from its backend.
    private static let savedLoginKey = "trackme.savedLogin"

    /// Reopening the app: back to where the user was, without the login screen.
    /// - The SDK is still initialized (the app stayed alive in the background, e.g. while sharing): only the screens
    ///   come back. Calling initialize() again would replace the session and stop the sharing.
    /// - A fresh start: log in again with the saved form (a new token, as on first login).
    private func restoreSession() {
        guard let data = UserDefaults.standard.data(forKey: Self.savedLoginKey),
              let form = try? JSONDecoder().decode(LoginForm.self, from: data) else { return }
        if TrackMe.shared.isInitialized {
            session = form
            append("Session restored for \(form.email)")
            refreshPermission()
            loadTrips()
            mapRefresh()
        } else {
            login(form)
        }
    }

    deinit { observers.forEach { $0.cancel() } }

    /// One place for every TrackMeEvent.
    private func onEvent(_ event: TrackMeEvent) {
        switch event {
        case is TrackMeEvent.AuthenticationFailed:
            append("Auth failed: need a new token"); message = "Session expired: log in again."
        case is TrackMeEvent.TokenRefreshed:
            append("Token refreshed by the token provider")
        case let e as TrackMeEvent.SharingStopped:
            append("Sharing stopped: \(e.reason.name)")

        // ---- live updates of the open trip (TrackMe.realtime.watchTrip) ----
        case let e as TrackMeEvent.TripPosition:
            if let t = e.position.tripId?.int64Value, t != liveTripId { return }   // stale event from the previous trip
            livePositions[e.position.userId] = e.position
            livePositionAt[e.position.userId] = Date()
            lastLivePositionAt = Date()
        case let e as TrackMeEvent.MemberSharingChanged:
            append("\(e.displayName ?? "Member \(e.userId)"): \(e.state.name.lowercased())")
            if e.tripId == liveTripId { memberSharing[e.userId] = (e.state.name, Date()) }
        case let e as TrackMeEvent.MemberChanged:
            append("\(e.change.displayName ?? "Someone") \(e.change.change)")
            refreshTrip(); reloadMapTrip()
        case let e as TrackMeEvent.TripEnded:
            append("Trip \(e.tripId?.int64Value ?? 0) ended (\(e.reason ?? "no reason"))")
            watchingLive = false; liveTripId = nil
            refreshTrip(); reloadMapTrip(); loadTrips()   // screens stay open and show "ended"
        case let e as TrackMeEvent.Evicted:
            append("Removed from trip: \(e.reason ?? "")")
            liveTripId = nil
            closeTrip()
            let gone = e.tripId?.int64Value
            if gone == nil || mapDetail?.trip.id == gone { mapDetail = nil }
            loadTrips(); mapRefresh()
            message = "You're no longer in this trip (\(e.reason ?? "removed"))."

        // ---- viewer tab (TrackMe.realtime.watchShare) ----
        case let e as TrackMeEvent.SharePosition:
            let key = "\(e.position.userId)"
            viewerPositions[key] = e.position
        case is TrackMeEvent.ShareEnded:
            append("View link ended"); viewerWatching = false; viewerEnded = true
        default:
            append("Event: \(event)")
        }
    }

    // MARK: lifecycle

    func login(_ form: LoginForm) {
        run("Logging in") {
            guard DemoAuth.isConfigured else { throw SampleError(message: "Set TRACKME_API_SECRET in sample-ios/Secrets.xcconfig (demo only).") }
            guard let token = await DemoAuth.login(email: form.email, fullName: form.fullName) else {
                throw SampleError(message: "Login failed (check email / secret / network).")
            }
            // Swift doesn't see Kotlin default arguments, so the full TrackMeSharingUi initializer lists everything.
            let ui = TrackMeSharingUi(
                title: form.title, text: form.text, pausedText: "Location sharing is paused.",
                accentColor: form.accentColor, showPauseAction: form.showPause, showStopAction: form.showStop,
                pauseLabel: "Pause", resumeLabel: "Resume", stopLabel: "Stop",
                androidSmallIcon: 0, androidChannelName: "Location sharing", iosSymbolName: "location.fill"
            )
            let config = TrackMeConfig(
                token: token,
                tokenProvider: DemoTokenProvider(email: form.email, fullName: form.fullName),
                sharingUi: ui
            )
            _ = try unwrap(TrackMe.shared.initialize(context: PlatformContext(), config: config))
            self.session = form
            UserDefaults.standard.set(try? JSONEncoder().encode(form), forKey: Self.savedLoginKey)
            self.append("Initialized as \(form.email)")
            self.refreshPermission()
            await self.loadTripsNow()
            await self.loadNotificationsNow(reset: true)
            self.mapRefresh()
        }
    }

    /// dispose(): stops sharing, ends the Live Activity, closes socket + HTTP. Safe to call twice.
    func logout() {
        TrackMe.shared.dispose()
        UserDefaults.standard.removeObject(forKey: Self.savedLoginKey)
        session = nil; trips = []; created = nil; preview = nil; detail = nil; permission = nil; locationAutoAsked = false
        livePositions = [:]; livePositionAt = [:]; memberSharing = [:]; viewerPositions = [:]; viewerWatching = false; notifications = []; health = nil
        mapDetail = nil; mapLiveTrips = []; mapViewLink = nil; invite = nil; mapSharingError = nil; liveTripId = nil
        append("Disposed")
    }

    /// initialize() again with the same user: replaces the session (the old one is fully disposed first).
    func reinitialize() { if let form = session { login(form) } }

    // MARK: permission (app-owned UI; the SDK only reports)

    func refreshPermission() {
        permission = TrackMe.shared.location.permissionStatus().getOrNull()
    }

    func requestWhenInUse() {
        locationManager.requestWhenInUseAuthorization()
        // Only needed for the iOS 15 / Live-Activities-off fallback notification.
        Task { _ = await TrackMeActivityController.shared.requestNotificationPermission() }
    }
    func requestAlways() { locationManager.requestAlwaysAuthorization() }

    var locationGranted: Bool { permission?.isGranted == true }
    /// iOS shows the location prompt only while the level is "not determined"; after that only Settings can change it.
    var locationCanAsk: Bool { permission?.level.isNotDetermined == true }

    /// The live map's one way to ask: the system prompt while iOS still shows it, otherwise Settings.
    func askLocation() {
        if locationCanAsk { requestWhenInUse() } else { openSettings() }
    }

    /// "Always": iOS offers the upgrade prompt once per install; after that it's a Settings switch.
    func askLocationAlways() {
        let key = "trackme.sample.askedAlways"
        if UserDefaults.standard.bool(forKey: key) {
            openSettings()
        } else {
            UserDefaults.standard.set(true, forKey: key)
            requestAlways()
        }
    }

    /// Called when the map appears: asks once, by itself, if location was never decided.
    func autoAskLocationIfNeeded() {
        refreshPermission()
        guard !locationAutoAsked, locationCanAsk, permission?.servicesEnabled == true else { return }
        locationAutoAsked = true
        requestWhenInUse()
    }

    func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            self.refreshPermission()
            // "Allow location" from the live map (or sharing failed for lack of it): start sharing once granted.
            let failedForPermission = self.mapSharingError is TrackMeError.LocationPermissionDenied
            if self.pendingMapShare || failedForPermission, self.permission?.isGranted == true {
                self.pendingMapShare = false
                self.startSharingFromMap()
            }
        }
    }

    // MARK: trips

    func loadTrips() { run("Loading trips") { await self.loadTripsNow() } }

    private func loadTripsNow() async {
        do {
            let list = try unwrap(try await TrackMe.shared.trips.list(limit: 50))
            trips = (list as? [Trip]) ?? []
        } catch { fail(error) }
    }

    func createTrip(name: String, minutes: Int) {
        run("Creating trip") {
            let trip = try unwrap(try await TrackMe.shared.trips.create(name: name.isEmpty ? nil : name, durationMin: Int32(minutes)))
            self.created = trip
            self.append("Created trip \(trip.id), code \(trip.joinCode)")
            await self.loadTripsNow()
        }
    }

    func previewTrip(code: String) {
        run("Looking up code") { self.preview = try unwrap(try await TrackMe.shared.trips.preview(code: code)) }
    }

    func joinTrip(code: String) {
        run("Joining") {
            let trip = try unwrap(try await TrackMe.shared.trips.join(code: code))
            self.preview = nil
            self.append("Joined trip \(trip.id)")
            await self.loadTripsNow()
            self.openTrip(trip.id)
        }
    }

    // MARK: open trip

    func openTrip(_ id: Int64) {
        run("Loading trip") {
            let d = try unwrap(try await TrackMe.shared.trips.detail(tripId: id))
            self.viewLink = nil; self.history = nil
            self.detail = d
            self.detailLoadedAt = Date()
            if d.trip.isLive { self.setWatchLive(true) }   // live positions while the screen is open
        }
    }

    /// Reload the open trip without resetting links, history or live positions.
    func refreshTrip() {
        guard let id = detail?.trip.id else { return }
        Task {
            if let d = try? unwrap(try await TrackMe.shared.trips.detail(tripId: id)), self.detail?.trip.id == id {
                self.detail = d
                self.detailLoadedAt = Date()
            }
        }
    }

    func closeTrip() {
        guard detail != nil else { return }
        _ = TrackMe.shared.realtime.stopWatchingTrip()
        detail = nil; watchingLive = false; liveTripId = nil; livePositions = [:]; livePositionAt = [:]; memberSharing = [:]; history = nil; viewLink = nil
    }

    func setWatchLive(_ on: Bool) {
        guard let id = detail?.trip.id else { return }
        guard on else {
            _ = TrackMe.shared.realtime.stopWatchingTrip()
            watchingLive = false
            return
        }
        watchLive(id) { ok in self.watchingLive = ok }
    }

    /// One socket watches one trip; switching trips clears the positions of the previous one.
    private func watchLive(_ tripId: Int64, onResult: @escaping (Bool) -> Void = { _ in }) {
        if liveTripId != tripId { liveTripId = tripId; livePositions = [:]; livePositionAt = [:]; memberSharing = [:] }
        Task {
            do {
                _ = try unwrap(try await TrackMe.shared.realtime.watchTrip(tripId: tripId))   // waits for the server's OK
                onResult(true)
            } catch {
                if self.liveTripId == tripId { self.liveTripId = nil }
                self.append("Live updates unavailable: \((error as? SampleError)?.message ?? "\(error)")")
                onResult(false)
            }
        }
    }

    func renameTrip(_ name: String) {
        withTrip("Renaming") { id in
            let t = try unwrap(try await TrackMe.shared.trips.rename(tripId: id, name: name))
            self.append("Renamed to \(t.name)"); self.refreshTrip(); await self.loadTripsNow()
        }
    }

    /// Owner only. Members get trip_ended over the socket; everyone's sharing to this trip stops.
    func endTrip() {
        withTrip("Ending trip") { id in
            _ = try unwrap(try await TrackMe.shared.trips.end(tripId: id))
            self.append("Ended trip \(id)"); self.refreshTrip(); await self.loadTripsNow()
        }
    }

    func leaveTrip() {
        withTrip("Leaving trip") { id in
            _ = try unwrap(try await TrackMe.shared.trips.leave(tripId: id))
            self.append("Left trip \(id)"); self.closeTrip(); await self.loadTripsNow()
        }
    }

    func removeMember(_ userId: Int64) {
        withTrip("Removing member") { id in
            _ = try unwrap(try await TrackMe.shared.trips.removeMember(tripId: id, userId: userId))
            self.append("Removed user \(userId)"); self.refreshTrip()
        }
    }

    /// Only for ended trips; a live one fails with Conflict ("end it first").
    func deleteTrip() {
        withTrip("Deleting trip") { id in
            _ = try unwrap(try await TrackMe.shared.trips.delete(tripId: id))
            self.append("Deleted trip \(id)"); self.closeTrip(); await self.loadTripsNow()
        }
    }

    func loadViewLink() {
        withTrip("Getting link") { id in self.viewLink = try unwrap(try await TrackMe.shared.trips.viewLink(tripId: id)).viewLink }
    }

    /// The old link stops working for everyone (viewers get share_ended).
    func rotateViewLink() {
        withTrip("Rotating link") { id in
            self.viewLink = try unwrap(try await TrackMe.shared.trips.rotateViewLink(tripId: id)).viewLink
            self.append("View link rotated")
        }
    }

    /// `lastMinutes == nil` → the whole trip. Times are ISO-8601 UTC.
    func loadHistory(lastMinutes: Int?) {
        withTrip("Loading history") { id in
            let from = lastMinutes.map { ISO8601DateFormatter().string(from: Date().addingTimeInterval(-Double($0) * 60)) }
            let json = try unwrap(try await TrackMe.shared.trips.history(tripId: id, from: from, to: nil))
            self.history = json as String
        }
    }

    /// Opens the Viewer tab with this trip's public link, to see what a non-member sees.
    func watchLinkInViewer() {
        guard let link = viewLink else { return }
        closeTrip()
        section = .developer
        tab = .viewer
        viewerInput = link
        watchShare()
    }

    // MARK: sharing

    /// Returns a hint for the UI when start fails (permission, location off, background, …).
    func startSharing() -> String? {
        defer { refreshPermission() }
        guard let error = tryStartSharing() else { return nil }
        if let denied = error as? TrackMeError.LocationPermissionDenied {
            return denied.canAskAgain ? "Tap \"While using\" first." : "Permission denied: open Settings."
        }
        if error is TrackMeError.LocationServicesDisabled { return "Turn on Location Services in Settings." }
        if error is TrackMeError.BackgroundNotAllowed { return "Start while the app is visible." }
        return error.message
    }

    /// nil = started (or already sharing).
    private func tryStartSharing() -> TrackMeError? {
        guard let failure = (TrackMe.shared.sharing.start(options: options) as AnyObject) as? TrackMeResultFailure else { return nil }
        append("Start failed: \(failure.error.message)")
        return failure.error
    }

    func pauseSharing() { _ = TrackMe.shared.sharing.pause() }
    func resumeSharing() { _ = TrackMe.shared.sharing.resume() }
    func stopSharing() { _ = TrackMe.shared.sharing.stop() }

    // MARK: viewer (public view link)

    func watchShare() {
        run("Watching link") {
            self.viewerPositions = [:]; self.viewerEnded = false
            do {
                // The SDK takes the view TOKEN. Taking it out of a pasted link is this app's choice (Invite.parse).
                let token = Invite.parse(self.viewerInput)?.viewToken ?? self.viewerInput.trimmingCharacters(in: .whitespaces)
                _ = try unwrap(try await TrackMe.shared.realtime.watchShare(viewToken: token))
                self.viewerWatching = true
                self.append("Watching view link")
            } catch {
                self.viewerWatching = false
                throw error
            }
        }
    }

    func stopWatchingShare() {
        _ = TrackMe.shared.realtime.stopWatchingShare()
        viewerWatching = false
    }

    // MARK: inbox

    func setUnreadOnly(_ on: Bool) {
        unreadOnly = on
        loadNotifications(reset: true)
    }

    func loadNotifications(reset: Bool) { run("Loading inbox") { await self.loadNotificationsNow(reset: reset) } }

    /// `reset` = first page; otherwise the next page via the cursor.
    private func loadNotificationsNow(reset: Bool) async {
        do {
            let page = try unwrap(try await TrackMe.shared.notifications.list(
                unreadOnly: unreadOnly, tripId: nil, cursor: reset ? nil : nextCursor, limit: 20))
            notifications = reset ? page.items : notifications + page.items
            nextCursor = page.nextCursor
            hasMore = page.hasMore
        } catch { fail(error) }
    }

    func markRead(_ n: TrackMeNotification) {
        run("Marking read") {
            _ = try unwrap(try await TrackMe.shared.notifications.markRead(notificationId: n.notificationId))
            await self.loadNotificationsNow(reset: true)
        }
    }

    func markAllRead() {
        run("Marking all read") {
            _ = try unwrap(try await TrackMe.shared.notifications.markAllRead())
            await self.loadNotificationsNow(reset: true)
        }
    }

    func openNotificationTrip(_ n: TrackMeNotification) {
        guard let id = n.tripId?.int64Value else { return }
        section = .developer
        tab = .trips
        openTrip(id)
    }

    // MARK: sdk / debug

    func checkHealth() {
        run("Health check") {
            let h = try unwrap(try await TrackMe.shared.checkHealth())
            self.health = "\(h.status) at \(DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium))"
        }
    }

    /// Apps that refresh tokens on their own schedule call updateToken(); the next request uses it.
    func refreshTokenNow() {
        run("Fetching a new token") {
            guard let form = self.session, let token = await DemoAuth.login(email: form.email, fullName: form.fullName) else {
                throw SampleError(message: "Couldn't get a token")
            }
            _ = try unwrap(TrackMe.shared.updateToken(token: token))
            self.append("updateToken() with a fresh token")
        }
    }

    /// Puts a broken token in the SDK. The next request gets 401 → the SDK asks the token provider →
    /// "Token refreshed" in the log, and the request succeeds anyway. That's the expiry flow, on demand.
    func simulateExpiredToken() {
        run("Simulating expiry") {
            _ = TrackMe.shared.updateToken(token: "expired-demo-token")
            self.append("Token replaced with a broken one; reloading trips…")
            await self.loadTripsNow()
            self.append("Trips loaded after automatic refresh")
        }
    }

    func stopRealtime() {
        _ = TrackMe.shared.realtime.stop()
        watchingLive = false; viewerWatching = false; liveTripId = nil
    }

    func clearLog() { log = [] }

    // MARK: live map (showcase)
    // A ready-to-use flow on top of the same SDK calls: start or join, share, invite, leave / end.

    /// Picks the trip the map shows and keeps it live. Call whenever the map appears.
    func mapRefresh() {
        Task {
            guard let list = try? unwrap(try await TrackMe.shared.trips.list(limit: 50)), let all = list as? [Trip] else { return }
            let live = all.filter { $0.isLive }
            self.trips = all
            self.mapLiveTrips = live
            // Keep the current trip while it's in the list (even ended: the panel shows "Trip ended" until Done).
            let keep = self.mapDetail.map { $0.trip.id }.flatMap { cur in all.contains(where: { $0.id == cur }) ? cur : nil }
            if let id = keep ?? live.first?.id { await self.loadMapTrip(id) } else { self.mapDetail = nil }
        }
    }

    func mapSelect(_ id: Int64) { Task { await loadMapTrip(id) } }

    /// Fly the live map to a member (`MapMarker.id`, e.g. "u17"); your own id flies to you.
    func focusOnMember(_ markerId: String) {
        mapFocus = MapFocus(markerId: markerId, seq: (mapFocus?.seq ?? 0) + 1)
    }

    /// The live map's 15 s snapshot refresh, next to the socket.
    /// Called every 5 s by the live map. Reloads the trip snapshot only when no live position came in the last
    /// 10 s, so positions keep moving (like the web page's polling) even if live messages don't arrive.
    func mapPoll() {
        guard mapDetail?.trip.isLive == true, Date().timeIntervalSince(lastLivePositionAt) > 10 else { return }
        reloadMapTrip()
    }

    private func reloadMapTrip() {
        guard let id = mapDetail?.trip.id else { return }
        Task { await loadMapTrip(id) }
    }

    private func loadMapTrip(_ id: Int64) async {
        guard let d = try? unwrap(try await TrackMe.shared.trips.detail(tripId: id)) else { return }
        let switched = mapDetail?.trip.id != id
        mapDetail = d
        mapLoadedAt = Date()
        if switched { mapViewLink = nil }
        if d.trip.isLive && (liveTripId != id || realtimeState is RealtimeState.Idle) { watchLive(id) }
    }

    /// Create → start sharing → show it.
    func mapCreate(name: String, minutes: Int) {
        run("Starting trip") {
            let c = try unwrap(try await TrackMe.shared.trips.create(name: name.isEmpty ? nil : name, durationMin: Int32(minutes)))
            self.append("Created trip \(c.id), code \(c.joinCode)")
            self.startSharingFromMap()
            await self.loadMapTrip(c.id)
            self.created = c
            self.mapViewLink = c.viewLink   // after loadMapTrip, which resets it for a new trip
            await self.loadTripsNow()
        }
    }

    /// Join → show everyone. Sharing your own location is a separate, later choice ("Share my location too"):
    /// watching the trip (realtime.watchTrip) is what delivers the others' positions.
    func mapJoin(code: String) {
        run("Joining") {
            let trip = try unwrap(try await TrackMe.shared.trips.join(code: code))
            self.append("Joined trip \(trip.id)")
            self.mapCancelInvite()
            await self.loadMapTrip(trip.id)
            await self.loadTripsNow()
        }
    }

    /// Owner removes a member from the trip shown on the map (`trips.removeMember`). They get `evicted`.
    func mapRemoveMember(_ userId: Int64) {
        guard let id = mapDetail?.trip.id else { return }
        run("Removing member") {
            _ = try unwrap(try await TrackMe.shared.trips.removeMember(tripId: id, userId: userId))
            self.append("Removed user \(userId)")
            self.livePositions[userId] = nil
            self.livePositionAt[userId] = nil
            self.memberSharing[userId] = nil
            await self.loadMapTrip(id)
        }
    }

    func mapLeave() {
        guard let id = mapDetail?.trip.id else { return }
        run("Leaving") {
            _ = try unwrap(try await TrackMe.shared.trips.leave(tripId: id))
            self.append("Left trip \(id)")
            _ = TrackMe.shared.realtime.stopWatchingTrip()
            self.mapDetail = nil; self.liveTripId = nil
            self.mapRefresh()
        }
    }

    func mapEnd() {
        guard let id = mapDetail?.trip.id else { return }
        run("Ending trip") {
            _ = try unwrap(try await TrackMe.shared.trips.end(tripId: id))
            self.append("Ended trip \(id)")
            await self.loadMapTrip(id)
            await self.loadTripsNow()
        }
    }

    /// Dismiss the "Trip ended" card.
    func mapDone() {
        mapDetail = nil
        mapRefresh()
    }

    /// Builds the invite text (view link + join code) for the share sheet, or copies it.
    func mapShareInvite(copyOnly: Bool = false) {
        guard let d = mapDetail else { return }
        run("Preparing invite") {
            var link = self.mapViewLink
            if link == nil { link = (try? unwrap(try await TrackMe.shared.trips.viewLink(tripId: d.trip.id)))?.viewLink }
            self.mapViewLink = link
            let text = inviteText(tripName: d.trip.name, viewLink: link, joinCode: d.trip.joinCode)
            if copyOnly { UIPasteboard.general.string = text } else { shareText(text) }
        }
    }

    /// A pasted invite: show who's on the map (watchShare) and the trip (preview) before joining.
    func mapOpenInvite(_ text: String) {
        guard let parsed = Invite.parse(text) else { message = "Paste a join code or a TrackMe invite."; return }
        invite = parsed; viewerPositions = [:]; viewerEnded = false
        run("Opening invite") {
            if let token = parsed.viewToken {
                do {
                    _ = try unwrap(try await TrackMe.shared.realtime.watchShare(viewToken: token))
                    self.viewerWatching = true
                } catch { self.append("Can't watch this link: \((error as? SampleError)?.message ?? "")") }
            }
            if let code = parsed.code {
                let p = try unwrap(try await TrackMe.shared.trips.preview(code: code))
                self.invite?.preview = p
            }
        }
    }

    func mapCancelInvite() {
        guard invite != nil else { return }
        _ = TrackMe.shared.realtime.stopWatchingShare()
        invite = nil; viewerWatching = false; viewerPositions = [:]
    }

    /// Start, pause or resume, depending on the current state.
    func mapToggleSharing() {
        switch sharing {
        case is SharingState.Paused: resumeSharing()
        case is SharingState.Sharing, is SharingState.Starting: pauseSharing()
        default: startSharingFromMap()
        }
    }

    func startSharingFromMap() {
        guard !sharing.isActive else { return }
        mapSharingError = tryStartSharing()
        refreshPermission()
    }

    /// The map's "Allow location" button: ask (sharing starts once granted), or open Settings if denied for good.
    func mapAllowLocation(canAskAgain: Bool) {
        if canAskAgain { pendingMapShare = true; requestWhenInUse() } else { openSettings() }
    }

    // MARK: live status

    /// How long a member still counts as "live" after their newest position. Sharing re-sends at least every heartbeat
    /// (30 s by default) even when standing still, so a longer silence means paused, stopped, offline or no GPS.
    /// The server doesn't announce a pause: silence is the only signal, for every member.
    static let liveWindowSeconds: Int64 = 45

    /// Seconds since `userId`'s newest live (socket) position for `tripId`, or nil if none arrived.
    func liveAgeSeconds(_ userId: Int64, tripId: Int64, now: Date) -> Int64? {
        guard liveTripId == tripId, let p = livePositions[userId], let at = livePositionAt[userId] else { return nil }
        return Int64(now.timeIntervalSince(at)) + (p.fixAgeSeconds?.int64Value ?? 0)
    }

    /// Seconds since `m`'s fix in a trips.detail() snapshot loaded at `loadedAt`, or nil without a position.
    func snapshotAgeSeconds(_ m: TripMember, loadedAt: Date, now: Date) -> Int64? {
        guard m.hasPosition else { return nil }
        return (m.fixAgeSeconds?.int64Value ?? 0) + Int64(now.timeIntervalSince(loadedAt))
    }

    /// The member's newest reported sharing state ("Sharing" / "Paused" / "Stopped", the Kotlin enum name) and when
    /// it was known: the socket event or the snapshot loaded at `loadedAt`, whichever is newer.
    /// Nil until the backend supports `sharing_state`.
    func reportedSharing(_ m: TripMember, tripId: Int64, loadedAt: Date) -> (state: String, at: Date)? {
        let event = liveTripId == tripId ? memberSharing[m.userId] : nil
        let snapshot = m.sharingState.map { (state: $0.name, at: loadedAt) }
        return [event, snapshot].compactMap { $0 }.max { $0.at < $1.at }
    }

    /// "paused" / "not sharing" when the member said so (and no position arrived since), otherwise "live",
    /// "2 min 5 s ago" or "no location" from whichever is newer: the live position or the snapshot.
    func memberStatus(_ m: TripMember, tripId: Int64, loadedAt: Date, now: Date) -> String {
        if let reported = reportedSharing(m, tripId: tripId, loadedAt: loadedAt), reported.state != "Sharing" {
            let liveAt = liveTripId == tripId ? livePositionAt[m.userId] : nil
            if liveAt == nil || liveAt! <= reported.at { return reported.state == "Paused" ? "paused" : "not sharing" }
        }
        let ages = [liveAgeSeconds(m.userId, tripId: tripId, now: now), snapshotAgeSeconds(m, loadedAt: loadedAt, now: now)]
        guard let age = ages.compactMap({ $0 }).min() else { return "no location" }
        return age <= Self.liveWindowSeconds ? "live" : "\(formatSeconds(age)) ago"
    }

    /// The live position, unless the snapshot is newer (e.g. the socket missed some while the poll caught up).
    func newestLive(_ m: TripMember, tripId: Int64, loadedAt: Date, now: Date) -> MemberPosition? {
        guard let live = liveAgeSeconds(m.userId, tripId: tripId, now: now) else { return nil }
        if let snap = snapshotAgeSeconds(m, loadedAt: loadedAt, now: now), snap < live { return nil }
        return livePositions[m.userId]
    }

    // MARK: helpers

    private func withTrip(_ what: String, _ block: @escaping (Int64) async throws -> Void) {
        guard let id = detail?.trip.id else { return }
        run(what) { try await block(id) }
    }

    private func run(_ what: String, _ block: @escaping () async throws -> Void) {
        busy = true
        append("\(what)…")
        Task {
            do { try await block() } catch { fail(error) }
            busy = false
        }
    }

    private func fail(_ error: Error) {
        let text = (error as? SampleError)?.message ?? error.localizedDescription
        message = text
        append("Error: \(text)")
    }

    func append(_ line: String) {
        let time = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        log.insert("\(time)  \(line)", at: 0)
        if log.count > 200 { log.removeLast() }
    }
}
