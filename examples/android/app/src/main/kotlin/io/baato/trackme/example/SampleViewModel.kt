package io.baato.trackme.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeConfig
import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeEvent
import io.baato.trackme.TrackMeResult
import io.baato.trackme.initialize
import io.baato.trackme.location.LocationPermissionStatus
import io.baato.trackme.model.CreatedTrip
import io.baato.trackme.model.MemberSharingState
import io.baato.trackme.model.TrackMeNotification
import io.baato.trackme.model.Trip
import io.baato.trackme.model.TripDetail
import io.baato.trackme.model.TripMember
import io.baato.trackme.model.TripPreview
import io.baato.trackme.realtime.MemberPosition
import io.baato.trackme.sharing.SharingOptions
import io.baato.trackme.sharing.TrackMeSharingUi
import io.baato.trackme.tokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Top level: the ready-to-use live map, and (dev-tools builds only) the developer screens. */
enum class Section { Map, Developer }

/** An invite pasted into the live map: "Follow me on TrackMe: https://…/trackme/s/<token>\nJoin code: ABC123", a link or a bare code. */
data class Invite(val viewToken: String?, val code: String?, val preview: TripPreview? = null) {
    companion object {
        private val link = Regex("""/s/([A-Za-z0-9_\-]+)""")
        private val labelledCode = Regex("""code[:\s]+([A-Za-z0-9]{4,16})""", RegexOption.IGNORE_CASE)
        private val bareCode = Regex("""^[A-Za-z0-9]{4,16}$""")

        fun parse(text: String): Invite? {
            val t = text.trim()
            val token = link.find(t)?.groupValues?.get(1)
            val code = labelledCode.find(t)?.groupValues?.get(1) ?: t.takeIf { bareCode.matches(it) }
            return if (token == null && code == null) null else Invite(token, code?.uppercase())
        }
    }
}

/** The text the live map shares; the same shape the Invite parser reads back. */
fun inviteText(tripName: String, viewLink: String?, joinCode: String?): String = buildString {
    append("Follow me on TrackMe")
    if (viewLink != null) append(": ").append(viewLink)
    if (joinCode != null) append("\nJoin code: ").append(joinCode)
    if (viewLink == null && joinCode == null) append(" (").append(tripName).append(")")
}

enum class Tab(val label: String, val glyph: String) {
    Trips("Trips", "🗺"), Sharing("Share", "📍"), Viewer("Viewer", "👁"), Inbox("Inbox", "🔔"), Sdk("SDK", "⚙")
}

/** What the login screen collects: who to log in as, and how the sharing notification should look. */
data class LoginForm(
    val email: String,
    val fullName: String,
    val notificationTitle: String = "Sharing your location",
    val notificationText: String = "Trip members can see where you are.",
    val accentColor: Long = 0xFF1565C0,
    val showPause: Boolean = true,
    val showStop: Boolean = true,
)

