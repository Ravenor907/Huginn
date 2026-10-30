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
import io.github.ravenor907.muninn.net.PowerLogic.Check
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
            var check by remember { mutableStateOf(app.power.check) }
            val stay = check == Check.LIVE
            var free by remember { mutableStateOf(io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx)) }
            io.github.ravenor907.muninn.ui.EveryWhileShown(1500) { free = io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx) }
            // How Muninn checks while it's closed: the biggest choice for the battery.
            Text("Check for messages", style = Type.normal.copy(color = Palette.text))
            Check.entries.chunked(2).forEach { row ->
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c -> MButton(c.title, Modifier.weight(1f), if (check == c) Kind.Accent else Kind.Normal) { check = c; app.power.check = c } }
                }
            }
            Text(when (check) {
                Check.LIVE -> "Messages and calls arrive within seconds. In the background Muninn keeps one light connection, checked about every 5 minutes. No Google servers (a small silent “Listening” notification you can hide)."
                Check.QUARTER -> "Saves battery. Messages arrive within about 15 minutes. Calls don't ring and show as missed. Fully connected while charging."
                Check.HOUR -> "Saves more battery. Messages arrive within about an hour. Calls don't ring and show as missed. Fully connected while charging."
                Check.OPEN -> "No notifications, messages or calls until you open Muninn, even while charging. Messages wait for you."
            }, style = Type.small, modifier = Modifier.padding(top = 6.dp))
            if (stay && !free) {
                Text("Android's battery saver can still pause Muninn while the phone sleeps, so messages arrive late. Allowing it costs little: Muninn wakes the phone only for its short check about every 5 minutes.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
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
            // Only "Right away" stays connected, so only it has a speed to trade.
            if (stay) ToggleRow("Battery saver",
                if (saver) "Saves battery while the screen is off: one light connection, and every relay for a few minutes after a message. Messages and calls still arrive within seconds."
                else "Maximum speed: always fully connected. Uses more battery.", saver) {
                saver = it; app.power.saver = it
            }
            androidx.compose.runtime.LaunchedEffect(check) { app.power.refresh() }
            Text(away.label, style = Type.small, modifier = Modifier.padding(top = 2.dp))
            RightAwayRow(app)
            var low by remember { mutableStateOf(app.power.lowBatterySave) }
            ToggleRow("Save more when the battery is low", "Below 30%: phones close by aren't looked for in the background. Below 15%: the connection " +
                "is checked every 10 minutes instead of 5, but never dropped. Your Always right away list is never held back", low) { low = it; app.power.lowBatterySave = it }
            var adaptive by remember { mutableStateOf(app.power.adaptive) }
            ToggleRow("Check less when it's quiet", "After 3 hours with nothing new and Muninn closed, it checks less often until a message comes " +
                "or you open it (Right away: every 10 minutes, unless someone is on your Always right away list). Calls still ring with Right away", adaptive) { adaptive = it; app.power.adaptive = it }
            var data by remember { mutableStateOf(app.power.dataSaver) }
            ToggleRow("Data saver", "On mobile data, big files and videos wait until you tap them, and updates wait for Wi-Fi", data) {
                data = it; app.power.dataSaver = it
            }
            QuietHours(app)
            MButton("Sound and vibration settings", Modifier.fillMaxWidth().padding(top = 6.dp)) {
                try {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Muninn.MESSAGES))
                } catch (_: Exception) {}
            }
        }
        Section("Battery report")
        BatteryReport(app)
        }
        if (folder == "privacy") {
        Panel {
            Text("Everything Muninn keeps on this phone (messages, contacts, keys) is encrypted. Messages are end-to-end encrypted.", style = Type.small)
            Text("Delete messages after", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Every chat already deletes messages after its limit (7 days, up to 30 for Family and Work); Day is sooner.
                val day = deleteAfter in 1 until 7 * DAY
                listOf(0L to "The chat's limit", DAY to "A day").forEach { (ms, label) ->
                    MButton(label, Modifier.weight(1f), if (day == (ms > 0)) Kind.Accent else Kind.Normal) {
                        deleteAfter = ms; id.deleteAfter = ms; app.chat.pruneSoon()
                    }
                }
            }
            Text("Chats keep messages for 7 days, or up to 30 in Family and Work groups and with people in your Family or Work folder. " +
                "Applies to both sides of every chat on this phone.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            ToggleRow("Show message text in notifications", "Off: notifications only say “New message”", preview) { preview = it; id.showPreview = it }
            ToggleRow("Send read receipts", "Off: people don't see when you've read their messages. Delivered still shows", receipts) { receipts = it; id.readReceipts = it }
            ToggleRow("Active status", "A green dot shows contacts when Muninn is open. Off: nobody sees yours, and you don't see theirs", activeOn) { activeOn = it; app.chat.setActiveStatus(it) }
            ToggleRow("Block screenshots", "Also hides Muninn's screen in recent apps", secure) { secure = it; id.secureScreen = it; onSecure() }
            var previews by remember { mutableStateOf(id.linkPreviews) }
            ToggleRow("Link previews", "Shows a link's title and picture. This phone fetches the page, so that site sees your address. Off: links stay plain", previews) { previews = it; id.linkPreviews = it }
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
            var closeBy by remember { mutableStateOf(app.power.closeBy) }
            ToggleRow("Find phones close by in the background", if (closeBy)
                "Bluetooth and Nearby keep looking while Muninn is closed, for chatting without the internet. Uses more battery"
                else "Off: they look only while Muninn is open. Saves battery", closeBy) { closeBy = it; app.power.closeBy = it }
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
            ToggleRow("True black", "A pure black background. On phones with OLED screens it saves a little battery", A.trueBlack) { A.trueBlack = it; id.trueBlack = it }
        }
        }
        if (folder == "link") LinkedDevices(app)
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

/**
 * The "Always right away" list in one row ("Always right away: Family + 2 people"): whose messages a low battery,
 * quiet hours and checking less never hold back. It starts as the Family folder. Tap to see it, add or take off
 * anyone (a chat's menu does the same).
 */
@Composable
private fun RightAwayRow(app: Muninn) {
    val ready by app.ready.collectAsState()
    val ra = app.power.rightAway
    if (!ready || ra == null) return
    val st by ra.all.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
    val contacts by app.chat.contacts.all.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    val keys = remember(st, folders) { app.power.rightAwayKeys() }
    val family = remember(folders) { folders.firstOrNull { it.name.equals(io.github.ravenor907.muninn.net.Power.FAMILY, true) }?.keys.orEmpty().toSet() }
    fun name(k: String): String = if (k.startsWith("g:")) groups[k.removePrefix("g:")]?.state?.name ?: "A group" else app.chat.nameOf(k)
    val extra = keys.count { it !in family }
    val summary = when {
        keys.isEmpty() -> "Nobody yet"
        keys.none { it in family } -> if (extra == 1) "1 person" else "$extra people"
        extra == 0 -> "Family"
        else -> "Family + $extra " + if (extra == 1) "person" else "people"
    }
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(top = 10.dp, bottom = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Always right away: $summary", style = Type.normal.copy(color = Palette.text))
            Text("Their messages arrive and ring at once, even with a low battery, in quiet hours or after a quiet spell", style = Type.small)
        }
        Text(if (open) "Hide" else "Change", style = Type.small.copy(color = Palette.accent), modifier = Modifier.padding(start = 8.dp))
    }
    if (!open) return
    if (keys.isEmpty()) Text("Put people in the Family folder, or add them here.", style = Type.small.copy(color = Palette.textMuted))
    keys.sortedBy { name(it).lowercase() }.forEach { k ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(name(k) + if (k in family) " (Family)" else "", style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
            io.github.ravenor907.muninn.ui.IconAction("close", "Take ${name(k)} off", Palette.textMuted, size = 18.dp) { app.power.setRightAway(k, false) }
        }
    }
    MButton(if (adding) "Done adding" else "Add someone", Modifier.fillMaxWidth().padding(top = 6.dp)) { adding = !adding }
    if (adding) {
        val others = (contacts.filter { !it.groupOnly }.map { it.chatKey } + groups.filterValues { !it.readOnly }.keys.map { "g:$it" })
            .filter { it !in keys }.distinct().sortedBy { name(it).lowercase() }
        if (others.isEmpty()) Text("Everyone is on the list.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        others.forEach { k ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(name(k), style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
                MButton("Add", kind = Kind.Accent) { app.power.setRightAway(k, true) }
            }
        }
    }
}

