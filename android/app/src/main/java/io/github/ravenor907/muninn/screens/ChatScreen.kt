package io.github.ravenor907.muninn.screens

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.ravenor907.muninn.net.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.ravenor907.muninn.net.Voice
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
object ChatNav {
    var open by mutableStateOf("")
    /** A code handed in from a scanned QR, waiting on Add someone. */
    var incoming by mutableStateOf("")
    /** Scrolling a conversation hides the logo, Settings and tabs; back brings them back first. */
    var full by mutableStateOf(false)
}

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
            else -> androidx.compose.runtime.key(open) { Conversation(app, open) }
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
        // Contacts, plus anyone else there's a conversation with (met close by, or removed as a contact).
    val chatRows = (contacts.map { ChatRow(it.chatKey, it.name) } +
        convos.filter { (k, v) -> v.isNotEmpty() && !app.chat.isContact(k) }.map { (k, _) -> ChatRow(k, met[k]?.name ?: app.chat.nameOf(k)) })
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
            // Messages only arrive right away if Android lets Muninn run in the background.
            val ctx = LocalContext.current
            var free by remember { mutableStateOf(io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx)) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); free = io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx) } }
            if (app.identity.stayConnected && !free) Panel(Modifier.padding(top = 8.dp)) {
                Text("Get messages right away", style = Type.big)
                Text("Android's battery saver pauses Muninn while the phone sleeps, so messages and their notifications arrive late. Allow Muninn to run in the background to fix that.", style = Type.small)
                MButton("Allow", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { io.github.ravenor907.muninn.net.MessageService.askUnrestricted(ctx) }
            }
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
                    Line(c.name, last?.let { if (it.unsent.isNotEmpty()) "Message deleted" else if (it.voice > 0) (if (it.mine) "You sent a voice message" else "Voice message") else if (it.photo.isNotEmpty()) (if (it.mine) "You sent a photo" else "Photo") else it.text } ?: routeText(r), icon = routeIcon(r),
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
    LaunchedEffect(msgs.lastOrNull()?.id) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
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
    // First back: bring the logo and tabs back. Second back: the chat list (the outer handler).
    BackHandler(enabled = ChatNav.full) { ChatNav.full = false }
    DisposableEffect(key) { onDispose { ChatNav.full = false } }
    val convos by app.chat.conversations.collectAsState()
    val c = app.chat.contacts.get(key)
    val met by app.chat.met.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // redraw when they become a contact
    val m = met[key]
    val name = c?.name ?: m?.name ?: app.chat.nameOf(key)
    val msgs = convos[key].orEmpty()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.lastOrNull()?.id) { app.chat.markRead(key); if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    // Redraw as they come into or go out of range (route, and when to ask "Keep in touch?").
    @Suppress("UNUSED_VARIABLE") val closeNow by app.close.list.collectAsState()
    @Suppress("UNUSED_VARIABLE") val wifiNow by app.net.devices.collectAsState()
    val route = app.chat.route(key)
    val context = LocalContext.current
    // While this chat is on screen, new messages here just buzz instead of notifying; its old notification goes.
    DisposableEffect(key) {
        app.openChat = key
        androidx.core.app.NotificationManagerCompat.from(context).cancel(key.hashCode())
        onDispose { if (app.openChat == key) app.openChat = "" }
    }
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Uri?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Chat.Msg?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) picked = uri }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Ui.say(if (ok) "Ready. Hold the mic to record." else "Voice messages need the microphone. Muninn only listens while you hold the button.", !ok)
    }
    DisposableEffect(Unit) { onDispose { Voice.stopPlaying(); Voice.cancel() } }
    fun sendPhoto(uri: Uri, hd: Boolean) {
        preparing = true
        scope.launch {
            val jpeg = withContext(Dispatchers.Default) { Photos.prepare(context, uri, hd) }
            if (jpeg == null) Ui.say("That photo couldn't be opened.", true)
            else withContext(Dispatchers.IO) { app.chat.sendPhoto(key, jpeg) }
            preparing = false; picked = null
        }
    }
    viewing?.let { v ->
        BackHandler { viewing = null }
        PhotoViewer(app, v) { viewing = null }
        return
    }
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
        // Scrolling by hand gives the conversation the whole screen.
        val hide = remember { object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && kotlin.math.abs(available.y) > 6f) ChatNav.full = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        } }
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp).nestedScroll(hide), state = list) {
            items(msgs, key = { it.id }) { m ->
                Bubble(m, showName = false, photo = if (m.photo.isNotEmpty()) ({ app.chat.photo(m.photo) }) else null, onTap = { viewing = m }) {
                    when {
                        // Mine, and it got out: take it back from both phones, or just remove it here.
                        m.mine && m.unsent.isEmpty() && !m.failed -> Ui.sure(Ask("Unsend this message?",
                            "Unsend removes it from both phones. $name will see “Message deleted by ${app.identity.username}”.",
                            "Unsend", danger = true, extra = "Delete for me", onExtra = { app.chat.deleteMessage(key, m.id) },
                            onConfirm = { app.chat.unsend(key, m.id) }))
                        m.mine && m.failed -> Ui.sure(Ask("This message didn't send", "Try again, or delete it.", "Delete", danger = true,
                            extra = "Try again", onExtra = { app.chat.retry(key, m.id) }, onConfirm = { app.chat.deleteMessage(key, m.id) }))
                        else -> Ui.sure(Ask("Delete this message?", "It's deleted on this phone only.", "Delete", danger = true,
                            onConfirm = { app.chat.deleteMessage(key, m.id) }))
                    }
                }
            }
        }
        if (msgs.isEmpty()) Text("No messages yet.", style = Type.normal, modifier = Modifier.padding(bottom = 8.dp))
        picked?.let { uri ->
            Panel(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UriThumb(uri, Modifier.size(72.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (preparing) "Getting it ready…" else "Send this photo?", style = Type.big)
                        Text("Location and camera details are removed.", style = Type.small)
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("Cancel", Modifier.weight(1f), enabled = !preparing) { picked = null }
                    MButton("High quality", Modifier.weight(1f), enabled = !preparing) { sendPhoto(uri, true) }
                    MButton("Send", Modifier.weight(1f), Kind.Accent, enabled = !preparing) { sendPhoto(uri, false) }
                }
                Text("Send: quick anywhere. High quality: sharper, best close by or on Wi-Fi; up to a minute over the internet.",
                    style = Type.small, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Composer(text, { text = it }, "Message $name", onPhoto = {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, voice = VoiceHooks(
            allowed = { androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            ask = { micAsk.launch(android.Manifest.permission.RECORD_AUDIO) },
            send = { aac, ms -> scope.launch(Dispatchers.IO) { app.chat.sendVoice(key, aac, ms) } },
        )) { app.chat.sendPrivate(key, text); text = "" }
    }
}

private class VoiceHooks(val allowed: () -> Boolean, val ask: () -> Unit, val send: (ByteArray, Int) -> Unit)

@Composable
private fun Composer(text: String, onText: (String) -> Unit, hint: String, onPhoto: (() -> Unit)? = null, voice: VoiceHooks? = null, onSend: () -> Unit) {
    val context = LocalContext.current
    val recording by Voice.recording.collectAsState()
    var dragged by remember { mutableStateOf(0f) }
    var shown by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) { while (recording) { shown = Voice.elapsed(); kotlinx.coroutines.delay(200) } }
    if (voice != null && recording) {
        // Recording: hold the mic, let go to send, slide left to cancel.
        Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().background(Palette.dangerFill, Corner).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Palette.danger, CircleShape))
            Text("  ${Voice.length(shown.toInt())}", style = Type.big)
            Spacer(Modifier.weight(1f))
            Text(if (dragged < -120f) "Let go to cancel" else "‹ Slide left to cancel", style = Type.small.copy(color = if (dragged < -120f) Palette.danger else Palette.textSoft))
        }
    }
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onPhoto != null) Box(Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = "Send a photo") { onPhoto() }, contentAlignment = Alignment.Center) {
            MIcon("image", Palette.accent, 26.dp)
        }
        Field(text, { onText(it.take(Chat.MAX)) }, hint, Modifier.weight(1f), single = false)
        if (voice != null && text.isBlank()) {
            Box(Modifier.padding(start = 8.dp).size(48.dp).background(if (recording) Palette.danger else Palette.accentFill, CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!voice.allowed()) { voice.ask(); return@awaitEachGesture }
                        dragged = 0f
                        Voice.onLimit = { Voice.finish()?.let { (aac, ms) -> voice.send(aac, ms); Ui.say("5 minutes is the most: sent.") } }
                        if (!Voice.start(context)) { Ui.say("The microphone isn't available right now.", true); return@awaitEachGesture }
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            dragged = c.position.x - down.position.x
                            if (!c.pressed) break
                        }
                        if (!Voice.recording.value) { dragged = 0f; return@awaitEachGesture }   // the 5-minute limit already sent it
                        if (dragged < -120f) { Voice.cancel(); Ui.say("Recording cancelled.") }
                        else Voice.finish()?.let { (aac, ms) -> voice.send(aac, ms) } ?: Ui.say("Hold the mic while you talk.")
                        dragged = 0f
                    }
                }.semantics { contentDescription = "Hold to record a voice message" },
                contentAlignment = Alignment.Center) { MIcon("mic", if (recording) Palette.ground else Palette.accent, 24.dp) }
        } else MButton("Send", Modifier.padding(start = 8.dp), Kind.Accent, enabled = text.isNotBlank()) { onSend() }
    }
}

