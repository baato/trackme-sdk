package io.baato.trackme

/** Sends a diagnostic line to [TrackMe.debugLogger], if one is set. Never throws. */
internal inline fun debugLog(message: () -> String) {
    val logger = TrackMe.debugLogger ?: return
    runCatching { logger(message()) }
}