data class SampleUiState(
    val login: LoginForm? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val section: Section = Section.Map,
    val tab: Tab = Tab.Trips,

    // ---- live map (showcase) ----
    /** The trip the live map shows (the most recent live trip, or the one picked / just created / joined). */
    val mapDetail: TripDetail? = null,
    val mapLoadedAt: Long = 0,
    val mapLiveTrips: List<Trip> = emptyList(),
    val mapViewLink: String? = null,
    val invite: Invite? = null,
    /** Why sharing couldn't start from the map (e.g. no permission); the panel offers a fix. */
    val mapSharingError: TrackMeError? = null,

    /** The trip TrackMe.realtime is watching; [livePositions] belong to it. Shared by the map and the trip screen. */
    val liveTripId: Long? = null,

    // ---- trips tab ----
    val trips: List<Trip> = emptyList(),
    val created: CreatedTrip? = null,
    val preview: TripPreview? = null,

    // ---- open trip (full screen) ----
    val detail: TripDetail? = null,
    /** When [detail] was loaded, so the screen can count remainingSeconds down. */
    val detailLoadedAt: Long = 0,
    val viewLink: String? = null,
    val watchingLive: Boolean = false,
    /** Latest socket position per member of the open trip. Key = user id. */
    val livePositions: Map<Long, MemberPosition> = emptyMap(),
    /** When each of [livePositions] arrived (epoch ms): a position only says "live" while new ones keep coming. */
    val livePositionAt: Map<Long, Long> = emptyMap(),
    /** Sharing state each member reported (TrackMeEvent.MemberSharingChanged) and when it arrived (epoch ms). */
    val memberSharing: Map<Long, Pair<MemberSharingState, Long>> = emptyMap(),
    val history: String? = null,

    // ---- sharing tab ----
    val permission: LocationPermissionStatus? = null,
    /** The map asked for location once this session (it asks by itself only once, so it never nags). */
    val locationAutoAsked: Boolean = false,
    /** Android stopped showing the permission dialog ("don't ask again"): only Settings can grant it now. */
    val locationBlocked: Boolean = false,
    val options: SharingOptions = SharingOptions.Default,

    // ---- viewer tab (public view links) ----
    val viewerInput: String = "",
    val viewerWatching: Boolean = false,
    val viewerEnded: Boolean = false,
    /** Key = user id (or name when the server sends no id). */
    val viewerPositions: Map<String, MemberPosition> = emptyMap(),

    // ---- inbox tab ----
    val notifications: List<TrackMeNotification> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val unreadOnly: Boolean = false,

    // ---- sdk tab ----
    val health: String? = null,
    val log: List<String> = emptyList(),
) {
    val loggedIn: Boolean get() = login != null
}

/**
 * How long a member still counts as "live" after their newest position. Sharing re-sends at least every heartbeat
 * (30 s by default) even when standing still, so a longer silence means paused, stopped, offline or no GPS.
 * The server doesn't announce a pause: silence is the only signal, for every member.
 */
const val LIVE_WINDOW_SECONDS = 45L

/** Seconds since [userId]'s newest live (socket) position for [tripId], or null if none arrived. */
fun SampleUiState.liveAgeSeconds(userId: Long, tripId: Long, now: Long): Long? {
    if (liveTripId != tripId) return null
    val p = livePositions[userId] ?: return null
    val at = livePositionAt[userId] ?: return null
    return (now - at) / 1000 + (p.fixAgeSeconds ?: 0)
}

/** Seconds since [m]'s fix in a trips.detail() snapshot loaded at [loadedAt], or null without a position. */
fun snapshotAgeSeconds(m: TripMember, loadedAt: Long, now: Long): Long? =
    if (m.hasPosition) (m.fixAgeSeconds ?: 0) + (now - loadedAt) / 1000 else null

/**
 * The member's newest reported sharing state and when it was known: the socket event or the trips.detail()
 * snapshot loaded at [loadedAt], whichever is newer. Null until the backend supports `sharing_state`.
 */
fun SampleUiState.reportedSharing(m: TripMember, tripId: Long, loadedAt: Long): Pair<MemberSharingState, Long>? {
    val event = memberSharing[m.userId]?.takeIf { liveTripId == tripId }
    val snapshot = m.sharingState?.let { it to loadedAt }
    return listOfNotNull(event, snapshot).maxByOrNull { it.second }
}

/**
 * "paused" / "not sharing" when the member said so (and no position arrived since), otherwise "live",
 * "2 min 5 s ago" or "no location" from whichever is newer: the live position or the snapshot.
 */
fun SampleUiState.memberStatus(m: TripMember, tripId: Long, loadedAt: Long, now: Long): String {
    val reported = reportedSharing(m, tripId, loadedAt)
    val liveAt = livePositionAt[m.userId]?.takeIf { liveTripId == tripId }
    if (reported != null && reported.first != MemberSharingState.Sharing && (liveAt == null || liveAt <= reported.second)) {
        return if (reported.first == MemberSharingState.Paused) "paused" else "not sharing"
    }
    val age = listOfNotNull(liveAgeSeconds(m.userId, tripId, now), snapshotAgeSeconds(m, loadedAt, now)).minOrNull()
        ?: return "no location"
    return if (age <= LIVE_WINDOW_SECONDS) "live" else "${formatSeconds(age)} ago"
}

