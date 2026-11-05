package io.baato.trackme.bridge

import io.baato.trackme.PlatformContext
import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeConfig
import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeEvent
import io.baato.trackme.TrackMeResult
import io.baato.trackme.TrackMeState
import io.baato.trackme.TrackMeTokenProvider
import io.baato.trackme.location.LocationPermissionStatus
import io.baato.trackme.location.TrackMeLocation
import io.baato.trackme.network.TrackMeJson
import io.baato.trackme.realtime.MemberPosition
import io.baato.trackme.realtime.RealtimeState
import io.baato.trackme.sharing.SharingOptions
import io.baato.trackme.sharing.SharingState
import io.baato.trackme.sharing.TrackMeSharingUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The whole SDK as a **JSON-in / JSON-out** API, for wrappers in other frameworks (the Flutter plugin, the
 * React Native module, …). A wrapper's native code only passes strings across its own bridge; the mapping between
 * SDK types and JSON lives here, once, and is tested once (`TrackMeBridgeTest`).
 *
 * - [call]: `method` + a JSON object of arguments → [onResult] is called **once** with
 *   `{"ok":true,"value":…}` or `{"ok":false,"error":{"type":"NotFound","message":"…"}}`.
 * - [observe]: every state change and event as `{"stream":"sharing","value":{…}}` (streams: `state`, `sharing`,
 *   `realtime`, `event`). The current value of each state stream is sent first.
 *
 * JSON uses the API's own snake_case names (see docs/trackme-api.md); server models are passed exactly as the server
 * sends them. Callbacks may arrive on any thread: wrappers hop to their main thread before answering their framework.
 * Native apps use the typed SDK directly; this is only for wrappers.
 */
object TrackMeBridge {
    /** Bumped when a method or a JSON shape changes incompatibly. Wrappers can check it with the "protocol" call. */
    const val PROTOCOL = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Starts an SDK session (`TrackMe.initialize`) from a JSON config (see [configFrom]). Android:
     * `PlatformContext(context)`; iOS: `PlatformContext()`. [tokenProvider]: the wrapper asks its app (Dart / JS) for
     * a fresh token, or passes null. Returns the same JSON envelope as [call].
     * Named `start`, not `initialize`: Objective-C treats `init…` selectors as initializers.
     */
    fun start(context: PlatformContext, configJson: String, tokenProvider: TrackMeTokenProvider?): String =
        envelope(
            runCatching { configFrom(parseObject(configJson), tokenProvider) }
                .fold(
                    onSuccess = { TrackMe.initialize(context, it).map { JsonNull } },
                    onFailure = { TrackMeResult.Failure(TrackMeError.Validation("Invalid config: ${it.message}")) },
                ),
        )

