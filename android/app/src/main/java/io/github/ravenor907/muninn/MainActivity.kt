package io.github.ravenor907.muninn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.net.Received
import io.github.ravenor907.muninn.screens.CallsScreen
import io.github.ravenor907.muninn.screens.ChatColumn
import io.github.ravenor907.muninn.screens.ChatMain
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import android.view.WindowManager
import io.github.ravenor907.muninn.screens.ChatNav
import io.github.ravenor907.muninn.screens.ChatScreen
import io.github.ravenor907.muninn.screens.ContactsScreen
import io.github.ravenor907.muninn.screens.LockScreen
import io.github.ravenor907.muninn.screens.FirstLock
import androidx.compose.runtime.remember
import io.github.ravenor907.muninn.screens.SettingsScreen
import io.github.ravenor907.muninn.screens.Share
import io.github.ravenor907.muninn.screens.TransferScreen
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.Brand
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.MuninnTheme
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as Muninn
    /** The bottom tab (phones) or rail button (tablets): chat, calls, files, contacts, or settings (the gear). */
    private var tab by mutableStateOf("chat")
    /** Where the gear goes back to. */
    private var beforeSettings = "chat"
    /** Password set and Muninn was away long enough: ask for it before showing anything. */
    private var uiLocked by mutableStateOf(false)
    /** Test builds only: the emulator screenshots open screens without setting a lock. */
    private val ciSkip get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && intent?.getBooleanExtra("ci", false) == true
    private var awaySince = 0L
    private var netStarted = false

    private val closePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) { app.close.start { app.chat.hello() }; app.ble.start(); return@registerForActivityResult }
        // Android stops showing the prompt after it's been refused (twice on newer versions). Then the
        // only way to allow it is Muninn's app settings, so the message on Chat says to tap for that.
        val refused = granted.filterValues { !it }.keys
        closeBlocked = refused.isNotEmpty() && refused.none { shouldShowRequestPermissionRationale(it) }
        if (closeBlocked) app.close.blocked() else app.close.denied()
    }
    private var closeBlocked = false
    private val autoAsked = mutableSetOf<String>()

    private fun closePerms(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    } + app.close.missing.value

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Starts close-by chat, asking for whatever permission it still needs. Also what tapping the Chat message does. */
    fun askClose() {
        val need = closePerms().distinct().filterNot { granted(it) }
        when {
            need.isEmpty() -> { app.close.start { app.chat.hello() }; app.ble.start() }
            closeBlocked -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            else -> closePermission.launch(need.toTypedArray())
        }
    }

    private val directPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) app.direct.start()
        else Ui.say("Direct connect needs the ${if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Location"} permission to make a hotspot.", true)
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    /** Android 8 and 9: saving into Downloads needs the storage permission, asked when a transfer is accepted. */
    private var afterStorage: (() -> Unit)? = null
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Refused: files still arrive, in Muninn's own Downloads folder (Received says where).
        afterStorage?.invoke(); afterStorage = null
    }

    private fun acceptFiles(accept: () -> Unit) {
        if (Received.needsPermission(this)) { afterStorage = accept; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else accept()
    }

    /** Opens a tab by name (also what CI and older links ask for); anything unknown is the chat list. */
    private fun show(name: String) {
        val t = when (name) { "send", "files" -> "files"; "calls", "contacts", "settings" -> name; else -> "chat" }
        if (t == "settings" && tab != "settings") beforeSettings = tab
        tab = t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        io.github.ravenor907.muninn.ui.Panic.watch(this, this)
        secure()
        if (app.ready.value) startNet()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // ChatNav lives as long as the process, and the message service keeps the process alive after
        // Muninn's window is closed. So a fresh open would land back in whatever chat was last on screen:
        // start on the chat list unless a notification (extra "chat") or a scanned code asks otherwise.
        if (savedInstanceState == null) { ChatNav.open = ""; ChatNav.menu = false }
        intent?.getStringExtra("screen")?.let { show(it) }   // CI screenshots open each tab
        if (ciSkip && intent?.getStringExtra("screen") == "lock") uiLocked = true   // CI: the lock screen
        if (ciSkip && intent?.getBooleanExtra("menu", false) == true) ChatNav.menu = true   // CI: the + menu
        if (savedInstanceState == null) { openChat(intent); openAdd(intent) }
        handleShare(intent)
        setContent { MuninnTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        // The launcher icon while Muninn is still open: the chat list, like a fresh open.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.getStringExtra("chat") == null) toList()
        openChat(intent)
        openAdd(intent)
        intent.getStringExtra("screen")?.let { show(it) }
    }

    /** Set when an intent picked the chat to show; cleared once Muninn is back on screen (onResume). */
    private var intentChose = false

    private fun toList() { tab = "chat"; ChatNav.open = ""; ChatNav.menu = false }

    /** A scanned contact QR opened Muninn: go to Add someone with the code filled in. */
    private fun openAdd(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "muninn" || d.host != "add") return
        val body = d.path?.trim('/')?.takeIf { it.isNotEmpty() } ?: return
        ChatNav.incoming = io.github.ravenor907.muninn.net.Contacts.PREFIX + body
        tab = "chat"; ChatNav.open = "add"; intentChose = true
    }

    /** A message notification was tapped: open that conversation. */
    private fun openChat(i: Intent?) {
        val key = i?.getStringExtra("chat") ?: return
        tab = "chat"
        ChatNav.open = if (key == "inbox") "" else key
        intentChose = true
    }

    /** Wi-Fi, close-by (Bluetooth) and internet: started once the vault is open. */
    private fun startNet() {
        if (netStarted) return
        netStarted = true
        app.listen()
        io.github.ravenor907.muninn.net.MessageService.start(this)
        askClose()
    }

    /** "Block screenshots": also hides Muninn's screen in recent apps. */
    fun secure() {
        if (app.identity.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStart() {
        super.onStart()
        app.visible = true
        if (app.vault.hasPassword && awaySince > 0 && System.currentTimeMillis() - awaySince >= app.identity.lockAfter) {
            uiLocked = true
            // Away long enough to lock: come back to the chat list, not the chat that was open. A notification
            // tap can arrive just before or just after this (onNewIntent), so only its choice is kept.
            if (!intentChose) toList()
        }
        app.listen()   // relays (with reconnect) and the Wi-Fi side
    }

    override fun onStop() {
        app.visible = false
        awaySince = System.currentTimeMillis()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        intentChose = false
        app.updater.checkOnOpen()
        if (app.ready.value) app.net.announce()
        // Back from Muninn's app settings with the permission now allowed: start close-by chat.
        if (netStarted && !app.close.running && app.close.error.value != null && closePerms().all { granted(it) }) {
            closeBlocked = false
            app.close.start { app.chat.hello() }
            app.ble.start()
        }
    }

    private fun startDirect() {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) app.direct.start() else directPermission.launch(perms)
    }


    /** "Share" › Muninn from another app: copy what was shared so it can be sent, then open Files. */
    private fun handleShare(i: Intent?) {
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        val uris = if (i.action == Intent.ACTION_SEND) listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            else (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        if (uris.isEmpty()) return
        val busy = app.ready.value && app.net.transfer.value.active
        Thread {
            val files = Share.copyIn(this, uris, busy)
            runOnUiThread { Share.pending.addAll(files); show("files") }
        }.start()
    }

    private fun title(t: String) = when (t) { "calls" -> "Calls"; "files" -> "Files"; "contacts" -> "Contacts"; "settings" -> "Settings"; else -> "Muninn" }

    private fun toggleSettings() {
        if (tab == "settings") tab = beforeSettings else show("settings")
    }

    @Composable
    private fun Root() {
        val ready by app.ready.collectAsState()
        val panic by io.github.ravenor907.muninn.ui.Panic.locked.collectAsState()
        LaunchedEffect(panic) { if (panic) toList() }
        if (!ready || uiLocked || panic) { LockScreen(app) { ok -> if (ok) { uiLocked = false; app.start(); startNet() } }; return }
        // A lock is required: on first open (or from a version before it was), set one before anything else.
        var locked by remember { mutableStateOf(app.vault.hasPassword || ciSkip) }
        if (!locked) { FirstLock(app) { locked = true }; return }
        val request by app.net.request.collectAsState()
        val offer by app.closeFiles.pendingOffer.collectAsState()
        val ask by Ui.ask.collectAsState()
        val toast by Ui.toast.collectAsState()
        LaunchedEffect(Unit) {
            launch { app.net.messages.collect { (ok, msg) -> Ui.say(msg, !ok) } }
            launch { app.chat.notices.collect { Ui.say(it) } }
            // Nearby Connections named a permission the usual set didn't cover (location on some phones): ask once.
            launch { app.close.missing.collect { m -> if (m.isNotEmpty() && autoAsked.addAll(m)) askClose() } }
            launch { app.net.pinNeeded.collect { Ui.say("That device asks for a PIN. Turn off its PIN setting in LocalSend to send from Muninn.", true) } }
        }
        LaunchedEffect(toast) { if (toast != null) { delay(4500); Ui.toast.value = null } }
        // Back from another tab (or Settings) goes to where you came from, then the chat list.
        BackHandler(enabled = tab != "chat") { if (tab == "settings") tab = beforeSettings else tab = "chat" }

        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Palette.ground)) {
            // Tablets and wide screens (600dp and up): Discord style, a rail, the chat list and the open chat.
            val wide = maxWidth >= ChatNav.WIDE
            SideEffect { ChatNav.wide = wide }
            if (wide) TabletLayout(toast) else PhoneLayout(toast)
        }

        request?.let { r ->
            AlertDialog(
                onDismissRequest = { app.net.decline() },
                containerColor = Palette.panel,
                title = { Text("${r.from} wants to send ${if (r.count == 1) "a file" else "${r.count} files"}", style = Type.big) },
                text = { Text("${r.names} (${LocalSend.size(r.size)}). They'll be saved in ${Received.place(this@MainActivity)}.", style = Type.normal) },
                confirmButton = { MButton("Accept", kind = Kind.Accent) { acceptFiles { app.net.accept() } } },
                dismissButton = { MButton("Decline") { app.net.decline() } },
            )
        }
        offer?.let { r ->
            AlertDialog(
                onDismissRequest = { app.closeFiles.decline() },
                containerColor = Palette.panel,
                title = { Text("${r.from} wants to send ${if (r.count == 1) "a file" else "${r.count} files"}", style = Type.big) },
                text = { Text("${r.names} (${LocalSend.size(r.size)}), from close by. They'll be saved in ${Received.place(this@MainActivity)}.", style = Type.normal) },
                confirmButton = { MButton("Accept", kind = Kind.Accent) { acceptFiles { app.closeFiles.accept() } } },
                dismissButton = { MButton("Decline") { app.closeFiles.decline() } },
            )
        }
        ask?.let { AskDialog(it) }
    }

    /** What a tab shows (the chat tab on phones; tablets lay chat out themselves). */
    @Composable
    private fun TabContent(t: String) {
        when (t) {
            "files" -> TransferScreen(app) { startDirect() }
            "calls" -> CallsScreen()
            "contacts" -> ContactsScreen(app)
            "settings" -> SettingsScreen(app) { secure() }
            else -> ChatScreen(app) { askClose() }
        }
    }

    @Composable
    private fun UpdateBanner() {
        val up by app.updater.state.collectAsState()
        if (up.ready && tab != "settings") Row(Modifier.fillMaxWidth().background(Palette.accentFill).clickable(role = Role.Button) { show("settings") }.padding(10.dp)) {
            Text("Muninn ${up.latest} is ready. Tap to install.", style = Type.small.copy(color = Palette.accent))
        }
    }

    @Composable
    private fun Toast(toast: Pair<String, Boolean>?, modifier: Modifier) {
        toast?.let { (text, bad) ->
            Box(modifier.padding(12.dp).fillMaxWidth().background(if (bad) Palette.dangerFill else Palette.raised).clickable { Ui.toast.value = null }.padding(14.dp)) {
                // A problem is marked with an icon too, never by color alone.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bad) { MIcon("info", Palette.danger, 20.dp); Spacer(Modifier.padding(start = 8.dp)) }
                    Text(text, style = Type.normal.copy(color = if (bad) Palette.danger else Palette.text))
                }
            }
        }
    }

    // ------------------------------------------------------------------ phones

    @Composable
    private fun PhoneLayout(toast: Pair<String, Boolean>?) {
        // An open conversation (or My code, Add someone, the Nearby room) gets the whole screen: no header, no tabs.
        val full = tab == "chat" && ChatNav.open.isNotEmpty()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val header: @Composable () -> Unit = { Brand(title = title(tab).uppercase()) {
                if (tab == "chat" && app.vault.hasPassword) IconAction("lock", "Lock Muninn") { uiLocked = true }
                IconAction("gear", if (tab == "settings") "Close settings" else "Settings", if (tab == "settings") Palette.accent else Palette.text) { toggleSettings() }
            } }
            if (Access.reduceMotion) { if (!full) header() } else androidx.compose.animation.AnimatedVisibility(!full) { header() }
            if (!full) UpdateBanner()
            Box(Modifier.weight(1f)) {
                TabContent(tab)
                Toast(toast, Modifier.align(Alignment.BottomCenter))
            }
            if (!full) BottomTabs()
        }
    }

    @Composable
    private fun BottomTabs() {
        Column(Modifier.fillMaxWidth().background(Palette.groundDeep)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)) {
                listOf("chat" to ("chat" to "Chats"), "calls" to ("call" to "Calls"), "files" to ("folder" to "Files"), "contacts" to ("people" to "Contacts")).forEach { (id, v) ->
                    val on = tab == id
                    val tint = if (on) Palette.goldBright else Palette.textMuted
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Tab) { if (id == "chat" && tab == "chat") toList() else show(id) }
                        .semantics { selected = on }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MIcon(v.first, tint, 22.dp)
                        Text(v.second, style = Type.small.copy(color = tint, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ tablets

    /** One button on the tablet rail. A gold bar at its left marks the one that's open. */
    @Composable
    private fun RailButton(label: String, on: Boolean, round: Boolean, fill: androidx.compose.ui.graphics.Color, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.width(80.dp), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.align(Alignment.CenterStart).size(width = 4.dp, height = 32.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(Palette.goldBright))
            val shape = if (round) CircleShape else RoundedCornerShape(16.dp)
            Box(Modifier.size(52.dp).clip(shape).background(fill).clickable(role = Role.Tab, onClick = onClick)
                .semantics { contentDescription = label; selected = on }, contentAlignment = Alignment.Center) { content() }
        }
    }

    /** The rail's top part: direct messages now; groups join this list in a later version. */
    private data class RailSpace(val id: String, val label: String)
    private val spaces = listOf(RailSpace("chat", "Direct messages"))

    @Composable
    private fun TabletLayout(toast: Pair<String, Boolean>?) {
        Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.width(80.dp).fillMaxHeight().background(Palette.groundDeep).padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                spaces.forEach { sp ->
                    RailButton(sp.label, tab == sp.id, round = false, fill = Palette.field, onClick = { if (tab == sp.id) toList() else show(sp.id) }) {
                        Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                }
                Box(Modifier.size(width = 32.dp, height = 2.dp).background(Palette.hairline))
                Spacer(Modifier.weight(1f))
                listOf("files" to ("folder" to "Files"), "calls" to ("call" to "Calls"), "contacts" to ("people" to "Contacts"), "settings" to ("gear" to "Settings")).forEach { (id, v) ->
                    val on = tab == id
                    RailButton(v.second, on, round = true, fill = if (on) Palette.field else androidx.compose.ui.graphics.Color.Transparent,
                        onClick = { if (id == "settings") toggleSettings() else show(id) }) {
                        MIcon(v.first, if (on) Palette.goldBright else Palette.textMuted, 22.dp)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                UpdateBanner()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tab == "chat") Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(270.dp).fillMaxHeight().background(Palette.panelLow)) {
                            ColumnHeader("Direct messages") {
                                if (app.vault.hasPassword) IconAction("lock", "Lock Muninn") { uiLocked = true }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) { ChatColumn(app) { askClose() } }
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.hairline))
                        Box(Modifier.weight(1f).fillMaxHeight()) { ChatMain(app) }
                    } else Column(Modifier.fillMaxSize()) {
                        ColumnHeader(title(tab))
                        Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(tab) }
                    }
                    Toast(toast, Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp))
                }
            }
        }
    }

    /** A tablet column's 60dp title bar with a hairline under it. */
    @Composable
    private fun ColumnHeader(text: String, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = Type.title.copy(fontSize = 18.sp, letterSpacing = 1.sp), maxLines = 1, modifier = Modifier.weight(1f))
                trailing()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        }
    }
}
