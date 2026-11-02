package io.baato.trackme.api

import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeResult
import io.baato.trackme.realtime.RealtimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live updates over the TrackMe socket. Access via [TrackMe.realtime]. Updates arrive on [TrackMe.events]:
 * `TripPosition`, `SharePosition`, `MemberChanged`, `TripEnded`, `ShareEnded`, `Evicted`.
 *
 * ```kotlin
 * TrackMe.realtime.watchTrip(trip.id)            // you must be a joined member of a live trip
 * TrackMe.events.collect { e ->
 *     when (e) {
 *         is TrackMeEvent.TripPosition -> map.move(e.position.userId, e.position.latitude, e.position.longitude)
 *         is TrackMeEvent.MemberChanged -> reloadMembers()
 *         is TrackMeEvent.TripEnded, is TrackMeEvent.Evicted -> close()
 *         else -> Unit
 *     }
 * }
 * TrackMe.realtime.stopWatchingTrip()
 * ```
 *
 * One connection serves everything: one trip (the server allows one trip room per socket; watching another trip
 * replaces it) plus any view link. The SDK reconnects by itself with backoff and re-subscribes; [state] shows it.
 * Watching does not upload your location; that's [TrackMe.sharing].
 */
class RealtimeApi internal constructor(private val sdk: TrackMe) {

    internal val mutableState = MutableStateFlow<RealtimeState>(RealtimeState.Idle)

    /** Idle → Connecting → Connected ⇄ Reconnecting(attempt) → Idle. */
    val state: StateFlow<RealtimeState> = mutableState.asStateFlow()

    /**
     * Subscribe to a trip's live positions and member changes. Suspends until the server confirms (≤ ~25 s).
     * Failures: Unauthorized ("Not authenticated"), Conflict ("Trip has ended"), Validation, Network (couldn't connect),
     * Unknown (any other server message, e.g. not a member). After a failure the SDK is not watching that trip.
     */
    suspend fun watchTrip(tripId: Long): TrackMeResult<Unit> =
        sdk.currentSession()?.realtime?.watchTrip(tripId) ?: TrackMeResult.Failure(TrackMeError.NotInitialized)

    /**
     * Subscribe to a public view link by its **view token** (`join_share {token}`). Positions arrive as
     * `SharePosition`. Failures: NotFound ("Share not found or expired"), Validation (blank token), Network.
     *
     * The API returns the link as a URL (`view_link`), not the token by itself, so an app that only has the link
     * has to take the token out of it; that's the app's decision about the link format, not the SDK's.
     */
    suspend fun watchShare(viewToken: String): TrackMeResult<Unit> =
        sdk.currentSession()?.realtime?.watchShare(viewToken) ?: TrackMeResult.Failure(TrackMeError.NotInitialized)

    /** Stop the trip subscription. Always safe. */
    fun stopWatchingTrip(): TrackMeResult<Unit> {
        sdk.currentSession()?.realtime?.stopWatchingTrip()
        return TrackMeResult.Success(Unit)
    }

    /** Stop the view-link subscription. Always safe. */
    fun stopWatchingShare(): TrackMeResult<Unit> {
        sdk.currentSession()?.realtime?.stopWatchingShare()
        return TrackMeResult.Success(Unit)
    }

    /** Stop everything and close the socket. Always safe; dispose() does this too. */
    fun stop(): TrackMeResult<Unit> {
        sdk.currentSession()?.realtime?.stop()
        return TrackMeResult.Success(Unit)
    }
}
