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
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.files.Files
import io.github.ravenor907.muninn.forge.Jobs
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.screens.ChatScreen
import io.github.ravenor907.muninn.screens.FilesScreen
import io.github.ravenor907.muninn.screens.SettingsScreen
import io.github.ravenor907.muninn.screens.Share
import io.github.ravenor907.muninn.screens.TransferScreen
import io.github.ravenor907.muninn.screens.UsbScreen
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.Brand
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.MuninnTheme
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val app get() = application as Muninn
    private var tab by mutableStateOf("send")
    private var storageOk by mutableStateOf(true)

    private val directPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) app.direct.start()
        else Ui.say("Direct connect needs the ${if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Location"} permission to make a hotspot.", true)
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val legacyStorage = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { storageOk = hasStorage() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.net.start()
        app.net.refresh()
        app.updater.check()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        intent?.getStringExtra("screen")?.let { tab = it }   // CI screenshots open each tab
        handleShare(intent)
        setContent { MuninnTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        intent.getStringExtra("screen")?.let { tab = it }
    }

    override fun onResume() {
        super.onResume()
        storageOk = hasStorage()
        Jobs.setVisible(true)
        app.net.announce()
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
        val request by app.net.request.collectAsState()
        val ask by Ui.ask.collectAsState()
        val toast by Ui.toast.collectAsState()
        val up by app.updater.state.collectAsState()
        LaunchedEffect(Unit) {
            launch { app.net.messages.collect { (ok, msg) -> Ui.say(msg, !ok) } }
            launch { app.chat.notices.collect { Ui.say(it) } }
            launch { app.net.pinNeeded.collect { Ui.say("That device asks for a PIN. Turn off its PIN setting in LocalSend to send from Muninn.", true) } }
        }
        LaunchedEffect(toast) { if (toast != null) { delay(4500); Ui.toast.value = null } }

        Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
            Brand {
                MButton("Settings", kind = if (tab == "settings") Kind.Accent else Kind.Normal, icon = "gear") { tab = if (tab == "settings") "send" else "settings" }
            }
            if (up.ready && tab != "settings") Row(Modifier.fillMaxWidth().background(Palette.accentFill).clickable { tab = "settings" }.padding(10.dp)) {
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
                    "chat" -> ChatScreen(app)
                    "files" -> FilesScreen(app) { files -> Share.pending.addAll(files); tab = "send" }
                    "usb" -> UsbScreen()
                    "settings" -> SettingsScreen(app)
                    else -> TransferScreen(app) { startDirect() }
                }
                toast?.let { (text, bad) ->
                    Box(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().background(if (bad) Palette.dangerFill else Palette.raised).clickable { Ui.toast.value = null }.padding(14.dp)) {
                        Text(text, style = Type.normal.copy(color = if (bad) Palette.danger else Palette.text))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(Palette.groundDeep).padding(vertical = 6.dp)) {
                listOf("send" to ("send" to "Send"), "chat" to ("chat" to "Chat"), "files" to ("folder" to "Files"), "usb" to ("usb" to "USB")).forEach { (id, v) ->
                    val on = tab == id
                    Column(Modifier.weight(1f).clickable(role = Role.Tab) { tab = id }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MIcon(v.first, if (on) Palette.accent else Palette.textMuted, 24.dp)
                        Text(v.second, style = Type.small.copy(color = if (on) Palette.accent else Palette.textMuted))
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
        ask?.let { AskDialog(it) }
    }
}
