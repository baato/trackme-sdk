package io.baato.trackme

import io.baato.trackme.location.BatteryInfo
import io.baato.trackme.location.LocationPermissionLevel
import io.baato.trackme.location.LocationPermissionStatus
import io.baato.trackme.location.LocationUpdate
import io.baato.trackme.location.TrackMeLocation
import io.baato.trackme.platform.PlatformServices
import io.baato.trackme.sharing.SharingOptions
import io.baato.trackme.sharing.SharingState
import io.baato.trackme.sharing.SharingWindow
import io.baato.trackme.sharing.TrackMeSharingUi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.TimeSource

/** A scriptable OS: tests set permission/foreground/host behaviour and push GPS updates. */
internal class FakePlatform(
    var permission: LocationPermissionStatus = granted(),
    var foreground: Boolean = true,
    var hostResult: TrackMeResult<Unit> = TrackMeResult.Success(Unit),
) : PlatformServices {
    val updates = Channel<LocationUpdate>(Channel.UNLIMITED)
    // Written from the SDK's threads, read by tests: @Volatile for visibility.
    @kotlin.concurrent.Volatile var hostStarted = 0
    @kotlin.concurrent.Volatile var hostStopped = 0

    override fun permissionStatus() = permission
    override fun locationUpdates(options: SharingOptions): Flow<LocationUpdate> = updates.receiveAsFlow()
    override fun battery() = BatteryInfo(80, false)
    override fun isAppInForeground() = foreground
    override fun startSharingHost(ui: TrackMeSharingUi): TrackMeResult<Unit> { hostStarted++; return hostResult }
    @kotlin.concurrent.Volatile var lastWindow: SharingWindow? = null
    override fun updateSharingHost(ui: TrackMeSharingUi, state: SharingState, window: SharingWindow?) {
        if (window != null) lastWindow = window
    }
    override fun stopSharingHost() { hostStopped++ }
    private val started = TimeSource.Monotonic.markNow()
    override fun nowMillis(): Long = 1_761_600_000_000 + started.elapsedNow().inWholeMilliseconds

    fun push(lat: Double, lon: Double) {
        updates.trySend(
            LocationUpdate.Fix(TrackMeLocation(lat, lon, nowMillis(), 5.0, 1300.0, 1.2, 90.0)),
        )
    }

    companion object {
        fun granted(level: LocationPermissionLevel = LocationPermissionLevel.WhileInUse) =
            LocationPermissionStatus(level, precise = true, servicesEnabled = true, backgroundCapable = true)
    }
}