/** The live position, unless the snapshot is newer (e.g. the socket missed some while the poll caught up). */
fun SampleUiState.newestLive(m: TripMember, tripId: Long, loadedAt: Long, now: Long): MemberPosition? {
    val live = liveAgeSeconds(m.userId, tripId, now) ?: return null
    val snap = snapshotAgeSeconds(m, loadedAt, now)
    return if (snap == null || live <= snap) livePositions[m.userId] else null
}

/**
 * Every SDK call in the sample goes through here, so this file doubles as a "how to use the SDK" reference.
 * Pattern: every call returns TrackMeResult; nothing needs try/catch.
 */
class SampleViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(SampleUiState())
    val ui: StateFlow<SampleUiState> = _ui.asStateFlow()

    /** The SDK's own StateFlows, exposed as-is to Compose. */
    val sdkState = TrackMe.state
    val sharingState = TrackMe.sharing.state
    val realtimeState = TrackMe.realtime.state

    init {
        // Dev builds: SDK diagnostics (socket handshake, joins, every live message) in logcat and the SDK tab's log.
        if (BuildConfig.DEV_TOOLS) TrackMe.debugLogger = { line -> android.util.Log.d("TrackMe", line); log("sdk  $line") }
        viewModelScope.launch { TrackMe.events.collect(::onEvent) }
        viewModelScope.launch { TrackMe.realtime.state.collect { log("Live: $it") } }
        viewModelScope.launch { TrackMe.sharing.state.collect { log("Sharing: ${it::class.simpleName}") } }
        viewModelScope.launch { TrackMe.state.collect { log("SDK: ${it::class.simpleName}") } }
    }

    /** One place for every TrackMeEvent. */
    private fun onEvent(event: TrackMeEvent) {
        when (event) {
            TrackMeEvent.AuthenticationFailed -> { log("Auth failed: need a new token"); fail("Session expired: log in again.") }
            TrackMeEvent.TokenRefreshed -> log("Token refreshed by the token provider")
            is TrackMeEvent.SharingStopped -> log("Sharing stopped: ${event.reason}")

            // ---- live updates of the open trip (TrackMe.realtime.watchTrip) ----
            is TrackMeEvent.TripPosition -> {
                val p = event.position
                val id = p.userId
                if (p.tripId != null && p.tripId != _ui.value.liveTripId) return   // a stale event from the previous trip
                val now = System.currentTimeMillis()
                lastLivePositionAt = now
                _ui.update { it.copy(livePositions = it.livePositions + (id to p), livePositionAt = it.livePositionAt + (id to now)) }
            }
            is TrackMeEvent.MemberSharingChanged -> {
                log("${event.displayName ?: "Member ${event.userId}"}: ${event.state.name.lowercase()}")
                if (event.tripId == _ui.value.liveTripId) {
                    _ui.update { it.copy(memberSharing = it.memberSharing + (event.userId to (event.state to System.currentTimeMillis()))) }
                }
            }
            is TrackMeEvent.MemberChanged -> {
                log("${event.change.displayName ?: "Someone"} ${event.change.change}")
                refreshTrip()   // new member → reload the member list
                reloadMapTrip()
            }
            is TrackMeEvent.TripEnded -> {
                log("Trip ${event.tripId} ended (${event.reason ?: "no reason"})")
                _ui.update { it.copy(watchingLive = false, liveTripId = null) }
                refreshTrip()   // keep the screen open: it now shows "ended" and the Delete button
                reloadMapTrip() // the map shows "Trip ended"
                loadTrips()
            }
            is TrackMeEvent.Evicted -> {
                log("Removed from trip ${event.tripId}: ${event.reason}")
                _ui.update { it.copy(liveTripId = null) }
                closeTrip()
                if (_ui.value.mapDetail?.trip?.id.let { it == null || it == event.tripId || event.tripId == null }) {
                    _ui.update { it.copy(mapDetail = null) }
                }
                fail("You're no longer in this trip (${event.reason ?: "removed"}).")
                loadTrips()
                mapRefresh()
            }

            // ---- viewer tab (TrackMe.realtime.watchShare) ----
            is TrackMeEvent.SharePosition -> {
                val p = event.position
                val key = p.userId.toString()
                _ui.update { it.copy(viewerPositions = it.viewerPositions + (key to p)) }
            }
            TrackMeEvent.ShareEnded -> {
                log("View link ended")
                _ui.update { it.copy(viewerWatching = false, viewerEnded = true) }
            }
        }
    }

    // ======================= lifecycle =======================

    fun login(form: LoginForm) = run("Logging in") {
        if (!DemoAuth.isConfigured) return@run fail("Add trackme.apiSecret to local.properties (demo only).")
        val token = DemoAuth.login(form.email, form.fullName) ?: return@run fail("Login failed (check email / secret / network).")

        val config = TrackMeConfig(
            token = token,
            // Called automatically when the server says the token expired.
            tokenProvider = tokenProvider { DemoAuth.login(form.email, form.fullName) },
            sharingUi = TrackMeSharingUi(
                title = form.notificationTitle,
                text = form.notificationText,
                accentColor = form.accentColor,
                showPauseAction = form.showPause,
                showStopAction = form.showStop,
            ),
        )
        when (val r = TrackMe.initialize(getApplication<Application>(), config)) {
            is TrackMeResult.Success -> {
                _ui.update { it.copy(login = form) }
                log("Initialized as ${form.email}")
                refreshPermission()
                loadTrips()
                loadNotifications(reset = true)
                mapRefresh()
            }
            is TrackMeResult.Failure -> fail(r.error.message)
        }
    }

    /** dispose(): stops sharing, removes the notification, closes the socket and HTTP. Safe to call twice. */
    fun logout() {
        TrackMe.dispose()
        _ui.value = SampleUiState(log = _ui.value.log)
        log("Disposed")
    }

    /** initialize() again with the same user: replaces the session (old one fully disposed first). */
    fun reinitialize() {
        val form = _ui.value.login ?: return
        login(form)
    }

    fun selectTab(tab: Tab) = _ui.update { it.copy(tab = tab) }
    fun selectSection(section: Section) = _ui.update { it.copy(section = section) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    // ======================= permission =======================

    fun refreshPermission() {
        val p = TrackMe.location.permissionStatus().getOrNull()
        _ui.update { it.copy(permission = p, locationBlocked = it.locationBlocked && p?.isGranted != true) }
    }

    fun markLocationAutoAsked() = _ui.update { it.copy(locationAutoAsked = true) }
    fun setLocationBlocked(blocked: Boolean) = _ui.update { it.copy(locationBlocked = blocked) }

    /** Location was just granted: if sharing from the map failed for lack of it, start it now. */
    fun onLocationGranted() {
        refreshPermission()
        if (_ui.value.mapSharingError is TrackMeError.LocationPermissionDenied) startSharingFromMap()
    }

    // ======================= trips =======================

    fun loadTrips() = run("Loading trips") {
        TrackMe.trips.list(limit = 50).handle { trips -> _ui.update { it.copy(trips = trips) } }
    }

    fun createTrip(name: String, minutes: Int) = run("Creating trip") {
        TrackMe.trips.create(name.ifBlank { null }, durationMin = minutes).handle { created ->
            _ui.update { it.copy(created = created) }
            log("Created trip ${created.id}, code ${created.joinCode}")
            loadTrips()
        }
    }

    fun previewTrip(code: String) = run("Looking up code") {
        TrackMe.trips.preview(code).handle { p -> _ui.update { it.copy(preview = p) } }
    }

    fun joinTrip(code: String) = run("Joining") {
        TrackMe.trips.join(code).handle { trip ->
            _ui.update { it.copy(preview = null) }
            log("Joined trip ${trip.id}")
            loadTrips()
            openTrip(trip.id)
        }
    }

    // ======================= open trip =======================

    fun openTrip(tripId: Long) = run("Loading trip") {
        TrackMe.trips.detail(tripId).handle { d ->
            _ui.update { it.copy(detail = d, detailLoadedAt = System.currentTimeMillis(), viewLink = null, history = null) }
            if (d.trip.isLive) setWatchLive(true)   // live positions while the screen is open
        }
    }

    /** Reload the open trip without resetting links, history or live positions. */
    fun refreshTrip() {
        val id = _ui.value.detail?.trip?.id ?: return
        viewModelScope.launch {
            TrackMe.trips.detail(id).onSuccess { d ->
                _ui.update { if (it.detail?.trip?.id == id) it.copy(detail = d, detailLoadedAt = System.currentTimeMillis()) else it }
            }
        }
    }

    fun closeTrip() {
        if (_ui.value.detail == null) return
        TrackMe.realtime.stopWatchingTrip()
        _ui.update { it.copy(detail = null, watchingLive = false, liveTripId = null, livePositions = emptyMap(), livePositionAt = emptyMap(), memberSharing = emptyMap(), history = null, viewLink = null) }
    }

    fun setWatchLive(on: Boolean) {
        val id = _ui.value.detail?.trip?.id ?: return
        if (!on) {
            TrackMe.realtime.stopWatchingTrip()
            _ui.update { it.copy(watchingLive = false) }
            return
        }
        watchLive(id) { ok -> _ui.update { it.copy(watchingLive = ok) } }
    }

    /** One socket watches one trip; switching trips clears the positions of the previous one. */
    private fun watchLive(tripId: Long, onResult: (Boolean) -> Unit = {}) {
        if (_ui.value.liveTripId != tripId) _ui.update { it.copy(liveTripId = tripId, livePositions = emptyMap(), livePositionAt = emptyMap(), memberSharing = emptyMap()) }
        viewModelScope.launch {
            when (val r = TrackMe.realtime.watchTrip(tripId)) {   // suspends until the server confirms
                is TrackMeResult.Success -> onResult(true)
                is TrackMeResult.Failure -> {
                    if (_ui.value.liveTripId == tripId) _ui.update { it.copy(liveTripId = null) }
                    log("Live updates unavailable: ${r.error.message}")
                    onResult(false)
                }
            }
        }
    }

    fun renameTrip(name: String) = withTrip("Renaming") { id ->
        TrackMe.trips.rename(id, name).handle { log("Renamed to ${it.name}"); refreshTrip(); loadTrips() }
    }

    /** Owner only. Members get trip_ended over the socket; everyone's sharing to this trip stops. */
    fun endTrip() = withTrip("Ending trip") { id ->
        TrackMe.trips.end(id).handle { log("Ended trip $id"); refreshTrip(); loadTrips() }
    }

    fun leaveTrip() = withTrip("Leaving trip") { id ->
        TrackMe.trips.leave(id).handle { log("Left trip $id"); closeTrip(); loadTrips() }
    }

    fun removeMember(userId: Long) = withTrip("Removing member") { id ->
        TrackMe.trips.removeMember(id, userId).handle { log("Removed user $userId"); refreshTrip() }
    }

    /** Only for ended trips; a live one fails with Conflict ("end it first"). */
    fun deleteTrip() = withTrip("Deleting trip") { id ->
        TrackMe.trips.delete(id).handle { log("Deleted trip $id"); closeTrip(); loadTrips() }
    }

    fun loadViewLink() = withTrip("Getting link") { id ->
        TrackMe.trips.viewLink(id).handle { l -> _ui.update { it.copy(viewLink = l.viewLink) } }
    }

    /** The old link stops working for everyone (viewers get share_ended). */
    fun rotateViewLink() = withTrip("Rotating link") { id ->
        TrackMe.trips.rotateViewLink(id).handle { l -> _ui.update { it.copy(viewLink = l.viewLink) }; log("View link rotated") }
    }

    /** [lastMinutes] = null → the whole trip. Times are ISO-8601 UTC. */
    fun loadHistory(lastMinutes: Int?) = withTrip("Loading history") { id ->
        val from = lastMinutes?.let { iso(System.currentTimeMillis() - it * 60_000L) }
        TrackMe.trips.history(id, from = from, to = null).handle { json -> _ui.update { it.copy(history = json) } }
    }

    fun clearHistory() = _ui.update { it.copy(history = null) }

    /** Opens the Viewer tab with this trip's public link, to see what a non-member sees. */
    fun watchLinkInViewer() {
        val link = _ui.value.viewLink ?: return
        closeTrip()
        _ui.update { it.copy(section = Section.Developer, tab = Tab.Viewer, viewerInput = link) }
        watchShare()
    }

    // ======================= sharing =======================

    fun setOptions(options: SharingOptions) = _ui.update { it.copy(options = options) }

    /** Returns the error so the screen can decide: ask for permission, open settings, … */
    fun startSharing(): TrackMeError? {
        val r = TrackMe.sharing.start(_ui.value.options)
        r.onFailure { log("Start failed: ${it.message}") }
        refreshPermission()
        return r.errorOrNull()
    }

    fun pauseSharing() { TrackMe.sharing.pause() }
    fun resumeSharing() { TrackMe.sharing.resume() }
    fun stopSharing() { TrackMe.sharing.stop() }

    // ======================= viewer (public view link) =======================

    fun setViewerInput(text: String) = _ui.update { it.copy(viewerInput = text) }

    fun watchShare() = run("Watching link") {
        _ui.update { it.copy(viewerPositions = emptyMap(), viewerEnded = false) }
        // The SDK takes the view TOKEN. Taking it out of a pasted link is this app's choice (Invite.parse).
        val input = _ui.value.viewerInput
        val token = Invite.parse(input)?.viewToken ?: input.trim()
        when (val r = TrackMe.realtime.watchShare(token)) {
            is TrackMeResult.Success -> { _ui.update { it.copy(viewerWatching = true) }; log("Watching view link") }
            is TrackMeResult.Failure -> { _ui.update { it.copy(viewerWatching = false) }; fail(r.error.message) }
        }
    }

    fun stopWatchingShare() {
        TrackMe.realtime.stopWatchingShare()
        _ui.update { it.copy(viewerWatching = false) }
    }

    // ======================= inbox =======================

    fun setUnreadOnly(on: Boolean) {
        _ui.update { it.copy(unreadOnly = on) }
        loadNotifications(reset = true)
    }

    /** [reset] = first page; otherwise the next page via the cursor. */
    fun loadNotifications(reset: Boolean) = run("Loading inbox") {
        val s = _ui.value
        val cursor = if (reset) null else s.nextCursor
        TrackMe.notifications.list(unreadOnly = s.unreadOnly, tripId = null, cursor = cursor, limit = 20).handle { page ->
            _ui.update {
                it.copy(
                    notifications = if (reset) page.items else it.notifications + page.items,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                )
            }
        }
    }

    fun markRead(n: TrackMeNotification) = run("Marking read") {
        TrackMe.notifications.markRead(n.notificationId).handle { loadNotifications(reset = true) }
    }

    fun markAllRead() = run("Marking all read") {
        TrackMe.notifications.markAllRead().handle { loadNotifications(reset = true) }
    }

    fun openNotificationTrip(n: TrackMeNotification) {
        val id = n.tripId ?: return
        _ui.update { it.copy(section = Section.Developer, tab = Tab.Trips) }
        openTrip(id)
    }

    // ======================= sdk / debug =======================

    fun checkHealth() = run("Health check") {
        TrackMe.checkHealth().handle { h -> _ui.update { it.copy(health = "${h.status} at ${clock()}") } }
    }

    /** Apps that refresh tokens on their own schedule call updateToken(); the next request uses it. */
    fun refreshTokenNow() = run("Fetching a new token") {
        val form = _ui.value.login ?: return@run
        val token = DemoAuth.login(form.email, form.fullName) ?: return@run fail("Couldn't get a token")
        TrackMe.updateToken(token).handle { log("updateToken() with a fresh token") }
    }

    /**
     * Puts a broken token in the SDK. The next request gets 401 → the SDK asks the token provider →
     * TokenRefreshed in the log, and the request succeeds anyway. That's the expiry flow, on demand.
     */
    fun simulateExpiredToken() = run("Simulating expiry") {
        TrackMe.updateToken("expired-demo-token")
        log("Token replaced with a broken one; reloading trips…")
        TrackMe.trips.list(limit = 50).handle { trips -> _ui.update { it.copy(trips = trips) }; log("Trips loaded after automatic refresh") }
    }

    fun stopRealtime() {
        TrackMe.realtime.stop()
        _ui.update { it.copy(watchingLive = false, viewerWatching = false, liveTripId = null) }
    }

    fun clearLog() = _ui.update { it.copy(log = emptyList()) }

    /** Map style / load errors (e.g. a wrong Baato key): logged, and shown once. */
    fun reportMapError(message: String) = fail(message)

    // ======================= live map (showcase) =======================
    // A ready-to-use flow on top of the same SDK calls: start or join, share, invite, leave / end.

    /** Picks the trip the map shows and keeps it live. Call whenever the map appears. */
    fun mapRefresh() {
        viewModelScope.launch {
            val trips = TrackMe.trips.list(limit = 50).getOrNull() ?: return@launch
            val live = trips.filter { it.isLive }
            _ui.update { it.copy(trips = trips, mapLiveTrips = live) }
            // Keep the current trip while it's in the list (even ended: the panel shows "Trip ended" until Done).
            val keep = _ui.value.mapDetail?.trip?.id?.takeIf { cur -> trips.any { it.id == cur } }
            val id = keep ?: live.firstOrNull()?.id
            if (id == null) _ui.update { it.copy(mapDetail = null) } else loadMapTrip(id)
        }
    }

    fun mapSelect(tripId: Long) { viewModelScope.launch { loadMapTrip(tripId) } }

    /** When the last live (socket) position arrived. (App-side fallback below; the SDK itself doesn't poll.) */
    @Volatile private var lastLivePositionAt = 0L

    /**
     * Called every 5 s by the live map. Reloads the trip snapshot only when no live position came in the last
     * 10 s, so positions keep moving (like the web page's polling) even if live messages don't arrive.
     */
    fun mapPoll() {
        if (System.currentTimeMillis() - lastLivePositionAt > 10_000) reloadMapTrip()
    }

    private fun reloadMapTrip() {
        val id = _ui.value.mapDetail?.trip?.id ?: return
        viewModelScope.launch { loadMapTrip(id) }
    }

    private suspend fun loadMapTrip(id: Long) {
        val d = TrackMe.trips.detail(id).getOrNull() ?: return
        val switched = _ui.value.mapDetail?.trip?.id != id
        _ui.update {
            it.copy(mapDetail = d, mapLoadedAt = System.currentTimeMillis(), mapViewLink = if (switched) null else it.mapViewLink)
        }
        val idle = TrackMe.realtime.state.value is io.baato.trackme.realtime.RealtimeState.Idle
        if (d.trip.isLive && (_ui.value.liveTripId != id || idle)) watchLive(id)
    }

    /** Create → start sharing → show it. */
    fun mapCreate(name: String, minutes: Int) = run("Starting trip") {
        TrackMe.trips.create(name.ifBlank { null }, durationMin = minutes).handle { c ->
            log("Created trip ${c.id}, code ${c.joinCode}")
            startSharingFromMap()
            loadMapTrip(c.id)
            _ui.update { it.copy(created = c, mapViewLink = c.viewLink) }   // after loadMapTrip, which resets it for a new trip
            loadTrips()
        }
    }

    /**
     * Join → show everyone. Sharing your own location is a separate, later choice ("Share my location too"):
     * watching the trip (realtime.watchTrip) is what delivers the others' positions.
     */
    fun mapJoin(code: String) = run("Joining") {
        TrackMe.trips.join(code).handle { trip ->
            log("Joined trip ${trip.id}")
            mapCancelInvite()
            loadMapTrip(trip.id)
            loadTrips()
        }
    }

    /** Owner removes a member from the trip shown on the map (`trips.removeMember`). They get `evicted`. */
    fun mapRemoveMember(userId: Long) {
        val id = _ui.value.mapDetail?.trip?.id ?: return
        run("Removing member") {
            TrackMe.trips.removeMember(id, userId).handle {
                log("Removed user $userId")
                _ui.update { it.copy(livePositions = it.livePositions - userId, livePositionAt = it.livePositionAt - userId, memberSharing = it.memberSharing - userId) }
                loadMapTrip(id)
            }
        }
    }

    fun mapLeave() {
        val id = _ui.value.mapDetail?.trip?.id ?: return
        run("Leaving") {
            TrackMe.trips.leave(id).handle {
                log("Left trip $id")
                TrackMe.realtime.stopWatchingTrip()
                _ui.update { it.copy(mapDetail = null, liveTripId = null) }
                mapRefresh()
            }
        }
    }

    fun mapEnd() {
        val id = _ui.value.mapDetail?.trip?.id ?: return
        run("Ending trip") { TrackMe.trips.end(id).handle { log("Ended trip $id"); loadMapTrip(id); loadTrips() } }
    }

    /** Dismiss the "Trip ended" card. */
    fun mapDone() {
        _ui.update { it.copy(mapDetail = null) }
        mapRefresh()
    }

    /** Builds the invite text (view link + join code); [onText] opens the share sheet. */
    fun mapShareInvite(onText: (String) -> Unit) {
        val d = _ui.value.mapDetail ?: return
        run("Preparing invite") {
            val link = _ui.value.mapViewLink ?: TrackMe.trips.viewLink(d.trip.id).getOrNull()?.viewLink
            _ui.update { it.copy(mapViewLink = link) }
            onText(inviteText(d.trip.name, link, d.trip.joinCode))
        }
    }

    /** A pasted invite: show who's on the map (watchShare) and the trip (preview) before joining. */
    fun mapOpenInvite(text: String) {
        val invite = Invite.parse(text) ?: return fail("Paste a join code or a TrackMe invite.")
        _ui.update { it.copy(invite = invite, viewerPositions = emptyMap(), viewerEnded = false) }
        run("Opening invite") {
            invite.viewToken?.let { token ->
                TrackMe.realtime.watchShare(token)
                    .onSuccess { _ui.update { it.copy(viewerWatching = true) } }
                    .onFailure { log("Can't watch this link: ${it.message}") }
            }
            invite.code?.let { code ->
                TrackMe.trips.preview(code)
                    .onSuccess { p -> _ui.update { s -> s.copy(invite = s.invite?.copy(preview = p)) } }
                    .onFailure { fail(it.message) }
            }
        }
    }

    fun mapCancelInvite() {
        if (_ui.value.invite == null) return
        TrackMe.realtime.stopWatchingShare()
        _ui.update { it.copy(invite = null, viewerWatching = false, viewerPositions = emptyMap()) }
    }

    /** Start, pause or resume, depending on the current state. */
    fun mapToggleSharing() {
        when (TrackMe.sharing.state.value) {
            is io.baato.trackme.sharing.SharingState.Paused -> resumeSharing()
            is io.baato.trackme.sharing.SharingState.Sharing, io.baato.trackme.sharing.SharingState.Starting -> pauseSharing()
            else -> startSharingFromMap()
        }
    }

    /** Also used after the user grants location from the map's "Allow location" button. */
    fun startSharingFromMap() {
        if (TrackMe.sharing.state.value.isActive) return
        val error = startSharing()
        _ui.update { it.copy(mapSharingError = error) }
    }

    // ======================= helpers =======================

    private fun withTrip(what: String, block: suspend (Long) -> Unit) {
        val id = _ui.value.detail?.trip?.id ?: return
        run(what) { block(id) }
    }

    private inline fun <T> TrackMeResult<T>.handle(onSuccess: (T) -> Unit) {
        when (this) {
            is TrackMeResult.Success -> onSuccess(data)
            is TrackMeResult.Failure -> fail(error.message)
        }
    }

    private fun run(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            log("$what…")
            try { block() } finally { _ui.update { it.copy(busy = false) } }
        }
    }

    private fun fail(message: String) {
        _ui.update { it.copy(message = message) }
        log("Error: $message")
    }

    private fun log(line: String) {
        _ui.update { it.copy(log = (listOf("${clock()}  $line") + it.log).take(200)) }
    }

    private fun clock() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun iso(millis: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
}
