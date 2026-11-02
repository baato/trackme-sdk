@file:OptIn(ExperimentalMaterial3Api::class)

package io.baato.trackme.example

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.baato.trackme.TrackMeError
import io.baato.trackme.model.Trip
import io.baato.trackme.model.TripDetail
import io.baato.trackme.model.TripMember
import io.baato.trackme.realtime.RealtimeState
import io.baato.trackme.sharing.SharingState
import kotlinx.coroutines.delay

// ======================= Trips tab: create, join, list =======================

private val durations = listOf("5 min" to 5, "15 min" to 15, "1 h" to 60, "4 h" to 240)

@Composable
fun TripsTab(vm: SampleViewModel, ui: SampleUiState) {
    val context = LocalContext.current
    val copy = rememberCopier()
    var name by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf(60) }
    var code by rememberSaveable { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Create a trip  · trips.create") {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Duration: the trip ends by itself after this")
                ChipRow(durations, minutes) { minutes = it }
                Button(onClick = { vm.createTrip(name, minutes) }) { Text("Create") }
                ui.created?.let { c ->
                    HorizontalDivider()
                    Text("Join code: ${c.joinCode}", style = MaterialTheme.typography.headlineSmall)
                    Caption("Ends in ${formatSeconds(c.remainingSeconds)} · public link ${c.viewLink}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copy(c.joinCode) }) { Text("Copy code") }
                        OutlinedButton(onClick = { context.shareText("Follow me on TrackMe: ${c.viewLink}\nJoin code: ${c.joinCode}") }) { Text("Share") }
                        Button(onClick = { vm.openTrip(c.id) }) { Text("Open") }
                    }
                }
            }
        }
        item {
            SectionCard("Join with a code  · trips.preview / join") {
                OutlinedTextField(code, { code = it.uppercase().trim() }, label = { Text("Join code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.previewTrip(code) }, enabled = code.isNotBlank()) { Text("Preview") }
                    Button(onClick = { vm.joinTrip(code) }, enabled = code.isNotBlank()) { Text("Join") }
                }
                ui.preview?.let { Text("“${it.name}” by ${it.ownerName ?: "?"} · ${it.memberCount} members · ends ${it.expiresAt}") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My trips  · trips.list", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::loadTrips) { Text("Refresh") }
            }
        }
        if (ui.trips.isEmpty()) item { Caption("No trips yet. Create one, or join with a code from another device.") }
        items(ui.trips, key = { it.id }) { trip -> TripRow(trip) { vm.openTrip(trip.id) } }
        item { Caption("Tap a trip for members, live positions, links, history, rename, end, leave and delete.") }
    }
}

@Composable
private fun TripRow(trip: Trip, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trip.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusBadge(trip)
            }
            Text("${trip.myRole} · ${trip.memberCount} members" + if (trip.isLive) " · ${formatSeconds(trip.remainingSeconds)} left" else "")
            trip.joinCode?.takeIf { trip.isLive }?.let { Caption("Code: $it") }
        }
    }
}

@Composable
private fun StatusBadge(trip: Trip) {
    val (text, color) = if (trip.isLive) "● LIVE" to MaterialTheme.colorScheme.primary
    else "ENDED" + (trip.endedReason?.let { " ($it)" } ?: "") to MaterialTheme.colorScheme.outline
    Text(text, color = color, style = MaterialTheme.typography.labelMedium)
}

// ======================= Trip screen: everything about one trip =======================

/** Which confirmation / input dialog is open. */
private sealed interface TripDialog {
    data object Rename : TripDialog
    data object End : TripDialog
    data object Leave : TripDialog
    data object Delete : TripDialog
    data object Rotate : TripDialog
    data class Remove(val member: TripMember) : TripDialog
}

