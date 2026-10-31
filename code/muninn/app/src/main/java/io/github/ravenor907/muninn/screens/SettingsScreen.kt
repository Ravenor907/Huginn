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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import io.github.ravenor907.muninn.ui.Access
import kotlin.math.roundToInt
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.PowerLogic.Check
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.ChoiceRow
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

/** Which Settings page is open: "" the short list, else a page's id (see [PAGES]). CI opens pages by setting it. */
object SettingsNav {
    var folder by mutableStateOf("")
    /** A chat page Settings asks for (My code from Your profile): MainActivity opens it on the Chats tab and clears this. */
    var goChat by mutableStateOf("")
}

/** One page of Settings: its title, one plain line about it, and the page Back goes to. */
private class Page(val id: String, val title: String, val detail: String, val parent: String = "")

/** Everything under More settings, in the order shown. Each keeps a single plain line. */
private val MORE = listOf(
    Page("battery", "Battery details", "How Muninn saves power, and what it used today", "more"),
    Page("calls", "Calls", "Noise removal on your calls", "more"),
    Page("nearby", "Close by details", "Bluetooth, Wi-Fi, this phone's name and received files", "more"),
    Page("link", "Linked devices", "Use Muninn on a tablet or second phone too", "more"),
    Page("backup", "Backup", "Save your chats to a file only you can open", "more"),
    Page("tv", "Kids' TV", "Who the TV can call, and noise removal on the TV", "more"),
    Page("access", "Accessibility", "High contrast, color-blind friendly, less motion", "more"),
    Page("about", "Updates and about", "Version, check for updates", "more"),
    Page("trash", "Trash", "Contacts you removed, for 30 days", "more"),
    Page("erase", "Erase everything", "Removes every chat and contact from this phone", "more"),
)

private val PAGES = listOf(
    Page("profile", "Your profile", ""), Page("notify", "Notifications", ""), Page("look", "Look and text size", ""), Page("lock", "Lock and privacy", ""),
    Page("keep", "Keep messages for", ""), Page("more", "More settings", "Everything else"), Page("lockset", "Change lock", "", "lock"),
) + MORE

