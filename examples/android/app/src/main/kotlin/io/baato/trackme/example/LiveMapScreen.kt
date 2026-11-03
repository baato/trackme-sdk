@file:OptIn(ExperimentalMaterial3Api::class)

package io.baato.trackme.example

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.baato.trackme.TrackMeError
import io.baato.trackme.model.TripDetail
import io.baato.trackme.realtime.RealtimeState
import io.baato.trackme.sharing.SharingState
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.launch

/**
 * The showcase: a ready-to-use "share my trip live" feature on a Baato map.
 * No trip → start or join. Invite pasted → see who's there, then join. In a trip → members live, share/pause,
 * invite, leave (member) or end (owner). Built only from the same SDK calls as the developer screens.
 *
 * The panel is a bottom sheet: drag it down to a small peek (title + status) to see more map, up for everything.
 *
 * [contentPadding]: system bars / bottom bar. The map itself draws edge to edge under the status bar.
 * [sheetOverBottomInset]: true when nothing sits below this screen (showcase-only build): the sheet then reaches
 * the screen edge and pads its own content above the gesture bar. False when an app bottom bar is below.
 */
@Composable
fun LiveMapScreen(vm: SampleViewModel, ui: SampleUiState, contentPadding: PaddingValues, sheetOverBottomInset: Boolean = false) {
    val realtime by vm.realtimeState.collectAsStateWithLifecycle()
    val sharing by vm.sharingState.collectAsStateWithLifecycle()
    var recenter by remember { mutableIntStateOf(0) }
    val granted = ui.permission?.isGranted == true

    LaunchedEffect(Unit) { vm.refreshPermission(); vm.mapRefresh() }   // every time the map appears

    // Location: asked once by itself when the map first shows without it; after that only when the user taps
    // the locate button, "Allow location", or the location pill. Never nags.
    val access = rememberLocationAccess(vm, ui)
    var showLocationDialog by remember { mutableStateOf(false) }
    LaunchedEffect(ui.permission) {
        val p = ui.permission ?: return@LaunchedEffect
        if (!p.isGranted && p.servicesEnabled && !ui.locationAutoAsked && !ui.locationBlocked) {
            vm.markLocationAutoAsked()
            access.ask()
        }
    }
    if (showLocationDialog) LocationDialog(access) { showLocationDialog = false }

    // Fallback next to the socket: while no live position arrives, refresh the trip snapshot every 5 s.
    val shownTrip = ui.mapDetail?.trip?.takeIf { it.isLive }?.id
    LaunchedEffect(shownTrip) {
        if (shownTrip == null) return@LaunchedEffect
        while (true) { delay(5_000); vm.mapPoll() }
    }

    // You: the SDK's own last fix while sharing (what members see), otherwise the phone's location.
    val device = rememberDeviceLocation(granted)
    val me = (sharing as? SharingState.Sharing)?.lastLocation?.let { LatLng(it.latitude, it.longitude) } ?: device

    val markers = remember(ui.mapDetail, ui.livePositions, ui.liveTripId, ui.invite, ui.viewerPositions, me != null) {
        buildMarkers(ui, hideMe = me != null)   // when we know where you are, you're the blue dot instead
    }
    val fitKey: Any? = ui.invite?.let { "invite:${it.viewToken}:${it.code}" } ?: ui.mapDetail?.trip?.id

    val bottomInset = contentPadding.calculateBottomPadding()
    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded, skipHiddenState = true)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    // A different panel (start → trip → ended, or an invite) opens fully, so its buttons are visible.
    val panelKind = when {
        ui.invite != null -> "invite"
        ui.mapDetail?.trip?.isLive == true -> "trip:${ui.mapDetail?.trip?.id}"
        ui.mapDetail != null -> "ended"
        else -> "start"
    }
    LaunchedEffect(panelKind) { runCatching { sheetState.expand() } }

    // What covers the map: the top buttons, and the sheet (its current height). Camera moves centre between them.
    val density = LocalDensity.current
    var containerHeight by remember { mutableIntStateOf(0) }
    val topInsetPx = with(density) { (contentPadding.calculateTopPadding() + 64.dp).roundToPx() }
    val bottomInsetPx: () -> Int = {
        val sheetTop = runCatching { sheetState.requireOffset() }.getOrNull()
        if (sheetTop == null || containerHeight == 0) 0
        else (containerHeight - sheetTop).toInt().coerceAtLeast(0) + with(density) { 16.dp.roundToPx() }
    }

    // Tap a member (chip or dot): drop the sheet to its peek, then fly to them.
    val scope = rememberCoroutineScope()
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    val focusOn: (String) -> Unit = { id ->
        scope.launch {
            runCatching { sheetState.partialExpand() }
            focus = MapFocus(id, (focus?.seq ?: 0) + 1)
        }
    }

    BottomSheetScaffold(
        modifier = Modifier.fillMaxSize().padding(bottom = if (sheetOverBottomInset) 0.dp else bottomInset),
        scaffoldState = scaffoldState,
        sheetPeekHeight = PEEK + if (sheetOverBottomInset) bottomInset else 0.dp,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetShadowElevation = 12.dp,
        sheetDragHandle = { BottomSheetDefaults.DragHandle() },
        sheetContent = {
            PanelContent(vm, ui, access, Modifier.padding(bottom = panelBottomPadding(bottomInset, sheetOverBottomInset)), onMemberTap = focusOn)
        },
    ) { _ ->
    Box(Modifier.fillMaxSize().onSizeChanged { containerHeight = it.height }) {
        if (Baato.isConfigured) {
            BaatoMap(
                markers, me, fitKey, recenter, Modifier.fillMaxSize(),
                focus = focus, onMarkerTap = focusOn,
                topInsetPx = topInsetPx, bottomInsetPx = bottomInsetPx,
                onError = vm::reportMapError,
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(
                    "Add baato.accessToken to local.properties\nto show the Baato map.",
                    textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp),
                )
            }
        }

        // Top: live status + location access, log out / recenter.
        Row(
            Modifier.fillMaxWidth().padding(top = contentPadding.calculateTopPadding()).padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.mapDetail?.trip?.isLive == true || ui.invite != null) LivePill(realtime, watchingInvite = ui.invite != null)
                LocationPill(access) { showLocationDialog = true }
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(50), shadowElevation = 4.dp) {
                    TextButton(onClick = vm::logout) { Text("Log out") }
                }
                // Locate button = "show me". Without location access it asks (or explains, when only Settings can help).
                LocateButton(
                    state = when {
                        !access.granted || access.servicesOff -> LocateState.Disabled
                        me == null -> LocateState.Searching
                        else -> LocateState.Located
                    },
                    onClick = {
                        when {
                            access.granted -> recenter++
                            access.servicesOff || access.blocked -> showLocationDialog = true
                            else -> access.ask()
                        }
                    },
                )
            }
        }
    }
    }
}

