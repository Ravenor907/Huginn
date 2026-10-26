package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Palette
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DAY = 86_400_000L

@Composable
fun SettingsScreen(app: Muninn, onSecure: () -> Unit) {
    var showContacts by remember { mutableStateOf(false) }
    if (showContacts) {
        androidx.activity.compose.BackHandler { showContacts = false }
        ContactsScreen(app) { showContacts = false }
        return
    }
    val id = app.identity
    var receipts by remember { mutableStateOf(id.readReceipts) }
    var name by remember { mutableStateOf(id.username) }
    var device by remember { mutableStateOf(id.deviceName) }
    var nearby by remember { mutableStateOf(id.nearbyChat) }
    var keep by remember { mutableStateOf(id.keepNearbyHistory) }
    var muted by remember { mutableStateOf(id.muted()) }
    val up by app.updater.state.collectAsState()
    var deleteAfter by remember { mutableStateOf(id.deleteAfter) }
    var preview by remember { mutableStateOf(id.showPreview) }
    var secure by remember { mutableStateOf(id.secureScreen) }
    var hasPw by remember { mutableStateOf(app.vault.hasPassword) }
    var lockAfter by remember { mutableStateOf(id.lockAfter) }
    var editingPw by remember { mutableStateOf(false) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        MButton("Contacts", Modifier.fillMaxWidth().padding(top = 12.dp), Kind.Accent, icon = "user") { showContacts = true }
        Section("Notifications")
        Panel {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var stay by remember { mutableStateOf(id.stayConnected) }
            var free by remember { mutableStateOf(io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx)) }
            androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); free = io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx) } }
            ToggleRow("Get messages right away", "Stays connected while Muninn is closed (a small silent “Listening” notification). Off: checks about every 15 minutes", stay) {
                stay = it; id.stayConnected = it
                if (it) io.github.ravenor907.muninn.net.MessageService.start(ctx) else io.github.ravenor907.muninn.net.MessageService.stop(ctx)
            }
            if (stay && !free) {
                Text("Android's battery saver can still pause Muninn while the phone sleeps, so messages arrive late.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
                MButton("Let Muninn run in the background", Modifier.fillMaxWidth().padding(top = 6.dp), Kind.Accent) { io.github.ravenor907.muninn.net.MessageService.askUnrestricted(ctx) }
            } else if (stay) Text("Muninn can run in the background. Messages arrive as they're sent.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            MButton("Sound and vibration settings", Modifier.fillMaxWidth().padding(top = 6.dp)) {
                try {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Muninn.MESSAGES))
                } catch (_: Exception) {}
            }
        }
        Section("Privacy")
        Panel {
            Text("Everything Muninn keeps on this phone (messages, contacts, keys) is encrypted. Messages are end-to-end encrypted.", style = Type.small)
            Text("Delete messages after a", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0L to "Never", DAY to "Day", 7 * DAY to "Week", 30 * DAY to "Month").forEach { (ms, label) ->
                    MButton(label, Modifier.weight(1f), if (deleteAfter == ms) Kind.Accent else Kind.Normal) {
                        deleteAfter = ms; id.deleteAfter = ms; app.chat.prune()
                    }
                }
            }
            Text("Applies to both sides of every chat on this phone.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            ToggleRow("Show message text in notifications", "Off: notifications only say “New message”", preview) { preview = it; id.showPreview = it }
            ToggleRow("Send read receipts", "Off: people don't see when you've read their messages. Delivered still shows", receipts) { receipts = it; id.readReceipts = it }
            ToggleRow("Block screenshots", "Also hides Muninn's screen in recent apps", secure) { secure = it; id.secureScreen = it; onSecure() }
        }
        Section("Password")
        Panel {
            Text(if (hasPw) "Muninn asks for your password to open. Without it, nothing it keeps can be read, even by Muninn."
                else "Optional. With a password, Muninn's messages, contacts and keys can't be read without it. There's no way to recover it.", style = Type.normal)
            if (editingPw) {
                Field(pw1, { pw1 = it }, "New password", Modifier.padding(top = 10.dp), secret = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                Field(pw2, { pw2 = it }, "Type it again", Modifier.padding(top = 8.dp), secret = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (pw2.isNotEmpty() && pw1 != pw2) Text("They don't match yet.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("Cancel", Modifier.weight(1f)) { editingPw = false; pw1 = ""; pw2 = "" }
                    MButton("Save password", Modifier.weight(1f), Kind.Accent, enabled = pw1.length >= 4 && pw1 == pw2) {
                        val p = pw1
                        scope.launch {
                            withContext(Dispatchers.Default) { app.vault.setPassword(p) }
                            hasPw = app.vault.hasPassword; editingPw = false; pw1 = ""; pw2 = ""
                            Ui.say("Password set. Don't lose it: it can't be recovered.")
                        }
                    }
                }
            } else {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton(if (hasPw) "Change password" else "Set a password", Modifier.weight(1f), Kind.Accent) { editingPw = true }
                    if (hasPw) MButton("Remove", Modifier.weight(1f)) {
                        Ui.sure(Ask("Remove the password?", "Muninn will open without one. Your data stays encrypted with this phone's key store.", "Remove") {
                            app.vault.setPassword(null); hasPw = app.vault.hasPassword
                        })
                    }
                }
            }
            if (hasPw) {
                Text("Lock again after Muninn is in the background for", style = Type.small, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0L to "Right away", 60_000L to "1 min", 5 * 60_000L to "5 min", 30 * 60_000L to "30 min").forEach { (ms, label) ->
                        MButton(label, Modifier.weight(1f), if (lockAfter == ms) Kind.Accent else Kind.Normal) { lockAfter = ms; id.lockAfter = ms }
                    }
                }
                Text("While locked, messages from the internet still arrive as “New message” and open once you unlock.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Section("Erase")
        Panel {
            Text("Delete every message on this phone, or erase everything Muninn keeps (messages, contacts, keys and settings).", style = Type.normal)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Delete all messages", Modifier.weight(1f)) {
                    Ui.sure(Ask("Delete all messages?", "Every conversation on this phone is deleted. Contacts stay.", "Hold to delete", danger = true, hold = true) {
                        app.chat.deleteAll(); Ui.say("All messages deleted.")
                    })
                }
                MButton("Erase everything", Modifier.weight(1f), Kind.Danger) {
                    Ui.sure(Ask("Erase everything?", "Messages, contacts, keys and settings are deleted for good, and Muninn closes. Files you received stay.",
                        "Hold to erase", danger = true, hold = true) { app.vault.eraseAll(); android.os.Process.killProcess(android.os.Process.myPid()) })
                }
            }
        }
        Section("Nearby chat")
        Panel {
            ToggleRow("Nearby chat", "Everyone close by or on the same Wi-Fi with Huginn or Muninn can see and message you", nearby) {
                nearby = it; id.nearbyChat = it; app.net.announce()
            }
            Text("Your name in Nearby chat", style = Type.small, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 4.dp)) {
                Field(name, { name = it.take(24) }, "A name, not your real one", Modifier.weight(1f))
                MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = name.isNotBlank() && name != id.username) { id.username = name; app.net.announce(); Ui.say("You're “${id.username}” now.") }
            }
            ToggleRow("Keep Nearby messages", "Off: they clear when Muninn closes. Encrypted when kept", keep) { keep = it; id.keepNearbyHistory = it; if (!it) app.chat.clearNearby() }

        }
        Section("This phone")
        Panel {
            Text("Name other devices see", style = Type.small)
            Row(Modifier.padding(top = 4.dp)) {
                Field(device, { device = it.take(40) }, "Muninn on my phone", Modifier.weight(1f))
                MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = device.isNotBlank() && device != id.deviceName) { id.deviceName = device; app.net.announce(); Ui.say("Saved.") }
            }
            Text("Received files go to ${id.receiveFolder.absolutePath.removePrefix("/storage/emulated/0/")}", style = Type.small, modifier = Modifier.padding(top = 8.dp))
        }
        Section("Updates")
        Panel {
            Text("Muninn ${app.updater.current}. ${up.status}", style = Type.normal)
            Text("Muninn checks for a new version every time you open it.", style = Type.small)
            if (up.busy && up.progress > 0f) Bar(up.progress, Modifier.padding(top = 6.dp))
            MButton(if (up.ready) "Install ${up.latest}" else "Check now", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, enabled = !up.busy) {
                if (up.ready) app.updater.download() else app.updater.check()
            }
        }
        Section("About")
        Text("Muninn ${app.updater.current}: Huginn's phone companion. Send and receive with LocalSend, chat, files and USB drives. Made by Ravenor907. GPL-3.0; drive tools from Handheld Forge.", style = Type.small, modifier = Modifier.padding(bottom = 24.dp))
    }
}
