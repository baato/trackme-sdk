package io.baato.trackme

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

internal val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

internal fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, jsonHeaders)

/** Starts the SDK on a MockEngine. Every test calls TrackMe.dispose() in @AfterTest. */
internal fun startWith(
    token: String = "token-1",
    provider: TrackMeTokenProvider? = null,
    platform: io.baato.trackme.platform.PlatformServices = FakePlatform(),
    realtime: io.baato.trackme.realtime.RealtimeTransport? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): MockEngine {
    val engine = MockEngine(handler)
    val r = TrackMe.start(
        context = null,
        config = TrackMeConfig(token = token, tokenProvider = provider),
        engine = engine,
        platform = platform,
        realtimeTransport = realtime,
    )
    check(r.isSuccess) { "start failed: $r" }
    return engine
}

internal const val TRIP_SUMMARY_JSON = """{"id":17,"name":"Trip 17","my_role":"owner","my_status":"joined","status":"live",
"ended_reason":null,"member_count":2,"started_on":"2026-10-16T06:40:37.547618Z","ended_on":null,"planned_s":3600,
"actual_s":129,"expires_at":"2026-10-16T07:40:37.547618Z","remaining_s":3470,"join_code":"C7FKK7"}"""

/** Run with real time (the SDK's sharing loop runs on Dispatchers.Default, not the test scheduler). */
internal suspend fun <T> realTime(timeoutMillis: Long = 5_000, block: suspend () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(timeoutMillis) { block() }
    }
