@file:OptIn(ExperimentalAtomicApi::class)

package io.baato.trackme.realtime

import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeEvent
import io.baato.trackme.TrackMeResult
import io.baato.trackme.debugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.math.min

/**
 * Live updates over the backend's Socket.IO server (contract: docs/trackme-api.md → Realtime):
 *
 *   connect  auth = {token: <access token>}      (no token → anonymous viewer)
 *   emit     join_trip  {trip_id}   → ack {ok, room_id} | {ok:false, error}   members, ONE trip room per socket
 *   emit     join_share {token}     → ack {ok, room_id} | {ok:false, error}   view-link watchers
 *   on       trip_position / share_position / member_changed / trip_ended / share_ended / evicted
 *
 * One connection, kept alive by a single loop: connect → handshake → (re)join → read. On drop it reconnects
 * with backoff and re-joins what's being watched. If the server rejects the handshake (expired token) it asks the
 * token provider once, then gives up with AuthenticationFailed.
 */
internal class RealtimeController(
    private val scope: CoroutineScope,
    private val transport: RealtimeTransport,
    socketUrl: String,
    private val currentToken: () -> String?,
    private val refreshToken: suspend () -> String?,
    private val state: MutableStateFlow<RealtimeState>,
    private val emit: (TrackMeEvent) -> Unit,
) {
    private val url = SocketIoCodec.webSocketUrl(socketUrl)

    private val desiredTrip = AtomicReference<Long?>(null)
    private val desiredShare = AtomicReference<String?>(null)
    /** Set while a watchTrip()/watchShare() call is sending its own join, so a reconnect's rejoin doesn't send it twice. */
    private val joiningTrip = AtomicReference<Long?>(null)
    private val joiningShare = AtomicReference<String?>(null)
    private val live = MutableStateFlow<Live?>(null)
    private val loop = AtomicReference<Job?>(null)
    /** The socket being opened or used right now (also during the handshake), so stop() can cut it. */
    private val rawConn = AtomicReference<RealtimeConnection?>(null)
    /** Set when the loop gave up because the server rejected the token. */
    private val authRejected = AtomicBoolean(false)
    /** Set when we cut the socket on purpose (stop watching one thing): reconnect at once, no backoff. */
    private val restartRequested = AtomicBoolean(false)

    private class Live(val conn: RealtimeConnection) {
        val nextAck = AtomicInt(1)
        val lock = Mutex()
        val pending = mutableMapOf<Int, CompletableDeferred<JsonElement?>>()
    }

    // ---------------- public operations ----------------

    suspend fun watchTrip(tripId: Long): TrackMeResult<Unit> {
        if (tripId <= 0) return TrackMeResult.Failure(TrackMeError.Validation("tripId must be positive"))
        joiningTrip.store(tripId)   // before desiredTrip: a rejoin that sees the wish also sees this
        desiredTrip.store(tripId)
        try {
            return when (val j = joinWithRetry("join_trip", buildJsonObject { put("trip_id", tripId) })) {
                is Join.Failed -> j.result
                is Join.Acked -> joinResult(j.ack, onFail = { desiredTrip.clearIfEquals(tripId); dropIfUnused() })
                    .also { if (it.isSuccess) publishConnected() }
            }
        } finally {
            joiningTrip.clearIfEquals(tripId)
        }
    }

    suspend fun watchShare(viewToken: String): TrackMeResult<Unit> {
        val token = viewToken.trim().takeIf { it.isNotEmpty() } ?: return TrackMeResult.Failure(TrackMeError.Validation("view token required"))
        joiningShare.store(token)
        desiredShare.store(token)
        try {
            return when (val j = joinWithRetry("join_share", buildJsonObject { put("token", token) })) {
                is Join.Failed -> j.result
                is Join.Acked -> joinResult(j.ack, onFail = { desiredShare.clearIfEquals(token); dropIfUnused() })
                    .also { if (it.isSuccess) publishConnected() }
            }
        } finally {
            joiningShare.clearIfEquals(token)
        }
    }

    private sealed class Join {
        class Acked(val ack: JsonElement) : Join()
        class Failed(val result: TrackMeResult<Unit>) : Join()
    }

    /**
     * Emit a join and wait for its ack. If the socket dies under the request (a reconnect, or another stop/watch
     * call cutting it), the ack never comes: wait for the next connection and try once more.
     */
    private suspend fun joinWithRetry(event: String, payload: JsonObject): Join {
        val noAnswer = Join.Failed(TrackMeResult.Failure(TrackMeError.Network("No answer from the live server.")))
        repeat(2) {
            val c = awaitLive() ?: return Join.Failed(connectFailure())
            emitWithAck(c, event, payload)?.let { return Join.Acked(it) }
            if (live.value === c) return noAnswer   // socket still up: the server just didn't answer in time
        }
        return noAnswer
    }

    /** The server has no "leave" event: drop the socket, and reconnect only if a share is still watched. */
    fun stopWatchingTrip() {
        if (desiredTrip.exchange(null) != null) restartConnection()
    }

    fun stopWatchingShare() {
        if (desiredShare.exchange(null) != null) restartConnection()
    }

    fun stop() {
        desiredTrip.store(null)
        desiredShare.store(null)
        restartConnection()
    }

    /** dispose(): synchronous, nothing survives. */
    fun shutdown() {
        desiredTrip.store(null)
        desiredShare.store(null)
        loop.exchange(null)?.cancel()
        rawConn.exchange(null)?.cancel()
        live.value = null
        state.value = RealtimeState.Idle
        runCatching { transport.close() }
    }

    // ---------------- connection loop ----------------

    private fun wanted() = desiredTrip.load() != null || desiredShare.load() != null

    private fun ensureLoop() {
        loop.load()?.let { if (it.isActive) return }
        val job = scope.launch(start = CoroutineStart.LAZY) { runLoop() }
        val previous = loop.load()
        if ((previous == null || !previous.isActive) && loop.compareAndSet(previous, job)) job.start() else job.cancel()
    }

    private fun restartConnection() {
        restartRequested.store(true)
        val conn = rawConn.load()
        // Forget the live socket NOW, not when the loop notices: a watchTrip() right after this call
        // must wait for the next connection instead of sending its join on the one being closed.
        live.value?.let { l -> if (conn == null || l.conn === conn) live.compareAndSet(l, null) }
        conn?.cancel()   // the loop notices, then reconnects or exits depending on wanted()
        if (!wanted() && loop.load()?.isActive != true) state.value = RealtimeState.Idle
    }

    private fun dropIfUnused() {
        if (!wanted()) restartConnection()
    }

    /** The live connection, or null on timeout / when the loop gave up (e.g. token rejected). */
    private suspend fun awaitLive(): Live? = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        while (true) {
            live.value?.let { return@withTimeoutOrNull it }
            ensureLoop()
            val job = loop.load()
            val got = coroutineScope {
                val waiter = async { live.filterNotNull().first() }
                val handle = job?.invokeOnCompletion { waiter.cancel() }
                try {
                    waiter.await()
                } catch (e: CancellationException) {
                    if (!isActive) throw e
                    live.value   // the loop ended; null unless it connected in the same instant
                } finally {
                    handle?.dispose()
                }
            }
            if (got != null) return@withTimeoutOrNull got
            if (authRejected.load() || !wanted()) return@withTimeoutOrNull null
            // We raced with a loop that was already exiting: wait for it to finish, then start a fresh one.
            job?.join()
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private suspend fun runLoop() {
        var attempt = 0
        var refreshedAfterReject = false
        var connectedBefore = false
        authRejected.store(false)
        restartRequested.store(false)
        try {
            while (currentCoroutineContext().isActive && wanted()) {
                if (attempt == 0) state.value = RealtimeState.Connecting
                val outcome = try {
                    session(currentToken(), resubscribe = connectedBefore)
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    Outcome.Dropped(wasConnected = false)   // socket cut by stop()/restart, not us being cancelled
                } catch (e: Throwable) {
                    Outcome.Dropped(wasConnected = false)
                }
                if (!wanted()) break
                when (outcome) {
                    Outcome.Rejected -> {
                        // Expired / invalid token: ask the app once, then give up.
                        if (!refreshedAfterReject && refreshToken() != null) {
                            refreshedAfterReject = true
                            continue
                        }
                        authRejected.store(true)
                        desiredTrip.store(null)
                        desiredShare.store(null)
                        break
                    }
                    is Outcome.Dropped -> {
                        if (outcome.wasConnected) connectedBefore = true
                        if (restartRequested.exchange(false)) { attempt = 0; continue }
                        if (outcome.wasConnected) { attempt = 0; refreshedAfterReject = false; connectedBefore = true }
                        attempt++
                        state.value = RealtimeState.Reconnecting(attempt)
                        delay(backoff(attempt))
                    }
                }
            }
        } finally {
            if (!wanted()) state.value = RealtimeState.Idle
            loop.compareAndSet(currentJob(), null)
        }
    }

    private suspend fun currentJob(): Job? = currentCoroutineContext()[Job]

    private sealed class Outcome {
        data object Rejected : Outcome()
        data class Dropped(val wasConnected: Boolean) : Outcome()
    }

    /** One WebSocket from open to close. */
    private suspend fun session(token: String?, resubscribe: Boolean): Outcome {
        val conn = transport.connect(url)
        rawConn.store(conn)
        if (!wanted()) { withContext(NonCancellable) { runCatching { conn.close() } }; return Outcome.Dropped(false) }
        var current: Live? = null
        try {
            // 1) Engine.IO open
            val open = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { nextPacket(conn) } as? EioPacket.Open
                ?: return Outcome.Dropped(false)
            val silenceLimit = open.pingIntervalMillis + open.pingTimeoutMillis

            // 2) Socket.IO connect with auth
            conn.send(SocketIoCodec.encodeConnect(token?.let { buildJsonObject { put("token", it) } }))
            when (withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { awaitConnectReply(conn) }) {
                false -> { debugLog { "socket: server REJECTED the handshake (token invalid/expired?)" }; return Outcome.Rejected }
                null -> { debugLog { "socket: no handshake answer, will retry" }; return Outcome.Dropped(false) }
                true -> debugLog { "socket: connected (${if (token != null) "with token" else "anonymous"})" }
            }

            // 3) live
            val l = Live(conn)
            current = l
            live.value = l
            publishConnected()
            // Reconnect within this loop: re-subscribe. (On a first connect the watchTrip/watchShare caller joins.)
            if (resubscribe) scope.launch { rejoin(l) }

            // 4) read until the socket dies or goes silent (server pings every pingInterval)
            while (true) {
                val text = withTimeoutOrNull(silenceLimit) { conn.incoming.receiveCatching().getOrNull() }
                    ?: return Outcome.Dropped(true)
                if (!handle(l, text)) return Outcome.Dropped(true)
            }
        } finally {
            val ended = current
            withContext(NonCancellable) {
                rawConn.compareAndSet(conn, null)
                if (ended != null) {
                    live.compareAndSet(ended, null)
                    ended.lock.withLock {
                        ended.pending.values.forEach { it.complete(null) }
                        ended.pending.clear()
                    }
                }
                runCatching { conn.close() }
            }
        }
    }

    /** true = accepted (40), false = rejected (44), null = socket closed. */
    private suspend fun awaitConnectReply(conn: RealtimeConnection): Boolean? {
        while (true) {
            when (val p = nextPacket(conn)) {
                EioPacket.Ping -> conn.send(SocketIoCodec.PONG)
                EioPacket.Close -> return null
                is EioPacket.Message -> when (SocketIoCodec.decodeSio(p.data)) {
                    is SioPacket.Connect -> return true
                    is SioPacket.ConnectError -> return false
                    else -> Unit
                }
                else -> Unit
            }
        }
    }

    private suspend fun nextPacket(conn: RealtimeConnection): EioPacket {
        val text = conn.incoming.receiveCatching().getOrNull() ?: return EioPacket.Close
        return SocketIoCodec.decodeEio(text)
    }

    /** Returns false when the server ended the session. */
    private suspend fun handle(l: Live, text: String): Boolean {
        when (val p = SocketIoCodec.decodeEio(text)) {
            EioPacket.Ping -> l.conn.send(SocketIoCodec.PONG)
            EioPacket.Close -> return false
            is EioPacket.Message -> when (val s = SocketIoCodec.decodeSio(p.data)) {
                is SioPacket.Event -> dispatch(s.name, s.args.firstOrNull())
                is SioPacket.Ack -> l.lock.withLock { l.pending.remove(s.ackId) }?.complete(s.args.firstOrNull())
                SioPacket.Disconnect -> return false
                is SioPacket.ConnectError -> return false
                else -> Unit
            }
            else -> Unit
        }
        return true
    }

    /**
     * Server events, decoded against the documented contract (docs/trackme-api.md → Realtime). The SDK doesn't
     * guess: a payload that doesn't match is dropped and reported to TrackMe.debugLogger, so the mismatch gets
     * fixed where it belongs (the backend's payload or the documented contract), not papered over here.
     */
    private fun dispatch(name: String, payload: JsonElement?) {
        debugLog { "← $name ${payload?.toString()?.take(400) ?: "(no payload)"}" }
        val report: (String) -> Unit = { line -> debugLog { line } }
        when (name) {
            "trip_position" -> decodeOrReport<PositionWire>(name, payload, report)?.let { emit(TrackMeEvent.TripPosition(it.toPublic())) }
            "share_position" -> decodeOrReport<PositionWire>(name, payload, report)?.let { emit(TrackMeEvent.SharePosition(it.toPublic())) }
            "member_changed" -> decodeOrReport<MemberChangedWire>(name, payload, report)?.let { emit(TrackMeEvent.MemberChanged(it.toPublic())) }
            "trip_ended" -> {
                // Payload not documented yet: an empty / unknown one still means "the watched trip ended".
                val wire = payload?.let { decodeOrReport<TripEndedWire>(name, it, report) }
                val tripId = wire?.tripId ?: desiredTrip.load()
                if (tripId == null || desiredTrip.load() == tripId) desiredTrip.store(null)
                emit(TrackMeEvent.TripEnded(tripId, wire?.reason))
            }
            "share_ended" -> {
                desiredShare.store(null)
                emit(TrackMeEvent.ShareEnded)
            }
            "evicted" -> {
                // Kicked, left elsewhere, or trip ended: the server disconnects right after. Don't re-join.
                val wire = payload?.let { decodeOrReport<EvictedWire>(name, it, report) }
                val tripId = wire?.tripId
                if (tripId == null || desiredTrip.load() == tripId) desiredTrip.store(null)
                emit(TrackMeEvent.Evicted(tripId, wire?.reason))
            }
            else -> debugLog { "  (event not part of the SDK's contract, ignored)" }
        }
    }

    /** After a reconnect: re-subscribe to what's being watched. */
    private suspend fun rejoin(l: Live) {
        // A watch call in flight sends (and retries) its own join on this connection: don't send a second one.
        desiredTrip.load()?.takeIf { it != joiningTrip.load() }?.let { tripId ->
            val ack = emitWithAck(l, "join_trip", buildJsonObject { put("trip_id", tripId) }) ?: return@let
            joinResult(ack, onFail = {}).onFailure { error ->
                if (desiredTrip.clearIfEquals(tripId)) {
                    if (error is TrackMeError.Conflict) emit(TrackMeEvent.TripEnded(tripId, "ended"))
                    else emit(TrackMeEvent.Evicted(tripId, error.message))
                }
            }
        }
        desiredShare.load()?.takeIf { it != joiningShare.load() }?.let { token ->
            val ack = emitWithAck(l, "join_share", buildJsonObject { put("token", token) }) ?: return@let
            joinResult(ack, onFail = {}).onFailure {
                if (desiredShare.clearIfEquals(token)) emit(TrackMeEvent.ShareEnded)
            }
        }
        publishConnected()
    }

    private suspend fun emitWithAck(l: Live, event: String, payload: JsonObject): JsonElement? {
        val id = l.nextAck.fetchAndIncrement()
        debugLog { "→ $event $payload" }
        val reply = CompletableDeferred<JsonElement?>()
        l.lock.withLock { l.pending[id] = reply }
        return try {
            l.conn.send(SocketIoCodec.encodeEvent(event, payload, id))
            withTimeoutOrNull(ACK_TIMEOUT_MS) { reply.await() }
                .also { debugLog { "← $event answer: ${it ?: "none (timeout or socket closed)"}" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        } finally {
            withContext(NonCancellable) { l.lock.withLock { l.pending.remove(id) } }
        }
    }

    private inline fun joinResult(ack: JsonElement, onFail: () -> Unit): TrackMeResult<Unit> {
        val wire = decodeOrReport<JoinAckWire>("join answer", ack) { line -> debugLog { line } }
        if (wire?.ok == true) return TrackMeResult.Success(Unit)
        onFail()
        val msg = wire?.error ?: "Unexpected join answer: $ack"
        // The server's exact error strings for join_trip / join_share. Anything else (e.g. a membership check's
        // HTTP detail) is passed through as Unknown with the server's message.
        val error = when (msg) {
            "Not authenticated" -> TrackMeError.Unauthorized(msg)
            "Trip has ended" -> TrackMeError.Conflict(msg)
            "Share not found or expired" -> TrackMeError.NotFound(msg)
            "Invalid trip_id", "Token required" -> TrackMeError.Validation(msg)
            else -> TrackMeError.Unknown(msg)
        }
        return TrackMeResult.Failure(error)
    }

    private fun connectFailure(): TrackMeResult<Unit> = TrackMeResult.Failure(
        if (authRejected.load()) TrackMeError.Unauthorized("The live server rejected the access token.")
        else TrackMeError.Network("Could not connect to live updates.")
    )

    private fun publishConnected() {
        if (live.value != null) {
            state.value = RealtimeState.Connected(desiredTrip.load(), desiredShare.load() != null)
        }
    }

    /**
     * Clears the slot only if it still holds [value], comparing by VALUE. AtomicReference.compareAndSet compares by
     * identity, and a boxed Long (trip id) is a new object each time outside the small-value cache.
     */
    private fun <T : Any> AtomicReference<T?>.clearIfEquals(value: T): Boolean {
        while (true) {
            val current = load() ?: return false
            if (current != value) return false
            if (compareAndSet(current, null)) return true
        }
    }

    internal companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val ACK_TIMEOUT_MS = 10_000L

        /** 1 s, 2 s, 4 s … 30 s */
        fun backoff(attempt: Int): Long = min(30_000L, 1_000L shl (attempt - 1).coerceIn(0, 5))
    }
}
