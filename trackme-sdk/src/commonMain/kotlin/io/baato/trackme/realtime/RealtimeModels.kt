package io.baato.trackme.realtime

import io.baato.trackme.network.TrackMeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Connection status of live updates. Observe via `TrackMe.realtime.state`. */
sealed class RealtimeState {
    /** Not watching anything. */
    data object Idle : RealtimeState()
    data object Connecting : RealtimeState()
    /** Connection dropped; retrying with backoff (1 s … 30 s). Events resume by themselves. */
    data class Reconnecting(val attempt: Int) : RealtimeState()
    /** Connected and subscribed. [tripId] = the watched trip (one per connection), [watchingShare] = a view link. */
    data class Connected(val tripId: Long?, val watchingShare: Boolean) : RealtimeState()
}

/**
 * One live position of a trip member: `trip_position` for members, `share_position` for view-link watchers.
 *
 * Contract: `{trip_id?, member_user_id, display_name?, lat, lon, speed?, fix_age_s?}`, the field names the server
 * sends today (see docs/trackme-api.md → Realtime; pending the backend's written spec). A payload that doesn't
 * match is not guessed at: it's dropped and reported to [io.baato.trackme.TrackMe.debugLogger].
 */
data class MemberPosition(
    /** Null when the server doesn't include it (e.g. on a view link). */
    val tripId: Long?,
    val userId: Long,
    /** The member's display name (`display_name`). */
    val fullName: String?,
    val latitude: Double,
    val longitude: Double,
    val speedMetersPerSecond: Double?,
    /** Seconds since the member's phone took this fix. */
    val fixAgeSeconds: Long?,
)

/** `member_changed`: someone joined the watched trip (socket server contract). */
data class MemberChange(
    val tripId: Long,
    val userId: Long,
    val displayName: String?,
    /** "joined" today; other values are passed through as sent. */
    val change: String,
) {
    val joined: Boolean get() = change == "joined"
}

// ---- wire formats: exactly the documented fields, decoded with kotlinx.serialization ----

/** `trip_position` / `share_position` payload. */
@Serializable
internal data class PositionWire(
    @SerialName("trip_id") val tripId: Long? = null,
    @SerialName("member_user_id") val userId: Long,
    @SerialName("display_name") val displayName: String? = null,
    val lat: Double,
    val lon: Double,
    val speed: Double? = null,
    @SerialName("fix_age_s") val fixAgeSeconds: Long? = null,
) {
    fun toPublic() = MemberPosition(tripId, userId, displayName, lat, lon, speed, fixAgeSeconds)
}

/** `member_changed`: `{trip_id, user_id, display_name, change}` (socket server). */
@Serializable
internal data class MemberChangedWire(
    @SerialName("trip_id") val tripId: Long,
    @SerialName("user_id") val userId: Long,
    @SerialName("display_name") val displayName: String? = null,
    val change: String,
) {
    fun toPublic() = MemberChange(tripId, userId, displayName, change)
}

/** `evicted`: `{reason, trip_id?}` (socket server). */
@Serializable
internal data class EvictedWire(
    val reason: String? = null,
    @SerialName("trip_id") val tripId: Long? = null,
)

/** `trip_ended`: payload not documented yet; both fields optional. */
@Serializable
internal data class TripEndedWire(
    @SerialName("trip_id") val tripId: Long? = null,
    val reason: String? = null,
)

/** Ack of `join_trip` / `join_share`: `{ok, room_id}` or `{ok: false, error}` (socket server). */
@Serializable
internal data class JoinAckWire(
    val ok: Boolean,
    @SerialName("room_id") val roomId: String? = null,
    val error: String? = null,
)

/** Decodes [element] as [T]; on a contract mismatch returns null and reports why. */
internal inline fun <reified T> decodeOrReport(event: String, element: JsonElement?, report: (String) -> Unit): T? {
    if (element == null) { report("⚠︎ $event: no payload"); return null }
    return runCatching { TrackMeJson.decodeFromJsonElement<T>(element) }
        .onFailure { report("⚠︎ $event: payload doesn't match the documented contract (${it.message?.take(160)}): ${element.toString().take(400)}") }
        .getOrNull()
}
