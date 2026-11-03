package io.baato.trackme.platform

import io.baato.trackme.PlatformContext
import io.baato.trackme.TrackMeResult
import io.baato.trackme.location.BatteryInfo
import io.baato.trackme.location.LocationPermissionStatus
import io.baato.trackme.location.LocationUpdate
import io.baato.trackme.sharing.SharingOptions
import io.baato.trackme.sharing.SharingState
import io.baato.trackme.sharing.SharingWindow
import io.baato.trackme.sharing.TrackMeSharingUi
import kotlinx.coroutines.flow.Flow

/**
 * Everything the shared sharing engine needs from the OS, behind one interface.
 *
 * KMP design note: instead of many tiny `expect fun`s, there is ONE `expect fun createPlatformServices()`
 * that returns an interface. Common code depends only on the interface, so tests can pass a fake.
 */
internal interface PlatformServices {
    fun permissionStatus(): LocationPermissionStatus

    /** Cold flow: collecting starts GPS, cancelling stops it. Must never throw; problems become LocationUpdates. */
    fun locationUpdates(options: SharingOptions): Flow<LocationUpdate>

    fun battery(): BatteryInfo

    /** true if the host app is currently visible to the user. */
    fun isAppInForeground(): Boolean

    /**
     * Shows the platform "you're sharing" UI and keeps the process allowed to receive location in background
     * (Android foreground service, iOS presenter / background flags). Returns Failure(BackgroundNotAllowed)
     * if the OS would refuse.
     */
    fun startSharingHost(ui: TrackMeSharingUi): TrackMeResult<Unit>

    /**
     * Called on every SharingState change so the notification / Live Activity stays current.
     * [window] = when the shared trips end (null until known), for a countdown.
     */
    fun updateSharingHost(ui: TrackMeSharingUi, state: SharingState, window: SharingWindow?)

    fun stopSharingHost()

    fun nowMillis(): Long
}

internal expect fun createPlatformServices(context: PlatformContext?): PlatformServices
