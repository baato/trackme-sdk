@file:OptIn(ExperimentalAtomicApi::class)

package io.baato.trackme

import io.baato.trackme.api.LocationApi
import io.baato.trackme.api.NotificationsApi
import io.baato.trackme.api.RealtimeApi
import io.baato.trackme.api.SharingApi
import io.baato.trackme.api.TripsApi
import io.baato.trackme.model.HealthResponse
import io.baato.trackme.platform.PlatformServices
import io.baato.trackme.realtime.RealtimeState
import io.baato.trackme.realtime.RealtimeTransport
import io.baato.trackme.sharing.SharingState
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The SDK's single entry point.
 *
 * ```kotlin
 * // Android
 * TrackMe.initialize(context, TrackMeConfig(token = accessToken, tokenProvider = tokenProvider { api.newToken() }))
 * when (val r = TrackMe.trips.create("School run", durationMin = 60)) {
 *     is TrackMeResult.Success -> show(r.data.joinCode)
 *     is TrackMeResult.Failure -> show(r.error.message)
 * }
 * TrackMe.dispose()
 * ```
 * ```swift
 * // iOS
 * TrackMe.shared.initialize(context: PlatformContext(), config: TrackMeConfig(token: token, tokenProvider: provider))
 * let r = try await TrackMe.shared.trips.create(name: "School run", durationMin: 60)
 * TrackMe.shared.dispose()
 * ```
 *
 * Thread-safe: any method may be called from any thread, in any order.
 */
object TrackMe {
    private val session = AtomicReference<Session?>(null)

    private val _state = MutableStateFlow<TrackMeState>(TrackMeState.NotInitialized)
    /** NotInitialized → Ready → Disposed (→ Ready again after a new initialize). */
    val state: StateFlow<TrackMeState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TrackMeEvent>(extraBufferCapacity = 64)
    /** One-off events: auth, sharing stopped, and live updates from [realtime]. */
    val events: SharedFlow<TrackMeEvent> = _events.asSharedFlow()

    val isInitialized: Boolean get() = session.load() != null

    /**
     * Debug only: receives SDK diagnostics (socket handshake, join answers, every live message with its raw
     * payload, anything the SDK couldn't read). Null (the default) = silent. Called from a background thread.
     *
     * ```kotlin
     * if (BuildConfig.DEBUG) TrackMe.debugLogger = { Log.d("TrackMe", it) }
     * ```
     * ```swift
     * TrackMe.shared.debugLogger = { print("TrackMe:", $0) }
     * ```
     */
    @kotlin.concurrent.Volatile
    var debugLogger: ((String) -> Unit)? = null

    /** Trip endpoints. Safe to touch before initialize: calls return Failure(NotInitialized). */
    val trips: TripsApi = TripsApi(this)

    /** Inbox endpoints. */
    val notifications: NotificationsApi = NotificationsApi(this)

    /** Live location sharing: start / pause / resume / stop + observable state. */
    val sharing: SharingApi = SharingApi(this)

    /** Location permission status (the SDK never prompts; the app owns permission UI). */
    val location: LocationApi = LocationApi(this)

    /** Live positions / member changes over the socket. Events arrive on [events]. */
    val realtime: RealtimeApi = RealtimeApi(this)

    /**
     * Starts an SDK session. Calling it again replaces the previous session (e.g. a different user):
     * the old one is fully disposed first.
     */
    fun initialize(context: PlatformContext, config: TrackMeConfig): TrackMeResult<Unit> =
        start(context, config, engine = null)

    /** For apps that refresh tokens on their own schedule. The next request uses the new token. */
    fun updateToken(token: String): TrackMeResult<Unit> {
        if (token.isBlank()) return TrackMeResult.Failure(TrackMeError.Validation("token must not be blank"))
        val s = session.load() ?: return TrackMeResult.Failure(TrackMeError.NotInitialized)
        s.updateToken(token)
        return TrackMeResult.Success(Unit)
    }

    /**
     * Stops everything this SDK started: location sharing (state → Stopped(Disposed)), the notification /
     * Live Activity, the socket, HTTP, coroutines. Safe to call any number of times, including before initialize.
     */
    fun dispose() {
        val old = session.exchange(null) ?: return
        runCatching { old.close() }
        realtime.mutableState.value = RealtimeState.Idle
        _state.value = TrackMeState.Disposed
    }

    /** `GET /api/health`. Doesn't need a valid token. */
    suspend fun checkHealth(): TrackMeResult<HealthResponse> = call { http.get("health").body() }

    // ---- internal ----

    internal fun currentSession(): Session? = session.load()

    /** https / wss anywhere; http / ws only to a local dev host (the Android emulator reaches the Mac as 10.0.2.2). */
    internal fun isSecureOrLocal(url: String): Boolean {
        val u = url.trim().lowercase()
        if (u.startsWith("https://") || u.startsWith("wss://")) return true
        if (!u.startsWith("http://") && !u.startsWith("ws://")) return false
        val host = u.substringAfter("://").substringBefore('/').let { hp ->
            if (hp.startsWith("[")) hp.substringBefore(']') + "]" else hp.substringBefore(':')
        }
        return host in setOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2")
    }

    /** Tests inject a MockEngine here and pass no PlatformContext. */
    internal fun start(
        context: PlatformContext?,
        config: TrackMeConfig,
        engine: HttpClientEngine?,
        platform: PlatformServices? = null,
        realtimeTransport: RealtimeTransport? = null,
    ): TrackMeResult<Unit> {
        if (config.token.isBlank()) return TrackMeResult.Failure(TrackMeError.Validation("token must not be blank"))
        // The access token travels with every request and the socket handshake: never over cleartext,
        // except to a local development server.
        if (!isSecureOrLocal(config.apiUrl)) return TrackMeResult.Failure(TrackMeError.Validation("apiUrl must be https:// (http:// only for localhost / 10.0.2.2)"))
        if (!isSecureOrLocal(config.socketUrl)) return TrackMeResult.Failure(TrackMeError.Validation("socketUrl must be https:// or wss:// (http/ws only for localhost / 10.0.2.2)"))
        // A previous session (if any) is closed first, so its sharing stops before the new one can start.
        session.exchange(null)?.let { old -> runCatching { old.close() } }
        sharing.mutableState.value = SharingState.Idle
        realtime.mutableState.value = RealtimeState.Idle
        val created = runCatching {
            Session(config, context, engine, platform, sharing.mutableState, realtime.mutableState, realtimeTransport) { _events.tryEmit(it) }
        }
            .getOrElse { return TrackMeResult.Failure(TrackMeError.Unknown("Could not start SDK: ${it.message}")) }
        session.exchange(created)?.let { old -> runCatching { old.close() } }   // lost a race with another initialize
        _state.value = TrackMeState.Ready
        return TrackMeResult.Success(Unit)
    }
}
