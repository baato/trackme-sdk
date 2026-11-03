package io.baato.trackme

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class TripsApiTest {

    @AfterTest fun tearDown() = TrackMe.dispose()

    @Test
    fun create_sendsBody_andParsesJoinCode() = runTest {
        startWith { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/trackme/api/v1/trips", request.url.encodedPath)
            val body = (request.body as TextContent).text
            assertTrue("\"duration_min\":60" in body, body)
            json(
                """{"id":17,"name":"Trip 17","join_code":"C7FKK7",
                "view_link":"https://tracker.baato.io/trackme/s/abc","expires_at":"2026-10-16T07:40:37Z","remaining_s":3599}""",
                HttpStatusCode.Created,
            )
        }
        val trip = TrackMe.trips.create("Trip 17", durationMin = 60).getOrNull() ?: fail("expected success")
        assertEquals("C7FKK7", trip.joinCode)
    }

    /** The duration range is the server's: any value is sent, and a 422 comes back with the server's message. */
    @Test
    fun create_durationIsSentAsIs_andTheServersErrorIsShown() = runTest {
        var sent = ""
        startWith { request ->
            sent = (request.body as io.ktor.http.content.TextContent).text
            json(
                """{"detail":[{"type":"greater_than_equal","loc":["body","duration_min"],"msg":"Input should be greater than or equal to 5","input":2}]}""",
                io.ktor.http.HttpStatusCode.UnprocessableEntity,
            )
        }
        val error = assertIs<TrackMeError.Validation>(TrackMe.trips.create(null, durationMin = 2).errorOrNull())
        assertTrue("\"duration_min\":2" in sent, sent)
        assertEquals("duration_min: Input should be greater than or equal to 5", error.message)
    }

    @Test
    fun join_isPost_withTrimmedCode() = runTest {
        startWith { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("""{"code":"C7FKK7"}""", (request.body as TextContent).text)
            json(TRIP_SUMMARY_JSON)
        }
        val trip = TrackMe.trips.join("  C7FKK7 ").getOrNull() ?: fail("expected success")
        assertTrue(trip.isLive && trip.isOwner)
    }

    @Test
    fun detail_parsesMembersWithNullPositions() = runTest {
        startWith {
            json("""{"trip":$TRIP_SUMMARY_JSON,"members":[
                {"user_id":2,"full_name":"First User","role":"owner","lat":27.7,"lon":85.3,"speed":1.5,"fix_age_s":3},
                {"user_id":4,"full_name":null,"role":"member","lat":null,"lon":null,"speed":null,"fix_age_s":null}]}""")
        }
        val detail = TrackMe.trips.detail(17).getOrNull() ?: fail("expected success")
        assertEquals(2, detail.members.size)
        assertTrue(detail.members[0].hasPosition)
        assertTrue(!detail.members[1].hasPosition)
    }

    @Test
    fun deleteLiveTrip_mapsToConflict_withServerMessage() = runTest {
        startWith { respond("""{"detail":"End the trip first"}""", HttpStatusCode.Conflict, jsonHeaders) }
        val error = assertIs<TrackMeError.Conflict>(TrackMe.trips.delete(17).errorOrNull())
        assertEquals("End the trip first", error.message)
    }

    @Test
    fun validationErrorArray_isFlattened() = runTest {
        startWith {
            respond("""{"detail":[{"loc":["body","duration_min"],"msg":"too small","type":"x"}]}""",
                HttpStatusCode.UnprocessableEntity, jsonHeaders)
        }
        val error = assertIs<TrackMeError.Validation>(TrackMe.trips.create(null, 60).errorOrNull())
        assertEquals("duration_min: too small", error.message)   // the field name in front: which input it means
    }

    @Test
    fun leave_handles204() = runTest {
        startWith { respond("", HttpStatusCode.NoContent) }
        assertTrue(TrackMe.trips.leave(17).isSuccess)
    }
}
