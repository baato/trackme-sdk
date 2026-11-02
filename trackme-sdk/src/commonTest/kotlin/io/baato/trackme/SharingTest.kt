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
        startWith(platform = platform) { uploads.value++; json("""{"accepted":true,"trip_ids":[17],"fix_age_s":0}""") }
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

    // ---------- pure logic ----------

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
