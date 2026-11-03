@file:OptIn(ExperimentalForeignApi::class)

package io.baato.trackme.platform

import io.baato.trackme.PlatformContext
import io.baato.trackme.TrackMeResult
import io.baato.trackme.location.BatteryInfo
import io.baato.trackme.location.LocationPermissionLevel
import io.baato.trackme.location.LocationPermissionStatus
import io.baato.trackme.location.LocationUpdate
import io.baato.trackme.location.TrackMeLocation
import io.baato.trackme.sharing.IosSharingHost
import io.baato.trackme.sharing.SharingWindow
import io.baato.trackme.sharing.SharingOptions
import io.baato.trackme.sharing.SharingState
import io.baato.trackme.sharing.TrackMeSharingUi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLActivityTypeOther
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

internal actual fun createPlatformServices(context: PlatformContext?): PlatformServices = IosPlatformServices

/** `kCLErrorDenied` (CLError raw value 1). Raw value avoids depending on how cinterop maps the CLError enum. */
private const val CL_ERROR_DENIED = 1L

internal object IosPlatformServices : PlatformServices {

    // ---------- permission ----------

    override fun permissionStatus(): LocationPermissionStatus {
        val manager = CLLocationManager()
        val level = when (manager.authorizationStatus) {
            kCLAuthorizationStatusNotDetermined -> LocationPermissionLevel.NotDetermined
            kCLAuthorizationStatusAuthorizedAlways -> LocationPermissionLevel.Always
            kCLAuthorizationStatusAuthorizedWhenInUse -> LocationPermissionLevel.WhileInUse
            else -> LocationPermissionLevel.Denied   // denied or restricted (parental controls / MDM)
        }
        return LocationPermissionStatus(
            level = level,
            precise = manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy,
            servicesEnabled = servicesEnabled(),
            backgroundCapable = hasBackgroundLocationMode,
        )
    }

    /**
     * `CLLocationManager.locationServicesEnabled()` can block, and Apple warns against calling it on the main thread
     * (where apps call permissionStatus()). There the last known value is returned and a fresh one is read in the
     * background; iOS calls the app's `locationManagerDidChangeAuthorization` when it changes, so the app asks again.
     */
    @kotlin.concurrent.Volatile private var servicesOn = true

    private fun servicesEnabled(): Boolean {
        if (!NSThread.isMainThread) return CLLocationManager.locationServicesEnabled().also { servicesOn = it }
        onBackground { servicesOn = CLLocationManager.locationServicesEnabled() }
        return servicesOn
    }

    private fun onBackground(block: () -> Unit) =
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u), block)

    init {
        onBackground { servicesOn = CLLocationManager.locationServicesEnabled() }   // warm it before the first call
    }

    /**
     * `UIBackgroundModes` contains `location` in the host app's Info.plist.
     * Setting `allowsBackgroundLocationUpdates = true` WITHOUT it crashes the app, so we check first.
     */
    internal val hasBackgroundLocationMode: Boolean by lazy {
        (NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*>)?.contains("location") == true
    }

    // ---------- location ----------

    override fun locationUpdates(options: SharingOptions): Flow<LocationUpdate> = callbackFlow {
        // CLLocationManager delivers delegate callbacks on the run loop of the thread that created it → main.
        val manager = CLLocationManager()
        val delegate = LocationDelegate(
            onFix = { trySend(LocationUpdate.Fix(it)) },
            onRevoked = { trySend(LocationUpdate.PermissionRevoked) },
            onServicesOff = { trySend(LocationUpdate.ServicesDisabled) },
        )
        manager.delegate = delegate   // delegate is a weak reference in CoreLocation; `delegate` is kept alive below
        manager.desiredAccuracy = if (options.highAccuracy) kCLLocationAccuracyBest else kCLLocationAccuracyHundredMeters
        manager.distanceFilter = kCLDistanceFilterNone   // the engine filters by distance itself
        manager.activityType = CLActivityTypeOther
        manager.pausesLocationUpdatesAutomatically = false
        if (hasBackgroundLocationMode) {
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true   // the blue pill; required for "While Using" in background
        }
        manager.startUpdatingLocation()

        awaitClose {
            manager.stopUpdatingLocation()
            manager.delegate = null
            delegate.hashCode()   // keep a strong reference to the delegate until here
        }
    }.flowOn(Dispatchers.Main)

    private class LocationDelegate(
        private val onFix: (TrackMeLocation) -> Unit,
        private val onRevoked: () -> Unit,
        private val onServicesOff: () -> Unit,
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            (didUpdateLocations.lastOrNull() as? CLLocation)?.toTrackMe()?.let(onFix)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            // kCLErrorLocationUnknown is transient (no fix yet); only "denied" ends sharing.
            if (didFailWithError.code == CL_ERROR_DENIED) onRevoked()
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse,
                kCLAuthorizationStatusNotDetermined -> Unit
                // Off the main thread: locationServicesEnabled() can block. Both callbacks only trySend.
                else -> IosPlatformServices.onBackground { if (!CLLocationManager.locationServicesEnabled()) onServicesOff() else onRevoked() }
            }
        }
    }

    private fun CLLocation.toTrackMe(): TrackMeLocation? {
        if (horizontalAccuracy < 0) return null   // invalid fix
        val (lat, lon) = coordinate.useContents { latitude to longitude }
        return TrackMeLocation(
            latitude = lat,
            longitude = lon,
            timestampMillis = (timestamp.timeIntervalSince1970 * 1000).toLong(),
            accuracyMeters = horizontalAccuracy,
            altitudeMeters = if (verticalAccuracy >= 0) altitude else null,
            speedMetersPerSecond = if (speed >= 0) speed else null,
            bearingDegrees = if (course >= 0) course else null,
        )
    }

    // ---------- device state ----------

    override fun battery(): BatteryInfo = onMain {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val level = device.batteryLevel.takeIf { it >= 0f }?.let { (it * 100).toInt() }
        val state = device.batteryState
        val charging = when (state) {
            UIDeviceBatteryState.UIDeviceBatteryStateCharging, UIDeviceBatteryState.UIDeviceBatteryStateFull -> true
            UIDeviceBatteryState.UIDeviceBatteryStateUnplugged -> false
            else -> null
        }
        BatteryInfo(level, charging)
    }

    override fun isAppInForeground(): Boolean = onMain {
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
    }

    override fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    // ---------- sharing UI (Live Activity / notification via the Swift companion package) ----------

    override fun startSharingHost(ui: TrackMeSharingUi): TrackMeResult<Unit> {
        IosSharingHost.started(ui)
        return TrackMeResult.Success(Unit)
    }

    override fun updateSharingHost(ui: TrackMeSharingUi, state: SharingState, window: SharingWindow?) =
        IosSharingHost.updated(ui, state, window)

    override fun stopSharingHost() = IosSharingHost.ended()

    /** UIKit must be touched on the main thread. */
    private fun <T> onMain(block: () -> T): T {
        if (NSThread.isMainThread) return block()
        var result: T? = null
        dispatch_sync(dispatch_get_main_queue()) { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
