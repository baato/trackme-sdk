package io.baato.trackme.sharing

import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeEvent
import io.baato.trackme.TrackMeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * What the Live Activity / notification shows, flattened so Swift can read it without Kotlin sealed classes.
 * `lastSentAtMillis == 0` means nothing was sent yet.
 */
data class SharingSnapshot(
    val title: String,
    val text: String,
    val paused: Boolean,
    val retrying: Boolean,
    val tripCount: Int,
    val lastSentAtMillis: Long,
    val accentColor: Long,
    val symbolName: String,
    val showPauseAction: Boolean,
    val showStopAction: Boolean,
    val pauseLabel: String,
    val resumeLabel: String,
    val stopLabel: String,
)

/**
 * Implemented in Swift by the `TrackMeLiveActivity` companion package (ActivityKit can't be used from Kotlin,
 * and the Live Activity UI must live in the host app's Widget Extension).
 *
 * All calls arrive on the main thread.
 *
 * ```swift
 * TrackMeIos.shared.setSharingPresenter(presenter: TrackMeLiveActivityPresenter())
 * ```
 */
interface TrackMeSharingPresenter {
    fun sharingStarted(snapshot: SharingSnapshot)
    fun sharingUpdated(snapshot: SharingSnapshot)
    fun sharingEnded()
}

/** Returned by the observe functions. Call [cancel] (e.g. in `deinit` / `onDisappear`) to stop observing. */
interface TrackMeCancellable {
    fun cancel()
}

/**
 * iOS-only entry points. Swift: `TrackMeIos.shared`.
 *
 * Swift can't collect Kotlin Flows directly, so these wrap the SDK's flows in plain callbacks
 * (delivered on the main thread, safe to update SwiftUI state from). Later SKIE can replace them with AsyncSequence.
 */
object TrackMeIos {
    /** Register (or clear with nil) the UI that shows sharing on the lock screen. Optional: sharing works without it. */
    fun setSharingPresenter(presenter: TrackMeSharingPresenter?) {
        IosSharingHost.presenter = presenter
    }

    /** Calls [onChange] with the current sharing state immediately, then on every change. */
    fun observeSharing(onChange: (SharingState) -> Unit): TrackMeCancellable =
        observe(TrackMe.sharing.state, onChange)

    /** Calls [onEvent] for every [TrackMeEvent] (sharing stopped, token refreshed, auth failed, …). */
    fun observeEvents(onEvent: (TrackMeEvent) -> Unit): TrackMeCancellable =
        observe(TrackMe.events, onEvent)

    /** Calls [onChange] with the live-updates connection state (Idle / Connecting / Connected / Reconnecting). */
    fun observeRealtime(onChange: (io.baato.trackme.realtime.RealtimeState) -> Unit): TrackMeCancellable =
        observe(TrackMe.realtime.state, onChange)

    /** Calls [onChange] with the SDK lifecycle state (NotInitialized / Ready / Disposed). */
    fun observeState(onChange: (TrackMeState) -> Unit): TrackMeCancellable =
        observe(TrackMe.state, onChange)

    private fun <T> observe(flow: Flow<T>, callback: (T) -> Unit): TrackMeCancellable {
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            flow.collect { runCatching { callback(it) } }
        }
        return object : TrackMeCancellable {
            override fun cancel() = job.cancel()
        }
    }
}

internal object IosSharingHost {
    @kotlin.concurrent.Volatile var presenter: TrackMeSharingPresenter? = null

    fun started(ui: TrackMeSharingUi) = onMain { it.sharingStarted(snapshot(ui, SharingState.Starting)) }

    fun updated(ui: TrackMeSharingUi, state: SharingState) {
        if (state.isActive) onMain { it.sharingUpdated(snapshot(ui, state)) }
    }

    fun ended() = onMain { it.sharingEnded() }

    private fun onMain(call: (TrackMeSharingPresenter) -> Unit) {
        val p = presenter ?: return
        dispatch_async(dispatch_get_main_queue()) { runCatching { call(p) } }
    }

    private fun snapshot(ui: TrackMeSharingUi, state: SharingState): SharingSnapshot {
        val sharing = state as? SharingState.Sharing
        val paused = state is SharingState.Paused
        return SharingSnapshot(
            title = ui.title,
            text = if (paused) ui.pausedText else ui.text,
            paused = paused,
            retrying = sharing?.retrying == true,
            tripCount = sharing?.tripIds?.size ?: 0,
            lastSentAtMillis = sharing?.lastSentAtMillis ?: 0,
            accentColor = ui.accentColor,
            symbolName = ui.iosSymbolName,
            showPauseAction = ui.showPauseAction,
            showStopAction = ui.showStopAction,
            pauseLabel = ui.pauseLabel,
            resumeLabel = ui.resumeLabel,
            stopLabel = ui.stopLabel,
        )
    }
}