@Composable
fun SettingsScreen(app: Muninn, onSecure: () -> Unit) {
    val folder = SettingsNav.folder
    val page = PAGES.firstOrNull { it.id == folder }
    androidx.activity.compose.BackHandler(enabled = folder.isNotEmpty()) { SettingsNav.folder = page?.parent ?: "" }
    // Kids' TV settings and the trash are whole screens of their own.
    if (folder == "trash") { TrashScreen(app) { SettingsNav.folder = "more" }; return }
    if (folder.startsWith("tv:")) { io.github.ravenor907.muninn.tv.ParentTvScreen(app, folder.removePrefix("tv:")) { SettingsNav.folder = "tv" }; return }
    if (folder == "lockset") { ChangeLock(app); return }
    androidx.compose.runtime.key(folder) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        if (page != null) FolderHeader(page.title) { SettingsNav.folder = page.parent }
        when (folder) {
            "" -> ShortList(app)
            "profile" -> ProfileScreen(app)
            "notify" -> NotifyPage(app)
            "look" -> LookPage(app)
            "lock" -> LockPage(app, onSecure)
            "keep" -> KeepPage(app)
            "more" -> MorePage()
            "battery" -> BatteryPage(app)
            "calls" -> CallsPage()
            "nearby" -> NearbyPage(app)
            "link" -> LinkedDevices(app)
            "backup" -> BackupPanel(app)
            "tv" -> TvPage(app)
            "access" -> AccessPage(app)
            "about" -> AboutPage(app)
            "erase" -> ErasePage(app)
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

/** The short list: you (opens Your profile), Link a computer, battery, five rows that open a page each, Nearby as one switch, and More settings. */
@Composable
private fun ShortList(app: Muninn) {
    val id = app.identity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Spacer(Modifier.height(6.dp))
    MeRow(app)
    PageRow("Link a computer", "Use Muninn on a tablet or second phone too") { SettingsNav.folder = "link" }

    Text("Battery", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 16.dp))
    var choice by remember { mutableStateOf(BatteryChoice.of(app)) }
    ChoiceRow(BatteryChoice.entries.size, Modifier.padding(top = 6.dp), minWidth = 96.dp) { i, m ->
        val c = BatteryChoice.entries[i]
        MButton(c.title, m, if (choice == c) Kind.Accent else Kind.Normal) { c.apply(app); choice = c }
    }
    Help(choice?.help ?: "Your own mix, set under More settings › Battery details.")

    Spacer(Modifier.height(12.dp))
    val notifyOn = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val ra = app.power.rightAwayKeys().size
    PageRow("Notifications", (if (notifyOn) "On" else "Off") + if (ra > 0) ", $ra right away" else "") { SettingsNav.folder = "notify" }
    PageRow("Look and text size", "${io.github.ravenor907.muninn.ui.Looks.name(Access.look)}, ${pct(Access.text)} text") { SettingsNav.folder = "look" }
    PageRow("Lock and privacy", when {
        !app.vault.hasPassword -> "No lock set. Read receipts, screenshots"
        app.vault.usesPattern -> "Pattern position: ${posName(id.patternPos)}"
        else -> "Password, read receipts, screenshots"
    }) { SettingsNav.folder = "lock" }
    PageRow("Keep messages for", if (id.deleteAfter in 1 until 7 * DAY) "1 day, every chat" else "7 days, Family and Work up to 30") { SettingsNav.folder = "keep" }

    // Nearby as one switch: on, people close by or on the same Wi-Fi can find you; the details are under More.
    var nearby by remember { mutableStateOf(id.visibleNearby && id.nearbyChat) }
    ToggleRow("Find people close by", "Chat and share files with no internet", nearby) {
        nearby = it; id.visibleNearby = it; id.nearbyChat = it; app.net.announce(); app.close.readvertise(); app.ble.hiddenChanged()
    }
    PageRow("More settings", "Everything else") { SettingsNav.folder = "more" }
}

/** Your picture, name and status at the top of Settings; tap for Your profile. */
@Composable
private fun MeRow(app: Muninn) {
    val status by app.profile.myStatus.collectAsState()
    val custom by app.profile.myCustomStatus.collectAsState()
    val name = app.identity.username
    val line = Status.of(status).title + if (custom.isNotBlank()) " · $custom" else ""
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { SettingsNav.folder = "profile" }
        .semantics(mergeDescendants = true) { contentDescription = "Your profile: $name, $line" }
        .padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        io.github.ravenor907.muninn.ui.Avatar("me", name, 48.dp, showDot = true)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, style = Type.big, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(line, style = Type.small, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        MIcon("forward", Palette.textMuted, 20.dp)
    }
}

/** A row that opens a page: its name, what it's set to, and an arrow. */
@Composable
private fun PageRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = Type.normal.copy(color = Palette.text))
            if (value.isNotEmpty()) Text(value, style = Type.small)
        }
        MIcon("forward", Palette.textMuted, 20.dp)
    }
}

/** One short help line under something that isn't obvious. */
@Composable
private fun Help(text: String) = Text(text, style = Type.small, modifier = Modifier.padding(top = 4.dp))

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun posName(p: String) = when (p) { "top" -> "Top"; "middle" -> "Middle"; else -> "Lower" }

/**
 * The one battery choice. Messages still arrive on time with the first two; the switches each one sets are all
 * under More settings › Battery details, for anyone who wants their own mix.
 */
private enum class BatteryChoice(val title: String, val help: String) {
    FASTEST("Fastest", "Always fully connected. Messages and calls arrive in seconds. Uses the most battery."),
    BALANCED("Balanced", "Messages and calls still arrive in seconds. Muninn saves power while the screen is off."),
    EXTREME("Extreme battery saver", "No notifications. Messages arrive when you open Muninn or refresh. Calls ring only while Muninn is open. Nothing runs in the background.");

