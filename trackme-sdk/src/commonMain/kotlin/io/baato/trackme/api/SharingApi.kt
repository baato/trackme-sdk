package io.baato.trackme.api

import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeResult
import io.baato.trackme.location.LocationPermissionStatus
import io.baato.trackme.sharing.SharingOptions
import io.baato.trackme.sharing.SharingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live location sharing. Access via [TrackMe.sharing].
 *
 * The server fans each upload out to **every live trip the user is in**: create or join a trip first,
 * then call [start]. Sharing stops by itself when the user is in no live trip any more.
 *
 * Typical flow:
 * ```kotlin
 * when (val r = TrackMe.sharing.start()) {
 *     is TrackMeResult.Success -> Unit // observe TrackMe.sharing.state
 *     is TrackMeResult.Failure -> when (val e = r.error) {
 *         is TrackMeError.LocationPermissionDenied -> if (e.canAskAgain) askPermission() else openSettings()
 *         TrackMeError.LocationServicesDisabled -> promptTurnOnLocation()
 *         is TrackMeError.BackgroundNotAllowed -> Unit // only start while visible
 *         else -> showError(e.message)
 *     }
 * }
 * ```
 */
class SharingApi internal constructor(private val sdk: TrackMe) {

    internal val mutableState = MutableStateFlow<SharingState>(SharingState.Idle)

    /** Idle → Starting → Sharing ⇄ Paused → Stopped(reason). Survives dispose: shows Stopped(Disposed). */
    val state: StateFlow<SharingState> = mutableState.asStateFlow()

    /** Starts GPS + uploads + the platform "sharing" UI. Calling it while already sharing is a no-op Success. */
    fun start(options: SharingOptions): TrackMeResult<Unit> =
        sdk.currentSession()?.sharing?.start(options) ?: TrackMeResult.Failure(TrackMeError.NotInitialized)

    /** [start] with [SharingOptions.Default]. */
    fun start(): TrackMeResult<Unit> = start(SharingOptions.Default)

    /** Stops uploading; GPS and the notification / Live Activity stay so the user can resume. */
    fun pause(): TrackMeResult<Unit> =
        sdk.currentSession()?.sharing?.pause() ?: TrackMeResult.Failure(TrackMeError.NotInitialized)

    fun resume(): TrackMeResult<Unit> =
        sdk.currentSession()?.sharing?.resume() ?: TrackMeResult.Failure(TrackMeError.NotInitialized)

    /** Stops everything sharing-related. Always safe. */
    fun stop(): TrackMeResult<Unit> =
        sdk.currentSession()?.sharing?.stop() ?: TrackMeResult.Success(Unit)
}

/** Location permission info. Access via [TrackMe.location]. The SDK never shows a permission prompt itself. */
class LocationApi internal constructor(private val sdk: TrackMe) {
    /** Current permission, accuracy and device-location state. Cheap; call it whenever your screen resumes. */
    fun permissionStatus(): TrackMeResult<LocationPermissionStatus> {
        val session = sdk.currentSession() ?: return TrackMeResult.Failure(TrackMeError.NotInitialized)
        return runCatching { session.platform.permissionStatus() }
            .fold({ TrackMeResult.Success(it) }, { TrackMeResult.Failure(TrackMeError.Unknown(it.message ?: "permission check failed")) })
    }
}
