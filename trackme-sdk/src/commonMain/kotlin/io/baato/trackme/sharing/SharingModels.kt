package io.baato.trackme.sharing

import io.baato.trackme.location.TrackMeLocation

/**
 * Tuning for [TrackMe.sharing.start]. The defaults suit people and vehicles in a city.
 *
 * Swift: `SharingOptions.companion.default_` or `SharingOptions(intervalMillis:…)` with all arguments.
 */
data class SharingOptions(
    /** Minimum time between two uploads. The SDK always sends the newest fix and never queues old ones. */
    val intervalMillis: Long = 5_000,
    /** Ignore fixes that moved less than this since the last upload (saves battery and data when parked). */
    val minDistanceMeters: Double = 5.0,
    /** Re-send the last fix at least this often even when stationary, so members see you're still online. */
    val heartbeatMillis: Long = 30_000,
    /** GPS-level accuracy (true) or network-level (false, saves battery). */
    val highAccuracy: Boolean = true,
) {
    companion object {
        val Default = SharingOptions()
    }
}

/**
 * Look of the "you are sharing" UI: the Android ongoing notification, and the iOS Live Activity / iOS 15 notification.
 * All strings are the host app's, so it can localize them.
 */
data class TrackMeSharingUi(
    val title: String = "Sharing your location",
    val text: String = "Members of your trip can see where you are.",
    val pausedText: String = "Location sharing is paused.",
    /** ARGB, e.g. 0xFF1565C0. Android notification accent and iOS Live Activity tint. 0 = platform default. */
    val accentColor: Long = 0,
    val showPauseAction: Boolean = true,
    val showStopAction: Boolean = true,
    val pauseLabel: String = "Pause",
    val resumeLabel: String = "Resume",
    val stopLabel: String = "Stop",
    /** Android: drawable resource id for the status-bar icon (monochrome). 0 = SDK default pin icon. */
    val androidSmallIcon: Int = 0,
    /** Android: notification channel name shown in system settings. */
    val androidChannelName: String = "Location sharing",
    /** iOS: SF Symbol shown in the Live Activity. */
    val iosSymbolName: String = "location.fill",
) {
    /** For Swift, which doesn't see Kotlin default arguments: `TrackMeSharingUi(title:text:accentColor:)`. */
    constructor(title: String, text: String, accentColor: Long) : this(title = title, text = text, pausedText = "Location sharing is paused.", accentColor = accentColor)
}

/**
 * When the shared trips run, for the notification / Live Activity countdown: from the start to the end of the
 * trip that ends **last** (sharing lasts until then). Epoch milliseconds, on this device's clock.
 */
internal data class SharingWindow(val startedAtMillis: Long, val endsAtMillis: Long) {
    /** Share of the trip already over, 0…1. */
    fun elapsedFraction(nowMillis: Long): Double {
        val total = endsAtMillis - startedAtMillis
        if (total <= 0) return 1.0
        return ((nowMillis - startedAtMillis).toDouble() / total).coerceIn(0.0, 1.0)
    }
}

/** Why sharing ended. Observe it via [SharingState.Stopped]. */
enum class StopReason {
    /** The app called stop(). */
    User,
    /** Location permission was revoked while sharing. */
    PermissionRevoked,
    /** Device location was switched off. */
    ServicesDisabled,
    /** The server says the user is in no live trip any more (trip ended, expired, left or removed). */
    NoActiveTrips,
    /** The token was rejected and the token provider couldn't supply a new one. */
    Unauthorized,
    /** The app went to background and the OS didn't allow continuing. */
    BackgroundNotAllowed,
    /** TrackMe.dispose() was called. */
    Disposed,
}

/** Observable via `TrackMe.sharing.state`. */
sealed class SharingState {
    data object Idle : SharingState()

    /** Location updates are being requested; nothing sent yet. */
    data object Starting : SharingState()

    data class Sharing(
        /** Trips that received the last upload (from the server). */
        val tripIds: List<Long>,
        val lastLocation: TrackMeLocation?,
        /** Epoch ms of the last successful upload, or null if none succeeded yet. */
        val lastSentAtMillis: Long?,
        /** True while uploads are failing (offline, 5xx). Sharing keeps retrying with the newest fix. */
        val retrying: Boolean,
    ) : SharingState()

    /** GPS off, nothing sent; the notification / Live Activity stays up with Resume and Stop. */
    data object Paused : SharingState()

    data class Stopped(val reason: StopReason) : SharingState()

    val isActive: Boolean get() = this is Starting || this is Sharing || this is Paused
}
