package io.github.ravenor907.muninn.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
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

private data class ChatRow(val key: String, val name: String)

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
fun ChatScreen(app: Muninn, onAllowNearby: () -> Unit = {}) {
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
    val met by app.chat.met.collectAsState()
    val blocked by app.chat.blocked.collectAsState()
    // Everyone reachable right now: close by first, then the same Wi-Fi. (key, name, route)
    val around = buildList {
        close.filter { it.connected && it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.name, "close")) }
        wifi.filter { it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.chatName ?: it.alias, "wifi")) }
    }.distinctBy { it.first }
    // Close-by people already chatting with (not contacts yet) sit with the chats.
    val strangers = around.filter { !app.chat.isContact(it.first) && convos[it.first].isNullOrEmpty() && it.first !in blocked }
        val chatRows = (contacts.map { ChatRow(it.chatKey, it.name) } +
        met.filter { (k, _) -> !convos[k].isNullOrEmpty() && !app.chat.isContact(k) }.map { (k, m) -> ChatRow(k, m.name) })
        .filter { it.key !in blocked }
        .sortedByDescending { convos[it.key]?.lastOrNull()?.time ?: 0L }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Add someone", Modifier.weight(1f), Kind.Accent, icon = "plus") { ChatNav.open = "add" }
                MButton("My code", Modifier.weight(1f), icon = "user") { ChatNav.open = "code" }
            }
            if (closeError != null) Text(closeError!!, style = Type.small.copy(color = Palette.danger),
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onAllowNearby() }.padding(top = 8.dp, bottom = 4.dp))
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
        items(chatRows, key = { "c" + it.key }) { c ->
            val last = convos[c.key]?.lastOrNull()
            val r = app.chat.route(c.key)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Line(c.name, last?.text ?: routeText(r), icon = routeIcon(r),
                        trailing = last?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.time)) } ?: "") { ChatNav.open = c.key }
                }
                if (c.key in unread || app.chat.shouldAskKeep(c.key)) Box(Modifier.size(10.dp).background(Palette.accent, CircleShape))
            }
        }
        if (chatRows.isEmpty()) item {
            Text("No chats yet. Tap anyone under Around you to message them, no asking needed. To message someone far away, swap codes with Add someone and My code.",
                style = Type.normal, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (strangers.isNotEmpty()) item { Section("Around you") }
        items(strangers, key = { "s" + it.first }) { (k, name, r) ->
            Line(name, "${routeText(r)} · tap to message", icon = routeIcon(r)) { app.chat.meet(k, name); ChatNav.open = k }
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
        Text("Everyone close by or on this Wi-Fi with Muninn or Huginn sees this. You're “${app.identity.username}”. Hold a message to delete it or mute someone.", style = Type.small)
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = list) {
            items(msgs, key = { it.id }) { m ->
                Bubble(m, showName = true) {
                    Ui.sure(Ask("Delete this message?", "It's deleted on this phone only.", "Delete", danger = true,
                        extra = if (m.mine) null else "Mute ${m.name}", onExtra = { app.chat.block(m.peerKey) },
                        onConfirm = { app.chat.deleteNearbyMessage(m.id) }))
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
    val met by app.chat.met.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // redraw when they become a contact
    val m = met[key]
    val name = c?.name ?: m?.name ?: app.chat.nameOf(key)
    val msgs = convos[key].orEmpty()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { app.chat.markRead(key); if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    val route = app.chat.route(key)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding()) {
        Section(name) { MButton("Back", icon = "back") { ChatNav.open = "" } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(routeIcon(route), Palette.accent, 18.dp)
            Text("  ${routeText(route)} · end-to-end encrypted · ${if (app.chat.isVerified(key)) "verified" else "not verified"}", style = Type.small, modifier = Modifier.weight(1f))
        }
        if (!app.chat.isVerified(key)) Row(Modifier.padding(top = 6.dp)) {
            MButton("Check code") {
                Ui.sure(Ask("Check it's really $name", "Code: ${app.chat.codeWith(key)}\n\nAsk them to open your chat and tap Check code. If their screen shows the same code, nobody is in between.",
                    "It matches", onConfirm = { app.chat.verify(key, name) }))
            }
        }
        if (c == null && m != null && app.chat.shouldAskKeep(key)) Panel(Modifier.padding(top = 8.dp)) {
            Text(if (m.theySaid) "$name wants to keep in touch" else "Keep in touch with $name?", style = Type.big)
            Text("If you both say yes, you can message each other from anywhere, not just close by.", style = Type.small)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Yes", Modifier.weight(1f), Kind.Accent) { app.chat.keepInTouch(key) }
                MButton("Not now", Modifier.weight(1f)) { app.chat.notNow(key) }
            }
        } else if (c == null && m?.iSaid == true) Text("You said yes to keeping in touch. Waiting for $name.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = list) {
            items(msgs, key = { it.id }) { m ->
                Bubble(m, showName = false) {
                    Ui.sure(Ask("Delete this message?", "It's deleted on this phone only.", "Delete", danger = true,
                        extra = if (m.mine && m.failed) "Try again" else null, onExtra = { app.chat.retry(key, m.id) },
                        onConfirm = { app.chat.deleteMessage(key, m.id) }))
                }
            }
        }
        if (msgs.isEmpty()) Text("No messages yet.", style = Type.normal, modifier = Modifier.padding(bottom = 8.dp))
        Composer(text, { text = it }, "Message $name") { app.chat.sendPrivate(key, text); text = "" }
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
            val status = when {
                !m.mine -> ""
                m.failed -> " · Didn't send"
                m.state == "read" -> " · Read"
                m.state == "delivered" -> " · Delivered"
                m.state == "sent" -> " · Sent"
                m.state == "sending" -> " · Sending"
                else -> ""
            }
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.time)) + status,
                style = Type.small.copy(color = when { m.failed -> Palette.danger; m.state == "read" -> Palette.accent; else -> Palette.textFaint }))
        }
    }
}
