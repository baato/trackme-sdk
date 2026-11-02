package io.baato.trackme.api

import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeResult
import io.baato.trackme.call
import io.baato.trackme.model.MarkReadResponse
import io.baato.trackme.model.NotificationPage
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post

/** `/api/v1/notifications…`: the user's inbox. Access via [TrackMe.notifications]. */
class NotificationsApi internal constructor(private val sdk: TrackMe) {

    /** One page. Pass the previous page's `nextCursor` as [cursor] (null for the first page). */
    suspend fun list(unreadOnly: Boolean, tripId: Long?, cursor: String?, limit: Int): TrackMeResult<NotificationPage> {
        if (limit < 1) return TrackMeResult.Failure(TrackMeError.Validation("limit must be at least 1"))
        return sdk.call {
            http.get("v1/notifications") {
                parameter("unread_only", unreadOnly)
                tripId?.let { parameter("trip_id", it) }
                cursor?.let { parameter("cursor", it) }
                parameter("limit", limit)
            }.body()
        }
    }

    suspend fun markRead(notificationId: Long): TrackMeResult<Unit> =
        sdk.call<Unit> { http.post("v1/notifications/$notificationId/read").body<MarkReadResponse>() }

    suspend fun markAllRead(): TrackMeResult<Unit> =
        sdk.call<Unit> { http.post("v1/notifications/read-all").body<MarkReadResponse>() }
}
