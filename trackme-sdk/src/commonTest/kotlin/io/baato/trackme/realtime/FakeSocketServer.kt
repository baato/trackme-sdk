package io.baato.trackme.realtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import kotlin.test.assertTrue

/** Plays the Socket.IO server: every connect() hands the test a [FakeConn] to script. */
internal class FakeSocketServer : RealtimeTransport {
    val connections = Channel<FakeConn>(Channel.UNLIMITED)
    val urls = mutableListOf<String>()

    override suspend fun connect(url: String): RealtimeConnection {
        urls += url
        return FakeConn().also { connections.send(it) }
    }

    suspend fun nextConnection(timeoutMillis: Long = 3_000): FakeConn = withTimeout(timeoutMillis) { connections.receive() }

    /** Accept the next socket: Engine.IO open, read the client's `40{auth}`, answer `40{sid}`. Returns (conn, auth frame). */
    suspend fun accept(accept: Boolean = true): Pair<FakeConn, String> {
        val c = nextConnection()
        c.push(OPEN)
        val auth = c.next()
        assertTrue(auth.startsWith("40"), "expected CONNECT, got $auth")
        c.push(if (accept) """40{"sid":"abc"}""" else """44{"message":"Connection rejected by server"}""")
        return c to auth
    }

    companion object {
        const val OPEN = """0{"sid":"eio1","upgrades":[],"pingInterval":25000,"pingTimeout":20000,"maxPayload":1000000}"""
    }
}

internal class FakeConn : RealtimeConnection {
    private val toClient = Channel<String>(Channel.UNLIMITED)
    private val fromClient = Channel<String>(Channel.UNLIMITED)

    override val incoming: ReceiveChannel<String> = toClient
    override suspend fun send(text: String) { fromClient.send(text) }
    override suspend fun close() { toClient.close() }
    override fun cancel() { toClient.close() }

    val isClosed: Boolean get() = toClient.isClosedForSend

    fun push(text: String) { toClient.trySend(text) }
    /** Server-side hang-up. */
    fun drop() { toClient.close() }

    /**
     * Reads the SDK's next emit-with-ack (`42<id>["event",…]`), checks it starts with [expected] once the id is
     * removed, and answers it with [answer] under the SAME ack id, like a Socket.IO server does.
     */
    suspend fun answer(expected: String, answer: String) {
        val frame = next()
        val id = frame.removePrefix("42").takeWhile { it.isDigit() }
        kotlin.test.assertTrue(id.isNotEmpty(), "expected an emit with ack, got $frame")
        kotlin.test.assertEquals(expected, frame.removePrefix("42$id"))
        push("43$id[$answer]")
    }

    /** True if the SDK sends nothing within [millis] (real time). */
    suspend fun nothingSentWithin(millis: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(millis) { fromClient.receive() } == null

    /** Next frame the SDK sent. */
    suspend fun next(timeoutMillis: Long = 3_000): String = withTimeout(timeoutMillis) { fromClient.receive() }
}