    /** Calls one SDK method by name. See the method list in docs/07-ports.md. */
    fun call(method: String, argsJson: String, onResult: (String) -> Unit) {
        val args = runCatching { parseObject(argsJson) }.getOrElse {
            onResult(envelope(TrackMeResult.Failure(TrackMeError.Validation("Arguments must be a JSON object: ${it.message}"))))
            return
        }
        scope.launch {
            val result = try {
                dispatch(method, args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A missing or mistyped argument: the wrapper's bug, reported instead of crashing the app.
                TrackMeResult.Failure(TrackMeError.Validation("Bad arguments for $method: ${e.message}"))
            }
            onResult(envelope(result))
        }
    }

    /** Streams every state change and event to [onEvent] until [BridgeSubscription.cancel]. */
    fun observe(onEvent: (String) -> Unit): BridgeSubscription {
        val job = SupervisorJob()
        val s = CoroutineScope(scope.coroutineContext + job)
        fun send(stream: String, value: JsonElement) = onEvent(buildJsonObject { put("stream", stream); put("value", value) }.toString())
        s.launch { TrackMe.state.collect { send("state", encode(it)) } }
        s.launch { TrackMe.sharing.state.collect { send("sharing", encode(it)) } }
        s.launch { TrackMe.realtime.state.collect { send("realtime", encode(it)) } }
        s.launch { TrackMe.events.collect { send("event", encode(it)) } }
        return BridgeSubscription(job)
    }

    // ---------- dispatch ----------

    private suspend fun dispatch(method: String, a: JsonObject): TrackMeResult<JsonElement> = when (method) {
        "protocol" -> ok(JsonPrimitive(PROTOCOL))
        "isInitialized" -> ok(JsonPrimitive(TrackMe.isInitialized))
        "state" -> ok(encode(TrackMe.state.value))
        "updateToken" -> TrackMe.updateToken(a.str("token")).map { JsonNull }
        "dispose" -> { TrackMe.dispose(); ok(JsonNull) }
        "checkHealth" -> TrackMe.checkHealth().json()

        "trips.create" -> TrackMe.trips.create(a.strOrNull("name"), a.int("duration_min")).json()
        "trips.list" -> TrackMe.trips.list(a.int("limit")).json()
        "trips.preview" -> TrackMe.trips.preview(a.str("code")).json()
        "trips.join" -> TrackMe.trips.join(a.str("code")).json()
        "trips.detail" -> TrackMe.trips.detail(a.long("trip_id")).json()
        "trips.rename" -> TrackMe.trips.rename(a.long("trip_id"), a.str("name")).json()
        "trips.end" -> TrackMe.trips.end(a.long("trip_id")).json()
        "trips.leave" -> TrackMe.trips.leave(a.long("trip_id")).map { JsonNull }
        "trips.removeMember" -> TrackMe.trips.removeMember(a.long("trip_id"), a.long("user_id")).map { JsonNull }
        "trips.delete" -> TrackMe.trips.delete(a.long("trip_id")).map { JsonNull }
        "trips.viewLink" -> TrackMe.trips.viewLink(a.long("trip_id")).json()
        "trips.rotateViewLink" -> TrackMe.trips.rotateViewLink(a.long("trip_id")).json()
        // Raw history JSON from the server, passed through as JSON (or as a string if it isn't JSON).
        "trips.history" -> TrackMe.trips.history(a.long("trip_id"), a.strOrNull("from"), a.strOrNull("to"))
            .map { raw -> runCatching { TrackMeJson.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) } }

        "sharing.start" -> TrackMe.sharing.start(optionsFrom(a["options"] as? JsonObject)).map { JsonNull }
        "sharing.pause" -> TrackMe.sharing.pause().map { JsonNull }
        "sharing.resume" -> TrackMe.sharing.resume().map { JsonNull }
        "sharing.stop" -> TrackMe.sharing.stop().map { JsonNull }
        "sharing.state" -> ok(encode(TrackMe.sharing.state.value))

        "location.permissionStatus" -> TrackMe.location.permissionStatus().map { encode(it) }

        "realtime.watchTrip" -> TrackMe.realtime.watchTrip(a.long("trip_id")).map { JsonNull }
        "realtime.watchShare" -> TrackMe.realtime.watchShare(a.str("view_token")).map { JsonNull }
        "realtime.stopWatchingTrip" -> TrackMe.realtime.stopWatchingTrip().map { JsonNull }
        "realtime.stopWatchingShare" -> TrackMe.realtime.stopWatchingShare().map { JsonNull }
        "realtime.stop" -> TrackMe.realtime.stop().map { JsonNull }
        "realtime.state" -> ok(encode(TrackMe.realtime.state.value))

        "notifications.list" -> TrackMe.notifications.list(
            unreadOnly = a.boolOrNull("unread_only") ?: false,
            tripId = a.longOrNull("trip_id"),
            cursor = a.strOrNull("cursor"),
            limit = a.intOrNull("limit") ?: 20,
        ).json()
        "notifications.markRead" -> TrackMe.notifications.markRead(a.long("notification_id")).map { JsonNull }
        "notifications.markAllRead" -> TrackMe.notifications.markAllRead().map { JsonNull }

        else -> TrackMeResult.Failure(TrackMeError.Validation("Unknown method: $method"))
    }

    // ---------- JSON in ----------

