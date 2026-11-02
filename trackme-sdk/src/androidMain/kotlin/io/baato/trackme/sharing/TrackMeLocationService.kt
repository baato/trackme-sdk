package io.baato.trackme.sharing

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.baato.trackme.TrackMe

/**
 * Foreground service of type `location`.
 *
 * It does no location work itself: the shared [SharingController] owns GPS and uploads. The service exists
 * so Android keeps delivering location after the app leaves the screen, and to host the notification.
 * Without a foreground service, background location stops within minutes on Android 8+.
 */
class TrackMeLocationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always call startForeground first: Android crashes the app if a service started with
        // startForegroundService() doesn't call it within a few seconds.
        val promoted = promoteToForeground()

        val session = TrackMe.currentSession()
        if (!promoted || session == null) {
            // Process was restarted without an SDK session, or Android refused the foreground service.
            if (!promoted) session?.sharing?.hostFailed()
            stopSelfSafely()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PAUSE -> TrackMe.sharing.pause()
            ACTION_RESUME -> TrackMe.sharing.resume()
            ACTION_STOP -> TrackMe.sharing.stop()   // → stopSharingHost() → stopService(this)
        }
        // Never auto-restart after the process dies: sharing needs a live SDK session and an explicit start().
        return START_NOT_STICKY
    }

    private fun promoteToForeground(): Boolean = try {
        val notification = SharingNotification.build(this, SharingHostBridge.ui, SharingHostBridge.state)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, SharingNotification.NOTIFICATION_ID, notification, type)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (12+), SecurityException (14+ missing permission), …
        false
    }

    private fun stopSelfSafely() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** User swiped the app away from Recents: keep sharing (that's the point of the service). */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    companion object {
        internal const val ACTION_PAUSE = "io.baato.trackme.action.PAUSE"
        internal const val ACTION_RESUME = "io.baato.trackme.action.RESUME"
        internal const val ACTION_STOP = "io.baato.trackme.action.STOP"
    }
}
