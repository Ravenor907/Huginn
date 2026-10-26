package io.github.ravenor907.muninn.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.files.Files
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import java.io.File

/** Files shared into Muninn from another app ("Share" › Muninn), waiting to be sent. */
object Share {
    val pending = androidx.compose.runtime.mutableStateListOf<File>()
}

@Composable
fun TransferScreen(app: Muninn, askDirectPermission: () -> Unit) {
    val devices by app.net.devices.collectAsState()
    val transfer by app.net.transfer.collectAsState()
    val items by app.net.items.collectAsState()
    val scanning by app.net.scanning.collectAsState()
    val error by app.net.error.collectAsState()
    val direct by app.direct.state.collectAsState()
    val browser = remember { BrowserState(Files.downloads) }
    var picking by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Share.pending.size) {
        if (Share.pending.isNotEmpty()) { browser.picked.addAll(Share.pending.filterNot { browser.picked.contains(it) }); Share.pending.clear(); picking = true }
    }

    fun sendTo(d: LocalSend.Device) {
        val files = browser.picked.flatMap { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.toList() else listOf(it) }
        if (files.isEmpty()) { picking = true; Ui.say("Pick files to send, then choose ${d.alias}."); return }
        app.net.send(d.id, files)
        browser.picked.clear()
        picking = false
    }

    if (picking) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Section("Pick files to send") { MButton("Done", kind = Kind.Accent) { picking = false } }
            FileBrowser(browser, Modifier.weight(1f))
            if (browser.picked.isNotEmpty()) {
                Text("Send to:", style = Type.small, modifier = Modifier.padding(top = 8.dp))
                devices.take(4).forEach { d -> Line(d.alias, d.model.ifEmpty { d.ip }, icon = if (d.type == "mobile") "phone" else "network") { sendTo(d) } }
                if (devices.isEmpty()) Text("No devices nearby yet. Go back and press Look again.", style = Type.small)
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            if (transfer.active) {
                Panel(Modifier.padding(top = 12.dp)) {
                    Text(transfer.status, style = Type.big)
                    Spacer(Modifier.height(8.dp))
                    Bar(transfer.fraction)
                    Text("${LocalSend.size(transfer.done)} of ${LocalSend.size(transfer.total)}", style = Type.small, modifier = Modifier.padding(top = 6.dp))
                    if (transfer.sending) MButton("Stop sending", Modifier.padding(top = 8.dp)) { app.net.cancelSend() }
                }
            }
            Section("Nearby") { MButton(if (scanning) "Looking…" else "Look again", enabled = !scanning) { app.net.refresh() } }
            if (error != null) Text(error!!, style = Type.normal.copy(color = Palette.danger))
            if (devices.isEmpty() && error == null) Text(
                "No devices yet. Open Huginn, Muninn or the free LocalSend app on another device on the same Wi-Fi, then press Look again. " +
                    "Not on the same Wi-Fi? Use Direct connect below. This phone shows up as “${app.identity.deviceName}”.",
                style = Type.normal,
            )
        }
        items(devices, key = { it.id }) { d ->
            Panel(Modifier.padding(bottom = 8.dp), onClick = { sendTo(d) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MIcon(if (d.type == "mobile") "phone" else "network", Palette.accent, 28.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(d.alias, style = Type.big)
                        Text(listOfNotNull(d.app?.replaceFirstChar { it.uppercase() } ?: "LocalSend", d.model.ifEmpty { null }, d.ip).joinToString(" · "), style = Type.small)
                    }
                    Text("Send", style = Type.small.copy(color = Palette.accent))
                }
            }
        }
        item {
            MButton(if (browser.picked.isEmpty()) "Pick files to send" else "${browser.picked.size} picked · change", Modifier.fillMaxWidth().padding(top = 4.dp), Kind.Accent, icon = "send") { picking = true }
            Section("Direct connect")
            Panel {
                if (!direct.on) {
                    Text("No shared Wi-Fi? Muninn can make a private hotspot (no internet, no data used). The other phone scans the code with its camera to join, then sending and chat work as usual.", style = Type.normal)
                    if (direct.error != null) Text(direct.error!!, style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
                    MButton(if (direct.starting) "Starting…" else "Start Direct connect", Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !direct.starting, icon = "wifi") { askDirectPermission() }
                    Text("For the handheld: start Direct connect in Huginn's Phone Transfer and scan its code with this phone's camera.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text("Scan with the other phone's camera", style = Type.big)
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Qr(direct.qr, 220) }
                    Text("Wi-Fi: ${direct.ssid}", style = Type.normal)
                    Text("Password: ${direct.password}", style = Type.normal)
                    MButton("Stop Direct connect", Modifier.fillMaxWidth().padding(top = 10.dp)) { app.direct.stop() }
                }
            }
            Section("Received") { Text("Saved in ${app.identity.receiveFolder.name}", style = Type.small) }
            if (items.isEmpty()) Text("Nothing yet. Files show up here as they arrive, after you accept them.", style = Type.normal)
        }
        items(items) { it ->
            Column(Modifier.padding(bottom = 8.dp)) {
                Row { Text(it.name, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f), maxLines = 1); Text(it.status, style = Type.small.copy(color = if (it.failed) Palette.danger else Palette.textSoft)) }
                Bar(it.fraction, Modifier.padding(top = 4.dp), if (it.fraction >= 1f) Palette.textFaint else Palette.accent)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun Qr(text: String, sizeDp: Int) {
    val bmp = remember(text) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
        Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565).apply {
            for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) 0xFF101112.toInt() else 0xFFE4E1DA.toInt())
        }
    }
    Box(Modifier.size((sizeDp + 20).dp).background(Palette.text), contentAlignment = Alignment.Center) {
        Image(bmp.asImageBitmap(), contentDescription = "Wi-Fi code", modifier = Modifier.size(sizeDp.dp))
    }
}
