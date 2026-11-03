package io.baato.trackme.api

import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeResult
import io.baato.trackme.call
import io.baato.trackme.model.CreateTripRequest
import io.baato.trackme.model.CreatedTrip
import io.baato.trackme.model.JoinTripRequest
import io.baato.trackme.model.RenameTripRequest
import io.baato.trackme.model.Trip
import io.baato.trackme.model.TripDetail
import io.baato.trackme.model.TripListResponse
import io.baato.trackme.model.TripPreview
import io.baato.trackme.model.ViewLink
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * `/api/v1/trips…` endpoints. Access via [TrackMe.trips].
 * Inputs are validated against the OpenAPI limits *before* a request is sent.
 */
class TripsApi internal constructor(private val sdk: TrackMe) {

    /**
     * Owner creates a trip. Show [CreatedTrip.joinCode] so others can join; [CreatedTrip.viewLink] is public.
     *
     * [durationMin] is sent as is: the allowed range is the server's to decide (5–1440 today), so it can change
     * without an SDK release. Out of range → [TrackMeError.Validation] with the server's own message,
     * e.g. "duration_min: Input should be greater than or equal to 5".
     */
    suspend fun create(name: String?, durationMin: Int): TrackMeResult<CreatedTrip> {
        if (name != null && name.length > NAME_MAX) return invalid("name must be at most $NAME_MAX characters")
        return sdk.call {
            http.post("v1/trips") {
                contentType(ContentType.Application.Json)
                setBody(CreateTripRequest(name?.trim()?.ifBlank { null }, durationMin))
            }.body()
        }
    }

    /**
     * Trips the user created or joined, newest first. [limit] is sent as is: the server sets the allowed range
     * (1–50 today) and answers [TrackMeError.Validation] with its own message outside it.
     */
    suspend fun list(limit: Int): TrackMeResult<List<Trip>> {
        return sdk.call { http.get("v1/trips") { parameter("limit", limit) }.body<TripListResponse>().trips }
    }

    /** Peek at a trip by join code before joining. Works without being a member. */
    suspend fun preview(code: String): TrackMeResult<TripPreview> {
        val c = normalizeCode(code) ?: return invalid("join code must be 1–16 characters")
        return sdk.call { http.get("v1/trips/preview") { parameter("code", c) }.body() }
    }

    /** Join with a code. Start sharing location afterwards. */
    suspend fun join(code: String): TrackMeResult<Trip> {
        val c = normalizeCode(code) ?: return invalid("join code must be 1–16 characters")
        return sdk.call {
            http.post("v1/trips/join") {
                contentType(ContentType.Application.Json)
                setBody(JoinTripRequest(c))
            }.body()
        }
    }

    /** Trip + members with their last known positions. Must be a member. */
    suspend fun detail(tripId: Long): TrackMeResult<TripDetail> =
        sdk.call { http.get("v1/trips/$tripId").body() }

    /** Owner only. */
    suspend fun rename(tripId: Long, name: String): TrackMeResult<Trip> {
        val n = name.trim()
        if (n.isEmpty() || n.length > NAME_MAX) return invalid("name must be 1–$NAME_MAX characters")
        return sdk.call {
            http.patch("v1/trips/$tripId") {
                contentType(ContentType.Application.Json)
                setBody(RenameTripRequest(n))
            }.body()
        }
    }

    /** Owner ends the trip early. Members' sharing should stop (the socket also sends `trip_ended`). */
    suspend fun end(tripId: Long): TrackMeResult<Trip> =
        sdk.call { http.post("v1/trips/$tripId/end").body() }

    /** Member leaves. They can re-join later with the code. */
    suspend fun leave(tripId: Long): TrackMeResult<Unit> =
        sdk.call<Unit> { http.delete("v1/trips/$tripId/members/me") }

    /** Owner removes a member. */
    suspend fun removeMember(tripId: Long, userId: Long): TrackMeResult<Unit> =
        sdk.call<Unit> { http.delete("v1/trips/$tripId/members/$userId") }

    /** Owner deletes an *ended* trip. A live trip → Failure(Conflict): call [end] first. */
    suspend fun delete(tripId: Long): TrackMeResult<Unit> =
        sdk.call<Unit> { http.delete("v1/trips/$tripId") }

    /** Public read-only link. Anyone with it can watch. */
    suspend fun viewLink(tripId: Long): TrackMeResult<ViewLink> =
        sdk.call { http.get("v1/trips/$tripId/view-link").body() }

    /** Invalidate the old public link (e.g. it leaked) and get a new one. */
    suspend fun rotateViewLink(tripId: Long): TrackMeResult<ViewLink> =
        sdk.call { http.post("v1/trips/$tripId/view-link/rotate").body() }

    /**
     * Positions per member during the trip, as raw JSON. The spec leaves this response untyped,
     * so it gets typed once we see real payloads. [from]/[to] are optional ISO-8601 strings.
     */
    suspend fun history(tripId: Long, from: String?, to: String?): TrackMeResult<String> =
        sdk.call {
            http.get("v1/trips/$tripId/history") {
                from?.let { parameter("from", it) }
                to?.let { parameter("to", it) }
            }.bodyAsText()
        }

    private fun normalizeCode(code: String): String? = code.trim().takeIf { it.length in 1..16 }

    private fun invalid(msg: String) = TrackMeResult.Failure(TrackMeError.Validation(msg))

    private companion object {
        const val NAME_MAX = 100
    }
}
