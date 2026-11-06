@file:OptIn(ExperimentalMaterial3Api::class)

package io.baato.trackme.sample

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: SampleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { SampleApp(vm) } }
    }
}

/**
 * Two faces of the same SDK:
 * - **Live map** (always): the ready-to-use showcase, see LiveMapScreen.kt.
 * - **Developer** (only when BuildConfig.DEV_TOOLS, i.e. `trackme.devTools`, default on for debug builds):
 *   a control for every SDK call.
 *
 * Developer screen map (every public SDK call has a control somewhere):
 * - Login → TrackMe.initialize (+ TrackMeSharingUi options)
 * - Trips tab → trips.create / preview / join / list ; tap a trip → TripScreen
 * - TripScreen → trips.detail / rename / end / leave / removeMember / delete / viewLink / rotateViewLink / history,
 *   realtime.watchTrip / stopWatchingTrip, sharing.start / stop
 * - Share tab → location.permissionStatus, sharing.start(options) / pause / resume / stop / state
 * - Viewer tab → realtime.watchShare / stopWatchingShare
 * - Inbox tab → notifications.list (paging, unread filter) / markRead / markAllRead
 * - SDK tab → state, checkHealth, updateToken, token-provider refresh, realtime.stop, initialize again, dispose, events log
 */
@Composable
private fun SampleApp(vm: SampleViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    // Re-check permission whenever the user comes back (e.g. from Settings).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (ui.loggedIn) vm.refreshPermission() }

    ui.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text("OK") } },
            title = { Text("TrackMe") },
            text = { Text(msg) },
        )
    }

    val detail = ui.detail
    when {
        !ui.loggedIn -> LoginScreen(vm, ui.busy)
        detail != null -> TripScreen(vm, ui, detail)
        // Showcase only. System bars + cutout, NOT safeDrawing: that one includes the keyboard, which the map's
        // panel handles itself (counting it twice threw the focused field to the top of the screen).
        !BuildConfig.DEV_TOOLS -> LiveMapScreen(
            vm, ui, WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues(), sheetOverBottomInset = true,
        )
        else -> DevShell(vm, ui)
    }
}

/** Dev-tools build: bottom bar switches between the showcase and the developer screens. */
@Composable
private fun DevShell(vm: SampleViewModel, ui: SampleUiState) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = ui.section == Section.Map, onClick = { vm.selectSection(Section.Map) },
                    icon = { Text("🗺") }, label = { Text("Live map") },
                )
                NavigationBarItem(
                    selected = ui.section == Section.Developer, onClick = { vm.selectSection(Section.Developer) },
                    icon = { Text("🛠") }, label = { Text("Developer") },
                )
            }
        },
    ) { padding ->
        when (ui.section) {
            Section.Map -> LiveMapScreen(vm, ui, padding)
            Section.Developer -> DeveloperScreen(vm, ui, Modifier.padding(bottom = padding.calculateBottomPadding()))
        }
    }
}

