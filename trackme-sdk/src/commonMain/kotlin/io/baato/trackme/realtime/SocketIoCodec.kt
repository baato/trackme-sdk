package io.baato.trackme.realtime

import io.baato.trackme.network.TrackMeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Socket.IO v4 over Engine.IO v4, WebSocket transport only. Spec:
 * https://socket.io/docs/v4/socket-io-protocol/ and https://socket.io/docs/v4/engine-io-protocol/
 *
 * Each WebSocket text frame is one Engine.IO packet: <eio type digit><data>
 *   0 open   {"sid","upgrades","pingInterval","pingTimeout","maxPayload"}   server → client
 *   1 close
 *   2 ping   (EIO v4: the SERVER pings, the client answers 3 pong)
 *   3 pong
 *   4 message → the data is a Socket.IO packet: <sio type digit>[<ack id>]<json>
 *       0 CONNECT        client: 40{"token":"…"}   server: 40{"sid":"…"}
 *       1 DISCONNECT     41
 *       2 EVENT          42["name",payload]   or with ack id 4212["name",payload]
 *       3 ACK            4312[reply]
 *       4 CONNECT_ERROR  44{"message":"…"}
 *   6 noop
 *
 * Only the default namespace "/" is used, so packets carry no namespace prefix.
 */

internal sealed class EioPacket {
    data class Open(val sid: String?, val pingIntervalMillis: Long, val pingTimeoutMillis: Long) : EioPacket()
    data object Close : EioPacket()
    data object Ping : EioPacket()
    data object Pong : EioPacket()
    data class Message(val data: String) : EioPacket()
    data object Noop : EioPacket()
    data class Unknown(val raw: String) : EioPacket()
}

internal sealed class SioPacket {
    data class Connect(val data: JsonObject?) : SioPacket()
    data object Disconnect : SioPacket()
    data class Event(val name: String, val args: List<JsonElement>, val ackId: Int?) : SioPacket()
    data class Ack(val ackId: Int, val args: List<JsonElement>) : SioPacket()
    data class ConnectError(val message: String) : SioPacket()
}

internal object SocketIoCodec {
    const val PONG = "3"

    fun decodeEio(text: String): EioPacket {
        if (text.isEmpty()) return EioPacket.Unknown(text)
        val body = text.substring(1)
        return when (text[0]) {
            '0' -> runCatching {
                val o = TrackMeJson.parseToJsonElement(body) as JsonObject
                EioPacket.Open(
                    sid = (o["sid"] as? JsonPrimitive)?.contentOrNull,
                    pingIntervalMillis = (o["pingInterval"] as? JsonPrimitive)?.longOrNull ?: 25_000,
                    pingTimeoutMillis = (o["pingTimeout"] as? JsonPrimitive)?.longOrNull ?: 20_000,
                )
            }.getOrElse { EioPacket.Unknown(text) }
            '1' -> EioPacket.Close
            '2' -> EioPacket.Ping
            '3' -> EioPacket.Pong
            '4' -> EioPacket.Message(body)
            '6' -> EioPacket.Noop
            else -> EioPacket.Unknown(text)
        }
    }

    /** [data] is what follows the Engine.IO "4". Returns null for packets we don't use (binary, other namespaces). */
    fun decodeSio(data: String): SioPacket? {
        if (data.isEmpty()) return null
        var rest = data.substring(1)
        if (rest.startsWith("/")) {   // non-default namespace "/ns,…": not used by TrackMe
            val comma = rest.indexOf(',')
            if (comma < 0) return null
            rest = rest.substring(comma + 1)
        }
        val digits = rest.takeWhile { it.isDigit() }
        val ackId = digits.toIntOrNull()
        val json = rest.substring(digits.length)
        return when (data[0]) {
            '0' -> SioPacket.Connect(json.takeIf { it.isNotBlank() }?.let { parseObject(it) })
            '1' -> SioPacket.Disconnect
            '2' -> {
                val arr = parseArray(json) ?: return null
                val name = (arr.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return null
                SioPacket.Event(name, arr.drop(1), ackId)
            }
            '3' -> SioPacket.Ack(ackId ?: return null, parseArray(json) ?: emptyList())
            '4' -> {
                val obj = parseObject(json)
                SioPacket.ConnectError((obj?.get("message") as? JsonPrimitive)?.contentOrNull ?: json.ifBlank { "Connection rejected" })
            }
            else -> null
        }
    }

    /** `40` (anonymous) or `40{"token":"…"}`: the Socket.IO server receives this object as `auth` to its connect handler. */
    fun encodeConnect(auth: JsonObject?): String = "40" + (auth?.toString() ?: "")

    fun encodeEvent(name: String, payload: JsonElement, ackId: Int?): String =
        "42" + (ackId?.toString() ?: "") + JsonArray(listOf(JsonPrimitive(name), payload)).toString()

    /** `https://host/trackme` → `wss://host/trackme/socket.io/?EIO=4&transport=websocket` */
    fun webSocketUrl(socketUrl: String): String {
        val base = socketUrl.trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$ws/socket.io/?EIO=4&transport=websocket"
    }

    private fun parseObject(s: String): JsonObject? = runCatching { TrackMeJson.parseToJsonElement(s) as? JsonObject }.getOrNull()
    private fun parseArray(s: String): JsonArray? = runCatching { TrackMeJson.parseToJsonElement(s) as? JsonArray }.getOrNull()
}
