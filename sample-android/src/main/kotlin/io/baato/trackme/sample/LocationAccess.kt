package io.baato.trackme.sample

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import io.baato.trackme.location.LocationPermissionLevel
import io.baato.trackme.location.LocationPermissionStatus

/**
 * Location access for the live map, in one place. The SDK never shows a prompt: it reports
 * (`TrackMe.location.permissionStatus()`), and the app asks.
 *
 * - [ask]: the system dialog (precise / approximate, plus notifications on Android 13+). When Android no longer shows
 *   it ("don't ask again"), it opens the app's settings instead.
 * - [askAlways]: "Allow all the time". Android 11+ shows the app's location settings page for it.
 */
class LocationAccess(
    val status: LocationPermissionStatus?,
    /** Android stopped showing the dialog: only Settings can grant location now. */
    val blocked: Boolean,
    /** Off = the sharing notification (with Pause / Stop) can't show. Sharing still works. */
    val notificationsOn: Boolean,
    val ask: () -> Unit,
    val askAlways: () -> Unit,
    val openSettings: () -> Unit,
    val openLocationSettings: () -> Unit,
) {
    val granted: Boolean get() = status?.isGranted == true
    val servicesOff: Boolean get() = status?.servicesEnabled == false
}

/** A system dialog answered faster than this was never shown: Android refused without asking. */
private const val NO_DIALOG_MILLIS = 400L

@Composable
fun rememberLocationAccess(vm: SampleViewModel, ui: SampleUiState): LocationAccess {
    val context = LocalContext.current
    val launchedAt = remember { longArrayOf(0) }
    fun quickAnswer() = SystemClock.elapsedRealtime() - launchedAt[0] < NO_DIALOG_MILLIS

    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            vm.setLocationBlocked(false)
            vm.onLocationGranted()
        } else {
            vm.refreshPermission()
            if (quickAnswer()) {          // no dialog appeared: denied for good
                vm.setLocationBlocked(true)
                context.openAppSettings()
            }
        }
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refreshPermission()
        if (!granted && quickAnswer()) context.openAppSettings()
    }

    return LocationAccess(
        status = ui.permission,
        blocked = ui.locationBlocked && ui.permission?.isGranted != true,
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        ask = {
            if (ui.locationBlocked) {
                context.openAppSettings()
            } else {
                launchedAt[0] = SystemClock.elapsedRealtime()
                foreground.launch(
                    buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray(),
                )
            }
        },
        askAlways = {
            if (Build.VERSION.SDK_INT >= 29) {
                launchedAt[0] = SystemClock.elapsedRealtime()
                background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                context.openAppSettings()   // before Android 10 there is no separate "all the time"
            }
        },
        openSettings = { context.openAppSettings() },
        openLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
    )
}

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFEF6C00)
private val Red = Color(0xFFC62828)

/** Small status chip on the map: what access the app has. Tap → [LocationDialog]. */
@Composable
fun LocationPill(access: LocationAccess, onClick: () -> Unit) {
    val s = access.status
    val (text, color) = when {
        s == null -> "Location" to Color.Gray
        !s.servicesEnabled -> "Location is off" to Red
        !s.isGranted -> "No location access" to Red
        s.level == LocationPermissionLevel.Always -> "Location: always" to Green
        else -> "Location: while using" to Amber
    }
    val approximate = s?.isGranted == true && !s.precise
    Surface(onClick = onClick, shape = RoundedCornerShape(50), shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(
                "  $text${if (approximate) " · approx." else ""}",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** What each level means for sharing, which one the app has, and the one action that improves it. */
@Composable
fun LocationDialog(access: LocationAccess, onDismiss: () -> Unit) {
    val s = access.status
    val level = s?.level
    val (actionLabel, action) = when {
        access.servicesOff -> "Turn on location" to access.openLocationSettings
        !access.granted && !access.blocked -> "Allow" to access.ask
        !access.granted -> "Open Settings" to access.openSettings
        level == LocationPermissionLevel.WhileInUse -> "Allow all the time" to access.askAlways
        else -> "Open Settings" to access.openSettings
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Location access") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LevelRow(
                    "Not allowed", "Nobody in your trip sees you, and the map can't show you.",
                    selected = !access.granted,
                )
                LevelRow(
                    "While using the app",
                    "Enough to share. Sharing you start keeps running in the background, with a notification.",
                    selected = level == LocationPermissionLevel.WhileInUse,
                )
                LevelRow(
                    "All the time",
                    "Also lets sharing start while the app is in the background, e.g. from the notification.",
                    selected = level == LocationPermissionLevel.Always,
                )
                if (access.servicesOff) Note("Location is off for the whole phone.", error = true)
                if (access.blocked) Note("Android won't ask again: allow location under Settings → Permissions.")
                if (access.granted && s?.precise == false) Note("Precise location is off, so others see an approximate position.")
                if (!access.notificationsOn) Note("Notifications are off, so the sharing notification (Pause / Stop) won't show.")
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); action() }) { Text(actionLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LevelRow(title: String, description: String, selected: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