/** Collapsed sheet: drag handle + the panel's title and one line. */
private val PEEK = 128.dp

/**
 * Space under the panel's content: the keyboard when it's open, else the gesture bar. Counted from the screen's
 * bottom edge, so when the sheet already sits [bottomInset] higher (above the app's bottom bar), that part is
 * subtracted. Only the keyboard *once*: the window itself doesn't resize (edge-to-edge).
 */
@Composable
private fun panelBottomPadding(bottomInset: Dp, sheetOverBottomInset: Boolean): Dp {
    val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    return if (sheetOverBottomInset) maxOf(ime, bottomInset) else (ime - bottomInset).coerceAtLeast(0.dp)
}

private fun buildMarkers(ui: SampleUiState, hideMe: Boolean): List<MapMarker> {
    // Watching an invite: whatever the view link shows.
    if (ui.invite != null) {
        return ui.viewerPositions.map { (key, p) ->
            MapMarker("v$key", (p.fullName ?: "Member").substringBefore(' '), p.latitude, p.longitude, markerColor(key))
        }
    }
    val d = ui.mapDetail ?: return emptyList()
    val myName = ui.login?.fullName
    return d.members.mapNotNull { m ->
        // You are the blue dot when your location is known.
        if (hideMe && m.fullName != null && m.fullName == myName) return@mapNotNull null
        // The newer of the live socket position and the snapshot from trips.detail().
        val live = ui.newestLive(m, d.trip.id, ui.mapLoadedAt, System.currentTimeMillis())
        val lat = live?.latitude ?: m.lat ?: return@mapNotNull null
        val lon = live?.longitude ?: m.lon ?: return@mapNotNull null
        MapMarker("u${m.userId}", (m.fullName ?: "Member").substringBefore(' '), lat, lon, markerColor(m.userId))
    }
}

