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

@Composable
fun SettingsScreen(app: Muninn) {
    val id = app.identity
    var name by remember { mutableStateOf(id.username) }
    var device by remember { mutableStateOf(id.deviceName) }
    var nearby by remember { mutableStateOf(id.nearbyChat) }
    var keep by remember { mutableStateOf(id.keepNearbyHistory) }
    var channel by remember { mutableStateOf(id.channel) }
    var muted by remember { mutableStateOf(id.muted()) }
    val up by app.updater.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Section("Nearby chat")
        Panel {
            ToggleRow("Nearby chat", "Everyone on the same Wi-Fi with Huginn or Muninn can see and message you", nearby) {
                nearby = it; id.nearbyChat = it; app.net.announce()
            }
            Text("Your name in Nearby chat", style = Type.small, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 4.dp)) {
                Field(name, { name = it.take(24) }, "A name, not your real one", Modifier.weight(1f))
                MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = name.isNotBlank() && name != id.username) { id.username = name; app.net.announce(); Ui.say("You're “${id.username}” now.") }
            }
            ToggleRow("Keep Nearby messages", "Off: they clear when Muninn closes", keep) { keep = it; id.keepNearbyHistory = it; if (!it) app.chat.clearNearby() }
            if (muted.isNotEmpty()) MButton("Unmute everyone (${muted.size})", Modifier.fillMaxWidth().padding(top = 6.dp)) { muted.forEach { id.setMuted(it, false) }; muted = id.muted() }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Stable", Modifier.weight(1f), if (channel == "stable") Kind.Accent else Kind.Normal) { channel = "stable"; id.channel = "stable" }
                MButton("Testing", Modifier.weight(1f), if (channel == "testing") Kind.Accent else Kind.Normal) { channel = "testing"; id.channel = "testing" }
            }
            Text("Muninn ${app.updater.current}. ${up.status}", style = Type.normal, modifier = Modifier.padding(top = 8.dp))
            if (up.busy && up.progress > 0f) Bar(up.progress, Modifier.padding(top = 6.dp))
            MButton(if (up.ready) "Install ${up.latest}" else "Check now", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, enabled = !up.busy) {
                if (up.ready) app.updater.download() else app.updater.check()
            }
        }
        Section("About")
        Text("Muninn ${app.updater.current}: Huginn's phone companion. Send and receive with LocalSend, chat, files and USB drives. Made by Ravenor907. GPL-3.0; drive tools from Handheld Forge.", style = Type.small, modifier = Modifier.padding(bottom = 24.dp))
    }
}
