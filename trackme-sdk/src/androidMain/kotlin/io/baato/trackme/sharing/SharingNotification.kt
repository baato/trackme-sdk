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
}

/** Builds the ongoing "sharing location" notification with Pause/Resume and Stop actions. */
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

    fun build(ctx: Context, ui: TrackMeSharingUi, state: SharingState): Notification {
        val paused = state is SharingState.Paused
        val sharing = state as? SharingState.Sharing

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

        // "Updated 2 min ago" for free: Android renders the relative time of `when`.
        sharing?.lastSentAtMillis?.let { builder.setWhen(it).setShowWhen(true) } ?: builder.setShowWhen(false)

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

    /** Re-post with the new state. Silently skipped if the user blocked notifications (sharing still runs). */
    @SuppressLint("MissingPermission")   // checked by canPost()
    fun show(ctx: Context, ui: TrackMeSharingUi, state: SharingState) {
        if (!canPost(ctx)) return
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, build(ctx, ui, state)) }
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
