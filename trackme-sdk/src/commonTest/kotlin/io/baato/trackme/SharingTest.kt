package io.baato.trackme

import io.baato.trackme.location.LocationPermissionLevel
import io.baato.trackme.location.LocationPermissionStatus
import io.baato.trackme.location.LocationUpdate
import io.baato.trackme.location.TrackMeLocation
import io.baato.trackme.location.BatteryInfo
import io.baato.trackme.sharing.SharingController
import io.baato.trackme.sharing.SharingOptions
import io.baato.trackme.sharing.SharingState
import io.baato.trackme.sharing.StopReason
import io.baato.trackme.sharing.toPayload
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bumped only by the MockEngine (one request at a time), read by the test: @Volatile is enough. */
private class Counter { @kotlin.concurrent.Volatile var value = 0 }

class SharingTest {

    private val fast = SharingOptions(intervalMillis = 40, minDistanceMeters = 0.0, heartbeatMillis = 40)

    @AfterTest fun tearDown() = TrackMe.dispose()

    private suspend fun awaitState(predicate: (SharingState) -> Boolean): SharingState =
        realTime { TrackMe.sharing.state.first(predicate) }

    // ---------- guards: nothing starts, nothing crashes ----------

    @Test
    fun notInitialized() {
        TrackMe.dispose()
        assertIs<TrackMeError.NotInitialized>(TrackMe.sharing.start().errorOrNull())
        assertTrue(TrackMe.sharing.stop().isSuccess)   // stop is always safe
    }

    @Test
    fun permissionNotGranted_returnsDenied_andDoesNotStartHost() {
        val platform = FakePlatform(permission = LocationPermissionStatus(LocationPermissionLevel.NotDetermined, false, true, true))
        startWith(platform = platform) { error("no request") }
        val error = assertIs<TrackMeError.LocationPermissionDenied>(TrackMe.sharing.start(fast).errorOrNull())
        assertTrue(error.canAskAgain)
        assertEquals(0, platform.hostStarted)
        assertEquals(SharingState.Idle, TrackMe.sharing.state.value)
    }

    @Test
    fun deniedPermission_cannotAskAgain() {
        startWith(platform = FakePlatform(permission = LocationPermissionStatus(LocationPermissionLevel.Denied, false, true, true))) { error("no request") }
        val error = assertIs<TrackMeError.LocationPermissionDenied>(TrackMe.sharing.start(fast).errorOrNull())
        assertTrue(!error.canAskAgain)
    }

    @Test
    fun locationServicesOff() {
        startWith(platform = FakePlatform(permission = FakePlatform.granted().copy(servicesEnabled = false))) { error("no request") }
        assertIs<TrackMeError.LocationServicesDisabled>(TrackMe.sharing.start(fast).errorOrNull())
    }

    @Test
    fun startFromBackground_needsAlways() {
        val platform = FakePlatform(foreground = false)
        startWith(platform = platform) { json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""") }
        assertIs<TrackMeError.BackgroundNotAllowed>(TrackMe.sharing.start(fast).errorOrNull())

        platform.permission = FakePlatform.granted(LocationPermissionLevel.Always)
        assertTrue(TrackMe.sharing.start(fast).isSuccess)
    }

    @Test
    fun hostRefused_isReported_andNothingRuns() {
        val platform = FakePlatform(hostResult = TrackMeResult.Failure(TrackMeError.BackgroundNotAllowed("nope")))
        startWith(platform = platform) { error("no request") }
        assertIs<TrackMeError.BackgroundNotAllowed>(TrackMe.sharing.start(fast).errorOrNull())
        assertTrue(!TrackMe.sharing.state.value.isActive)
    }

    @Test
    fun invalidOptions_rejected() {
        startWith { error("no request") }
        assertIs<TrackMeError.Validation>(TrackMe.sharing.start(SharingOptions(intervalMillis = 1000, heartbeatMillis = 10)).errorOrNull())
    }

    // ---------- the loop ----------

    @Test
    fun fix_isUploaded_andStateBecomesSharing() = runTest {
        val platform = FakePlatform()
        var body = ""
        startWith(platform = platform) { request ->
            if (!request.url.encodedPath.endsWith("ingest/live")) return@startWith json("""{"trips":[]}""")
            body = (request.body as TextContent).text
            json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""")
        }
        assertTrue(TrackMe.sharing.start(fast).isSuccess)
        assertTrue(TrackMe.sharing.start(fast).isSuccess, "second start is a no-op")
        platform.push(27.7172, 85.3240)

