package io.github.ravenor907.muninn.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import io.github.ravenor907.muninn.ui.MIcon
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
    val id = app.identity
    var receipts by remember { mutableStateOf(id.readReceipts) }
    var activeOn by remember { mutableStateOf(id.activeStatus) }
    var name by remember { mutableStateOf(id.username) }
    var device by remember { mutableStateOf(id.deviceName) }
    var nearby by remember { mutableStateOf(id.nearbyChat) }
    var visible by remember { mutableStateOf(id.visibleNearby) }
    var keep by remember { mutableStateOf(id.keepNearbyHistory) }
    var muted by remember { mutableStateOf(id.muted()) }
    val up by app.updater.state.collectAsState()
    var deleteAfter by remember { mutableStateOf(id.deleteAfter) }
    var preview by remember { mutableStateOf(id.showPreview) }
    var secure by remember { mutableStateOf(id.secureScreen) }
    var hasPw by remember { mutableStateOf(app.vault.hasPassword) }
    var lockAfter by remember { mutableStateOf(id.lockAfter) }
    var editingPw by remember { mutableStateOf(false) }
    var usePattern by remember { mutableStateOf(app.vault.usesPattern) }
    var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
    var patternNote by remember { mutableStateOf("") }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Settings is a short list of folders (the same as Huginn's); each opens on its own, and Back comes out.
    var folder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    androidx.activity.compose.BackHandler(enabled = folder.isNotEmpty()) { folder = "" }
    androidx.compose.runtime.key(folder) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        // No folder open: the list. A folder: its title, then only its own settings below.
        if (folder.isEmpty()) FolderList { folder = it }
        FOLDERS.firstOrNull { it.id == folder }?.let { f -> FolderHeader(f.title) { folder = "" } }
        if (folder == "notify") {
        Panel {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var stay by remember { mutableStateOf(id.stayConnected) }
            var free by remember { mutableStateOf(io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx)) }
            androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); free = io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx) } }
            ToggleRow("Get messages right away", "Stays connected while Muninn is closed (a small silent “Listening” notification you can hide). Off: checks about every 15 minutes", stay) {
                stay = it; id.stayConnected = it
                if (it) io.github.ravenor907.muninn.net.MessageService.start(ctx) else io.github.ravenor907.muninn.net.MessageService.stop(ctx)
            }
            if (stay && !free) {
                Text("Android's battery saver can still pause Muninn while the phone sleeps, so messages arrive late.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
                MButton("Let Muninn run in the background", Modifier.fillMaxWidth().padding(top = 6.dp), Kind.Accent) { io.github.ravenor907.muninn.net.MessageService.askUnrestricted(ctx) }
            } else if (stay) Text("Muninn can run in the background. Messages arrive as they're sent.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            if (stay && Build.VERSION.SDK_INT >= 26) {
                MButton("Hide the Listening notification", Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    try {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, io.github.ravenor907.muninn.net.MessageService.CHANNEL))
                    } catch (_: Exception) {}
                }
                Text("Turn it off on the next screen. Muninn keeps listening and messages still notify you.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            }
            var saver by remember { mutableStateOf(app.power.saver) }
            val away by app.power.away.collectAsState()
            ToggleRow("Battery saver",
                if (saver) "Saves battery while the screen is off. Messages and calls still arrive, sometimes a few seconds later."
                else "Maximum speed: always fully connected. Uses more battery.", saver) {
                saver = it; app.power.saver = it
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { app.power.refresh() }
            Text(away.label, style = Type.small, modifier = Modifier.padding(top = 2.dp))
            MButton("Sound and vibration settings", Modifier.fillMaxWidth().padding(top = 6.dp)) {
                try {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Muninn.MESSAGES))
                } catch (_: Exception) {}
            }
        }
        }
        if (folder == "privacy") {
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
            ToggleRow("Active status", "A green dot shows contacts when Muninn is open. Off: nobody sees yours, and you don't see theirs", activeOn) { activeOn = it; app.chat.setActiveStatus(it) }
            ToggleRow("Block screenshots", "Also hides Muninn's screen in recent apps", secure) { secure = it; id.secureScreen = it; onSecure() }
        }
        }
        if (folder == "calls") {
        Panel {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var noise by remember { mutableStateOf(io.github.ravenor907.muninn.net.call.CallManager.noiseRemoval(ctx)) }
            ToggleRow("Noise removal", if (noise) "Background noise is taken out of your voice. Turn it off if people say you sound quiet"
                else "Off: Muninn asks your phone not to filter noise out of your voice. Background sound comes through too", noise) {
                noise = it; io.github.ravenor907.muninn.net.call.CallManager.setNoiseRemoval(ctx, it)
            }
            Text("Changes start with your next call.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        }
        }
        if (folder == "lock") {
        Panel {
            Text("Muninn asks for your ${if (app.vault.usesPattern) "pattern" else "password"} to open. Without it, nothing it keeps can be read, even by Muninn.", style = Type.normal)
            if (editingPw) LockSetup(app, onCancel = { editingPw = false }) { hasPw = app.vault.hasPassword; editingPw = false }
            else MButton("Change lock", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) { editingPw = true }
            if (hasPw) {
                Text("Lock again after Muninn is in the background for", style = Type.small, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0L to "Right away", 60_000L to "1 min", 5 * 60_000L to "5 min", 30 * 60_000L to "30 min").forEach { (ms, label) ->
                        MButton(label, Modifier.weight(1f), if (lockAfter == ms) Kind.Accent else Kind.Normal) { lockAfter = ms; id.lockAfter = ms }
                    }
                }
                Text("While locked, messages from the internet still arrive as “New message” and open once you unlock.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var panic by remember { mutableStateOf(io.github.ravenor907.muninn.ui.Panic.enabled(ctx)) }
                var shake by remember { mutableStateOf(io.github.ravenor907.muninn.ui.Panic.shakeOn(ctx)) }
                ToggleRow("Panic lock", "Lock Muninn in one move from anywhere: the “Lock Muninn” quick settings tile, or “Lock now” when you long-press Muninn's icon. " +
                    "Any call ends, message notifications are cleared, and Muninn forgets its key: nothing can be read until you unlock. New messages still arrive", panic) {
                    panic = it; io.github.ravenor907.muninn.ui.Panic.setEnabled(ctx, it)
                }
                if (panic) {
                    Text("Add the tile: swipe down twice, tap the pencil, and drag “Lock Muninn” into your tiles.", style = Type.small, modifier = Modifier.padding(bottom = 4.dp))
                    if (io.github.ravenor907.muninn.ui.Panic.canShake(ctx))
                        ToggleRow("Shake to lock", "Shake the phone hard 3 times while Muninn is open", shake) {
                            shake = it; io.github.ravenor907.muninn.ui.Panic.setShake(ctx, it)
                        }
                }
            }
        }
        }
        if (folder == "data") {
        Section("Backup")
        BackupPanel(app)
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
        }
        if (folder == "nearby") {
        Section("Nearby chat")
        Panel {
            ToggleRow("Visible to people nearby", if (visible) "People close by or on the same Wi-Fi can see your name and message you"
                else "Hidden: only your contacts can reach you close by or on the same Wi-Fi. Your name isn't sent out", visible) {
                visible = it; id.visibleNearby = it; app.net.announce(); app.close.readvertise()
            }
            if (visible) {
                ToggleRow("Nearby chat", "Everyone close by or on the same Wi-Fi with Huginn or Muninn can see and message you", nearby) {
                    nearby = it; id.nearbyChat = it; app.net.announce()
                }
                Text("Your name in Nearby chat", style = Type.small, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 4.dp)) {
                    Field(name, { name = it.take(24) }, "A name, not your real one", Modifier.weight(1f))
                    MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = name.isNotBlank() && name != id.username) {
                        id.username = name; app.net.announce(); app.close.readvertise(); Ui.say("You're “${id.username}” now.")
                    }
                }
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
            Text("Received files go to ${io.github.ravenor907.muninn.net.Received.place(androidx.compose.ui.platform.LocalContext.current)}.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
        }
        }
        if (folder == "display") {
        Panel {
            val A = io.github.ravenor907.muninn.ui.Access
            Text("Size of everything", style = Type.normal.copy(color = Palette.text))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.85f to "Smaller", 1f to "Normal", 1.15f to "Larger", 1.3f to "Largest").forEach { (v, label) ->
                    MButton(label, Modifier.weight(1f), if (A.size == v) Kind.Accent else Kind.Normal) { A.size = v; id.uiSize = v }
                }
            }
            Text("Text size", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1f to "Normal", 1.15f to "Large", 1.3f to "Larger", 1.5f to "Largest").forEach { (v, label) ->
                    MButton(label, Modifier.weight(1f), if (A.text == v) Kind.Accent else Kind.Normal) { A.text = v; id.textSize = v }
                }
            }
            Text("Text size adds to your phone's own font size setting.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            ToggleRow("High contrast", "White text on black, stronger outlines", A.contrast) { A.contrast = it; id.highContrast = it }
            ToggleRow("Color-blind friendly", "Warnings in amber instead of red; status always says it in words", A.colorBlind) { A.colorBlind = it; id.colorBlind = it }
            ToggleRow("Reduce motion", "No sliding or fading", A.reduceMotion) { A.reduceMotion = it; id.reduceMotion = it }
        }
        }
        if (folder == "about") {
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
        Text("Muninn ${app.updater.current}: private chat and file sharing, Huginn's phone companion. Sends and receives with LocalSend. Made by Ravenor907. GPL-3.0.", style = Type.small, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
    }
}