    fun apply(app: Muninn) {
        val p = app.power
        when (this) {
            FASTEST -> { p.check = Check.LIVE; p.saver = false; p.lowBatterySave = false; p.adaptive = false; p.dataSaver = false; p.extreme = false }
            BALANCED -> { p.check = Check.LIVE; p.saver = true; p.lowBatterySave = true; p.adaptive = true; p.dataSaver = true; p.extreme = false }
            EXTREME -> { p.check = Check.LIVE; p.saver = true; p.lowBatterySave = true; p.adaptive = true; p.dataSaver = true; p.extreme = true
                p.closeBy = false; app.identity.linkPreviews = false }
        }
        p.refresh()
    }

    companion object {
        /** The choice the current switches match, or null for someone's own mix. */
        fun of(app: Muninn): BatteryChoice? {
            val p = app.power
            if (p.check != Check.LIVE) return null
            return when {
                !p.saver && !p.lowBatterySave && !p.adaptive && !p.dataSaver -> FASTEST
                p.saver && p.lowBatterySave && p.adaptive && p.dataSaver -> if (p.extreme) EXTREME else BALANCED
                else -> null
            }
        }
    }
}

@Composable
private fun NotifyPage(app: Muninn) {
    val id = app.identity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var preview by remember { mutableStateOf(id.showPreview) }
    Panel {
        if (app.power.extreme) {
            Text("Extreme battery saver is on", style = Type.normal.copy(color = Palette.text))
            Text("No notifications. Messages arrive when you open Muninn or refresh. Calls ring only while Muninn is open.", style = Type.small)
        } else {
            Text("Messages arrive on time", style = Type.normal.copy(color = Palette.text))
            Text("Muninn keeps one light connection open, so you hear about messages right away without draining your battery.", style = Type.small)
        }
    }
    Spacer(Modifier.height(8.dp))
    val on = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    PageRow("Notifications", if (on) "On. Tell me when a message or call comes in" else "Off. Turn them on in Android's settings") {
        try {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        } catch (_: Exception) {}
    }
    // Android 14 and newer: without "Full-screen notifications" an incoming call is only a notification.
    var fullOff by remember { mutableStateOf(fullScreenOff(ctx)) }
    io.github.ravenor907.muninn.ui.EveryWhileShown(1500) { fullOff = fullScreenOff(ctx) }
    if (fullOff) {
        Panel(Modifier.padding(bottom = 8.dp)) {
            Text("Calls can't fill the screen", style = Type.normal.copy(color = Palette.danger))
            Text("Android isn't letting Muninn show full-screen calls, so an incoming call only shows as a notification, and not over the lock screen.", style = Type.small)
            MButton("Turn on full-screen calls", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { openFullScreenSetting(ctx) }
        }
    }
    ToggleRow("Show the message", "Off: only “New message”, not who it's from or what it says", preview) { preview = it; id.showPreview = it }
    PageRow("Sound and vibration", "Uses the phone's settings for Muninn") {
        try {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Muninn.MESSAGES))
        } catch (_: Exception) {}
    }
    RightAwayRow(app)
    QuietHours(app)
}

@Composable
private fun LookPage(app: Muninn) {
    val id = app.identity
    Text("Look", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 6.dp))
    val looks = io.github.ravenor907.muninn.ui.Looks.ALL
    // Two a line; one a line at a large text size, so no name breaks mid-word.
    ChoiceRow(looks.size, Modifier.padding(top = 6.dp), minWidth = 140.dp, max = 2) { i, m ->
        val (k, n) = looks[i]
        MButton(n, m, if (Access.look == k) Kind.Accent else Kind.Normal) { Access.look = k; id.look = k }
    }
    Help("True black saves a little battery on OLED screens. Light is parchment, like Huginn's. Seasonal follows the time of year: autumn's falling leaves for now.")
    if (Access.look == io.github.ravenor907.muninn.ui.Looks.SEASONAL && io.github.ravenor907.muninn.ui.Palette.autumn) io.github.ravenor907.muninn.ui.AutumnControls()
    Text("Size of everything", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 14.dp))
    val sizes = listOf(0.85f, 1f, 1.15f, 1.3f)
    ChoiceRow(sizes.size, Modifier.padding(top = 6.dp)) { i, m ->
        val v = sizes[i]; MButton(pct(v), m, if (Access.size == v) Kind.Accent else Kind.Normal) { Access.size = v; id.uiSize = v }
    }
    Text("Text size", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 14.dp))
    val texts = listOf(1f, 1.15f, 1.3f, 1.5f)
    ChoiceRow(texts.size, Modifier.padding(top = 6.dp)) { i, m ->
        val v = texts[i]; MButton(pct(v), m, if (Access.text == v) Kind.Accent else Kind.Normal) { Access.text = v; id.textSize = v }
    }
    Help("100% is the usual. Text size adds to your phone's own font size.")
    PageRow("Accessibility", "High contrast, color-blind friendly, less motion") { SettingsNav.folder = "access" }
}

