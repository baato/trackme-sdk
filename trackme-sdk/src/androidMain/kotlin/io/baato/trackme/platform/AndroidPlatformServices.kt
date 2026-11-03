package io.baato.trackme.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import io.baato.trackme.PlatformContext
import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeResult
import io.baato.trackme.location.BatteryInfo
import io.baato.trackme.location.LocationPermissionLevel
import io.baato.trackme.location.LocationPermissionStatus
import io.baato.trackme.location.LocationUpdate
import io.baato.trackme.location.TrackMeLocation
import io.baato.trackme.sharing.SharingHostBridge
import io.baato.trackme.sharing.SharingWindow
import io.baato.trackme.sharing.SharingNotification
import io.baato.trackme.sharing.SharingOptions
import io.baato.trackme.sharing.SharingState
import io.baato.trackme.sharing.TrackMeLocationService
import io.baato.trackme.sharing.TrackMeSharingUi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal actual fun createPlatformServices(context: PlatformContext?): PlatformServices =
    context?.let { AndroidPlatformServices(it.appContext) } ?: UnavailablePlatformServices

internal class AndroidPlatformServices(private val ctx: Context) : PlatformServices {

    private val locationManager: LocationManager? = ctx.getSystemService(LocationManager::class.java)

    // ---------- permission ----------

    override fun permissionStatus(): LocationPermissionStatus {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            fine || coarse   // before Android 10 foreground permission also covered background
        }
        val level = when {
            !fine && !coarse -> LocationPermissionLevel.NotDetermined   // Android can't tell "never asked" without an Activity
            background -> LocationPermissionLevel.Always
            else -> LocationPermissionLevel.WhileInUse
        }
        return LocationPermissionStatus(
            level = level,
            precise = fine,
            servicesEnabled = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false,
            backgroundCapable = true,
        )
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // ---------- location ----------

    @SuppressLint("MissingPermission")   // checked just below; SecurityException is also caught
    override fun locationUpdates(options: SharingOptions): Flow<LocationUpdate> = callbackFlow {
        val lm = locationManager
        if (lm == null || !LocationManagerCompat.isLocationEnabled(lm)) {
            trySend(LocationUpdate.ServicesDisabled); close(); return@callbackFlow
        }
        if (!permissionStatus().isGranted) {
            trySend(LocationUpdate.PermissionRevoked); close(); return@callbackFlow
        }
        val provider = pickProvider(lm, options.highAccuracy)
        if (provider == null) {
            trySend(LocationUpdate.ServicesDisabled); close(); return@callbackFlow
        }

        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                trySend(LocationUpdate.Fix(location.toTrackMe()))
            }

            override fun onProviderDisabled(provider: String) {
                if (!LocationManagerCompat.isLocationEnabled(lm)) trySend(LocationUpdate.ServicesDisabled)
            }
        }
        val request = LocationRequestCompat.Builder(options.intervalMillis)
            .setQuality(
                if (options.highAccuracy) LocationRequestCompat.QUALITY_HIGH_ACCURACY
                else LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY,
            )
            .setMinUpdateIntervalMillis(options.intervalMillis / 2)
            .setMinUpdateDistanceMeters(0f)   // the engine filters by distance itself (heartbeat needs every fix)
            .build()

        try {
            // A recent cached fix makes the first upload near-instant.
            lm.getLastKnownLocation(provider)
                ?.takeIf { System.currentTimeMillis() - it.time < 2 * 60_000 }
                ?.let { trySend(LocationUpdate.Fix(it.toTrackMe())) }
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, ContextCompat.getMainExecutor(ctx), listener)
        } catch (e: SecurityException) {
            trySend(LocationUpdate.PermissionRevoked); close(); return@callbackFlow
        }

        // Android usually kills the process when location permission is revoked, but not for every change
        // (e.g. precise → approximate, or location switched off). A cheap poll covers the rest.
        val watcher = launch {
            while (isActive) {
                delay(5_000)
                if (!permissionStatus().isGranted) trySend(LocationUpdate.PermissionRevoked)
                if (!LocationManagerCompat.isLocationEnabled(lm)) trySend(LocationUpdate.ServicesDisabled)
            }
        }

        awaitClose {
            watcher.cancel()
            runCatching { LocationManagerCompat.removeUpdates(lm, listener) }
        }
    }

    private fun pickProvider(lm: LocationManager, highAccuracy: Boolean): String? {
        fun usable(p: String) = runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && usable(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER
        }
        val order = if (highAccuracy) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        return order.firstOrNull(::usable)
    }

    private fun Location.toTrackMe() = TrackMeLocation(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = time,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        altitudeMeters = if (hasAltitude()) altitude else null,
        speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
        bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
    )

    // ---------- device state ----------

    override fun battery(): BatteryInfo {
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return BatteryInfo(null, null)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        return BatteryInfo(level, bm.isCharging)
    }

    override fun isAppInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        // FOREGROUND = an activity is resumed; VISIBLE = an activity is visible (e.g. behind a dialog).
        // Not FOREGROUND_SERVICE: that's our own service keeping us alive, not the user looking at the app.
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    override fun nowMillis(): Long = System.currentTimeMillis()

    // ---------- foreground service + notification ----------

    override fun startSharingHost(ui: TrackMeSharingUi): TrackMeResult<Unit> {
        SharingHostBridge.ui = ui
        SharingHostBridge.state = SharingState.Starting
        SharingNotification.ensureChannel(ctx, ui)
        return try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackMeLocationService::class.java))
            TrackMeResult.Success(Unit)
        } catch (e: IllegalStateException) {
            // Android 12+: ForegroundServiceStartNotAllowedException (a subclass) when started from background.
            TrackMeResult.Failure(TrackMeError.BackgroundNotAllowed("Android refused to start the location service: ${e.message}"))
        } catch (e: SecurityException) {
            // Android 14+: missing FOREGROUND_SERVICE_LOCATION or location permission at start time.
            TrackMeResult.Failure(TrackMeError.BackgroundNotAllowed("Missing permission for the location service: ${e.message}"))
        }
    }

    override fun updateSharingHost(ui: TrackMeSharingUi, state: SharingState, window: SharingWindow?) {
        SharingHostBridge.ui = ui
        SharingHostBridge.state = state
        SharingHostBridge.window = window
        if (state.isActive) SharingNotification.show(ctx, ui, state, window)
    }

    override fun stopSharingHost() {
        SharingHostBridge.state = SharingState.Idle
        SharingHostBridge.window = null
        runCatching { ctx.stopService(Intent(ctx, TrackMeLocationService::class.java)) }
        SharingNotification.cancel(ctx)
    }
}

/** Used when no Context was supplied (shouldn't happen in apps). Reports "no permission" instead of crashing. */
internal object UnavailablePlatformServices : PlatformServices {
    override fun permissionStatus() = LocationPermissionStatus(LocationPermissionLevel.Denied, false, false, false)
    override fun locationUpdates(options: SharingOptions): Flow<LocationUpdate> =
        kotlinx.coroutines.flow.flowOf(LocationUpdate.PermissionRevoked)
    override fun battery() = BatteryInfo(null, null)
    override fun isAppInForeground() = false
    override fun startSharingHost(ui: TrackMeSharingUi): TrackMeResult<Unit> =
        TrackMeResult.Failure(TrackMeError.BackgroundNotAllowed("No Android Context: call TrackMe.initialize(context, config)"))
    override fun updateSharingHost(ui: TrackMeSharingUi, state: SharingState, window: SharingWindow?) = Unit
    override fun stopSharingHost() = Unit
    override fun nowMillis(): Long = System.currentTimeMillis()
}
