package io.baato.trackme

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LifecycleAndAuthTest {

    @AfterTest fun tearDown() = TrackMe.dispose()

    @Test
    fun callsBeforeInitialize_returnNotInitialized_neverThrow() = runTest {
        TrackMe.dispose()
        assertIs<TrackMeError.NotInitialized>(TrackMe.trips.list(10).errorOrNull())
        assertIs<TrackMeError.NotInitialized>(TrackMe.updateToken("x").errorOrNull())
    }

    @Test
    fun disposeIsIdempotent_andStateFollowsLifecycle() = runTest {
        TrackMe.dispose(); TrackMe.dispose()
        startWith { json("""{"status":"ok"}""") }
        assertEquals(TrackMeState.Ready, TrackMe.state.value)
        TrackMe.dispose(); TrackMe.dispose()
        assertEquals(TrackMeState.Disposed, TrackMe.state.value)
        assertIs<TrackMeError.NotInitialized>(TrackMe.checkHealth().errorOrNull())
    }

    @Test
    fun blankToken_isRejected() {
        val r = TrackMe.start(null, TrackMeConfig(token = "  "), engine = null)
        assertIs<TrackMeError.Validation>(r.errorOrNull())
    }

    @Test
    fun bearerToken_isSentOnFirstRequest() = runTest {
        startWith(token = "abc") { request ->
            assertEquals("Bearer abc", request.headers[HttpHeaders.Authorization])
            json("""{"trips":[]}""")
        }
        assertTrue(TrackMe.trips.list(5).isSuccess)
    }

    @Test
    fun on401_providerIsAsked_andRequestRetriedWithNewToken() = runTest {
        var providerCalls = 0
        val provider = object : TrackMeTokenProvider {
            override fun refreshToken(onResult: (String?) -> Unit) { providerCalls++; onResult("fresh") }
        }
        startWith(token = "stale", provider = provider) { request ->
            when (request.headers[HttpHeaders.Authorization]) {
                "Bearer fresh" -> json("""{"trips":[$TRIP_SUMMARY_JSON]}""")
                else -> respond("""{"detail":"expired"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            }
        }
        val trips = TrackMe.trips.list(10).getOrNull()
        assertEquals(1, trips?.size)
        assertEquals(1, providerCalls)
    }

    @Test
    fun on401_withoutWorkingToken_returnsUnauthorized() = runTest {
        startWith(provider = tokenProvider { null }) {
            respond("""{"detail":"expired"}""", HttpStatusCode.Unauthorized, jsonHeaders)
        }
        assertIs<TrackMeError.Unauthorized>(TrackMe.trips.list(10).errorOrNull())
    }

    @Test
    fun updateToken_isUsedByNextRequest() = runTest {
        val seen = mutableListOf<String?>()
        startWith(token = "one") { request ->
            seen += request.headers[HttpHeaders.Authorization]
            json("""{"trips":[]}""")
        }
        TrackMe.trips.list(1)
        TrackMe.updateToken("two")
        TrackMe.trips.list(1)
        assertEquals("Bearer one", seen.first())
        assertEquals("Bearer two", seen.last())
    }
}
