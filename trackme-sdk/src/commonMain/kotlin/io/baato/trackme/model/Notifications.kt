package io.baato.trackme.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `NotificationResponse`: inbox item (trip ended, member joined, trip deleted, …). */
@Serializable
data class TrackMeNotification(
    val id: Long,
    @SerialName("notification_id") val notificationId: Long,
    val title: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("trip_id") val tripId: Long? = null,
)

/** `CursorPage[NotificationResponse]`: pass [nextCursor] back to get the next page. */
@Serializable
data class NotificationPage(
    val items: List<TrackMeNotification>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
internal data class MarkReadResponse(val message: String)