@Composable
private fun AccessPage(app: Muninn) {
    val id = app.identity
    ToggleRow("High contrast", "White text on black, stronger outlines", Access.contrast) { Access.contrast = it; id.highContrast = it }
    ToggleRow("Color-blind friendly", "Warnings in amber instead of red; status always says it in words", Access.colorBlind) { Access.colorBlind = it; id.colorBlind = it }
    ToggleRow("Reduce motion", "No sliding or fading", Access.reduceMotion) { Access.reduceMotion = it; id.reduceMotion = it }
}

@Composable
private fun LockPage(app: Muninn, onSecure: () -> Unit) {
    val id = app.identity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val onTv = io.github.ravenor907.muninn.tv.KidMode.isTv(ctx)
    var lockAfter by remember { mutableStateOf(id.lockAfter) }
    var pos by remember { mutableStateOf(id.patternPos) }
    var receipts by remember { mutableStateOf(id.readReceipts) }
    var activeOn by remember { mutableStateOf(id.activeStatus) }
    var secure by remember { mutableStateOf(id.secureScreen) }
    var previews by remember { mutableStateOf(id.linkPreviews) }
    var trail by remember { mutableStateOf(id.patternTrail) }
    var privateKeys by remember { mutableStateOf(id.privateKeyboard) }
    var hasLock by remember { mutableStateOf(app.vault.hasPassword) }
    if (hasLock) {
        Text("Muninn asks for your ${if (app.vault.usesPattern) "pattern" else "password"} to open. Without it, nothing it keeps can be read, even by Muninn.", style = Type.normal)
        MButton("Change lock", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) { SettingsNav.folder = "lockset" }
        MButton("Turn off the lock", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Danger) {
            Ui.sure(Ask("Turn off the lock?", "Anyone who can use this phone could open Muninn and read your chats, contacts and keys. " +
                "They stay encrypted, but the key sits on the phone beside them, unprotected. Panic lock stops working too.",
                "Turn off", danger = true) {
                try { app.vault.setPassword(null) } catch (_: Exception) {}
                hasLock = app.vault.hasPassword
                if (!hasLock) { io.github.ravenor907.muninn.screens.skipLock(app); Ui.say("The lock is off. Muninn opens without a pattern or password.") }
            })
        }
    } else {
        Text("Muninn opens without a lock. Anyone who can use this phone can open it and read your chats.", style = Type.normal.copy(color = Palette.danger))
        MButton("Set a lock", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) { SettingsNav.folder = "lockset" }
    }
    if (app.vault.usesPattern) {
        Text("Pattern position", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 14.dp))
        val places = listOf("top", "middle", "lower")
        ChoiceRow(places.size, Modifier.padding(top = 6.dp)) { i, m ->
            val p = places[i]; MButton(posName(p), m, if (pos == p) Kind.Accent else Kind.Normal) { pos = p; id.patternPos = p }
        }
        Help("Where the dots sit when you unlock or set a pattern. Lower is easiest with one hand.")
        ToggleRow("Hide the pattern as I draw", "No line and no lit dots while you draw, so nobody watching sees it", !trail) { trail = !it; id.patternTrail = !it }
    }
    if (app.vault.hasPassword) {
        Text("Lock again after Muninn is in the background for", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 14.dp))
        val afters = listOf(0L to "Right away", 60_000L to "1 min", 5 * 60_000L to "5 min", 30 * 60_000L to "30 min")
        ChoiceRow(afters.size, Modifier.padding(top = 6.dp)) { i, m ->
            val (ms, label) = afters[i]
            MButton(label, m, if (lockAfter == ms) Kind.Accent else Kind.Normal) { lockAfter = ms; id.lockAfter = ms }
        }
        Help("While locked, one notification says new messages are waiting. They open once you unlock.")
        var panic by remember { mutableStateOf(io.github.ravenor907.muninn.ui.Panic.enabled(ctx)) }
        var shake by remember { mutableStateOf(io.github.ravenor907.muninn.ui.Panic.shakeOn(ctx)) }
        ToggleRow("Panic lock", if (onTv) "Lock Muninn at once from its icon's menu. Any call ends and notifications are cleared"
            else "Lock Muninn in one move from anywhere: the “Lock Muninn” quick settings tile, or “Lock now” when you hold Muninn's icon. Any call ends and notifications are cleared", panic) {
            panic = it; io.github.ravenor907.muninn.ui.Panic.setEnabled(ctx, it)
        }
        if (panic && !onTv) {
            Help("Add the tile: swipe down twice, tap the pencil, and drag “Lock Muninn” into your tiles.")
            if (io.github.ravenor907.muninn.ui.Panic.canShake(ctx))
                ToggleRow("Shake to lock", "Shake the phone hard 3 times while Muninn is open", shake) { shake = it; io.github.ravenor907.muninn.ui.Panic.setShake(ctx, it) }
        }
    }
    Section("Privacy")
    Text("Everything Muninn keeps on this phone is encrypted, and messages are end-to-end encrypted.", style = Type.small)
    ToggleRow("Send read receipts", "Off: people don't see when you've read their messages. Delivered still shows", receipts) { receipts = it; id.readReceipts = it }
    ToggleRow("Active status", "A dot shows contacts when Muninn is open. Off: nobody sees yours, and you don't see theirs", activeOn) { activeOn = it; app.chat.setActiveStatus(it) }
    ToggleRow("Block screenshots", "Also hides Muninn's screen in recent apps", secure) { secure = it; id.secureScreen = it; onSecure() }
    ToggleRow("Link previews", "Shows a link's title and picture. This phone opens the page, so that site sees your internet address", previews) { previews = it; id.linkPreviews = it }
    ToggleRow("Private keyboard", "The keyboard doesn't learn what you type. Also turns off voice typing on most keyboards", privateKeys) {
        privateKeys = it; id.privateKeyboard = it; io.github.ravenor907.muninn.ui.PrivateKeyboard.on = it
    }
}