@Composable
fun TripScreen(vm: SampleViewModel, ui: SampleUiState, d: TripDetail) {
    val trip = d.trip
    var dialog by remember { mutableStateOf<TripDialog?>(null) }
    BackHandler(onBack = vm::closeTrip)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = vm::closeTrip) { Text("‹ Back") } },
                title = { Text(trip.name, maxLines = 1) },
                actions = { TextButton(onClick = vm::refreshTrip) { Text("Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { OverviewCard(ui, trip) }
                item { LiveCard(vm, ui, trip) }
                item { MembersCard(ui, d) { dialog = TripDialog.Remove(it) } }
                item { YourLocationCard(vm, trip) }
                item { ViewLinkCard(vm, ui, trip) { dialog = TripDialog.Rotate } }
                item { HistoryCard(vm, ui) }
                item { ManageCard(trip) { dialog = it } }
                item { Caption("") }
            }
        }
    }

    when (val dlg = dialog) {
        null -> Unit
        TripDialog.Rename -> RenameDialog(trip.name, onRename = vm::renameTrip, onDismiss = { dialog = null })
        TripDialog.End -> ConfirmDialog("End trip?", "Everyone stops sharing to this trip and members are notified. This can't be undone.",
            "End trip", vm::endTrip) { dialog = null }
        TripDialog.Leave -> ConfirmDialog("Leave trip?", "You stop seeing members. You can join again with the code while it's live.",
            "Leave", vm::leaveTrip) { dialog = null }
        TripDialog.Delete -> ConfirmDialog("Delete trip?", "Removes the trip and its history for everyone.",
            "Delete", vm::deleteTrip) { dialog = null }
        TripDialog.Rotate -> ConfirmDialog("New public link?", "The current link stops working for everyone who has it.",
            "Rotate", vm::rotateViewLink) { dialog = null }
        is TripDialog.Remove -> ConfirmDialog("Remove ${dlg.member.fullName ?: "member"}?", "They're taken out of the trip and stop seeing it.",
            "Remove", { vm.removeMember(dlg.member.userId) }) { dialog = null }
    }
}

@Composable
private fun OverviewCard(ui: SampleUiState, trip: Trip) {
    val copy = rememberCopier()
    val context = LocalContext.current
    // remainingSeconds is from load time; count down locally.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(trip.id) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val remaining = trip.remainingSeconds - (now - ui.detailLoadedAt) / 1_000

    SectionCard("Trip  · trips.detail") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("You are ${trip.myRole} (${trip.myStatus})", Modifier.weight(1f))
            StatusBadge(trip)
        }
        Text("${trip.memberCount} members · started ${trip.startedOn.take(16).replace('T', ' ')} UTC")
        if (trip.isLive) {
            Text("Ends in ${formatSeconds(remaining)}", style = MaterialTheme.typography.titleMedium)
            Caption("Planned ${formatSeconds(trip.plannedSeconds)}. When it runs out the server ends the trip and everyone gets trip_ended.")
        } else {
            Text("Ended ${trip.endedOn?.take(16)?.replace('T', ' ') ?: ""} · ${trip.endedReason ?: ""} · lasted ${formatSeconds(trip.actualSeconds)}")
        }
        trip.joinCode?.takeIf { trip.isLive }?.let { code ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Join code: $code", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { copy(code) }) { Text("Copy") }
                TextButton(onClick = { context.shareText("Join my TrackMe trip “${trip.name}” with code $code") }) { Text("Share") }
            }
        }
    }
}

@Composable
private fun LiveCard(vm: SampleViewModel, ui: SampleUiState, trip: Trip) {
    val realtime by vm.realtimeState.collectAsStateWithLifecycle()
    SectionCard("Live updates  · realtime.watchTrip") {
        LabeledSwitch(
            label = when (val r = realtime) {
                is RealtimeState.Connected -> if (r.tripId == trip.id) "● Live" else "Connected (another trip)"
                is RealtimeState.Reconnecting -> "Reconnecting (attempt ${r.attempt})…"
                RealtimeState.Connecting -> "Connecting…"
                RealtimeState.Idle -> "Off"
            },
            checked = ui.watchingLive,
            enabled = trip.isLive,
            onChange = vm::setWatchLive,
        )
        Caption(
            if (trip.isLive) "Members' positions update as they move (only while they share). Joins refresh the list; " +
                "the screen reacts to trip_ended and to being removed."
            else "Live updates are only available while the trip is live.",
        )
    }
}

