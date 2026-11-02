package io.baato.trackme

import io.baato.trackme.sharing.TrackMeSharingUi

/**
 * Everything the host app supplies to [TrackMe.initialize].
 *
 * The SDK never logs in and never holds the API secret: the host app obtains an access token
 * (ideally its backend calls `POST /api/v1/auth/login`) and passes it here.
 *
 * Kotlin: `TrackMeConfig(token = t, tokenProvider = myProvider)`
 * Swift:  `TrackMeConfig(token: t, tokenProvider: myProvider)`
 */
class TrackMeConfig(
    /** Current access token (sent as `Authorization: Bearer <token>`). */
    val token: String,
    /** Called when the server answers 401. Return a fresh token, or null to give up. Optional. */
    val tokenProvider: TrackMeTokenProvider? = null,
    val apiUrl: String = DEFAULT_API_URL,
    val socketUrl: String = DEFAULT_SOCKET_URL,
    val requestTimeoutMillis: Long = 15_000,
    /** How long to wait for [TrackMeTokenProvider.refreshToken] to call back before giving up. */
    val tokenRefreshTimeoutMillis: Long = 30_000,
    /** Look and labels of the "sharing location" notification / Live Activity. */
    val sharingUi: TrackMeSharingUi = TrackMeSharingUi(),
) {
    /** Short forms for Swift, which doesn't see Kotlin default arguments. */
    constructor(token: String, tokenProvider: TrackMeTokenProvider?) :
        this(token, tokenProvider, DEFAULT_API_URL, DEFAULT_SOCKET_URL, 15_000, 30_000, TrackMeSharingUi())

    constructor(token: String, tokenProvider: TrackMeTokenProvider?, sharingUi: TrackMeSharingUi) :
        this(token, tokenProvider, DEFAULT_API_URL, DEFAULT_SOCKET_URL, 15_000, 30_000, sharingUi)

    companion object {
        const val DEFAULT_API_URL = "https://tracker.baato.io/trackme/api"
        const val DEFAULT_SOCKET_URL = "https://tracker.baato.io/trackme"
    }
}

/**
 * Supplies a fresh access token when the current one is rejected (HTTP 401).
 *
 * Callback-based on purpose: it is trivial to implement from Swift
 * (`func refreshToken(onResult: @escaping (String?) -> Void)`), and from Kotlin.
 * Call [onResult] exactly once, from any thread. Extra calls are ignored. If it isn't called within
 * [TrackMeConfig.tokenRefreshTimeoutMillis], the SDK treats it as `null`.
 */
interface TrackMeTokenProvider {
    fun refreshToken(onResult: (String?) -> Unit)
}

/** Kotlin convenience: `tokenProvider { myBackend.fetchToken() }`. */
fun tokenProvider(block: suspend () -> String?): TrackMeTokenProvider = SuspendTokenProvider(block)

internal class SuspendTokenProvider(val block: suspend () -> String?) : TrackMeTokenProvider {
    override fun refreshToken(onResult: (String?) -> Unit) =
        error("SuspendTokenProvider is awaited directly, never via callback")
}