    /**
     * `{"token":"…", "api_url"?, "socket_url"?, "request_timeout_ms"?, "token_refresh_timeout_ms"?, "sharing_ui"?: {…}}`.
     * `sharing_ui` keys: title, text, paused_text, accent_color (ARGB number), show_pause_action, show_stop_action,
     * pause_label, resume_label, stop_label, android_small_icon (resource id; wrappers resolve names), android_channel_name,
     * ios_symbol_name.
     */
    internal fun configFrom(c: JsonObject, tokenProvider: TrackMeTokenProvider?): TrackMeConfig {
        val d = TrackMeSharingUi()
        val ui = (c["sharing_ui"] as? JsonObject)?.let { u ->
            TrackMeSharingUi(
                title = u.strOrNull("title") ?: d.title,
                text = u.strOrNull("text") ?: d.text,
                pausedText = u.strOrNull("paused_text") ?: d.pausedText,
                accentColor = u.longOrNull("accent_color") ?: d.accentColor,
                showPauseAction = u.boolOrNull("show_pause_action") ?: d.showPauseAction,
                showStopAction = u.boolOrNull("show_stop_action") ?: d.showStopAction,
                pauseLabel = u.strOrNull("pause_label") ?: d.pauseLabel,
                resumeLabel = u.strOrNull("resume_label") ?: d.resumeLabel,
                stopLabel = u.strOrNull("stop_label") ?: d.stopLabel,
                androidSmallIcon = u.intOrNull("android_small_icon") ?: d.androidSmallIcon,
                androidChannelName = u.strOrNull("android_channel_name") ?: d.androidChannelName,
                iosSymbolName = u.strOrNull("ios_symbol_name") ?: d.iosSymbolName,
            )
        } ?: d
        return TrackMeConfig(
            token = c.str("token"),
            tokenProvider = tokenProvider,
            apiUrl = c.strOrNull("api_url") ?: TrackMeConfig.DEFAULT_API_URL,
            socketUrl = c.strOrNull("socket_url") ?: TrackMeConfig.DEFAULT_SOCKET_URL,
            requestTimeoutMillis = c.longOrNull("request_timeout_ms") ?: 15_000,
            tokenRefreshTimeoutMillis = c.longOrNull("token_refresh_timeout_ms") ?: 30_000,
            sharingUi = ui,
        )
    }

    /** `{"interval_ms"?, "min_distance_m"?, "heartbeat_ms"?, "high_accuracy"?}`; missing keys keep the defaults. */
    internal fun optionsFrom(o: JsonObject?): SharingOptions {
        val d = SharingOptions.Default
        if (o == null) return d
        return SharingOptions(
            intervalMillis = o.longOrNull("interval_ms") ?: d.intervalMillis,
            minDistanceMeters = o.doubleOrNull("min_distance_m") ?: d.minDistanceMeters,
            heartbeatMillis = o.longOrNull("heartbeat_ms") ?: d.heartbeatMillis,
            highAccuracy = o.boolOrNull("high_accuracy") ?: d.highAccuracy,
        )
    }

    // ---------- JSON out ----------

    internal fun encode(s: TrackMeState): JsonElement = buildJsonObject {
        put("type", when (s) { TrackMeState.NotInitialized -> "NotInitialized"; TrackMeState.Ready -> "Ready"; TrackMeState.Disposed -> "Disposed" })
    }

    internal fun encode(s: SharingState): JsonElement = buildJsonObject {
        when (s) {
            SharingState.Idle -> put("type", "Idle")
            SharingState.Starting -> put("type", "Starting")
            SharingState.Paused -> put("type", "Paused")
            is SharingState.Stopped -> { put("type", "Stopped"); put("reason", s.reason.name) }
            is SharingState.Sharing -> {
                put("type", "Sharing")
                put("trip_ids", buildJsonArray { s.tripIds.forEach { add(JsonPrimitive(it)) } })
                s.lastLocation?.let { put("last_location", encode(it)) }
                s.lastSentAtMillis?.let { put("last_sent_at_ms", it) }
                put("retrying", s.retrying)
            }
        }
    }

    internal fun encode(s: RealtimeState): JsonElement = buildJsonObject {
        when (s) {
            RealtimeState.Idle -> put("type", "Idle")
            RealtimeState.Connecting -> put("type", "Connecting")
            is RealtimeState.Reconnecting -> { put("type", "Reconnecting"); put("attempt", s.attempt) }
            is RealtimeState.Connected -> {
                put("type", "Connected")
                s.tripId?.let { put("trip_id", it) }
                put("watching_share", s.watchingShare)
            }
        }
    }

    internal fun encode(l: TrackMeLocation): JsonElement = buildJsonObject {
        put("latitude", l.latitude)
        put("longitude", l.longitude)
        put("timestamp_ms", l.timestampMillis)
        l.accuracyMeters?.let { put("accuracy_m", it) }
        l.altitudeMeters?.let { put("altitude_m", it) }
        l.speedMetersPerSecond?.let { put("speed_mps", it) }
        l.bearingDegrees?.let { put("bearing_deg", it) }
    }

    internal fun encode(p: LocationPermissionStatus): JsonElement = buildJsonObject {
        put("level", p.level.name)
        put("precise", p.precise)
        put("services_enabled", p.servicesEnabled)
        put("background_capable", p.backgroundCapable)
    }