        val sharing = assertIs<SharingState.Sharing>(awaitState { it is SharingState.Sharing && it.lastSentAtMillis != null })
        assertEquals(listOf(17L), sharing.tripIds)
        assertTrue("\"lat\":27.7172" in body && "\"batt\":80" in body, body)
        assertEquals(1, platform.hostStarted)
    }

    @Test
    fun emptyTripIds_stopsWithNoActiveTrips() = runTest {
        val platform = FakePlatform()
        startWith(platform = platform) { json("""{"accepted":true,"trip_ids":[],"fix_age_s":0}""") }
        TrackMe.sharing.start(fast)
        platform.push(27.7, 85.3)
        assertEquals(SharingState.Stopped(StopReason.NoActiveTrips), awaitState { it is SharingState.Stopped })
        assertEquals(1, platform.hostStopped)
    }

    @Test
    fun permissionRevokedWhileSharing_stopsCleanly() = runTest {
        val platform = FakePlatform()
        startWith(platform = platform) { json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""") }
        TrackMe.sharing.start(fast)
        platform.updates.trySend(LocationUpdate.PermissionRevoked)
        assertEquals(SharingState.Stopped(StopReason.PermissionRevoked), awaitState { it is SharingState.Stopped })
    }

    @Test
    fun gpsSwitchedOff_stopsWithServicesDisabled() = runTest {
        val platform = FakePlatform()
        startWith(platform = platform) { json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""") }
        TrackMe.sharing.start(fast)
        platform.updates.trySend(LocationUpdate.ServicesDisabled)
        assertEquals(SharingState.Stopped(StopReason.ServicesDisabled), awaitState { it is SharingState.Stopped })
    }

    @Test
    fun unrecoverable401_stopsWithUnauthorized() = runTest {
        val platform = FakePlatform()
        startWith(platform = platform) { respond("""{"detail":"expired"}""", HttpStatusCode.Unauthorized, jsonHeaders) }
        TrackMe.sharing.start(fast)
        platform.push(27.7, 85.3)
        assertEquals(SharingState.Stopped(StopReason.Unauthorized), awaitState { it is SharingState.Stopped })
    }

    @Test
    fun serverDown_keepsSharing_withRetryingFlag() = runTest {
        val platform = FakePlatform()
        startWith(platform = platform) { respond("down", HttpStatusCode.ServiceUnavailable) }
        TrackMe.sharing.start(fast)
        platform.push(27.7, 85.3)
        assertTrue((awaitState { it is SharingState.Sharing } as SharingState.Sharing).retrying)
        assertTrue(TrackMe.sharing.state.value.isActive)
    }

    @Test
    fun pause_stopsUploads_resume_continues() = runTest {
        val platform = FakePlatform()
        val uploads = Counter()
        startWith(platform = platform) { request ->
            if (!request.url.encodedPath.endsWith("ingest/live")) return@startWith json("""{"trips":[]}""")
            uploads.value++
            json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""")
        }
        TrackMe.sharing.start(fast)
        platform.push(27.7, 85.3)
        awaitState { it is SharingState.Sharing && it.lastSentAtMillis != null }

        assertTrue(TrackMe.sharing.pause().isSuccess)
        assertEquals(SharingState.Paused, TrackMe.sharing.state.value)
        realTime { delay(80) }   // let an in-flight upload land
        val atPause = uploads.value
        realTime { delay(300) }
        assertEquals(atPause, uploads.value, "no uploads while paused")

        assertTrue(TrackMe.sharing.resume().isSuccess)
        platform.push(27.71, 85.31)
        realTime { while (uploads.value == atPause) delay(10) }
    }

    @Test
    fun dispose_stopsSharing_andHost() = runTest {
        val platform = FakePlatform()
        startWith(platform = platform) { json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""") }
        TrackMe.sharing.start(fast)
        TrackMe.dispose()
        assertEquals(SharingState.Stopped(StopReason.Disposed), TrackMe.sharing.state.value)
        assertEquals(1, platform.hostStopped)
        assertIs<TrackMeError.NotInitialized>(TrackMe.sharing.pause().errorOrNull())
    }

    @Test
    fun userStop_isIdempotent() = runTest {
        val platform = FakePlatform()
        startWith(platform = platform) { json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""") }
        TrackMe.sharing.start(fast)
        TrackMe.sharing.stop(); TrackMe.sharing.stop()
        assertEquals(SharingState.Stopped(StopReason.User), TrackMe.sharing.state.value)
        assertEquals(1, platform.hostStopped)
    }

    @Test
    fun tripWindow_isLoadedOnce_andHandedToTheHost() = runTest {
        val platform = FakePlatform()
        val listCalls = Counter()
        startWith(platform = platform) { request ->
            if (request.url.encodedPath.endsWith("v1/trips")) {
                listCalls.value++
                json("""{"trips":[$TRIP_SUMMARY_JSON]}""")   // trip 17: planned 3600 s, 3470 s left
            } else {
                json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""")
            }
        }
        TrackMe.sharing.start(fast)
        platform.push(27.7, 85.3)
        val window = realTime { while (platform.lastWindow == null) delay(10); platform.lastWindow!! }
        assertEquals(3_600_000, window.endsAtMillis - window.startedAtMillis)
        val left = window.endsAtMillis - platform.nowMillis()
        assertTrue(left in 3_460_000..3_470_000, "left: $left")

        realTime { delay(300) }   // several more uploads with the same trip ids
        assertEquals(1, listCalls.value, "loaded once per set of trips")
    }

    @Test
    fun sharingState_isReported_onStart_pause_resume_stop() = runTest {
        val platform = FakePlatform()
        val reported = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        startWith(platform = platform) { request ->
            when {
                request.url.encodedPath.endsWith("ingest/state") -> {
                    reported.trySend((request.body as TextContent).text)
                    respond("", HttpStatusCode.NotFound)   // today's server: unknown endpoint, must change nothing
                }
                request.url.encodedPath.endsWith("v1/trips") -> json("""{"trips":[]}""")
                else -> json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""")
            }
        }
        suspend fun next() = realTime { reported.receive() }

        TrackMe.sharing.start(fast)
        assertEquals("""{"state":"sharing"}""", next())
        platform.push(27.7, 85.3)
        awaitState { it is SharingState.Sharing && it.lastSentAtMillis != null }   // the 404 didn't stop sharing

        TrackMe.sharing.pause()
        assertEquals("""{"state":"paused"}""", next())
        TrackMe.sharing.pause()   // already paused: nothing new to report
        TrackMe.sharing.resume()
        assertEquals("""{"state":"sharing"}""", next())
        TrackMe.sharing.stop()
        assertEquals("""{"state":"stopped"}""", next())
    }

    // ---------- pure logic ----------

    @Test
    fun tripMember_readsSharingState_andIgnoresUnknownValues() {
        fun member(raw: String) = io.baato.trackme.network.TrackMeJson.decodeFromString<io.baato.trackme.model.TripMember>(raw)
        assertEquals(
            io.baato.trackme.model.MemberSharingState.Paused,
            member("""{"user_id":9,"role":"member","sharing_state":"paused"}""").sharingState,
        )
        assertNull(member("""{"user_id":9,"role":"member"}""").sharingState)            // today's server
        assertNull(member("""{"user_id":9,"role":"member","sharing_state":"zzz"}""").sharingState)
    }

    @Test
    fun windowOf_picksTheLiveTripThatEndsLast() {
        fun trip(id: Long, status: String, planned: Long, remaining: Long) = io.baato.trackme.model.Trip(
            id = id, name = "t$id", myRole = "member", myStatus = "joined", status = status, memberCount = 1,
            startedOn = "", plannedSeconds = planned, actualSeconds = 0, expiresAt = "", remainingSeconds = remaining,
        )
        val trips = listOf(
            trip(1, "live", planned = 1_800, remaining = 600),
            trip(2, "live", planned = 7_200, remaining = 5_000),
            trip(3, "live", planned = 86_400, remaining = 80_000),   // not shared
            trip(4, "ended", planned = 3_600, remaining = 0),
        )
        val w = SharingController.windowOf(trips, setOf(1, 2, 4), nowMillis = 1_000_000)!!
        assertEquals(1_000_000 + 5_000_000, w.endsAtMillis)
        assertEquals(w.endsAtMillis - 7_200_000, w.startedAtMillis)
        assertNull(SharingController.windowOf(trips, setOf(4, 99), nowMillis = 0))
        assertEquals(0.5, io.baato.trackme.sharing.SharingWindow(0, 100).elapsedFraction(50))
    }

    private fun loc(lat: Double, lon: Double) = TrackMeLocation(lat, lon, 0, null, null, null, null)

    @Test
    fun shouldSend_respectsIntervalDistanceAndHeartbeat() {
        val o = SharingOptions(intervalMillis = 1_000, minDistanceMeters = 10.0, heartbeatMillis = 5_000)
        val a = loc(27.7000, 85.3000)
        val near = loc(27.70001, 85.30001)   // ~1.5 m
        val far = loc(27.7010, 85.3000)      // ~111 m
        assertTrue(SharingController.shouldSend(a, null, null, 0, o), "first fix always goes")
        assertTrue(!SharingController.shouldSend(far, a, 0, 500, o), "too soon")
        assertTrue(!SharingController.shouldSend(near, a, 0, 2_000, o), "too close")
        assertTrue(SharingController.shouldSend(far, a, 0, 2_000, o), "moved enough")
        assertTrue(SharingController.shouldSend(a, a, 0, 5_000, o), "heartbeat")
    }

    @Test
    fun backoff_grows_andCaps() {
        assertEquals(10_000, SharingController.backoff(5_000, 1))
        assertEquals(60_000, SharingController.backoff(5_000, 20))
    }

    @Test
    fun payload_isClamped_andUsesSeconds() {
        val p = TrackMeLocation(91.0, -181.0, 1_761_600_000_123, -1.0, null, 999.0, -1.0)
            .toPayload(BatteryInfo(150, true))
        assertEquals(90.0, p.lat); assertEquals(-180.0, p.lon)
        assertEquals(1_761_600_000, p.timestamp)
        assertNull(p.accuracy); assertEquals(400.0, p.speed); assertNull(p.bearing)
        assertEquals(100, p.batt)
    }
}
