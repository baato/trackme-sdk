package io.baato.trackme.bridge

import io.baato.trackme.FakePlatform
import io.baato.trackme.TRIP_SUMMARY_JSON
import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeEvent
import io.baato.trackme.json
import io.baato.trackme.location.LocationPermissionLevel
import io.baato.trackme.location.LocationPermissionStatus
import io.baato.trackme.network.TrackMeJson
import io.baato.trackme.realtime.MemberPosition
import io.baato.trackme.sharing.SharingState
import io.baato.trackme.sharing.StopReason
import io.baato.trackme.startWith
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackMeBridgeTest {
    @AfterTest fun tearDown() = TrackMe.dispose()

    /** Calls the bridge and waits for its single answer. */
    private suspend fun call(method: String, args: String = "{}"): JsonObject = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            val answer = CompletableDeferred<String>()
            TrackMeBridge.call(method, args) { answer.complete(it) }
            TrackMeJson.parseToJsonElement(answer.await()).jsonObject
        }
    }

    private fun JsonObject.ok() = this["ok"]!!.jsonPrimitive.content == "true"
    private fun JsonObject.errorType() = this["error"]!!.jsonObject["type"]!!.jsonPrimitive.content

    @Test fun serverModelsPassThroughWithTheApisFieldNames() = runTest {
        startWith { request ->
            assertTrue(request.url.encodedPath.endsWith("v1/trips"))
            assertEquals("5", request.url.parameters["limit"])
            json("""{"trips":[$TRIP_SUMMARY_JSON]}""")
        }
        val r = call("trips.list", """{"limit":5}""")
        assertTrue(r.ok(), r.toString())
        val trip = r["value"]!!.jsonArray[0].jsonObject
        assertEquals(17L, trip["id"]!!.jsonPrimitive.long)
        assertEquals("C7FKK7", trip["join_code"]!!.jsonPrimitive.content)   // snake_case, as the server sends it
        assertEquals(3470L, trip["remaining_s"]!!.jsonPrimitive.long)
    }

    @Test fun errorsArriveTypedWithTheServersMessage() = runTest {
        startWith { json("""{"detail":"Trip not found"}""", HttpStatusCode.NotFound) }
        val r = call("trips.detail", """{"trip_id":99}""")
        assertEquals(false, r.ok())
        assertEquals("NotFound", r.errorType())
        assertEquals("Trip not found", r["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test fun badCallsAreValidationErrorsNotCrashes() = runTest {
        startWith { error("no request expected") }
        assertEquals("Validation", call("trips.detail", "{}").errorType())                 // missing trip_id
        assertEquals("Validation", call("trips.detail", """{"trip_id":"x"}""").errorType()) // wrong type
        assertEquals("Validation", call("no.such.method").errorType())
        assertEquals("Validation", call("trips.list", "not json").errorType())
    }

    @Test fun beforeInitialize_callsSayNotInitialized() = runTest {
        TrackMe.dispose()
        assertEquals("NotInitialized", call("trips.list", """{"limit":5}""").errorType())
        assertEquals("false", call("isInitialized")["value"]!!.jsonPrimitive.content)
        assertEquals("1", call("protocol")["value"]!!.jsonPrimitive.content)
    }

    @Test fun configAndOptionsReadSnakeCaseWithDefaults() {
        val c = TrackMeBridge.configFrom(
            TrackMeJson.parseToJsonElement(
                """{"token":"t","sharing_ui":{"title":"Live","accent_color":4279592384,"show_stop_action":false}}""",
            ).jsonObject,
            tokenProvider = null,
        )
        assertEquals("t", c.token)
        assertEquals("Live", c.sharingUi.title)
        assertEquals(0xFF1565C0, c.sharingUi.accentColor)
        assertEquals(false, c.sharingUi.showStopAction)
        assertEquals("Pause", c.sharingUi.pauseLabel)   // default kept
        val o = TrackMeBridge.optionsFrom(TrackMeJson.parseToJsonElement("""{"interval_ms":2000}""").jsonObject)
        assertEquals(2_000L, o.intervalMillis)
        assertEquals(30_000L, o.heartbeatMillis)
    }

    @Test fun statesAndEventsHaveStableShapes() {
        assertEquals("""{"type":"Stopped","reason":"User"}""", TrackMeBridge.encode(SharingState.Stopped(StopReason.User)).toString())
        assertEquals(
            """{"type":"Sharing","trip_ids":[17],"last_sent_at_ms":5,"retrying":false}""",
            TrackMeBridge.encode(SharingState.Sharing(listOf(17), null, 5, false)).toString(),
        )
        assertEquals(
            """{"level":"WhileInUse","precise":true,"services_enabled":true,"background_capable":true}""",
            TrackMeBridge.encode(LocationPermissionStatus(LocationPermissionLevel.WhileInUse, true, true, true)).toString(),
        )
        assertEquals(
            """{"type":"TripPosition","position":{"trip_id":17,"user_id":9,"display_name":"Sita","latitude":27.7,"longitude":85.3,"fix_age_s":2}}""",
            TrackMeBridge.encode(TrackMeEvent.TripPosition(MemberPosition(17, 9, "Sita", 27.7, 85.3, null, 2))).toString(),
        )
        assertEquals(
            """{"type":"Server","message":"boom","status_code":503}""",
            TrackMeBridge.encode(TrackMeError.Server(503, "boom")).toString(),
        )
    }

    @Test fun observe_sendsCurrentStatesThenChanges() = runTest {
        val got = Channel<String>(Channel.UNLIMITED)
        val sub = TrackMeBridge.observe { got.trySend(it) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    val first = List(3) { got.receive() }
                    assertTrue(first.any { it.contains(""""stream":"sharing"""") && it.contains(""""type":"Idle"""") }, first.toString())
                    assertTrue(first.any { it.contains(""""stream":"state"""") }, first.toString())
                    startWith(platform = FakePlatform()) { error("no request expected") }
                    while (true) {
                        val next = got.receive()
                        if (next.contains(""""stream":"state"""") && next.contains("Ready")) break
                    }
                }
            }
        } finally {
            sub.cancel()
        }
    }
}
