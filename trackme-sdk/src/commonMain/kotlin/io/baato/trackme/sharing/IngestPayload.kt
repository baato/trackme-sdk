package io.baato.trackme.sharing

import io.baato.trackme.location.BatteryInfo
import io.baato.trackme.location.TrackMeLocation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `LocationPayload` from docs/openapi.json. Values are clamped to the server's limits so a noisy fix can't cause a 422. */
@Serializable
internal data class LocationPayload(
    val lat: Double,
    val lon: Double,
    /** Epoch **seconds**. See [TIMESTAMP_UNIT_SECONDS]. */
    val timestamp: Long,
    val accuracy: Double? = null,
    val altitude: Double? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    val batt: Int? = null,
    val charge: Boolean? = null,
)

/** `LocationIngestResponse`. An empty [tripIds] means the user is no longer in any live trip. */
@Serializable
internal data class LocationIngestResponse(
    val accepted: Boolean = true,
    @SerialName("trip_ids") val tripIds: List<Long> = emptyList(),
    @SerialName("fix_age_s") val fixAgeSeconds: Long = 0,
)

/** Epoch seconds: confirmed with the backend (an integer in seconds is enough; `fix_age_s` is also seconds). */
internal const val TIMESTAMP_UNIT_SECONDS = true

internal fun TrackMeLocation.toPayload(battery: BatteryInfo): LocationPayload = LocationPayload(
    lat = latitude.coerceIn(-90.0, 90.0),
    lon = longitude.coerceIn(-180.0, 180.0),
    timestamp = if (TIMESTAMP_UNIT_SECONDS) timestampMillis / 1000 else timestampMillis,
    accuracy = accuracyMeters?.takeIf { it >= 0 }?.coerceAtMost(100_000.0),
    altitude = altitudeMeters,
    speed = speedMetersPerSecond?.takeIf { it >= 0 }?.coerceAtMost(400.0),
    bearing = bearingDegrees?.takeIf { it >= 0 }?.rem(360.0),
    batt = battery.levelPercent?.coerceIn(0, 100),
    charge = battery.charging,
)
