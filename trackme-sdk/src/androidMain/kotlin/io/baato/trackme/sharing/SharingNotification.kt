package io.baato.trackme.sharing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Latest UI config + state, shared between the platform layer and the foreground service. */
internal object SharingHostBridge {
    @Volatile var ui: TrackMeSharingUi = TrackMeSharingUi()
    @Volatile var state: SharingState = SharingState.Idle
    @Volatile var window: SharingWindow? = null
}

/**
 * Builds the ongoing "sharing location" notification:
 * ```
 * (pin) TrackMe · 42:17 ⏱                    ← counts down to the trip's end by itself (chronometer)
 * Sharing your location
 * Members of your trip can see where you are.
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━──────────   ← time left in the trip
 * Live · 1 trip · ends 17:45
 * [Pause]  [Stop]
 * ```
 * Before the trip's end time is known (the first seconds), it shows "Updated … ago" instead of the countdown.
 */
internal object SharingNotification {
    const val CHANNEL_ID = "trackme_location_sharing"
    const val NOTIFICATION_ID = 0x7EAC

    fun ensureChannel(ctx: Context, ui: TrackMeSharingUi) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(ui.androidChannelName)
            .setShowBadge(false)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).createNotificationChannel(channel) }
    }

    fun build(ctx: Context, ui: TrackMeSharingUi, state: SharingState, window: SharingWindow?): Notification {
        val paused = state is SharingState.Paused
        val sharing = state as? SharingState.Sharing
        val now = System.currentTimeMillis()
        val countdown = window?.takeIf { it.endsAtMillis > now }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(if (ui.androidSmallIcon != 0) ui.androidSmallIcon else android.R.drawable.ic_menu_mylocation)
            .setContentTitle(ui.title)
            .setContentText(if (paused) ui.pausedText else ui.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_LOCATION_SHARING)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (ui.accentColor != 0L) builder.setColor(ui.accentColor.toInt())

        if (countdown != null) {
            // Android ticks a count-down chronometer by itself: no re-posting every second.
            builder.setWhen(countdown.endsAtMillis).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            val left = 1.0 - countdown.elapsedFraction(now)
            builder.setProgress(PROGRESS_MAX, (left * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX), false)
        } else {
            // "Updated 2 min ago" for free: Android renders the relative time of `when`.
            sharing?.lastSentAtMillis?.let { builder.setWhen(it).setShowWhen(true) } ?: builder.setShowWhen(false)
        }
        builder.setSubText(statusLine(state, countdown, ctx))

        launchIntent(ctx)?.let { builder.setContentIntent(it) }

        if (ui.showPauseAction) {
            if (paused) {
                builder.addAction(0, ui.resumeLabel, serviceIntent(ctx, TrackMeLocationService.ACTION_RESUME, 1))
            } else {
                builder.addAction(0, ui.pauseLabel, serviceIntent(ctx, TrackMeLocationService.ACTION_PAUSE, 2))
            }
        }
        if (ui.showStopAction) {
            builder.addAction(0, ui.stopLabel, serviceIntent(ctx, TrackMeLocationService.ACTION_STOP, 3))
        }
        return builder.build()
    }

    /** "Live · 1 trip · ends 17:45" / "Paused" / "Offline · retrying". */
    private fun statusLine(state: SharingState, window: SharingWindow?, ctx: Context): String {
        val sharing = state as? SharingState.Sharing
        val status = when {
            state is SharingState.Paused -> "Paused"
            sharing?.retrying == true -> "Offline · retrying"
            sharing != null && sharing.tripIds.isNotEmpty() -> "Live"
            else -> "Starting"
        }
        val trips = sharing?.tripIds?.size?.takeIf { it > 0 }?.let { if (it == 1) "1 trip" else "$it trips" }
        val ends = window?.let { "ends " + android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(it.endsAtMillis)) }
        return listOfNotNull(status, trips, ends).joinToString(" · ")
    }

    private const val PROGRESS_MAX = 1_000

    /** Re-post with the new state. Silently skipped if the user blocked notifications (sharing still runs). */
    @SuppressLint("MissingPermission")   // checked by canPost()
    fun show(ctx: Context, ui: TrackMeSharingUi, state: SharingState, window: SharingWindow?) {
        if (!canPost(ctx)) return
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, build(ctx, ui, state, window)) }
    }

    fun cancel(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID) }
    }

    /** Android 13+ needs POST_NOTIFICATIONS at runtime; the host app requests it. */
    fun canPost(ctx: Context): Boolean {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun launchIntent(ctx: Context): PendingIntent? {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceIntent(ctx: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            ctx,
            requestCode,
            Intent(ctx, TrackMeLocationService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
