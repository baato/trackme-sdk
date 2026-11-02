package io.baato.trackme.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.baato.trackme.TrackMe
import io.baato.trackme.TrackMeError
import io.baato.trackme.realtime.RealtimeState
import io.baato.trackme.sharing.SharingState

// ======================= Share tab: permission + sharing + options =======================

private val intervals = listOf("2 s" to 2_000L, "5 s" to 5_000L, "15 s" to 15_000L, "60 s" to 60_000L)
private val distances = listOf("0 m" to 0.0, "5 m" to 5.0, "25 m" to 25.0, "100 m" to 100.0)
private val heartbeats = listOf("15 s" to 15_000L, "30 s" to 30_000L, "1 min" to 60_000L, "5 min" to 300_000L)

@Composable
fun SharingTab(vm: SampleViewModel, ui: SampleUiState) {
    val sharing by vm.sharingState.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PermissionCard(vm, ui) }
        item { SharingCard(vm, sharing) }
        item { OptionsCard(vm, ui, locked = sharing.isActive) }
        item {
            SectionCard("Notification look") {
                Caption("Set at login through TrackMeSharingUi (title, text, accent, pause/stop buttons). Log out and back in to change it. " +
                    "Pause / Resume / Stop in the notification call the same SDK functions as the buttons here.")
            }
        }
    }
}

@Composable
private fun PermissionCard(vm: SampleViewModel, ui: SampleUiState) {
    val context = LocalContext.current
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshPermission() }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshPermission() }
    val p = ui.permission

    SectionCard("Location permission  · location.permissionStatus") {
        Text(
            if (p == null) "Unknown"
            else "${p.level} · ${if (p.precise) "precise" else "approximate"} · device location ${if (p.servicesEnabled) "on" else "OFF"}",
        )
        Caption("The SDK never shows a prompt: the app asks, the SDK reports. Sharing needs at least While-in-use.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                // Ask for both so Android offers "precise or approximate"; notifications for the sharing notification.
                val perms = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                foreground.launch(perms.toTypedArray())
            }) { Text("Request") }
            if (Build.VERSION.SDK_INT >= 29 && p?.isGranted == true) {
                // Must be asked separately, AFTER foreground permission (Android 11+ opens Settings).
                OutlinedButton(onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("All the time") }
            }
            TextButton(onClick = { context.openAppSettings() }) { Text("Settings") }
        }
        if (p != null && !p.servicesEnabled) {
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Turn on device location") }
        }
    }
}

@Composable
private fun SharingCard(vm: SampleViewModel, sharing: SharingState) {
    val context = LocalContext.current
    var hint by remember { mutableStateOf<String?>(null) }

    SectionCard("Live sharing  · sharing.start / pause / resume / stop") {
        Text(
            when (sharing) {
                SharingState.Idle -> "Not sharing. Create or join a trip, then start."
                SharingState.Starting -> "Starting… waiting for the first GPS fix"
                is SharingState.Sharing -> buildString {
                    append("Sharing to trips ${sharing.tripIds}")
                    if (sharing.retrying) append(" · offline, retrying")
                    sharing.lastLocation?.let {
                        append("\n%.5f, %.5f ±%.0f m".format(it.latitude, it.longitude, it.accuracyMeters ?: 0.0))
                        it.speedMetersPerSecond?.let { s -> append(" · %.1f m/s".format(s)) }
                    }
                    sharing.lastSentAtMillis?.let { append("\nlast sent ${formatSeconds((System.currentTimeMillis() - it) / 1_000)} ago") }
                }
                SharingState.Paused -> "Paused (GPS on, nothing sent)"
                is SharingState.Stopped -> "Stopped: ${sharing.reason}"
            },
        )
        hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!sharing.isActive) {
                Button(onClick = {
                    hint = when (val e = vm.startSharing()) {
                        null -> null
                        is TrackMeError.LocationPermissionDenied ->
                            if (e.canAskAgain) "Tap Request above first." else "Permission denied: open Settings."
                        TrackMeError.LocationServicesDisabled -> {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); "Turn on location."
                        }
                        is TrackMeError.BackgroundNotAllowed -> "Start while the app is visible."
                        else -> e.message
                    }
                }) { Text("Start") }
            } else {
                if (sharing is SharingState.Paused) OutlinedButton(onClick = vm::resumeSharing) { Text("Resume") }
                else OutlinedButton(onClick = vm::pauseSharing) { Text("Pause") }
                Button(onClick = vm::stopSharing) { Text("Stop") }
            }
        }
        if (sharing is SharingState.Stopped) {
            Caption(
                when (sharing.reason.name) {
                    "NoActiveTrips" -> "You're in no live trip any more (it ended, or you left / were removed)."
                    "PermissionRevoked" -> "Location permission was turned off while sharing."
                    "ServicesDisabled" -> "Device location was turned off."
                    "Unauthorized" -> "The token was rejected and the provider gave no new one."
                    else -> "Start again any time."
                },
            )
        }
    }
}

@Composable
private fun OptionsCard(vm: SampleViewModel, ui: SampleUiState, locked: Boolean) {
    val o = ui.options
    SectionCard("SharingOptions") {
        Caption(if (locked) "Applied when sharing starts. Stop to change them." else "Used by the next Start.")
        Text("Upload at most every")
        ChipRow(intervals, o.intervalMillis, enabled = !locked) { vm.setOptions(o.copy(intervalMillis = it)) }
        Text("Skip fixes that moved less than")
        ChipRow(distances, o.minDistanceMeters, enabled = !locked) { vm.setOptions(o.copy(minDistanceMeters = it)) }
        Text("…but re-send at least every")
        ChipRow(heartbeats, o.heartbeatMillis, enabled = !locked) { vm.setOptions(o.copy(heartbeatMillis = it)) }
        LabeledSwitch("High accuracy (GPS)", o.highAccuracy, enabled = !locked) { vm.setOptions(o.copy(highAccuracy = it)) }
    }
}