/** One folder of Settings: what it's called, what's in it, and its icon. The same folders as Huginn's. */
private class Folder(val id: String, val title: String, val detail: String, val icon: String)

private val FOLDERS = listOf(
    Folder("notify", "Notifications", "Messages right away, battery saver, sounds", "chat"),
    Folder("privacy", "Privacy", "Delete messages after, read receipts, active status, screenshots", "shield"),
    Folder("lock", "Lock", "Password or pattern, panic lock", "lock"),
    Folder("calls", "Calls", "Noise removal", "call"),
    Folder("nearby", "Nearby and sharing", "Visible to people nearby, Nearby chat, your names, received files", "wifi"),
    Folder("display", "Display and accessibility", "Size, text size, contrast, color-blind, reduce motion", "image"),
    Folder("data", "Backup and erase", "Back up, restore, delete all messages, erase everything", "download"),
    Folder("about", "About and updates", "Muninn's version, check for updates", "info"),
)

/** The first thing Settings shows: the folders, one row each. */
@Composable
private fun FolderList(onOpen: (String) -> Unit) {
    // The top bar already says Settings.
    Spacer(Modifier.height(14.dp))
    Panel {
        FOLDERS.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpen(f.id) }.padding(vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                MIcon(f.icon, Palette.accent, 24.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(f.title, style = Type.normal.copy(color = Palette.text))
                    Text(f.detail, style = Type.small)
                }
                MIcon("forward", Palette.textMuted, 20.dp)
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

/** A folder's title, with a way back to the list (Back does the same). */
@Composable
private fun FolderHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Back to Settings") { onBack() }.padding(10.dp)) {
            MIcon("back", Palette.text, 24.dp)
        }
        Text(title, style = Type.big, modifier = Modifier.padding(start = 4.dp))
    }
}
