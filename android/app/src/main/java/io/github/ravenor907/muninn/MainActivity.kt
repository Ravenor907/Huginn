package io.github.ravenor907.muninn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import io.github.ravenor907.muninn.files.Files
import io.github.ravenor907.muninn.forge.Jobs
import io.github.ravenor907.muninn.net.LocalSend
import android.view.WindowManager
import io.github.ravenor907.muninn.screens.ChatNav
import io.github.ravenor907.muninn.screens.ChatScreen
import io.github.ravenor907.muninn.screens.ContactsScreen
import io.github.ravenor907.muninn.screens.LockScreen
import io.github.ravenor907.muninn.screens.FirstLock
import androidx.compose.runtime.remember
import io.github.ravenor907.muninn.screens.FilesScreen
import io.github.ravenor907.muninn.screens.SettingsScreen
import io.github.ravenor907.muninn.screens.Share
import io.github.ravenor907.muninn.screens.TransferScreen
import io.github.ravenor907.muninn.screens.UsbScreen
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.Brand
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.MuninnTheme
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val app get() = application as Muninn
    private var tab by mutableStateOf("chat")
    private var storageOk by mutableStateOf(true)
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
    private val legacyStorage = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { storageOk = hasStorage() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure()
        if (app.ready.value) startNet()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // ChatNav lives as long as the process, and the message service keeps the process alive after
        // Muninn's window is closed. So a fresh open would land back in whatever chat was last on screen:
        // start on the chat list unless a notification (extra "chat") or a scanned code asks otherwise.
        if (savedInstanceState == null) { ChatNav.open = ""; ChatNav.menu = false }
        intent?.getStringExtra("screen")?.let { tab = it }   // CI screenshots open each tab
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
        intent.getStringExtra("screen")?.let { tab = it }
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
        storageOk = hasStorage()
        Jobs.setVisible(true)
        app.updater.checkOnOpen()
        if (app.ready.value) app.net.announce()
        // Back from Muninn's app settings with the permission now allowed: start close-by chat.
        if (netStarted && !app.close.running && app.close.error.value != null && closePerms().all { granted(it) }) {
            closeBlocked = false
            app.close.start { app.chat.hello() }
            app.ble.start()
        }
    }

    override fun onPause() {
        Jobs.setVisible(false)
        super.onPause()
    }

    private fun hasStorage() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            legacyStorage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun startDirect() {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) app.direct.start() else directPermission.launch(perms)
    }

    /** "Share" › Muninn from another app: copy what was shared so it can be sent. */
    private fun handleShare(i: Intent?) {
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        val uris = if (i.action == Intent.ACTION_SEND) listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            else (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        val dir = File(cacheDir, "share").also { it.deleteRecursively(); it.mkdirs() }
        Thread {
            val files = uris.mapNotNull { u ->
                try {
                    var name = "shared"
                    contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
                    val f = Files.free(dir, name.replace('/', '_'))
                    contentResolver.openInputStream(u)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    f
                } catch (e: Exception) { null }
            }
            runOnUiThread { Share.pending.addAll(files); tab = "send" }
        }.start()
    }

    @Composable
    private fun Root() {
        val ready by app.ready.collectAsState()
        if (!ready || uiLocked) { LockScreen(app) { ok -> if (ok) { uiLocked = false; app.start(); startNet() } }; return }
        // A lock is required: on first open (or from a version before it was), set one before anything else.
        var locked by remember { mutableStateOf(app.vault.hasPassword || ciSkip) }
        if (!locked) { FirstLock(app) { locked = true }; return }
        val request by app.net.request.collectAsState()
        val offer by app.closeFiles.pendingOffer.collectAsState()
        val ask by Ui.ask.collectAsState()
        val toast by Ui.toast.collectAsState()
        val up by app.updater.state.collectAsState()
        LaunchedEffect(Unit) {
            launch { app.net.messages.collect { (ok, msg) -> Ui.say(msg, !ok) } }
            launch { app.chat.notices.collect { Ui.say(it) } }
            // Nearby Connections named a permission the usual set didn't cover (location on some phones): ask once.
            launch { app.close.missing.collect { m -> if (m.isNotEmpty() && autoAsked.addAll(m)) askClose() } }
            launch { app.net.pinNeeded.collect { Ui.say("That device asks for a PIN. Turn off its PIN setting in LocalSend to send from Muninn.", true) } }
        }
        LaunchedEffect(toast) { if (toast != null) { delay(4500); Ui.toast.value = null } }

        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // An open conversation (or the Nearby room) gets the whole screen on phones: no header, no tabs.
        // Tablets keep the list beside it (ChatScreen's wide layout), so they keep both.
        val full = tab == "chat" && maxWidth < ChatNav.WIDE && ChatNav.open.isNotEmpty() && ChatNav.open != "add" && ChatNav.open != "code"
        Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
            val header: @Composable () -> Unit = { Brand {
                if (app.vault.hasPassword) IconAction("lock", "Lock Muninn") { uiLocked = true }
                IconAction("gear", if (tab == "settings") "Close settings" else "Settings", if (tab == "settings") Palette.accent else Palette.text) {
                    tab = if (tab == "settings") "chat" else "settings"
                }
            } }
            if (Access.reduceMotion) { if (!full) header() } else androidx.compose.animation.AnimatedVisibility(!full) { header() }
            if (up.ready && tab != "settings" && !full) Row(Modifier.fillMaxWidth().background(Palette.accentFill).clickable { tab = "settings" }.padding(10.dp)) {
                Text("Muninn ${up.latest} is ready. Tap to install.", style = Type.small.copy(color = Palette.accent))
            }
            if (!storageOk && (tab == "files" || tab == "send" || tab == "usb")) {
                Panel(Modifier.padding(16.dp)) {
                    Text("Muninn needs access to your files", style = Type.big)
                    Text("To browse, send and receive files the way a file manager does, Android asks you to allow access to all files once.", style = Type.normal)
                    MButton("Allow access", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) { askStorage() }
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    "send" -> TransferScreen(app) { startDirect() }
                    "files" -> FilesScreen(app) { files -> Share.pending.addAll(files); tab = "send" }
                    "usb" -> UsbScreen()
                    "contacts" -> ContactsScreen(app) { tab = "settings" }
                    "settings" -> SettingsScreen(app) { secure() }
                    else -> ChatScreen(app) { askClose() }
                }
                toast?.let { (text, bad) ->
                    Box(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().background(if (bad) Palette.dangerFill else Palette.raised).clickable { Ui.toast.value = null }.padding(14.dp)) {
                        // A problem is marked with an icon too, never by color alone.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (bad) { MIcon("info", Palette.danger, 20.dp); androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp)) }
                            Text(text, style = Type.normal.copy(color = if (bad) Palette.danger else Palette.text))
                        }
                    }
                }
            }
            if (!full) Column(Modifier.fillMaxWidth().background(Palette.groundDeep)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                    listOf("chat" to ("chat" to "Chat"), "send" to ("send" to "Send"), "files" to ("folder" to "Files"), "usb" to ("usb" to "USB")).forEach { (id, v) ->
                        val on = tab == id
                        val tint = if (on) Palette.goldBright else Palette.textMuted
                        Column(Modifier.weight(1f).clickable(role = Role.Tab) { tab = id }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            MIcon(v.first, tint, 22.dp)
                            Text(v.second, style = Type.small.copy(color = tint, fontSize = 12.sp,
                                fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal),
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        }

        request?.let { r ->
            AlertDialog(
                onDismissRequest = { app.net.decline() },
                containerColor = Palette.panel,
                title = { Text("${r.from} wants to send ${if (r.count == 1) "a file" else "${r.count} files"}", style = Type.big) },
                text = { Text("${r.names} (${LocalSend.size(r.size)}). They'll be saved in ${app.identity.receiveFolder.name}.", style = Type.normal) },
                confirmButton = { MButton("Accept", kind = Kind.Accent) { app.net.accept() } },
                dismissButton = { MButton("Decline") { app.net.decline() } },
            )
        }
        offer?.let { r ->
            AlertDialog(
                onDismissRequest = { app.closeFiles.decline() },
                containerColor = Palette.panel,
                title = { Text("${r.from} wants to send ${if (r.count == 1) "a file" else "${r.count} files"}", style = Type.big) },
                text = { Text("${r.names} (${LocalSend.size(r.size)}), from close by. They'll be saved in ${app.identity.receiveFolder.name}.", style = Type.normal) },
                confirmButton = { MButton("Accept", kind = Kind.Accent) { app.closeFiles.accept() } },
                dismissButton = { MButton("Decline") { app.closeFiles.decline() } },
            )
        }
        ask?.let { AskDialog(it) }
    }
}
