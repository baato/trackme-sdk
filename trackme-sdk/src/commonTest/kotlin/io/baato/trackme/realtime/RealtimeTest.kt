package io.baato.trackme.realtime

import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeEvent
import io.baato.trackme.TrackMeResult
import io.baato.trackme.json
import io.baato.trackme.realTime
import io.baato.trackme.startWith
import io.baato.trackme.tokenProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RealtimeTest {

    @AfterTest fun tearDown() = TrackMe.dispose()

    private fun start(server: FakeSocketServer, provider: io.baato.trackme.TrackMeTokenProvider? = null) {
        startWith(provider = provider, realtime = server) { json("{}") }
    }

    /** watchTrip + handshake + ack ok. Returns the live connection. */
    private suspend fun watching(server: FakeSocketServer, tripId: Long = 17): FakeConn = coroutineScope {
        val result = async { TrackMe.realtime.watchTrip(tripId) }
        val (conn, _) = server.accept()
        assertEquals("""421["join_trip",{"trip_id":$tripId}]""", conn.next())
        conn.push("""431[{"ok":true,"room_id":"trip:$tripId"}]""")
        assertTrue(result.await().isSuccess, "watchTrip failed")
        conn
    }

    private suspend fun awaitState(predicate: (RealtimeState) -> Boolean) =
        realTime { TrackMe.realtime.state.first(predicate) }

    @Test fun beforeInitializeEverythingIsSafe() = runTest {
        assertEquals(TrackMeResult.Failure(TrackMeError.NotInitialized), TrackMe.realtime.watchTrip(1))
        assertEquals(TrackMeResult.Failure(TrackMeError.NotInitialized), TrackMe.realtime.watchShare("abc"))
        assertTrue(TrackMe.realtime.stop().isSuccess)
        assertTrue(TrackMe.realtime.stopWatchingTrip().isSuccess)
        assertEquals(RealtimeState.Idle, TrackMe.realtime.state.value)
    }

    @Test fun invalidInputIsRejectedWithoutConnecting() = runTest {
        val server = FakeSocketServer()
        start(server)
        assertIs<TrackMeError.Validation>(TrackMe.realtime.watchTrip(0).errorOrNull())
        assertIs<TrackMeError.Validation>(TrackMe.realtime.watchShare(" ").errorOrNull())
        assertTrue(server.urls.isEmpty())
    }

    @Test fun handshakeSendsTokenAndJoinsTrip() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime {
            coroutineScope {
                val result = async { TrackMe.realtime.watchTrip(17) }
                val (conn, auth) = server.accept()
                assertEquals("""40{"token":"token-1"}""", auth)
                assertEquals("""421["join_trip",{"trip_id":17}]""", conn.next())
                conn.push("""431[{"ok":true,"room_id":"trip:17"}]""")
                assertTrue(result.await().isSuccess)
            }
        }
        assertEquals("wss://tracker.baato.io/trackme/socket.io/?EIO=4&transport=websocket", server.urls.single())
        assertEquals(RealtimeState.Connected(tripId = 17, watchingShare = false), awaitState { it is RealtimeState.Connected })
    }

    @Test fun joinErrorsMapToTypedFailures() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime {
            coroutineScope {
                val result = async { TrackMe.realtime.watchTrip(17) }
                val (conn, _) = server.accept()
                conn.next()
                conn.push("""431[{"ok":false,"error":"Trip has ended"}]""")
                assertIs<TrackMeError.Conflict>(result.await().errorOrNull())
                // nothing left to watch → the socket is closed
                while (!conn.isClosed) delay(10)
            }
        }
        awaitState { it == RealtimeState.Idle }
    }

    /** Regression: trip ids are boxed Longs; clearing them must compare by value (ids > 127 aren't cached boxes). */
    @Test fun failedJoinWithLargeTripIdClosesTheSocket() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime {
            coroutineScope {
                val result = async { TrackMe.realtime.watchTrip(1_234_567) }
                val (conn, _) = server.accept()
                assertEquals("""421["join_trip",{"trip_id":1234567}]""", conn.next())
                conn.push("""431[{"ok":false,"error":"Not a member of this trip"}]""")
                assertIs<TrackMeError.Unknown>(result.await().errorOrNull())   // not one of the server's documented join errors
                while (!conn.isClosed) delay(10)
            }
        }
        awaitState { it == RealtimeState.Idle }
    }

    /**
     * Regression: the live map stops watching an invite's view link, then watches the trip it just joined.
     * Stopping cuts the socket; the trip join must go out on a NEW socket, not the one being closed.
     */
    @Test fun watchTripRightAfterStoppingAShareUsesAFreshSocket() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime(timeoutMillis = 10_000) {
            coroutineScope {
                val share = async { TrackMe.realtime.watchShare("RY2jco") }
                val (first, _) = server.accept()
                assertEquals("""421["join_share",{"token":"RY2jco"}]""", first.next())
                first.push("""431[{"ok":true,"room_id":"share:x"}]""")
                assertTrue(share.await().isSuccess)

                TrackMe.realtime.stopWatchingShare()
                val trip = async { TrackMe.realtime.watchTrip(17) }
                val (second, _) = server.accept()
                // Exactly one join for the trip on the new socket (whichever path the timing takes), answered by id.
                second.answer("""["join_trip",{"trip_id":17}]""", """{"ok":true,"room_id":"trip:17"}""")
                assertTrue(trip.await().isSuccess, "watchTrip failed")
                assertTrue(first.isClosed)
                assertTrue(second.nothingSentWithin(300), "a second join_trip was sent")
            }
        }
        assertEquals(RealtimeState.Connected(tripId = 17, watchingShare = false), awaitState { it is RealtimeState.Connected })
    }

    @Test fun serverPingIsAnswered() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime {
            val conn = watching(server)
            conn.push("2")
            assertEquals("3", conn.next())
        }
    }

    @Test fun eventsAreParsedAndPublished() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime {
            val conn = watching(server)
            coroutineScope {
                val position = async(start = CoroutineStart.UNDISPATCHED) { TrackMe.events.filterIsInstance<TrackMeEvent.TripPosition>().first() }
                val member = async(start = CoroutineStart.UNDISPATCHED) { TrackMe.events.filterIsInstance<TrackMeEvent.MemberChanged>().first() }
                conn.push("""42["member_changed",{"trip_id":17,"user_id":9,"display_name":"Sita","change":"joined"}]""")
                conn.push("""42["trip_position",{"trip_id":17,"member_user_id":9,"display_name":"Sita","lat":27.7,"lon":85.3,"fix_age_s":2}]""")
                val p = position.await().position
                assertEquals(9L, p.userId); assertEquals(27.7, p.latitude); assertEquals(2L, p.fixAgeSeconds)
                val m = member.await().change
                assertTrue(m.joined); assertEquals("Sita", m.displayName)
            }
        }
    }

    /** A payload outside the contract is not guessed at: no event, and the debug logger says why. */
    @Test fun offContractPositionIsDroppedAndReported() = runTest {
        val server = FakeSocketServer()
        start(server)
        val logged = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)   // thread-safe
        val lines = mutableListOf<String>()
        fun drain() { while (true) lines += logged.tryReceive().getOrNull() ?: break }
        TrackMe.debugLogger = { logged.trySend(it) }
        try {
            realTime {
                val conn = watching(server)
                coroutineScope {
                    val position = async(start = CoroutineStart.UNDISPATCHED) { TrackMe.events.filterIsInstance<TrackMeEvent.TripPosition>().first() }
                    conn.push("""42["trip_position",{"trip_id":17,"member_user_id":9,"position":{"lat":27.7,"lon":85.3}}]""")   // not the contract
                    conn.push("""42["trip_position",{"trip_id":17,"member_user_id":9,"lat":27.7,"lon":85.3}]""")                 // the contract
                    assertEquals(9L, position.await().position.userId)
                }
                while (true) { drain(); if (lines.any { it.contains("doesn't match the documented contract") }) break; delay(10) }
            }
            assertTrue(lines.count { it.startsWith("← trip_position") } >= 2, "every live message is logged")
        } finally {
            TrackMe.debugLogger = null
        }
    }

    @Test fun evictionStopsWatchingWithoutReconnecting() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime {
            val conn = watching(server)
            coroutineScope {
                val evicted = async(start = CoroutineStart.UNDISPATCHED) { TrackMe.events.filterIsInstance<TrackMeEvent.Evicted>().first() }
                conn.push("""42["evicted",{"reason":"removed","trip_id":17}]""")
                conn.push("41")
                assertEquals(TrackMeEvent.Evicted(17, "removed"), evicted.await())
            }
            TrackMe.realtime.state.first { it == RealtimeState.Idle }
            delay(1_500)   // longer than the first backoff step
            assertTrue(server.connections.tryReceive().isFailure, "must not reconnect after eviction")
        }
    }

    @Test fun reconnectsAndRejoinsAfterDrop() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime(timeoutMillis = 10_000) {
            val first = watching(server)
            first.drop()
            TrackMe.realtime.state.first { it is RealtimeState.Reconnecting }
            val (second, _) = server.accept()
            assertEquals("""421["join_trip",{"trip_id":17}]""", second.next())
            second.push("""431[{"ok":true,"room_id":"trip:17"}]""")
            TrackMe.realtime.state.first { it == RealtimeState.Connected(17, false) }
        }
    }

    @Test fun rejectedTokenAsksTheProviderOnceThenRetries() = runTest {
        val server = FakeSocketServer()
        var asked = 0
        start(server, provider = tokenProvider { asked++; "token-2" })
        realTime(timeoutMillis = 10_000) {
            coroutineScope {
                val result = async { TrackMe.realtime.watchTrip(17) }
                server.accept(accept = false)
                val (conn, auth) = server.accept()
                assertEquals("""40{"token":"token-2"}""", auth)
                conn.next()
                conn.push("""431[{"ok":true}]""")
                assertTrue(result.await().isSuccess)
            }
        }
        assertEquals(1, asked)
    }

    @Test fun rejectedTokenWithoutProviderFailsAsUnauthorized() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime {
            coroutineScope {
                val failed = async(start = CoroutineStart.UNDISPATCHED) { TrackMe.events.filterIsInstance<TrackMeEvent.AuthenticationFailed>().first() }
                val result = async { TrackMe.realtime.watchTrip(17) }
                server.accept(accept = false)
                assertIs<TrackMeError.Unauthorized>(result.await().errorOrNull())
                failed.await()
            }
        }
    }

    @Test fun watchShareJoinsByToken() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime {
            coroutineScope {
                val result = async { TrackMe.realtime.watchShare(" RY2jco ") }
                val (conn, _) = server.accept()
                assertEquals("""421["join_share",{"token":"RY2jco"}]""", conn.next())
                conn.push("""431[{"ok":true,"room_id":"share:x"}]""")
                assertTrue(result.await().isSuccess)
                val pos = async(start = CoroutineStart.UNDISPATCHED) { TrackMe.events.filterIsInstance<TrackMeEvent.SharePosition>().first() }
                conn.push("""42["share_position",{"member_user_id":3,"lat":1.0,"lon":2.0}]""")
                assertEquals(2.0, pos.await().position.longitude)
            }
        }
        assertEquals(RealtimeState.Connected(null, true), TrackMe.realtime.state.value)
    }

    @Test fun stopAndDisposeCloseTheSocket() = runTest {
        val server = FakeSocketServer()
        start(server)
        realTime {
            val conn = watching(server)
            TrackMe.realtime.stop()
            while (!conn.isClosed) delay(10)
            TrackMe.realtime.state.first { it == RealtimeState.Idle }

            val again = watching(server)
            TrackMe.dispose()
            assertTrue(again.isClosed)
            assertEquals(RealtimeState.Idle, TrackMe.realtime.state.value)
        }
    }
}