/** Today's battery report: what Muninn kept running, what the connection cost, how often it checked, and the data it used. */
@Composable
private fun BatteryReport(app: Muninn) {
    var r by remember { mutableStateOf(app.power.report()) }
    io.github.ravenor907.muninn.ui.EveryWhileShown(30_000) { r = app.power.report() }
    fun time(ms: Long): String { val m = ms / 60_000; return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }
    Panel {
        Text("Today so far", style = Type.normal.copy(color = Palette.text))
        Text("Open on screen or charging: ${time(r.openMs)}", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        Text("Fully connected in the background (conversations, calls): ${time(r.connectedMs)}", style = Type.small)
        Text("One light connection: ${time(r.savingMs)}", style = Type.small)
        Text("Resting (nothing running): ${time(r.restingMs)}", style = Type.small)
        Text("Connection checks: ${r.pings} (${r.wakeups} woke the phone)", style = Type.small)
        Text("Reconnects: ${r.reconnects}", style = Type.small)
        Text("Checks the message servers made on their own: ${r.serverPings} (each wakes the phone's radio for a moment)", style = Type.small)
        Text("Background checks for messages: ${r.checks}", style = Type.small)
        Text("Staying connected cost about ${"%.1f".format(java.util.Locale.US, r.costPercent)}% of the battery (a rough guess)", style = Type.small)
        r.dataBytes?.let { Text("Data used since the phone started: ${io.github.ravenor907.muninn.net.LocalSend.size(it)}", style = Type.small) }
        Text("Fully connected costs the most. Android's own Settings › Battery shows Muninn's real share of the battery.",
            style = Type.small, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Quiet hours: from and to on the hour, and how Muninn checks while they last (it saves the most then). */
@Composable
private fun QuietHours(app: Muninn) {
    var on by remember { mutableStateOf(app.power.quietOn) }
    var from by remember { mutableStateOf(app.power.quietFrom) }
    var to by remember { mutableStateOf(app.power.quietTo) }
    var mode by remember { mutableStateOf(app.power.quietMode) }
    fun hour(h: Int) = when (h) { 0 -> "12 AM"; 12 -> "12 PM"; in 1..11 -> "$h AM"; else -> "${h - 12} PM" }
    var dnd by remember { mutableStateOf(app.power.followDnd) }
    ToggleRow("Quiet hours", if (on) "From ${hour(from)} to ${hour(to)}: ${mode.title.lowercase()}" else "Check less overnight, then go back to your choice", on) {
        on = it; app.power.quietOn = it
    }
    ToggleRow("Also during Do Not Disturb", if (dnd) "While Do Not Disturb or Bedtime mode is on: ${mode.title.lowercase()}"
        else "Do Not Disturb doesn't change how Muninn checks", dnd) { dnd = it; app.power.followDnd = it }
    if (!on) {
        if (dnd) QuietMode(mode) { mode = it; app.power.quietMode = it }
        return
    }
    @Composable fun Stepper(label: String, h: Int, set: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
            MButton("−") { set(Math.floorMod(h - 1, 24)) }
            Text(hour(h), style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(horizontal = 12.dp))
            MButton("+") { set(Math.floorMod(h + 1, 24)) }
        }
    }
    Stepper("From", from) { from = it; app.power.quietFrom = it }
    Stepper("To", to) { to = it; app.power.quietTo = it }
    QuietMode(mode) { mode = it; app.power.quietMode = it }
}

/** How Muninn checks during quiet hours and Do Not Disturb. */
@Composable
private fun QuietMode(mode: Check, set: (Check) -> Unit) {
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Check.HOUR, Check.OPEN).forEach { c ->
            MButton(c.title, Modifier.weight(1f), if (mode == c) Kind.Accent else Kind.Normal) { set(c) }
        }
    }
    Text("While quiet hours or Do Not Disturb last, Muninn checks this way unless your own choice saves more.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
}

/** One folder of Settings: what it's called, what's in it, and its icon. The same folders as Huginn's. */
private class Folder(val id: String, val title: String, val detail: String, val icon: String)

private val FOLDERS = listOf(
    Folder("notify", "Notifications", "Check for messages, quiet hours, battery and data saving, sounds, battery report", "chat"),
    Folder("privacy", "Privacy", "Delete messages after, read receipts, active status, screenshots", "shield"),
    Folder("lock", "Lock", "Password or pattern, panic lock", "lock"),
    Folder("calls", "Calls", "Noise removal", "call"),
    Folder("nearby", "Nearby and sharing", "Visible to people nearby, Nearby chat, your names, received files", "wifi"),
    Folder("display", "Display and accessibility", "Size, text size, contrast, color-blind, reduce motion, true black", "image"),
    Folder("link", "Linked devices", "Your chats on a tablet or second phone, through this phone", "phone"),
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

/**
 * Linked devices, companion style: a tablet or second phone shows this phone's chats and sends through it while this
 * phone is online. Contacts see nothing new. One device shows a code, the other takes it.
 */
@Composable
private fun LinkedDevices(app: Muninn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val companions by io.github.ravenor907.muninn.net.Link.companions.collectAsState()
    val phone by io.github.ravenor907.muninn.net.Link.phone.collectAsState()
    var code by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    Panel {
        Text("A tablet or second phone can show this phone's chats and send through it. It works while this phone is on and online. " +
            "Everything between the two is end-to-end encrypted, and your contacts see nothing new.", style = Type.small)
    }
    phone?.let { p ->
        Section("This device mirrors")
        Panel {
            Text(p.name, style = Type.big)
            Text("Its chats are at the top of your chat list.", style = Type.small)
            MButton("Unlink", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Danger) { io.github.ravenor907.muninn.net.Link.unlink(p.key) }
        }
    }
    if (companions.isNotEmpty()) {
        Section("Linked to this phone")
        Panel {
            companions.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(d.name, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
                    MButton("Unlink", kind = Kind.Danger) { io.github.ravenor907.muninn.net.Link.unlink(d.key) }
                }
            }
        }
    }
    Section("Link a device to this phone")
    Panel {
        Text("On the other device: Settings › Linked devices › Use as a companion. Then copy its code here.", style = Type.small)
        io.github.ravenor907.muninn.ui.Field(pasted, { pasted = it }, "Paste the link code", Modifier.padding(top = 8.dp), single = false)
        MButton("Link", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, enabled = pasted.isNotBlank()) {
            val err = io.github.ravenor907.muninn.net.Link.link(pasted)
            io.github.ravenor907.muninn.ui.Ui.say(err ?: "Linked. Your chats are on their way to it.", err != null)
            if (err == null) pasted = ""
        }
    }
    if (phone == null) {
        Section("Use this device as a companion")
        Panel {
            Text("Show your phone this code (it works for 10 minutes). This device then shows your phone's chats.", style = Type.small)
            val c = code
            if (c == null) MButton("Show the link code", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { code = io.github.ravenor907.muninn.net.Link.myLinkCode() }
            else {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    io.github.ravenor907.muninn.screens.Qr(c, 220.dp)
                }
                MButton("Copy", Modifier.fillMaxWidth(), icon = "copy") {
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Muninn link code", c))
                    io.github.ravenor907.muninn.ui.Ui.say("Code copied. Paste it on your phone.")
                }
                MButton("Share", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "send") {
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, c), "Send the link code to your phone"))
                }
            }
        }
    }
}