/** Settings › Lock and privacy › Change lock: a page of its own, so the pattern sits where it's set to. */
@Composable
private fun ChangeLock(app: Muninn) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FolderHeader(if (app.vault.hasPassword) "Change lock" else "Set a lock") { SettingsNav.folder = "lock" }
        LockSetup(app, onCancel = { SettingsNav.folder = "lock" }, fill = true) { SettingsNav.folder = "lock" }
    }
}

@Composable
private fun KeepPage(app: Muninn) {
    val id = app.identity
    var shorter by remember { mutableStateOf(id.deleteAfter in 1 until 7 * DAY) }
    Text("Chats keep messages for 7 days. Chats in your Family and Work folders, and Family and Work groups, can keep them up to 30. " +
        "A chat's own menu can set its days.", style = Type.normal, modifier = Modifier.padding(top = 6.dp))
    ToggleRow("Shorten all chats to 1 day", "Every message on this phone goes after a day, on both sides of every chat", shorter) {
        shorter = it; val ms = if (it) DAY else 0L; id.deleteAfter = ms; app.chat.pruneSoon()
    }
}

@Composable
private fun MorePage() {
    Help("Things most people never need to change.")
    Spacer(Modifier.height(6.dp))
    MORE.forEach { p -> PageRow(p.title, p.detail) { SettingsNav.folder = p.id } }
}