@Composable
private fun LivePill(realtime: RealtimeState, watchingInvite: Boolean) {
    val (text, color) = when (realtime) {
        is RealtimeState.Connected -> (if (watchingInvite) "● Watching" else "● Live") to Color(0xFF2E7D32)
        is RealtimeState.Reconnecting -> "Reconnecting…" to Color(0xFFEF6C00)
        RealtimeState.Connecting -> "Connecting…" to Color(0xFFEF6C00)
        RealtimeState.Idle -> "Offline" to Color.Gray
    }
    Surface(shape = RoundedCornerShape(50), shadowElevation = 4.dp) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

// ======================= bottom panel =======================

/** Each panel starts with its title + one status line: that's what the collapsed sheet shows. */
@Composable
private fun PanelContent(vm: SampleViewModel, ui: SampleUiState, access: LocationAccess, modifier: Modifier, onMemberTap: (String) -> Unit) {
    Column(
        modifier                                      // includes the keyboard's height: see panelBottomPadding
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())   // small screens: scroll inside the expanded sheet
            .animateContentSize()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val invite = ui.invite
        val detail = ui.mapDetail
        when {
            invite != null -> InvitePanel(vm, ui, invite)
            detail != null && detail.trip.isLive -> TripPanel(vm, ui, detail, access, onMemberTap)
            detail != null -> EndedPanel(vm, detail)
            else -> StartPanel(vm)
        }
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private val panelDurations = listOf("30 min" to 30, "1 h" to 60, "2 h" to 120, "4 h" to 240)

@Composable
private fun StartPanel(vm: SampleViewModel) {
    var mode by rememberSaveable { mutableStateOf("") }   // "", "start", "join"
    var name by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableIntStateOf(60) }
    var invite by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Text("Share your trip, live", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("Start a trip and invite people, or join someone else's.", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { mode = "start" }, modifier = Modifier.weight(1f)) { Text("Start a trip") }
        OutlinedButton(onClick = { mode = "join" }, modifier = Modifier.weight(1f)) { Text("Join") }
    }
    when (mode) {
        "start" -> {
            OutlinedTextField(name, { name = it }, label = { Text("Trip name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DurationPicker(panelDurations, minutes) { minutes = it }
            Button(
                onClick = { vm.mapCreate(name, minutes) }, enabled = minutes >= 0, modifier = Modifier.fillMaxWidth(),
            ) { Text("Start & share my location") }
        }
        "join" -> {
            OutlinedTextField(
                invite, { invite = it }, label = { Text("Join code or invite message") }, modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        scope.launch { clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.let { invite = it.toString() } }
                    }) { Text("Paste") }
                },
            )
            Button(onClick = { vm.mapOpenInvite(invite) }, enabled = invite.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }
    }
}

@Composable
private fun InvitePanel(vm: SampleViewModel, ui: SampleUiState, invite: Invite) {
    var code by rememberSaveable(invite) { mutableStateOf(invite.code ?: "") }
    val p = invite.preview

    Text(p?.name ?: "Shared trip", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        when {
            ui.viewerEnded -> "This trip has ended."
            p != null -> "by ${p.ownerName ?: "someone"} · ${p.memberCount} members"
            else -> "Watching the shared link."
        },
    )
    if (invite.viewToken != null && !ui.viewerEnded) {
        Text(
            if (ui.viewerPositions.isEmpty()) "Waiting for positions…" else "${ui.viewerPositions.size} on the map, live",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (invite.code == null) {
        OutlinedTextField(code, { code = it.uppercase().trim() }, label = { Text("Join code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    // The server sends each upload to EVERY live trip you're in (the API has no per-trip choice yet), so joining
    // while you share elsewhere starts sharing to this trip too. Say so instead of doing it silently.
    val sharing by vm.sharingState.collectAsStateWithLifecycle()
    if (sharing.isActive) {
        Text(
            "You're sharing your location right now, so joining adds this trip to it. Pause or stop sharing first to join without sharing.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        )
    }
    // Join shows everyone; sharing your own location is a separate button once you're in.
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.mapJoin(code) }, enabled = code.isNotBlank() && !ui.viewerEnded, modifier = Modifier.weight(1f)) {
            Text("Join")
        }
        OutlinedButton(onClick = vm::mapCancelInvite, modifier = Modifier.weight(1f)) { Text("Close") }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)   // combinedClickable on older foundation versions
@Composable
private fun TripPanel(vm: SampleViewModel, ui: SampleUiState, d: TripDetail, access: LocationAccess, onMemberTap: (String) -> Unit) {
    val trip = d.trip
    val sharing by vm.sharingState.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<io.baato.trackme.model.TripMember?>(null) }
    val haptics = LocalHapticFeedback.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(trip.id) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val remaining = trip.remainingSeconds - (now - ui.mapLoadedAt) / 1_000

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(trip.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Ends in ${formatSeconds(remaining)} · ${d.members.size} ${if (d.members.size == 1) "person" else "people"}",
                style = MaterialTheme.typography.bodyMedium)
            // Time left, draining: same as the sharing notification.
            LinearProgressIndicator(
                progress = { (remaining.toFloat() / trip.plannedSeconds.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(50)),
            )
        }
    }

    // More than one live trip: pick which one the map shows.
    if (ui.mapLiveTrips.size > 1) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.mapLiveTrips.forEach { t ->
                FilterChip(selected = t.id == trip.id, onClick = { vm.mapSelect(t.id) }, label = { Text(t.name, maxLines = 1) })
            }
        }
    }

    // How others join: the code, big, with one-tap copy and share. (The share text also carries the view link.)
    InviteCard(vm, trip.joinCode)

    // Who's here. Tap someone to find them on the map; the owner long-presses someone to remove them.
    if (trip.isOwner && d.members.size > 1) {
        Text("Tap someone to find them · long-press to remove", style = MaterialTheme.typography.labelSmall)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        d.members.forEach { m ->
            // "live" only while positions keep arriving: a paused member turns into "50 s ago" by itself.
            val status = ui.memberStatus(m, trip.id, ui.mapLoadedAt, now)
            // Tap → the map flies to them (to you, for your own chip). Owner: long-press → remove them.
            val removable = trip.isOwner && !m.isOwner
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .combinedClickable(
                            onClick = { onMemberTap("u${m.userId}") },
                            onLongClick = if (removable) ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); removing = m }) else null,
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(Color(android.graphics.Color.parseColor(markerColor(m.userId))), CircleShape))
                    Text(
                        "  ${(m.fullName ?: "Member").substringBefore(' ')}${if (m.isOwner) " ★" else ""} · $status",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }

    // Your location.
    val error = ui.mapSharingError
    if (!sharing.isActive && error is TrackMeError.LocationPermissionDenied && !access.granted) {
        // Granting starts sharing by itself (vm.onLocationGranted).
        Button(onClick = access.ask, modifier = Modifier.fillMaxWidth()) {
            Text(if (access.blocked) "Allow location in Settings" else "Allow location to share")
        }
    } else {
        val (label, active) = when (sharing) {
            is SharingState.Sharing -> "● Sharing your location · tap to pause" to true
            SharingState.Starting -> "Starting…" to true
            SharingState.Paused -> "Paused · tap to resume" to false
            else -> "Share my location too" to false
        }
        val colors = if (active) ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)) else ButtonDefaults.buttonColors()
        Button(onClick = vm::mapToggleSharing, colors = colors, modifier = Modifier.fillMaxWidth()) { Text(label) }
        if (error != null && error !is TrackMeError.LocationPermissionDenied && !sharing.isActive) {
            Text(error.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    OutlinedButton(
        onClick = { confirm = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (trip.isOwner) "End trip" else "Leave trip") }

    removing?.let { m ->
        ConfirmDialog("Remove ${m.fullName ?: "member"}?", "They're taken out of the trip and stop seeing it.", "Remove",
            { vm.mapRemoveMember(m.userId) }) { removing = null }
    }
    if (confirm) {
        if (trip.isOwner) {
            ConfirmDialog("End this trip?", "Everyone stops sharing and the trip closes for all members.", "End trip", vm::mapEnd) { confirm = false }
        } else {
            ConfirmDialog("Leave this trip?", "You stop sharing with this trip. You can join again with the code.", "Leave", vm::mapLeave) { confirm = false }
        }
    }
}

/** The join code in large type, with Copy (code only), Copy invite (link + code) and Share. */
@Composable
private fun InviteCard(vm: SampleViewModel, joinCode: String?) {
    val context = LocalContext.current
    val copy = rememberCopier()
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(1_500); copied = null } }

    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (joinCode != null) "Join code" else "Invite people", style = MaterialTheme.typography.labelMedium)
                    if (joinCode != null) {
                        Text(
                            joinCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, letterSpacing = 4.sp,
                        )
                    }
                }
                if (joinCode != null) {
                    TextButton(onClick = { copy(joinCode); copied = "Code copied" }) { Text("Copy") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.mapShareInvite { copy(it); copied = "Invite copied" } }, modifier = Modifier.weight(1f)) {
                    Text("Copy invite")
                }
                Button(onClick = { vm.mapShareInvite { context.shareText(it) } }, modifier = Modifier.weight(1f)) { Text("Share") }
            }
            copied?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun EndedPanel(vm: SampleViewModel, d: TripDetail) {
    val trip = d.trip
    Text("Trip ended", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("${trip.name} · ${trip.endedReason ?: "ended"} · lasted ${formatSeconds(trip.actualSeconds)}")
    Button(onClick = vm::mapDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}
