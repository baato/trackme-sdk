package io.baato.trackme.location

/** How much location access the user granted this app. */
enum class LocationPermissionLevel {
    /** iOS: never asked yet. Android: not granted (Android can't tell "never asked" from "denied" without an Activity). */
    NotDetermined,
    /** Denied, or restricted by parental controls / MDM. */
    Denied,
    /** Android FINE/COARSE, iOS "While Using the App". Sharing works and continues in background if started while visible. */
    WhileInUse,
    /** Android + ACCESS_BACKGROUND_LOCATION, iOS "Always". */
    Always,
}

/** Snapshot of everything that decides whether sharing can run. Returned by [TrackMe.location.permissionStatus]. */
data class LocationPermissionStatus(
    val level: LocationPermissionLevel,
    /** false = approximate only (Android COARSE without FINE, iOS reduced accuracy). Sharing still works. */
    val precise: Boolean,
    /** Location switched off for the whole device. */
    val servicesEnabled: Boolean,
    /** The app is configured for background updates (iOS `UIBackgroundModes` contains `location`; always true on Android). */
    val backgroundCapable: Boolean,
) {
    val isGranted: Boolean get() = level == LocationPermissionLevel.WhileInUse || level == LocationPermissionLevel.Always
}

/** One GPS fix in SDK units: degrees, metres, metres/second, epoch milliseconds. */
data class TrackMeLocation(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Double?,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
)

/** Battery snapshot sent along with each fix (`batt`, `charge`). */
internal data class BatteryInfo(val levelPercent: Int?, val charging: Boolean?)

/** What a platform location stream can emit. */
internal sealed class LocationUpdate {
    data class Fix(val location: TrackMeLocation) : LocationUpdate()
    /** User turned permission off while we were listening. */
    data object PermissionRevoked : LocationUpdate()
    /** User turned device location off while we were listening. */
    data object ServicesDisabled : LocationUpdate()
}