@Composable
private fun MyCode(app: Muninn) {
    val context = LocalContext.current
    val code = remember { app.chat.myCode() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Section("My code") { MButton("Back", icon = "back") { ChatNav.open = "" } }
        Text("Anyone with this code can message you from anywhere, as “${app.identity.username}”. Share it only with people you want to hear from.", style = Type.normal)
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) { Qr(Contacts.link(code), 240) }
        Text("They scan it with their phone's camera: it opens Muninn, or shows where to download it if they don't have it yet. Or send it as a message:", style = Type.small)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Share code", Modifier.weight(1f), Kind.Accent, icon = "send") {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Message me on Muninn, the private messenger. Tap to add me (or get Muninn first):\n${Contacts.link(code)}"), "Share your Muninn code"))
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
        mutableStateOf(ChatNav.incoming.ifEmpty { if (clip.contains(Contacts.PREFIX)) clip else "" }.also { ChatNav.incoming = "" })
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
private fun Bubble(m: Chat.Msg, showName: Boolean, photo: (() -> ByteArray?)? = null, onTap: () -> Unit = {}, onLong: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        val tapNow by rememberUpdatedState(onTap)
        val longNow by rememberUpdatedState(onLong)
        Column(
            Modifier.widthIn(max = 300.dp).background(if (m.mine) Palette.accentFill else Palette.panel, Corner)
                .pointerInput(m.id) { detectTapGestures(onTap = { if (photo != null && m.voice == 0) tapNow() }, onLongPress = { longNow() }) }.padding(10.dp),
        ) {
            if (showName && !m.mine) Text(m.name, style = Type.small.copy(color = Palette.accent))
            if (m.unsent.isEmpty() && m.voice > 0 && photo != null) {
                VoiceNote(m, photo)
            } else if (m.unsent.isNotEmpty()) {
                Text(if (m.mine) "Message deleted by you" else "Message deleted by ${m.unsent}",
                    style = Type.normal.copy(color = Palette.textFaint, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
            } else if (photo != null && m.voice == 0) {
                val bmp by produceState<ImageBitmap?>(null, m.id) { value = withContext(Dispatchers.IO) { decode(photo(), 720) } }
                Box(Modifier.size(240.dp, 180.dp).background(Palette.groundDeep, Corner), contentAlignment = Alignment.Center) {
                    bmp?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(Corner)) }
                        ?: Text("Photo", style = Type.small)
                }
            } else Text(m.text, style = Type.normal.copy(color = Palette.text))
            val status = when {
                !m.mine -> ""
                m.state == "unsending" -> " · Removing from their phone"
                m.unsent.isNotEmpty() -> ""
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

/** Decodes a JPEG, no bigger than [max] pixels on the long side. */
private fun decode(bytes: ByteArray?, max: Int): ImageBitmap? {
    if (bytes == null) return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
private fun UriThumb(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { try { context.contentResolver.openInputStream(uri)?.use { decode(it.readBytes(), 200) } } catch (e: Exception) { null } }
    }
    Box(modifier.background(Palette.groundDeep, Corner), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(Corner)) }
    }
}

/** A photo full screen: Back, or Save to the phone's Pictures/Muninn. */
@Composable
private fun PhotoViewer(app: Muninn, m: Chat.Msg, close: () -> Unit) {
    val context = LocalContext.current
    val bytes = remember(m.id) { app.chat.photo(m.photo) }
    val bmp = remember(m.id) { decode(bytes, 2048) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Back", icon = "back") { close() }
            Spacer(Modifier.weight(1f))
            MButton("Save to phone", kind = Kind.Accent, icon = "download", enabled = bytes != null) {
                Ui.say(if (bytes != null && savePhoto(context, bytes)) "Saved in Pictures › Muninn." else "Couldn't save it.", bytes == null)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: Text("This photo can't be shown.", style = Type.normal)
        }
    }
}

private fun savePhoto(context: Context, bytes: ByteArray): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Muninn-${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Muninn")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    uri != null && context.contentResolver.openOutputStream(uri)?.use { it.write(bytes); true } == true
} catch (e: Exception) { false }

/** A voice message: play/pause, how far along, and its length. Plays from memory only. */
@Composable
private fun VoiceNote(m: Chat.Msg, sound: () -> ByteArray?) {
    val playing by Voice.playing.collectAsState()
    val progress by Voice.progress.collectAsState()
    val mine = playing == m.id
    LaunchedEffect(mine) { while (mine) { Voice.tick(); kotlinx.coroutines.delay(100) } }
    val scope = rememberCoroutineScope()
    Row(Modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(Palette.groundDeep, CircleShape).clickable(role = Role.Button, onClickLabel = if (mine) "Pause" else "Play") {
            if (mine) Voice.stopPlaying()
            else scope.launch { val b = withContext(Dispatchers.IO) { sound() }; if (b != null) Voice.play(m.id, b) else Ui.say("This voice message can't be played.", true) }
        }, contentAlignment = Alignment.Center) { MIcon(if (mine) "pause" else "play", Palette.accent, 20.dp) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Palette.line)) {
                Box(Modifier.fillMaxWidth(if (mine) progress.coerceIn(0f, 1f) else 0f).height(4.dp).background(Palette.accent))
            }
            Text("Voice message · ${Voice.length(m.voice)}", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