@Composable
private fun BatteryPage(app: Muninn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var check by remember { mutableStateOf(app.power.check) }
    val stay = check == Check.LIVE
    var free by remember { mutableStateOf(io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx)) }
    // Only while this page is on screen.
    io.github.ravenor907.muninn.ui.EveryWhileShown(1500) { free = io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx) }
    Text("Check for messages", style = Type.normal.copy(color = Palette.text))
    ChoiceRow(Check.entries.size, Modifier.padding(top = 6.dp), minWidth = 140.dp, max = 2) { i, m ->
        val c = Check.entries[i]; MButton(c.title, m, if (check == c) Kind.Accent else Kind.Normal) { check = c; app.power.check = c }
    }
    Help(when (check) {
        Check.LIVE -> "Messages and calls arrive within seconds. In the background Muninn keeps one light connection, checked about every 5 minutes. No Google servers (a small silent “Listening” notification you can hide)."
        Check.QUARTER -> "Saves battery. Messages arrive within about 15 minutes. Calls don't ring and show as missed. Fully connected while charging."
        Check.HOUR -> "Saves more battery. Messages arrive within about an hour. Calls don't ring and show as missed. Fully connected while charging."
        Check.OPEN -> "No notifications, messages or calls until you open Muninn, even while charging. Messages wait for you."
    })
    if (stay && !free) {
        Text("Android's battery saver can still pause Muninn while the phone sleeps, so messages arrive late. Allowing it costs little.",
            style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
        MButton("Let Muninn run in the background", Modifier.fillMaxWidth().padding(top = 6.dp), Kind.Accent) { io.github.ravenor907.muninn.net.MessageService.askUnrestricted(ctx) }
    } else if (stay) Help("Muninn can run in the background. Messages arrive as they're sent.")
    if (stay && Build.VERSION.SDK_INT >= 26) {
        MButton("Hide the Listening notification", Modifier.fillMaxWidth().padding(top = 6.dp)) {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, io.github.ravenor907.muninn.net.MessageService.CHANNEL))
            } catch (_: Exception) {}
        }
        Help("Turn it off on the next screen. Muninn keeps listening and messages still notify you.")
    }
    var saver by remember { mutableStateOf(app.power.saver) }
    val away by app.power.away.collectAsState()
    if (stay) ToggleRow("Save power when the screen is off", if (saver) "One light connection while the screen is off, and a full one for a few minutes after a message. Messages and calls still arrive within seconds"
        else "Always fully connected. Uses more battery", saver) { saver = it; app.power.saver = it }
    androidx.compose.runtime.LaunchedEffect(check) { app.power.refresh() }
    Help(away.label)
    var low by remember { mutableStateOf(app.power.lowBatterySave) }
    ToggleRow("Save more when the battery is low", "Below 30%: phones close by aren't looked for in the background. Below 15%: the connection " +
        "is checked every 10 minutes instead of 5, but never dropped. Your Always right away people are never held back", low) { low = it; app.power.lowBatterySave = it }
    var adaptive by remember { mutableStateOf(app.power.adaptive) }
    ToggleRow("Check less when it's quiet", "After 3 hours with nothing new and Muninn closed, it checks less often until a message comes or you open it. Calls still ring", adaptive) {
        adaptive = it; app.power.adaptive = it
    }
    var data by remember { mutableStateOf(app.power.dataSaver) }
    ToggleRow("Data saver", "On mobile data, big files and videos wait until you tap them, and updates wait for Wi-Fi", data) { data = it; app.power.dataSaver = it }
    Section("Today")
    BatteryReport(app)
}

@Composable
private fun CallsPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var noise by remember { mutableStateOf(io.github.ravenor907.muninn.net.call.CallManager.noiseRemoval(ctx)) }
    ToggleRow("Noise removal", if (noise) "Background noise is taken out of your voice. Turn it off if people say you sound quiet"
        else "Off: Muninn asks your phone not to filter noise out of your voice. Background sound comes through too", noise) {
        noise = it; io.github.ravenor907.muninn.net.call.CallManager.setNoiseRemoval(ctx, it)
    }
    Help("Changes start with your next call.")
}

