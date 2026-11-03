package io.baato.trackme.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors components.schemas in docs/openapi.json. Dates are ISO-8601 strings exactly as sent by the server.
// Roles and statuses stay Strings (with helpers) so a new server value can't break old SDK versions.

/** `TripSummary`: returned by list, join, rename and end. */
@Serializable
data class Trip(
    val id: Long,
    val name: String,
    @SerialName("my_role") val myRole: String,
    @SerialName("my_status") val myStatus: String,
    val status: String,
    @SerialName("ended_reason") val endedReason: String? = null,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("started_on") val startedOn: String,
    @SerialName("ended_on") val endedOn: String? = null,
    @SerialName("planned_s") val plannedSeconds: Long,
    @SerialName("actual_s") val actualSeconds: Long,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("remaining_s") val remainingSeconds: Long = 0,
    @SerialName("join_code") val joinCode: String? = null,
) {
    val isOwner: Boolean get() = myRole == "owner"
    val isLive: Boolean get() = status == "live"
}

/** `TripCreateResponse` */
@Serializable
data class CreatedTrip(
    val id: Long,
    val name: String,
    @SerialName("join_code") val joinCode: String,
    @SerialName("view_link") val viewLink: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("remaining_s") val remainingSeconds: Long,
)

/** `TripPreview`: what a would-be member sees before joining. */
@Serializable
data class TripPreview(
    val name: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("expires_at") val expiresAt: String,
)

/**
 * Whether a member is sending their location, as they reported it (`sharing.start / pause / resume / stop`).
 * Arrives as [io.baato.trackme.TrackMeEvent.MemberSharingChanged] and in [TripMember.sharingState].
 *
 * **Needs the backend's `sharing_state` support** (docs/trackme-api.md → Proposed: sharing state). Until the
 * server sends it, it's always null: fall back to how old the member's last position is.
 */
enum class MemberSharingState {
    Sharing, Paused, Stopped;

    /** The wire value: "sharing", "paused", "stopped". */
    val wireName: String get() = name.lowercase()

    companion object {
        /** null for anything else (an older or newer server value): treat it as unknown. */
        fun fromWire(value: String?): MemberSharingState? = entries.firstOrNull { it.wireName == value }
    }
}

/** `TripDetailMember`. Position fields are null until the member has sent a fix. */
@Serializable
data class TripMember(
    @SerialName("user_id") val userId: Long,
    @SerialName("full_name") val fullName: String? = null,
    val role: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val speed: Double? = null,
    @SerialName("fix_age_s") val fixAgeSeconds: Long? = null,
    /** Raw `sharing_state` (proposed field). Use [sharingState]. */
    @SerialName("sharing_state") val sharingStateValue: String? = null,
) {
    val isOwner: Boolean get() = role == "owner"
    val hasPosition: Boolean get() = lat != null && lon != null
    /** Sharing, paused or stopped, as last reported by the member. Null until the server supports it. */
    val sharingState: MemberSharingState? get() = MemberSharingState.fromWire(sharingStateValue)
}

/** `TripDetailResponse` */
@Serializable
data class TripDetail(
    val trip: Trip,
    val members: List<TripMember>,
)

/** `TripViewLinkResponse`: public, read-only link anyone can open. */
@Serializable
data class ViewLink(@SerialName("view_link") val viewLink: String)

// ---- request bodies (internal: apps call typed functions instead) ----

@Serializable
internal data class TripListResponse(val trips: List<Trip>)

/** Body of `POST /v1/ingest/state` (proposed). */
@Serializable
internal data class SharingStateRequest(val state: String)

@Serializable
internal data class CreateTripRequest(
    val name: String? = null,
    @SerialName("duration_min") val durationMin: Int,
)

@Serializable
internal data class JoinTripRequest(val code: String)

@Serializable
internal data class RenameTripRequest(val name: String?)
