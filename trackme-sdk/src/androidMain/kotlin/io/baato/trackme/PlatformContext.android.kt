package io.baato.trackme

import android.content.Context

actual class PlatformContext(context: Context) {
    /** Always the application context, so the SDK never leaks an Activity. */
    val appContext: Context = context.applicationContext
}

/** Android shortcut: `TrackMe.initialize(context, config)`. */
fun TrackMe.initialize(context: Context, config: TrackMeConfig): TrackMeResult<Unit> =
    initialize(PlatformContext(context), config)