@Composable
private fun NearbyPage(app: Muninn) {
    val id = app.identity
    var visible by remember { mutableStateOf(id.visibleNearby) }
    var nearby by remember { mutableStateOf(id.nearbyChat) }
    var keep by remember { mutableStateOf(id.keepNearbyHistory) }
    var device by remember { mutableStateOf(id.deviceName) }
    ToggleRow("Visible to people nearby", if (visible) "People close by or on the same Wi-Fi can see your name and message you"
        else "Hidden: only your contacts can reach you close by or on the same Wi-Fi. Your name isn't sent out", visible) {
        visible = it; id.visibleNearby = it; app.net.announce(); app.close.readvertise(); app.ble.hiddenChanged()
    }
    if (visible) ToggleRow("Nearby room", "A chat room for everyone close by or on the same Wi-Fi with Huginn or Muninn", nearby) {
        nearby = it; id.nearbyChat = it; app.net.announce()
    }
    var closeBy by remember { mutableStateOf(app.power.closeBy) }
    ToggleRow("Find phones close by in the background", if (closeBy) "Bluetooth keeps looking while Muninn is closed, for chatting without the internet. Uses more battery"
        else "Off: it looks only while Muninn is open. Saves battery", closeBy) { closeBy = it; app.power.closeBy = it }
    ToggleRow("Keep Nearby messages", "Off: they clear when Muninn closes. Encrypted when kept", keep) { keep = it; id.keepNearbyHistory = it; if (!it) app.chat.clearNearby() }
    Section("This phone")
    Text("Name other devices see", style = Type.small)
    Row(Modifier.padding(top = 4.dp)) {
        Field(device, { device = it.take(40) }, "Muninn on my phone", Modifier.weight(1f))
        MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = device.isNotBlank() && device != id.deviceName) { id.deviceName = device; app.net.announce(); Ui.say("Saved.") }
    }
    Help("Received files go to ${io.github.ravenor907.muninn.net.Received.place(androidx.compose.ui.platform.LocalContext.current)}.")
}

/** More settings › Kids' TV: every TV this phone looks after, each opening its own settings. */
@Composable
private fun TvPage(app: Muninn) {
    val tvs by io.github.ravenor907.muninn.tv.TvParent.tvs.collectAsState()
    val mine = tvs.values.filter { !it.disconnecting }
    if (mine.isEmpty()) Text("No Kids' TV yet. Install Muninn on the TV, then scan the code it shows with Add someone.", style = Type.normal)
    mine.forEach { t -> PageRow(app.chat.nameOf(t.key), "Who it can call, messages, microphone and noise removal") { SettingsNav.folder = "tv:" + t.key } }
}

@Composable
private fun AboutPage(app: Muninn) {
    val up by app.updater.state.collectAsState()
    var auto by remember { mutableStateOf(app.identity.autoUpdate) }
    Panel {
        Text("Muninn ${app.updater.current}. ${up.status}", style = Type.normal)
        Text(if (auto) "Muninn checks for a new version every time you open it." else "Muninn only checks when you tap Check now.", style = Type.small)
        if (up.busy && up.progress > 0f) Bar(up.progress, Modifier.padding(top = 6.dp))
        MButton(if (up.ready) "Install ${up.latest}" else "Check now", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, enabled = !up.busy) {
            if (up.ready) app.updater.download() else app.updater.check()
        }
    }
    ToggleRow("Check for updates automatically", "Asks GitHub for a new version when Muninn opens. A Kids' TV you look after follows this", auto) {
        auto = it; app.identity.autoUpdate = it; io.github.ravenor907.muninn.tv.TvParent.autoUpdateChanged(app)
    }
    Section("About")
    Text("Muninn ${app.updater.current}: private chat and file sharing, Huginn's phone companion. Sends and receives with LocalSend. Made by Ravenor907. GPL-3.0.", style = Type.small)
}

