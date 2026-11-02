package io.baato.trackme.realtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/** A text-frame pipe. Abstracted so tests can play the server without a network. */
internal interface RealtimeTransport {
    suspend fun connect(url: String): RealtimeConnection
    fun close() = Unit
}

internal interface RealtimeConnection {
    /** Text frames from the server; closed when the socket closes. */
    val incoming: ReceiveChannel<String>
    suspend fun send(text: String)
    suspend fun close()
    /** Non-suspending hard close, used by dispose(). */
    fun cancel()
}

/** Production transport: Ktor WebSockets on the platform engine (OkHttp on Android, NSURLSession on iOS). */
internal class KtorRealtimeTransport(engine: HttpClientEngine? = null) : RealtimeTransport {
    private val client: HttpClient =
        if (engine != null) HttpClient(engine) { install(WebSockets) } else HttpClient { install(WebSockets) }

    override suspend fun connect(url: String): RealtimeConnection {
        val session = client.webSocketSession(url)
        val frames = Channel<String>(Channel.UNLIMITED)
        val pump = session.launch {
            try {
                for (frame in session.incoming) {
                    if (frame is Frame.Text) frames.send(frame.readText())
                }
            } finally {
                frames.close()
            }
        }
        return object : RealtimeConnection {
            override val incoming: ReceiveChannel<String> = frames
            override suspend fun send(text: String) = session.send(Frame.Text(text))
            override suspend fun close() {
                runCatching { session.close() }
                pump.cancel()
                frames.close()
            }
            override fun cancel() {
                frames.close()
                session.cancel()
            }
        }
    }

    override fun close() = client.close()
}
