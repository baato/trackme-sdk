package io.baato.trackme

/** Lifecycle of the SDK, observable via [TrackMe.state]. */
sealed class TrackMeState {
    data object NotInitialized : TrackMeState()
    data object Ready : TrackMeState()
    data object Disposed : TrackMeState()
}

/**
 * One-off things that happen while the SDK runs, observable via [TrackMe.events].
 * Live updates (positions, members, trip end) arrive here once you call `TrackMe.realtime.watchTrip` / `watchShare`.
 */
sealed class TrackMeEvent {
    /** A 401 couldn't be recovered via the token provider. Get a new token and call [TrackMe.updateToken]. */
    data object AuthenticationFailed : TrackMeEvent()

    /** The token provider supplied a new token, which the SDK now uses. */
    data object TokenRefreshed : TrackMeEvent()

    /** Location sharing ended. Also visible as `TrackMe.sharing.state == Stopped(reason)`. */
    data class SharingStopped(val reason: io.baato.trackme.sharing.StopReason) : TrackMeEvent()

    // ---- realtime (TrackMe.realtime) ----

    /** A member of the watched trip moved (`trip_position`). */
    data class TripPosition(val position: io.baato.trackme.realtime.MemberPosition) : TrackMeEvent()

    /** A position on the watched view link (`share_position`). */
    data class SharePosition(val position: io.baato.trackme.realtime.MemberPosition) : TrackMeEvent()

    /** Someone joined the watched trip (`member_changed`). Refresh the member list if you show one. */
    data class MemberChanged(val change: io.baato.trackme.realtime.MemberChange) : TrackMeEvent()

    /** The watched trip ended (owner ended it, or it expired). The SDK stops watching it. */
    data class TripEnded(val tripId: Long?, val reason: String?) : TrackMeEvent()

    /** The watched view link ended or was rotated. The SDK stops watching it. */
    data object ShareEnded : TrackMeEvent()

    /** The server removed this user from the watched trip's live room (kicked, left on another device, ended). */
    data class Evicted(val tripId: Long?, val reason: String?) : TrackMeEvent()
}
