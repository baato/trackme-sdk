@file:OptIn(ExperimentalAtomicApi::class)

package io.baato.trackme.sharing

import io.baato.trackme.TrackMeError
import io.baato.trackme.TrackMeEvent
import io.baato.trackme.TrackMeResult
import io.baato.trackme.location.LocationPermissionLevel
import io.baato.trackme.location.LocationUpdate
import io.baato.trackme.location.TrackMeLocation
import io.baato.trackme.platform.PlatformServices
import io.baato.trackme.toTrackMeError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The location-sharing engine, shared by Android and iOS.
 *
 *   GPS fixes ──► keep only the newest ──► every tick: send? (interval, distance, heartbeat, backoff)
 *                                                  │
 *                          POST /api/v1/ingest/live ◄┘ ──► trip_ids empty → stop(NoActiveTrips)
 *                                                       401 unrecoverable → stop(Unauthorized)
 *                                                       offline / 5xx → retry with backoff, newest fix only
 *
 * One [Run] per start(). Every way a run can end goes through [finish], exactly once.
 */
internal class SharingController(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val platform: PlatformServices,
    private val ui: TrackMeSharingUi,
    private val state: MutableStateFlow<SharingState>,
    private val emit: (TrackMeEvent) -> Unit,
) {
    private class Run(val options: SharingOptions) {
        val finished = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        val latest = AtomicReference<TrackMeLocation?>(null)
        var job: Job? = null
    }

    private val current = AtomicReference<Run?>(null)

    fun start(options: SharingOptions): TrackMeResult<Unit> {
        validate(options)?.let { return TrackMeResult.Failure(it) }
        if (current.load() != null) return TrackMeResult.Success(Unit)   // already sharing: idempotent

        val perm = platform.permissionStatus()
        if (!perm.servicesEnabled) return TrackMeResult.Failure(TrackMeError.LocationServicesDisabled)
        if (!perm.isGranted) {
            return TrackMeResult.Failure(
                TrackMeError.LocationPermissionDenied(canAskAgain = perm.level == LocationPermissionLevel.NotDetermined),
            )
        }
        if (!platform.isAppInForeground() && perm.level != LocationPermissionLevel.Always) {
            return TrackMeResult.Failure(
                TrackMeError.BackgroundNotAllowed(
                    "Sharing can only start in background with 'Always' location permission. " +
                        "Start it while the app is visible instead.",
                ),
            )
        }

        val run = Run(options)
        if (!current.compareAndSet(null, run)) return TrackMeResult.Success(Unit)   // lost a race to another start()

        when (val host = platform.startSharingHost(ui)) {
            is TrackMeResult.Failure -> { current.compareAndSet(run, null); return host }
            is TrackMeResult.Success -> Unit
        }
        setState(SharingState.Starting)
        // LAZY so `run.job` is assigned before the loop can possibly call finish() → job.cancel().
        run.job = scope.launch(start = CoroutineStart.LAZY) { runLoop(run) }.also { it.start() }
        return TrackMeResult.Success(Unit)
    }

    /** Stop uploading. GPS stays on so resume works instantly, even from the lock screen. */
    fun pause(): TrackMeResult<Unit> {
        val run = current.load() ?: return notSharing()
        run.paused.store(true)
        setState(SharingState.Paused)
        return TrackMeResult.Success(Unit)
    }

    fun resume(): TrackMeResult<Unit> {
        val run = current.load() ?: return notSharing()
        if (!platform.permissionStatus().isGranted) {
            finish(run, StopReason.PermissionRevoked)
            return TrackMeResult.Failure(TrackMeError.LocationPermissionDenied(canAskAgain = false))
        }
        if (run.paused.compareAndSet(true, false)) {
            setState(SharingState.Sharing(emptyList(), run.latest.load(), null, retrying = false))
        }
        return TrackMeResult.Success(Unit)
    }

    /** Safe to call any time, any number of times. */
    fun stop(): TrackMeResult<Unit> {
        current.load()?.let { finish(it, StopReason.User) }
        return TrackMeResult.Success(Unit)
    }

    /** The platform host (e.g. Android foreground service) could not stay alive. */
    fun hostFailed() {
        current.load()?.let { finish(it, StopReason.BackgroundNotAllowed) }
    }

    /** Called by Session.close(): synchronous, so nothing survives dispose(). */
    fun shutdown() {
        current.load()?.let { finish(it, StopReason.Disposed) }
    }

    // ---- the loop ----

    private suspend fun runLoop(run: Run) = kotlinx.coroutines.coroutineScope {
        // 1) GPS collector: only remembers the newest fix, or ends the run on revoke / GPS off.
        launch {
            try {
                platform.locationUpdates(run.options).collect { update ->
                    when (update) {
                        is LocationUpdate.Fix -> run.latest.store(update.location)
                        LocationUpdate.PermissionRevoked -> finish(run, StopReason.PermissionRevoked)
                        LocationUpdate.ServicesDisabled -> finish(run, StopReason.ServicesDisabled)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A platform bug must not crash the host app: treat as GPS unavailable.
                finish(run, StopReason.ServicesDisabled)
            }
        }

        // 2) Sender: decides when to upload.
        var lastSent: TrackMeLocation? = null
        var lastSentAt: Long? = null
        var nextAttemptAt = 0L
        var failures = 0
        var tripIds: List<Long> = emptyList()
        val tick = (run.options.intervalMillis / 4).coerceIn(10, 1_000)

        while (isActive && !run.finished.load()) {
            delay(tick)
            if (run.paused.load()) continue
            val fix = run.latest.load() ?: continue
            val now = platform.nowMillis()
            if (now < nextAttemptAt) continue
            if (!shouldSend(fix, lastSent, lastSentAt, now, run.options)) continue

            val result = try {
                TrackMeResult.Success(
                    http.post("v1/ingest/live") {
                        contentType(ContentType.Application.Json)
                        setBody(fix.toPayload(platform.battery()))
                    }.body<LocationIngestResponse>(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TrackMeResult.Failure(e.toTrackMeError())
            }

            when (result) {
                is TrackMeResult.Success -> {
                    failures = 0
                    lastSent = fix
                    lastSentAt = now
                    tripIds = result.data.tripIds
                    if (tripIds.isEmpty()) {
                        finish(run, StopReason.NoActiveTrips)
                    } else if (!run.paused.load()) {
                        setState(SharingState.Sharing(tripIds, fix, now, retrying = false))
                    }
                }
                is TrackMeResult.Failure -> when (result.error) {
                    is TrackMeError.Unauthorized -> finish(run, StopReason.Unauthorized)
                    is TrackMeError.Validation -> {
                        // Server rejected this particular fix. Skip it; the next fix gets a fresh chance.
                        lastSent = fix
                        lastSentAt = now
                    }
                    else -> {
                        failures++
                        nextAttemptAt = now + backoff(run.options.intervalMillis, failures)
                        if (!run.paused.load()) setState(SharingState.Sharing(tripIds, fix, lastSentAt, retrying = true))
                    }
                }
            }
        }
    }

    private fun finish(run: Run, reason: StopReason) {
        if (!run.finished.compareAndSet(false, true)) return
        current.compareAndSet(run, null)
        run.job?.cancel()
        runCatching { platform.stopSharingHost() }
        setState(SharingState.Stopped(reason))
        emit(TrackMeEvent.SharingStopped(reason))
    }

    private fun setState(s: SharingState) {
        state.value = s
        runCatching { platform.updateSharingHost(ui, s) }
    }

    private fun notSharing() = TrackMeResult.Failure(TrackMeError.Validation("Location sharing is not active."))

    private fun validate(o: SharingOptions): TrackMeError? = when {
        o.intervalMillis < 1 -> TrackMeError.Validation("intervalMillis must be positive")
        o.heartbeatMillis < o.intervalMillis -> TrackMeError.Validation("heartbeatMillis must be >= intervalMillis")
        o.minDistanceMeters < 0 -> TrackMeError.Validation("minDistanceMeters must be >= 0")
        else -> null
    }

    companion object {
        internal fun shouldSend(
            fix: TrackMeLocation,
            lastSent: TrackMeLocation?,
            lastSentAt: Long?,
            now: Long,
            o: SharingOptions,
        ): Boolean {
            if (lastSent == null || lastSentAt == null) return true
            val elapsed = now - lastSentAt
            if (elapsed < o.intervalMillis) return false
            if (elapsed >= o.heartbeatMillis) return true
            return fix != lastSent && distanceMeters(lastSent, fix) >= o.minDistanceMeters
        }

        /** interval × 2^n, capped at 60 s. */
        internal fun backoff(intervalMillis: Long, failures: Int): Long =
            min(60_000.0, intervalMillis * 2.0.pow(failures.coerceAtMost(10))).toLong()

        /** Haversine distance. */
        internal fun distanceMeters(a: TrackMeLocation, b: TrackMeLocation): Double {
            val r = 6_371_000.0
            val dLat = (b.latitude - a.latitude).rad()
            val dLon = (b.longitude - a.longitude).rad()
            val h = sin(dLat / 2).pow(2) + cos(a.latitude.rad()) * cos(b.latitude.rad()) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        private fun Double.rad() = this * PI / 180.0
    }
}
