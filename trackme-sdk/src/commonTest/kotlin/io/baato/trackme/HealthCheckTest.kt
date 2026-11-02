package io.baato.trackme

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs on every target: `./gradlew allTests` (JVM for Android, simulator for iOS). */
class HealthCheckTest {

    @AfterTest fun tearDown() = TrackMe.dispose()

    @Test
    fun healthOk_isParsed_andHitsCorrectUrl() = runTest {
        startWith { request ->
            assertEquals("https://tracker.baato.io/trackme/api/health", request.url.toString())
            json("""{"status":"ok"}""")
        }
        val result = TrackMe.checkHealth()
        assertTrue(assertIs<TrackMeResult.Success<*>>(result).let { (it.data as io.baato.trackme.model.HealthResponse).isOk })
    }

    @Test
    fun serverError_isMappedToServerError() = runTest {
        startWith { respond("down", HttpStatusCode.ServiceUnavailable) }
        val error = TrackMe.checkHealth().errorOrNull()
        assertEquals(503, assertIs<TrackMeError.Server>(error).statusCode)
    }
}
