package io.github.ravenor907.muninn.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import java.text.DateFormat
import java.util.Date

@Composable
fun ChatScreen(app: Muninn) {
    var tab by remember { mutableStateOf(0) }      // 0 Nearby, 1 Private
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }   // private conversation: key, name
    val requests by app.chat.requests.collectAsState()

    if (open != null) {
        BackHandler { open = null }
        Conversation(app, open!!.first, open!!.second) { open = null }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Nearby", Modifier.weight(1f), if (tab == 0) Kind.Accent else Kind.Normal, icon = "chat") { tab = 0 }
            MButton(if (requests.isEmpty()) "Private" else "Private (${requests.size})", Modifier.weight(1f), if (tab == 1) Kind.Accent else Kind.Normal, icon = "lock") { tab = 1 }
        }
        if (tab == 0) Nearby(app, Modifier.weight(1f)) else PrivateList(app, Modifier.weight(1f)) { k, n -> open = k to n }
    }
}

@Composable
private fun Nearby(app: Muninn, modifier: Modifier) {
    val msgs by app.chat.nearby.collectAsState()
    val devices by app.net.devices.collectAsState()
    var text by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(app.identity.nearbyChat) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    val people = devices.count { it.canChat && it.chatName != null }
    Column(modifier.imePadding()) {
        Section("Nearby chat") { Text(if (on) "You're “${app.identity.username}” · $people here" else "Off", style = Type.small) }
        if (!on) {
            Text("Nearby chat is off, so nobody can see or message you here. Turn it back on in Settings.", style = Type.normal)
            MButton("Turn on Nearby chat", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { app.identity.nearbyChat = true; on = true; app.net.announce() }
            return@Column
        }
        Text("Everyone on this Wi-Fi with Huginn or Muninn sees what you post here. Hold a message to mute that person.", style = Type.small)
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = list) {
            items(msgs, key = { it.id }) { m ->
                Bubble(m, showName = true) {
                    if (!m.mine) Ui.sure(Ask("Mute ${m.name}?", "You won't see their Nearby messages. Unmute any time in Settings.", "Mute", onConfirm = { app.chat.mute(m.peerKey, true) }))
                }
            }
        }
        if (msgs.isEmpty()) Text(if (people == 0) "Nobody else is here yet." else "Say hello.", style = Type.normal, modifier = Modifier.padding(bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(text, { text = it.take(Chat.MAX) }, "Message everyone nearby", Modifier.weight(1f), single = false)
            MButton("Send", Modifier.padding(start = 8.dp), Kind.Accent, enabled = text.isNotBlank()) { app.chat.sendNearby(text); text = "" }
        }
    }
}

@Composable
private fun PrivateList(app: Muninn, modifier: Modifier, onOpen: (String, String) -> Unit) {
    val requests by app.chat.requests.collectAsState()
    val convos by app.chat.conversations.collectAsState()
    val devices by app.net.devices.collectAsState()
    LazyColumn(modifier) {
        if (requests.isNotEmpty()) item { Section("Wants to chat") }
        items(requests, key = { "r" + it.peerKey }) { r ->
            Panel(Modifier.padding(bottom = 8.dp)) {
                Text("${r.name} wants to chat privately", style = Type.big)
                Text("Code: ${r.code}", style = Type.title.copy(color = Palette.accent), modifier = Modifier.padding(vertical = 6.dp))
                Text("Accept only if their screen shows the same code.", style = Type.small)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("Codes match", kind = Kind.Accent) { app.chat.acceptPair(r) }
                    MButton("Decline") { app.chat.declinePair(r) }
                }
            }
        }
        item { Section("Conversations") }
        val keys = convos.keys.toList()
        if (keys.isEmpty()) item { Text("None yet. Pick someone below to start.", style = Type.normal) }
        items(keys, key = { "c$it" }) { k ->
            val last = convos[k]?.lastOrNull()
            val name = app.identity.trusted()[k] ?: devices.firstOrNull { it.chatKey == k }?.alias ?: "Someone"
            Line(name, last?.text ?: "", icon = "user", trailing = last?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.time)) } ?: "") { onOpen(k, name) }
        }
        item { Section("Nearby with Huginn or Muninn") }
        val chatters = devices.filter { it.canChat }
        if (chatters.isEmpty()) item { Text("Nobody nearby. Private chat works on the same Wi-Fi or over Direct connect.", style = Type.normal) }
        items(chatters, key = { "d" + it.id }) { d ->
            Line(d.alias, if (app.chat.isTrusted(d.chatKey!!)) "Verified" else "Not verified yet", icon = if (d.type == "mobile") "phone" else "network") {
                val key = d.chatKey
                if (app.chat.isTrusted(key)) onOpen(key, d.alias)
                else Ui.sure(Ask("Check this code with ${d.alias}", "Code: ${app.chat.codeWith(key)}\n\nTheir screen shows the same code when your first message arrives. " +
                    "Only continue if it matches. That's how you know nobody is in between.", "It matches", onConfirm = { onOpen(key, d.alias) }))
            }
        }
    }
}

@Composable
private fun Conversation(app: Muninn, key: String, name: String, onBack: () -> Unit) {
    val convos by app.chat.conversations.collectAsState()
    val msgs = convos[key].orEmpty()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding()) {
        Section(name) { MButton("Back", icon = "back") { onBack() } }
        Text("Private and verified: only you two can read this.", style = Type.small)
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = list) {
            items(msgs, key = { it.id }) { m -> Bubble(m, showName = false) {} }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(text, { text = it.take(Chat.MAX) }, "Message $name", Modifier.weight(1f), single = false)
            MButton("Send", Modifier.padding(start = 8.dp), Kind.Accent, enabled = text.isNotBlank()) { app.chat.sendPrivate(key, name, text); text = "" }
        }
    }
}

@Composable
private fun Bubble(m: Chat.Msg, showName: Boolean, onLong: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp).background(if (m.mine) Palette.accentFill else Palette.panel, Corner)
                .pointerInput(m.id) { detectTapGestures(onLongPress = { onLong() }) }.padding(10.dp),
        ) {
            if (showName && !m.mine) Text(m.name, style = Type.small.copy(color = Palette.accent))
            Text(m.text, style = Type.normal.copy(color = Palette.text))
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.time)) + if (m.failed) " · didn't send" else "",
                style = Type.small.copy(color = if (m.failed) Palette.danger else Palette.textFaint))
        }
    }
}