    internal fun encode(p: MemberPosition): JsonElement = buildJsonObject {
        p.tripId?.let { put("trip_id", it) }
        put("user_id", p.userId)
        p.fullName?.let { put("display_name", it) }
        put("latitude", p.latitude)
        put("longitude", p.longitude)
        p.speedMetersPerSecond?.let { put("speed_mps", it) }
        p.fixAgeSeconds?.let { put("fix_age_s", it) }
    }

    internal fun encode(e: TrackMeEvent): JsonElement = buildJsonObject {
        when (e) {
            TrackMeEvent.AuthenticationFailed -> put("type", "AuthenticationFailed")
            TrackMeEvent.TokenRefreshed -> put("type", "TokenRefreshed")
            TrackMeEvent.ShareEnded -> put("type", "ShareEnded")
            is TrackMeEvent.SharingStopped -> { put("type", "SharingStopped"); put("reason", e.reason.name) }
            is TrackMeEvent.TripPosition -> { put("type", "TripPosition"); put("position", encode(e.position)) }
            is TrackMeEvent.SharePosition -> { put("type", "SharePosition"); put("position", encode(e.position)) }
            is TrackMeEvent.MemberChanged -> {
                put("type", "MemberChanged")
                put("trip_id", e.change.tripId)
                put("user_id", e.change.userId)
                e.change.displayName?.let { put("display_name", it) }
                put("change", e.change.change)
            }
            is TrackMeEvent.MemberSharingChanged -> {
                put("type", "MemberSharingChanged")
                put("trip_id", e.tripId)
                put("user_id", e.userId)
                e.displayName?.let { put("display_name", it) }
                put("state", e.state.wireName)
            }
            is TrackMeEvent.TripEnded -> { put("type", "TripEnded"); e.tripId?.let { put("trip_id", it) }; e.reason?.let { put("reason", it) } }
            is TrackMeEvent.Evicted -> { put("type", "Evicted"); e.tripId?.let { put("trip_id", it) }; e.reason?.let { put("reason", it) } }
        }
    }

    internal fun encode(e: TrackMeError): JsonElement = buildJsonObject {
        put("type", e::class.simpleName ?: "Unknown")
        put("message", e.message)
        when (e) {
            is TrackMeError.Server -> put("status_code", e.statusCode)
            is TrackMeError.LocationPermissionDenied -> put("can_ask_again", e.canAskAgain)
            else -> Unit
        }
    }

    internal fun envelope(r: TrackMeResult<JsonElement>): String = when (r) {
        is TrackMeResult.Success -> buildJsonObject { put("ok", true); put("value", r.data) }
        is TrackMeResult.Failure -> buildJsonObject { put("ok", false); put("error", encode(r.error)) }
    }.toString()

    // ---------- helpers ----------

    private fun ok(v: JsonElement): TrackMeResult<JsonElement> = TrackMeResult.Success(v)

    /** Server models are @Serializable with the API's field names: encode them as they are. */
    private inline fun <reified T> TrackMeResult<T>.json(): TrackMeResult<JsonElement> = map { TrackMeJson.encodeToJsonElement(it) }

    private inline fun <T, R> TrackMeResult<T>.map(f: (T) -> R): TrackMeResult<R> = when (this) {
        is TrackMeResult.Success -> TrackMeResult.Success(f(data))
        is TrackMeResult.Failure -> this
    }

    private fun parseObject(json: String): JsonObject =
        if (json.isBlank()) JsonObject(emptyMap()) else TrackMeJson.parseToJsonElement(json).jsonObject

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(key: String): String = strOrNull(key) ?: throw IllegalArgumentException("missing \"$key\"")
    private fun JsonObject.strOrNull(key: String): String? = prim(key)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long = prim(key)?.long ?: throw IllegalArgumentException("missing \"$key\"")
    private fun JsonObject.longOrNull(key: String): Long? = prim(key)?.longOrNull ?: prim(key)?.doubleOrNull?.toLong()
    private fun JsonObject.int(key: String): Int = prim(key)?.int ?: throw IllegalArgumentException("missing \"$key\"")
    private fun JsonObject.intOrNull(key: String): Int? = prim(key)?.intOrNull
    private fun JsonObject.doubleOrNull(key: String): Double? = prim(key)?.doubleOrNull
    private fun JsonObject.boolOrNull(key: String): Boolean? = prim(key)?.booleanOrNull

    /** For tests: stop everything the bridge launched. */
    internal fun resetForTest() = scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
}

/** Handle returned by [TrackMeBridge.observe]. */
class BridgeSubscription internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