@Composable
private fun DeveloperScreen(vm: SampleViewModel, ui: SampleUiState, modifier: Modifier) {
    Column(modifier.fillMaxSize().imePadding()) {   // edge-to-edge: keep focused fields above the keyboard
        TopAppBar(
            title = { Text("Developer · ${ui.login?.fullName}", maxLines = 1) },
            actions = { TextButton(onClick = vm::logout) { Text("Log out") } },
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tab.entries.forEach { tab ->
                FilterChip(selected = ui.tab == tab, onClick = { vm.selectTab(tab) }, label = { Text("${tab.glyph} ${tab.label}") })
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (ui.tab) {
                Tab.Trips -> TripsTab(vm, ui)
                Tab.Sharing -> SharingTab(vm, ui)
                Tab.Viewer -> ViewerTab(vm, ui)
                Tab.Inbox -> InboxTab(vm, ui)
                Tab.Sdk -> SdkTab(vm, ui)
            }
        }
    }
}

// ======================= login =======================

private val accents = listOf("Blue" to 0xFF1565C0, "Green" to 0xFF2E7D32, "Orange" to 0xFFEF6C00, "Default" to 0L)

@Composable
private fun LoginScreen(vm: SampleViewModel, busy: Boolean) {
    var email by rememberSaveable { mutableStateOf(BuildConfig.DEMO_EMAIL) }
    var name by rememberSaveable { mutableStateOf(BuildConfig.DEMO_NAME) }
    var title by rememberSaveable { mutableStateOf("Sharing your location") }
    var text by rememberSaveable { mutableStateOf("Trip members can see where you are.") }
    var accent by rememberSaveable { mutableStateOf(0xFF1565C0) }
    var showPause by rememberSaveable { mutableStateOf(true) }
    var showStop by rememberSaveable { mutableStateOf(true) }
    // Release builds carry no secret: the tester types the key once (stored encrypted, see TesterKey).
    var testerKey by remember { mutableStateOf("") }
    var hasTesterKey by remember { mutableStateOf(DemoAuth.hasTesterKey) }

    Scaffold(topBar = { TopAppBar(title = { Text("TrackMe Sample") }) }) { padding ->
        Column(
            Modifier.padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Demo login", style = MaterialTheme.typography.titleLarge)
            Caption(
                "The sample logs in with the TrackMe API secret: from local.properties in debug builds, or the " +
                    "tester key you type in release builds. A real app gets the token " +
                    "from its own backend and only calls TrackMe.initialize(context, config). " +
                    "Log in with a different email on a second device to test trips together.",
            )
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!DemoAuth.isEmbedded) {
                if (hasTesterKey) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Caption("Tester key saved on this phone (encrypted).")
                        androidx.compose.material3.TextButton(onClick = { DemoAuth.forgetTesterKey(); hasTesterKey = false }) { Text("Forget") }
                    }
                } else {
                    OutlinedTextField(
                        testerKey, { testerKey = it }, label = { Text("Tester key") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text("Given to you by the TrackMe team. Saved encrypted on this phone, not in the app.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (BuildConfig.DEV_TOOLS) SectionCard("Sharing notification (TrackMeSharingUi)") {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                Text("Accent colour")
                ChipRow(accents, accent) { accent = it }
                LabeledSwitch("Pause button", showPause) { showPause = it }
                LabeledSwitch("Stop button", showStop) { showStop = it }
            }

            Button(
                onClick = {
                    if (testerKey.isNotBlank()) { DemoAuth.saveTesterKey(testerKey); testerKey = ""; hasTesterKey = true }
                    vm.login(LoginForm(email.trim(), name.trim(), title, text, accent, showPause, showStop))
                },
                enabled = email.contains("@") && !busy && (DemoAuth.isEmbedded || hasTesterKey || testerKey.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log in & initialize SDK") }
        }
    }
}

// ======================= shared building blocks =======================

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun Caption(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** One-of-N choice as a row of chips. */
@Composable
fun <T> ChipRow(options: List<Pair<String, T>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, value) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) }, enabled = enabled)
        }
    }
}

/**
 * Trip length: preset chips plus "Custom", any whole number of minutes. The keypad only offers digits, so nothing
 * negative or fractional can be typed. The limits are the server's (the API spec says 5–1440 today): out-of-range
 * values come back as a Validation error with the server's message, so this UI doesn't hard-code them.
 * [minutes] is -1 while the field is empty.
 */
@Composable
fun DurationPicker(presets: List<Pair<String, Int>>, minutes: Int, onChange: (Int) -> Unit) {
    var custom by rememberSaveable { mutableStateOf(presets.none { it.second == minutes }) }
    var text by rememberSaveable { mutableStateOf(if (custom && minutes >= 0) minutes.toString() else "") }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { (label, value) ->
            FilterChip(selected = !custom && value == minutes, onClick = { custom = false; onChange(value) }, label = { Text(label) })
        }
        FilterChip(
            selected = custom,
            onClick = {
                custom = true
                if (text.isEmpty()) text = minutes.coerceAtLeast(0).toString()
                onChange(text.toIntOrNull() ?: -1)
            },
            label = { Text("Custom") },
        )
    }
    if (custom) {
        OutlinedTextField(
            text,
            { typed -> text = typed.filter(Char::isDigit).take(5); onChange(text.toIntOrNull() ?: -1) },
            label = { Text("Minutes") },
            singleLine = true,
            supportingText = { if (minutes >= 0) Text("= ${formatMinutes(minutes)}") },
            // NumberPassword = a digits-only keypad (Number also shows "-", "." and ","); the text isn't hidden.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 90 → "1 h 30 min", 45 → "45 min", 1440 → "24 h". */
fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/** "Are you sure?" for anything destructive. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Returns a function that copies text to the clipboard. */
@Composable
fun rememberCopier(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("TrackMe", text))) } }
}

fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

fun Context.shareText(text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

/** 3725 → "1 h 2 min", 125 → "2 min 5 s". */
fun formatSeconds(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
        s >= 60 -> "${s / 60} min ${s % 60} s"
        else -> "$s s"
    }
}
