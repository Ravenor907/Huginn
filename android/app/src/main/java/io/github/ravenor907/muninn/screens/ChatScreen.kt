package io.github.ravenor907.muninn.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import java.text.DateFormat
import java.util.Date

/** Where a conversation is open: "" none, "nearby" the room, "code", "add", or a contact's key. */
object ChatNav { var open by mutableStateOf("") }

private fun routeText(r: String) = when (r) {
    "close" -> "Close by (Bluetooth)"
    "wifi" -> "Same Wi-Fi"
    "internet" -> "Over the internet"
    else -> "Not reachable right now"
}
private fun routeIcon(r: String) = when (r) { "close" -> "phone"; "wifi" -> "wifi"; "internet" -> "network"; else -> "user" }

@Composable
fun ChatScreen(app: Muninn) {
    val open = ChatNav.open
    if (open.isNotEmpty()) {
        BackHandler { ChatNav.open = "" }
        when (open) {
            "nearby" -> NearbyRoom(app)
            "code" -> MyCode(app)
            "add" -> AddSomeone(app)
            else -> Conversation(app, open)
        }
        return
    }
    val contacts by app.chat.contacts.all.collectAsState()
    val convos by app.chat.conversations.collectAsState()
    val requests by app.chat.requests.collectAsState()
    val unread by app.chat.unread.collectAsState()
    val close by app.close.list.collectAsState()
    val wifi by app.net.devices.collectAsState()
    val closeError by app.close.error.collectAsState()
    val nostrUp by app.nostr.connected.collectAsState()
    // Everyone reachable right now: close by first, then the same Wi-Fi. (key, name, route)
    val around = buildList {
        close.filter { it.connected && it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.name, "close")) }
        wifi.filter { it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.chatName ?: it.alias, "wifi")) }
    }.distinctBy { it.first }
    val strangers = around.filter { !app.chat.isContact(it.first) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Add someone", Modifier.weight(1f), Kind.Accent, icon = "plus") { ChatNav.open = "add" }
                MButton("My code", Modifier.weight(1f), icon = "user") { ChatNav.open = "code" }
            }
            if (closeError != null) Text(closeError!!, style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 8.dp))
        }
        if (requests.isNotEmpty()) item { Section("Wants to chat") }
        items(requests, key = { "r" + it.peerKey }) { r ->
            Panel(Modifier.padding(bottom = 8.dp)) {
                Text("${r.name} wants to chat", style = Type.big)
                if (r.viaInternet) Text("They have your code. Only accept if you know who it is.", style = Type.small)
                else {
                    Text("Code: ${r.code}", style = Type.title.copy(color = Palette.accent), modifier = Modifier.padding(vertical = 6.dp))
                    Text("Accept if their screen shows the same code.", style = Type.small)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("Accept", kind = Kind.Accent) { app.chat.acceptRequest(r); ChatNav.open = r.peerKey }
                    MButton("Decline") { app.chat.declineRequest(r) }
                }
            }
        }
        item { Section("Chats") { Text(if (nostrUp > 0) "Internet: connected" else "Internet: offline", style = Type.small) } }
        item {
            Line("Nearby room", if (app.identity.nearbyChat) "${around.size} around you · you're “${app.identity.username}”" else "Off", icon = "network") { ChatNav.open = "nearby" }
        }
        val sorted = contacts.sortedByDescending { convos[it.chatKey]?.lastOrNull()?.time ?: 0L }
        items(sorted, key = { "c" + it.chatKey }) { c ->
            val last = convos[c.chatKey]?.lastOrNull()
            val r = app.chat.route(c.chatKey)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Line(c.name, last?.text ?: routeText(r), icon = routeIcon(r),
                        trailing = last?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.time)) } ?: "") { ChatNav.open = c.chatKey }
                }
                if (c.chatKey in unread) Box(Modifier.size(10.dp).background(Palette.accent, CircleShape))
            }
        }
        if (contacts.isEmpty()) item {
            Text("No contacts yet. Meet someone nearby, or swap codes with Add someone and My code. After that you can message them from anywhere.",
                style = Type.normal, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (strangers.isNotEmpty()) item { Section("Around you") }
        items(strangers, key = { "s" + it.first }) { (k, name, r) ->
            Line(name, "${routeText(r)} · tap to add", icon = routeIcon(r)) {
                Ui.sure(Ask("Add $name?", "Code: ${app.chat.codeWith(k)}\n\nTheir screen shows the same code when your first message arrives. " +
                    "Only add them if it matches. Then you can message each other from anywhere.", "It matches",
                    onConfirm = { app.chat.addNearbyPerson(k, name); ChatNav.open = k }))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NearbyRoom(app: Muninn) {
    val msgs by app.chat.nearby.collectAsState()
    var text by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(app.identity.nearbyChat) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding()) {
        Section("Nearby room") { MButton("Back", icon = "back") { ChatNav.open = "" } }
        if (!on) {
            Text("Nearby chat is off, so nobody can see or message you here.", style = Type.normal)
            MButton("Turn on Nearby chat", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { app.identity.nearbyChat = true; on = true; app.net.announce() }
            return@Column
        }
        Text("Everyone close by or on this Wi-Fi with Muninn or Huginn sees this. You're “${app.identity.username}”. Hold a message to mute someone.", style = Type.small)
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = list) {
            items(msgs, key = { it.id }) { m ->
                Bubble(m, showName = true) {
                    if (!m.mine) Ui.sure(Ask("Mute ${m.name}?", "You won't see their messages here. Unmute any time in Settings.", "Mute", onConfirm = { app.chat.mute(m.peerKey, true) }))
                }
            }
        }
        if (msgs.isEmpty()) Text("Say hello.", style = Type.normal, modifier = Modifier.padding(bottom = 8.dp))
        Composer(text, { text = it }, "Message everyone nearby") { app.chat.sendNearby(text); text = "" }
    }
}

@Composable
private fun Conversation(app: Muninn, key: String) {
    val convos by app.chat.conversations.collectAsState()
    val c = app.chat.contacts.get(key)
    val msgs = convos[key].orEmpty()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { app.chat.markRead(key); if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    val route = app.chat.route(key)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding()) {
        Section(c?.name ?: "Chat") { MButton("Back", icon = "back") { ChatNav.open = "" } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(routeIcon(route), Palette.accent, 18.dp)
            Text("  ${routeText(route)} · end-to-end encrypted${if (c?.verified == true) " · verified" else ""}", style = Type.small)
        }
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = list) {
            items(msgs, key = { it.id }) { m -> Bubble(m, showName = false) {} }
        }
        if (msgs.isEmpty()) Text("No messages yet.", style = Type.normal, modifier = Modifier.padding(bottom = 8.dp))
        Composer(text, { text = it }, "Message ${c?.name ?: ""}") { app.chat.sendPrivate(key, text); text = "" }
    }
}

@Composable
private fun Composer(text: String, onText: (String) -> Unit, hint: String, onSend: () -> Unit) {
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(text, { onText(it.take(Chat.MAX)) }, hint, Modifier.weight(1f), single = false)
        MButton("Send", Modifier.padding(start = 8.dp), Kind.Accent, enabled = text.isNotBlank()) { onSend() }
    }
}

@Composable
private fun MyCode(app: Muninn) {
    val context = LocalContext.current
    val code = remember { app.chat.myCode() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Section("My code") { MButton("Back", icon = "back") { ChatNav.open = "" } }
        Text("Anyone with this code can message you from anywhere, as “${app.identity.username}”. Share it only with people you want to hear from.", style = Type.normal)
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) { Qr(code, 240) }
        Text("Their Muninn can scan this with the phone's camera, or you can send it as a message:", style = Type.small)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Share code", Modifier.weight(1f), Kind.Accent, icon = "send") {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Message me on Muninn. Open Muninn, tap Add someone and paste this:\n\n$code"), "Share your Muninn code"))
            }
            MButton("Copy", Modifier.weight(1f), icon = "copy") {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Muninn code", code))
                Ui.say("Code copied.")
            }
        }
    }
}

@Composable
private fun AddSomeone(app: Muninn) {
    val context = LocalContext.current
    var text by remember {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        mutableStateOf(if (clip.contains(Contacts.PREFIX)) clip else "")
    }
    val found = Contacts.parse(text)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Section("Add someone") { MButton("Back", icon = "back") { ChatNav.open = "" } }
        Text("Paste the code they sent you (it starts with MUNINN1). Someone close by? They show up under Around you, no code needed.", style = Type.normal)
        Field(text, { text = it }, "Paste their code", Modifier.padding(vertical = 10.dp), single = false)
        when {
            text.isBlank() -> {}
            found == null -> Text("That isn't a Muninn code. Ask them to tap My code › Share code.", style = Type.small.copy(color = Palette.danger))
            found.chatKey == app.identity.publicKey -> Text("That's your own code.", style = Type.small.copy(color = Palette.danger))
            else -> {
                Text("Found: ${found.name}", style = Type.big)
                MButton("Add ${found.name}", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) {
                    app.chat.addContact(found)
                    Ui.say("${found.name} added. Say hi: they'll get it wherever they are.")
                    ChatNav.open = found.chatKey
                }
            }
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