// ======================= Viewer tab: watch a public view link =======================

@Composable
fun ViewerTab(vm: SampleViewModel, ui: SampleUiState) {
    val realtime by vm.realtimeState.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Watch a view link  · realtime.watchShare") {
                Caption("What a non-member sees on the web page, live. Paste a link like …/trackme/s/<token> (or just the token). " +
                    "Get one from a trip's \"Public view link\".")
                OutlinedTextField(ui.viewerInput, vm::setViewerInput, label = { Text("View link or token") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::watchShare, enabled = ui.viewerInput.isNotBlank()) { Text("Watch") }
                    OutlinedButton(onClick = vm::stopWatchingShare, enabled = ui.viewerWatching) { Text("Stop") }
                }
                Text(
                    when {
                        ui.viewerEnded -> "This link has ended (trip over, or the link was rotated)."
                        ui.viewerWatching -> when (val r = realtime) {
                            is RealtimeState.Reconnecting -> "Reconnecting (attempt ${r.attempt})…"
                            else -> "● Watching. Positions appear when members move."
                        }
                        else -> "Not watching"
                    },
                )
            }
        }
        items(ui.viewerPositions.entries.toList(), key = { it.key }) { (_, p) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.fullName ?: "Member ${p.userId}", fontWeight = FontWeight.SemiBold)
                    Text("%.5f, %.5f".format(p.latitude, p.longitude))
                    Caption(listOfNotNull(
                        p.fixAgeSeconds?.let { "fix ${formatSeconds(it)} old" },
                    ).joinToString(" · "))
                }
            }
        }
    }
}

// ======================= Inbox tab: notifications =======================

@Composable
fun InboxTab(vm: SampleViewModel, ui: SampleUiState) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard("Inbox  · notifications.list / markRead / markAllRead") {
                LabeledSwitch("Unread only", ui.unreadOnly, onChange = vm::setUnreadOnly)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.loadNotifications(reset = true) }) { Text("Refresh") }
                    OutlinedButton(onClick = vm::markAllRead, enabled = ui.notifications.any { !it.isRead }) { Text("Mark all read") }
                }
                Caption("Trip ended, member joined, trip deleted… created by the server. Tap \"Open trip\" to jump to it.")
            }
        }
        if (ui.notifications.isEmpty()) item { Caption("Nothing here yet.") }
        items(ui.notifications) { n ->   // no key: a later page could repeat an item
            val colors = if (n.isRead) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            Card(Modifier.fillMaxWidth(), colors = colors) {
                Column(Modifier.padding(12.dp)) {
                    Text(n.title, fontWeight = if (n.isRead) FontWeight.Normal else FontWeight.Bold)
                    Text(n.body)
                    Caption(n.createdAt.take(16).replace('T', ' ') + " UTC")
                    Row {
                        if (!n.isRead) TextButton(onClick = { vm.markRead(n) }) { Text("Mark read") }
                        if (n.tripId != null) TextButton(onClick = { vm.openNotificationTrip(n) }) { Text("Open trip") }
                    }
                }
            }
        }
        if (ui.hasMore) item { OutlinedButton(onClick = { vm.loadNotifications(reset = false) }, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
    }
}

// ======================= SDK tab: lifecycle, token, health, log =======================

@Composable
fun SdkTab(vm: SampleViewModel, ui: SampleUiState) {
    val sdk by vm.sdkState.collectAsStateWithLifecycle()
    val sharing by vm.sharingState.collectAsStateWithLifecycle()
    val realtime by vm.realtimeState.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("State") {
                Text("TrackMe.state: ${sdk::class.simpleName} · isInitialized ${TrackMe.isInitialized}")
                Text("sharing.state: ${sharing::class.simpleName}")
                Text("realtime.state: $realtime")
            }
        }
        item {
            SectionCard("Server  · checkHealth") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::checkHealth) { Text("Check health") }
                    Text(ui.health ?: "—")
                }
            }
        }
        item {
            SectionCard("Token") {
                OutlinedButton(onClick = vm::refreshTokenNow, modifier = Modifier.fillMaxWidth()) { Text("Fetch new token → updateToken()") }
                OutlinedButton(onClick = vm::simulateExpiredToken, modifier = Modifier.fillMaxWidth()) { Text("Simulate expired token") }
                Caption("\"Simulate\" puts a broken token in the SDK, then loads trips: the 401 makes the SDK ask the token provider, " +
                    "the log shows \"Token refreshed\", and the request still succeeds.")
            }
        }
        item {
            SectionCard("Session") {
                OutlinedButton(onClick = vm::stopRealtime, modifier = Modifier.fillMaxWidth()) { Text("realtime.stop() (close socket)") }
                OutlinedButton(onClick = vm::reinitialize, modifier = Modifier.fillMaxWidth()) { Text("initialize() again (replaces session)") }
                Button(onClick = vm::logout, modifier = Modifier.fillMaxWidth()) { Text("dispose() and log out") }
                Caption("dispose() stops sharing, removes the notification and closes the socket. Every call after it returns NotInitialized instead of crashing.")
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Event log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::clearLog) { Text("Clear") }
            }
        }
        items(ui.log) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
    }
}
