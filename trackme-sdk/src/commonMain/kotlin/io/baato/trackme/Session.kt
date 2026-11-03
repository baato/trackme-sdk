@file:OptIn(ExperimentalAtomicApi::class)

package io.baato.trackme

import io.baato.trackme.network.TokenStore
import io.baato.trackme.platform.PlatformServices
import io.baato.trackme.platform.createPlatformServices
import io.baato.trackme.realtime.KtorRealtimeTransport
import io.baato.trackme.realtime.RealtimeController
import io.baato.trackme.realtime.RealtimeState
import io.baato.trackme.realtime.RealtimeTransport
import io.baato.trackme.sharing.SharingController
import io.baato.trackme.sharing.SharingState
import io.baato.trackme.network.TrackMeJson
import io.baato.trackme.network.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume

/**
 * Everything that lives between initialize() and dispose(). Dropping the Session is how dispose
 * guarantees that nothing (HTTP, GPS, the notification, the socket) keeps running.
 */
internal class Session(
    val config: TrackMeConfig,
    val context: PlatformContext?,
    engine: HttpClientEngine?,
    platformOverride: PlatformServices?,
    sharingState: MutableStateFlow<SharingState>,
    realtimeState: MutableStateFlow<RealtimeState>,
    realtimeTransport: RealtimeTransport?,
    private val onEvent: (TrackMeEvent) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tokens = TokenStore(config.token)
    val http: HttpClient = createHttpClient(config, tokens, ::refreshToken, engine)
    val platform: PlatformServices = platformOverride ?: createPlatformServices(context)
    val sharing = SharingController(scope, http, platform, config.sharingUi, sharingState, onEvent)
    val realtime = RealtimeController(
        scope = scope,
        transport = realtimeTransport ?: KtorRealtimeTransport(engine),
        socketUrl = config.socketUrl,
        currentToken = { tokens.current() },
        refreshToken = ::refreshToken,
        state = realtimeState,
        emit = onEvent,
    )

    internal suspend fun refreshToken(): String? {
        val fresh = config.tokenProvider?.await(config.tokenRefreshTimeoutMillis)?.takeIf { it.isNotBlank() }
        if (fresh == null) {
            onEvent(TrackMeEvent.AuthenticationFailed)
        } else {
            tokens.set(fresh)
            onEvent(TrackMeEvent.TokenRefreshed)
        }
        return fresh
    }

    /** Swap the token and drop Ktor's cached copy so the very next request uses it. */
    fun updateToken(token: String) {
        tokens.set(token)
        http.authProvider<BearerAuthProvider>()?.clearToken()
    }

    fun close() {
        runCatching { sharing.shutdown() }   // stops GPS + notification/Live Activity before anything else
        runCatching { realtime.shutdown() }  // closes the socket
        scope.cancel()
        http.close()
    }
}

/** Waits for the app's callback, at most once, with a timeout, never throwing. */
internal suspend fun TrackMeTokenProvider.await(timeoutMillis: Long): String? = withTimeoutOrNull(timeoutMillis) {
    if (this@await is SuspendTokenProvider) {
        return@withTimeoutOrNull runCatching { block() }.getOrNull()
    }
    suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        try {
            refreshToken { token ->
                if (done.compareAndSet(false, true) && cont.isActive) cont.resume(token)
            }
        } catch (t: Throwable) {
            if (done.compareAndSet(false, true) && cont.isActive) cont.resume(null)
        }
    }
}

/**
 * The one place where exceptions are turned into [TrackMeResult]s. Every public API call goes through here.
 */
internal suspend fun <T> TrackMe.call(block: suspend Session.() -> T): TrackMeResult<T> {
    val session = currentSession() ?: return TrackMeResult.Failure(TrackMeError.NotInitialized)
    return try {
        TrackMeResult.Success(session.block())
    } catch (e: CancellationException) {
        // The caller was cancelled → let cancellation propagate (structured concurrency).
        // Otherwise the session was disposed mid-request → report it as a value.
        if (!currentCoroutineContext().isActive) throw e
        TrackMeResult.Failure(TrackMeError.NotInitialized)
    } catch (e: Throwable) {
        TrackMeResult.Failure(e.toTrackMeError())
    }
}

internal suspend fun Throwable.toTrackMeError(): TrackMeError = when (this) {
    is ResponseException -> {
        val status = response.status.value
        val detail = runCatching { response.bodyAsText() }.getOrNull()?.let(::extractDetail)
            ?: response.status.description
        when (status) {
            401 -> TrackMeError.Unauthorized()
            403 -> TrackMeError.Forbidden(detail)
            404 -> TrackMeError.NotFound(detail)
            409 -> TrackMeError.Conflict(detail)
            400, 422 -> TrackMeError.Validation(detail)
            in 500..599 -> TrackMeError.Server(status, detail)
            else -> TrackMeError.Unknown("HTTP $status: $detail")
        }
    }
    is HttpRequestTimeoutException -> TrackMeError.Network("Request timed out.")
    is kotlinx.io.IOException -> TrackMeError.Network(message ?: "Network error.")
    is SerializationException -> TrackMeError.Unknown("Unexpected response from server: $message")
    else -> TrackMeError.Unknown(message ?: this::class.simpleName ?: "Unknown error")
}

/**
 * FastAPI errors: `{"detail":"text"}` or `{"detail":[{"msg":"...","loc":["body","duration_min"]}]}`.
 * Validation messages get their field name in front ("duration_min: Input should be …"): the bare `msg` doesn't
 * say which input it means.
 */
internal fun extractDetail(body: String): String? = runCatching {
    when (val detail = TrackMeJson.parseToJsonElement(body).jsonObject["detail"]) {
        is JsonPrimitive -> detail.contentOrNull
        is JsonArray -> detail.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val msg = obj["msg"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val field = (obj["loc"] as? JsonArray)?.lastOrNull()?.jsonPrimitive?.contentOrNull
            if (field != null) "$field: $msg" else msg
        }.joinToString("; ").ifBlank { null }
        else -> null
    }
}.getOrNull()