@Composable
private fun MembersCard(ui: SampleUiState, d: TripDetail, onRemove: (TripMember) -> Unit) {
    SectionCard("Members (${d.members.size})") {
        d.members.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider()
            val isMe = m.fullName != null && m.fullName == ui.login?.fullName
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${m.fullName ?: "User ${m.userId}"}${if (isMe) " (you)" else ""}", fontWeight = FontWeight.SemiBold)
                    Caption("${m.role} · id ${m.userId}")
                    // A live socket position (if one arrived) beats the snapshot from trips.detail().
                    val live = ui.livePositions[m.userId]
                    Text(
                        when {
                            live != null -> "%.5f, %.5f · live".format(live.latitude, live.longitude) +
                                (live.speedMetersPerSecond?.let { " · %.1f m/s".format(it) } ?: "")
                            m.hasPosition -> "%.5f, %.5f · %s ago".format(m.lat, m.lon, formatSeconds(m.fixAgeSeconds ?: 0))
                            else -> "no position yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (d.trip.isOwner && d.trip.isLive && !m.isOwner) {
                    TextButton(onClick = { onRemove(m) }) { Text("Remove") }   // trips.removeMember
                }
            }
        }
    }
}

@Composable
private fun YourLocationCard(vm: SampleViewModel, trip: Trip) {
    val sharing by vm.sharingState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hint by remember { mutableStateOf<String?>(null) }
    if (!trip.isLive) return
    SectionCard("Your location  · sharing") {
        Text(
            when (val s = sharing) {
                is SharingState.Sharing -> if (trip.id in s.tripIds) "Sharing to this trip" else "Sharing (waiting for the server to include this trip)"
                SharingState.Paused -> "Paused"
                SharingState.Starting -> "Starting…"
                else -> "Not sharing: members can't see you"
            },
        )
        hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!sharing.isActive) {
                Button(onClick = {
                    hint = when (val e = vm.startSharing()) {
                        null -> null
                        is TrackMeError.LocationPermissionDenied -> "Grant location on the Share tab first."
                        TrackMeError.LocationServicesDisabled -> {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); "Turn on location."
                        }
                        else -> e.message
                    }
                }) { Text("Start sharing") }
            } else {
                OutlinedButton(onClick = vm::stopSharing) { Text("Stop sharing") }
            }
        }
        Caption("Sharing goes to every live trip you're in. Options and pause are on the Share tab.")
    }
}

@Composable
private fun ViewLinkCard(vm: SampleViewModel, ui: SampleUiState, trip: Trip, onRotate: () -> Unit) {
    val copy = rememberCopier()
    val context = LocalContext.current
    SectionCard("Public view link  · trips.viewLink / rotateViewLink") {
        Caption("Anyone with this link can watch in a browser, no login.")
        val link = ui.viewLink
        if (link == null) {
            OutlinedButton(onClick = vm::loadViewLink) { Text("Get link") }
        } else {
            Text(link, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { copy(link) }) { Text("Copy") }
                TextButton(onClick = { context.shareText("Watch ${trip.name} live: $link") }) { Text("Share") }
                TextButton(onClick = vm::watchLinkInViewer) { Text("Watch in app") }
                if (trip.isOwner) TextButton(onClick = onRotate) { Text("Rotate") }
            }
        }
    }
}

@Composable
private fun HistoryCard(vm: SampleViewModel, ui: SampleUiState) {
    SectionCard("History  · trips.history") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.loadHistory(15) }) { Text("Last 15 min") }
            OutlinedButton(onClick = { vm.loadHistory(null) }) { Text("Whole trip") }
            if (ui.history != null) TextButton(onClick = vm::clearHistory) { Text("Clear") }
        }
        ui.history?.let { json ->
            Caption("${json.length} characters of raw JSON (the SDK returns it untyped until the format settles):")
            Text(json.take(2_000) + if (json.length > 2_000) "…" else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ManageCard(trip: Trip, open: (TripDialog) -> Unit) {
    val danger = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
    SectionCard(if (trip.isOwner) "Manage (owner)" else "Manage") {
        if (trip.isOwner) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open(TripDialog.Rename) }) { Text("Rename") }          // trips.rename
                if (trip.isLive) Button(onClick = { open(TripDialog.End) }, colors = danger) { Text("End trip") }   // trips.end
            }
            Button(onClick = { open(TripDialog.Delete) }, enabled = !trip.isLive, colors = danger) { Text("Delete trip") }   // trips.delete
            Caption(if (trip.isLive) "Delete is available once the trip has ended (end it, or wait for the time to run out)." else "This trip has ended; you can delete it.")
        } else {
            if (trip.isLive) Button(onClick = { open(TripDialog.Leave) }, colors = danger) { Text("Leave trip") }   // trips.leave
            Caption("Only the owner can rename, end, remove members or delete.")
        }
    }
}

@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename trip") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onDismiss(); onRename(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