@Composable
private fun ErasePage(app: Muninn) {
    Text("Delete every message on this phone, or erase everything Muninn keeps (messages, contacts, keys and settings).", style = Type.normal)
    MButton("Delete all messages", Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Ui.sure(Ask("Delete all messages?", "Every conversation on this phone is deleted. Contacts stay.", "Hold to delete", danger = true, hold = true) {
            app.chat.deleteAll(); Ui.say("All messages deleted.")
        })
    }
    MButton("Erase everything", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Danger) {
        Ui.sure(Ask("Erase everything?", "Messages, contacts, keys and settings are deleted for good, and Muninn closes. Files you received stay.",
            "Hold to erase", danger = true, hold = true) { app.vault.eraseAll(); android.os.Process.killProcess(android.os.Process.myPid()) })
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
    val modes = listOf(Check.HOUR, Check.OPEN)
    ChoiceRow(modes.size, Modifier.padding(top = 6.dp), minWidth = 140.dp) { i, m ->
        val c = modes[i]; MButton(c.title, m, if (mode == c) Kind.Accent else Kind.Normal) { set(c) }
    }
    Text("While quiet hours or Do Not Disturb last, Muninn checks this way unless your own choice saves more.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
}

/** A folder's title, with a way back to the list (Back does the same). */
@Composable
private fun FolderHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Back") { onBack() }.padding(12.dp)) {
            MIcon("back", Palette.text, 24.dp)
        }
        Text(title.uppercase(), style = Type.title.copy(fontSize = 17.sp, letterSpacing = 2.sp), modifier = Modifier.weight(1f).padding(start = 4.dp))
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
            MButton("Unlink", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Danger) {
                Ui.sure(Ask("Unlink ${p.name}?", "This device stops showing its chats.", "Unlink", danger = true) { io.github.ravenor907.muninn.net.Link.unlink(p.key) })
            }
        }
    }
    if (companions.isNotEmpty()) {
        Section("Linked to this phone")
        Panel {
            companions.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(d.name, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
                    MButton("Unlink", kind = Kind.Danger) {
                        Ui.sure(Ask("Unlink ${d.name}?", "Its copy of your chats is removed.", "Unlink", danger = true) { io.github.ravenor907.muninn.net.Link.unlink(d.key) })
                    }
                }
            }
        }
    }
    // Use on both (linked devices, below), or Move here (cap x1).
    MoveSection(app)
    Section("Use on both: link a device to this phone")
    Panel {
        Text("On the other device: Settings › More settings › Linked devices › Show the link code. Then scan it here, or paste it.", style = Type.small)
        var scanning by remember { mutableStateOf(false) }
        if (scanning) QrScanner(Modifier.fillMaxWidth().padding(top = 8.dp).height(260.dp)) { c ->
            scanning = false
            val err = io.github.ravenor907.muninn.net.Link.link(c)
            io.github.ravenor907.muninn.ui.Ui.say(err ?: "Now check the 4 emoji on both screens.", err != null)
        }
        MButton(if (scanning) "Stop scanning" else "Scan the code", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, icon = "qr") { scanning = !scanning }
        io.github.ravenor907.muninn.ui.Field(pasted, { pasted = it }, "Paste the link code", Modifier.padding(top = 8.dp), single = false)
        MButton("Link", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = pasted.isNotBlank()) {
            val err = io.github.ravenor907.muninn.net.Link.link(pasted)
            io.github.ravenor907.muninn.ui.Ui.say(err ?: "Now check the 4 emoji on both screens.", err != null)
            if (err == null) pasted = ""
        }
    }
    if (phone == null) {
        Section("Use this device as a companion")
        Panel {
            Text("Scan this code with your phone (it works for 10 minutes). This device then shows your phone's chats.", style = Type.small)
            val c = code
            if (c == null) MButton("Show the link code", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { code = io.github.ravenor907.muninn.net.Link.myLinkCode() }
            else {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    io.github.ravenor907.muninn.screens.Qr(c, 220.dp)
                }
                MButton("Copy", Modifier.fillMaxWidth(), icon = "copy") {
                    io.github.ravenor907.muninn.ui.Clip.copy(ctx, "Muninn link code", c)
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
