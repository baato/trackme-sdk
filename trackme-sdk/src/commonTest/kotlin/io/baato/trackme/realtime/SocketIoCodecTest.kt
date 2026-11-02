package io.baato.trackme.realtime

import io.baato.trackme.network.TrackMeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocketIoCodecTest {

    @Test fun websocketUrlFromHttpBase() {
        assertEquals(
            "wss://tracker.baato.io/trackme/socket.io/?EIO=4&transport=websocket",
            SocketIoCodec.webSocketUrl("https://tracker.baato.io/trackme/"),
        )
        assertEquals("ws://10.0.2.2:8000/socket.io/?EIO=4&transport=websocket", SocketIoCodec.webSocketUrl("http://10.0.2.2:8000"))
    }

    @Test fun decodesEngineIoPackets() {
        val open = assertIs<EioPacket.Open>(SocketIoCodec.decodeEio(FakeSocketServer.OPEN))
        assertEquals(25_000, open.pingIntervalMillis)
        assertEquals(20_000, open.pingTimeoutMillis)
        assertEquals(EioPacket.Ping, SocketIoCodec.decodeEio("2"))
        assertEquals(EioPacket.Close, SocketIoCodec.decodeEio("1"))
        assertEquals(EioPacket.Message("""2["x"]"""), SocketIoCodec.decodeEio("""42["x"]"""))
    }

    @Test fun decodesSocketIoPackets() {
        val ev = assertIs<SioPacket.Event>(SocketIoCodec.decodeSio("""2["trip_position",{"lat":27.7}]"""))
        assertEquals("trip_position", ev.name)
        assertNull(ev.ackId)
        val ack = assertIs<SioPacket.Ack>(SocketIoCodec.decodeSio("""312[{"ok":true,"room_id":"trip:17"}]"""))
        assertEquals(12, ack.ackId)
        assertEquals(JsonPrimitive(true), (ack.args.first() as JsonObject)["ok"])
        assertEquals("nope", assertIs<SioPacket.ConnectError>(SocketIoCodec.decodeSio("""4{"message":"nope"}""")).message)
        assertIs<SioPacket.Connect>(SocketIoCodec.decodeSio("""0{"sid":"x"}"""))
        assertEquals(SioPacket.Disconnect, SocketIoCodec.decodeSio("1"))
        assertNull(SocketIoCodec.decodeSio("""2not json"""))
    }

    @Test fun encodesConnectAndEvents() {
        assertEquals("40", SocketIoCodec.encodeConnect(null))
        assertEquals("""40{"token":"t"}""", SocketIoCodec.encodeConnect(buildJsonObject { put("token", "t") }))
        assertEquals("""423["join_trip",{"trip_id":17}]""", SocketIoCodec.encodeEvent("join_trip", buildJsonObject { put("trip_id", 17) }, 3))
        assertEquals("""42["ping",{}]""", SocketIoCodec.encodeEvent("ping", buildJsonObject { }, null))
    }

    /** Positions are decoded against the documented shape, exactly. */
    @Test fun decodesPositionsByTheContract() {
        val ok = TrackMeJson.parseToJsonElement(
            """{"trip_id":17,"member_user_id":5,"display_name":"Ram","lat":27.71,"lon":85.32,"speed":1.5,"fix_age_s":3,"extra":"ignored"}""",
        )
        val p = decodeOrReport<PositionWire>("trip_position", ok) { error("unexpected report: $it") }!!.toPublic()
        assertEquals(17L, p.tripId); assertEquals(5L, p.userId); assertEquals("Ram", p.fullName)
        assertEquals(27.71, p.latitude); assertEquals(85.32, p.longitude)
        assertEquals(1.5, p.speedMetersPerSecond); assertEquals(3L, p.fixAgeSeconds)
    }

    /** No guessing: other key names, strings for numbers, nested shapes → dropped and reported. */
    @Test fun rejectsPayloadsOutsideTheContract() {
        val notOurs = listOf(
            """{"user_id":5,"lat":27.7,"lon":85.3}""",                        // different id key
            """{"member_user_id":5,"latitude":27.7,"longitude":85.3}""",      // different coordinate keys
            """{"trip_id":17,"member_user_id":5,"position":{"lat":27.7,"lon":85.3}}""", // nested
            """["not","an","object"]""",
        )
        for (json in notOurs) {
            val reports = mutableListOf<String>()
            assertNull(decodeOrReport<PositionWire>("trip_position", TrackMeJson.parseToJsonElement(json)) { reports += it }, json)
            assertTrue(reports.single().contains("doesn't match the documented contract"), json)
        }
    }

    @Test fun decodesMemberChangedAndJoinAnswersBySioPy() {
        val m = decodeOrReport<MemberChangedWire>("member_changed",
            TrackMeJson.parseToJsonElement("""{"trip_id":17,"user_id":9,"display_name":"Sita","change":"joined"}""")) { error(it) }!!.toPublic()
        assertTrue(m.joined); assertEquals(9L, m.userId); assertEquals("Sita", m.displayName)
        val ack = decodeOrReport<JoinAckWire>("join", TrackMeJson.parseToJsonElement("""{"ok":false,"error":"Trip has ended"}""")) { error(it) }!!
        assertEquals(false, ack.ok); assertEquals("Trip has ended", ack.error)
    }

    @Test fun backoff() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map { RealtimeController.backoff(it) })
    }
}
