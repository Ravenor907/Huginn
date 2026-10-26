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
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Ui
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.hcBorder
import java.util.Calendar
import java.text.DateFormat
import java.util.Date

/** [name] is what's shown (a nickname if set); [alt] is the name they chose, so search finds either. */
private data class ChatRow(val key: String, val name: String, val alt: String = name)

/** Where a conversation is open: "" none, "nearby" the room, "code", "add", or a contact's key. */
object ChatNav {
    var open by mutableStateOf("")
    /** A code handed in from a scanned QR, waiting on Add someone. */
    var incoming by mutableStateOf("")
    /** The + menu on the chat list (Add someone, My code) is showing. */
    var menu by mutableStateOf(false)
    /** From this width on, the chat list stays beside the conversation; narrower, a conversation takes the whole screen. */
    val WIDE = 600.dp
}

private fun routeText(r: String) = when (r) {
    "close" -> "Close by (Bluetooth)"
    "wifi" -> "Same Wi-Fi"
    "internet" -> "Over the internet"
    else -> "Not reachable right now"
}
/** The few words for a conversation's top bar. */
private fun routeShort(r: String) = when (r) {
    "close" -> "Bluetooth"
    "wifi" -> "Same Wi-Fi"
    "internet" -> "Internet"
    else -> "Not reachable"
}
private fun routeIcon(r: String) = when (r) { "close" -> "phone"; "wifi" -> "wifi"; "internet" -> "network"; else -> "user" }

// The "Quiet" chat look. Every size is dp/sp, so Settings › Accessibility size and text scaling apply.
private val nameStyle get() = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.text)
private val previewStyle get() = TextStyle(fontFamily = Type.body, fontSize = 15.sp, color = Palette.textMuted)
private val metaStyle get() = TextStyle(fontFamily = Type.body, fontSize = 13.sp, color = Palette.textMuted)
private val tinyStyle get() = TextStyle(fontFamily = Type.body, fontSize = 12.sp, color = Palette.textMuted)
private val bodyStyle get() = TextStyle(fontFamily = Type.body, fontSize = 16.sp, color = Palette.text)
private val labelStyle get() = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.5.sp, color = Palette.textMuted)

private fun clock(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
private fun dayIndex(t: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR)
}
/** The chat list's time: the clock today, the date before that. */
private fun when_(t: Long) = if (dayIndex(t) == dayIndex(System.currentTimeMillis())) clock(t) else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t))
private fun dayLabel(t: Long): String {
    val now = System.currentTimeMillis()
    return when (dayIndex(t)) {
        dayIndex(now) -> "Today"
        dayIndex(now - 86_400_000L) -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
    }
}
private fun initial(name: String) = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** What the chat list shows under a name. */
private fun preview(m: Chat.Msg) = when {
    m.unsent.isNotEmpty() -> "Message deleted"
    m.voice > 0 -> "Voice message · ${Voice.length(m.voice)}"
    m.photo.isNotEmpty() -> "Photo"
    else -> m.text.replace('\n', ' ')
}

/** Unread messages: theirs since my last message (at least 1 once the chat is marked unread). */
private fun unreadCount(msgs: List<Chat.Msg>): Int {
    var n = 0
    for (m in msgs.asReversed()) { if (m.mine) break; if (m.unsent.isEmpty()) n++ }
    return maxOf(n, 1)
}

@Composable
fun ChatScreen(app: Muninn, onAllowNearby: () -> Unit = {}) {
    val open = ChatNav.open
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tablets and wide screens: the chat list stays beside the conversation.
        val wide = maxWidth >= ChatNav.WIDE
        if (open.isNotEmpty()) BackHandler { ChatNav.open = "" }
        when {
            open == "code" -> MyCode(app)
            open == "add" -> AddSomeone(app)
            !wide && open == "nearby" -> NearbyRoom(app)
            !wide && open.isNotEmpty() -> androidx.compose.runtime.key(open) { Conversation(app, open) }
            !wide -> ChatList(app, onAllowNearby)
            else -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.42f)) { ChatList(app, onAllowNearby) }
                Box(Modifier.weight(0.58f)) {
                    when (open) {
                        "" -> Text("Pick a chat on the left.", style = Type.normal, modifier = Modifier.align(Alignment.Center))
                        "nearby" -> NearbyRoom(app)
                        else -> androidx.compose.runtime.key(open) { Conversation(app, open) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar(name: String, size: Dp, textSize: TextUnit) {
    Box(Modifier.size(size).clip(CircleShape).background(Palette.goldTint), contentAlignment = Alignment.Center) {
        Text(initial(name), style = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = textSize, color = Palette.goldBright))
    }
}

@Composable
private fun RoomAvatar(size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Palette.coolTint), contentAlignment = Alignment.Center) {
        MIcon("network", Palette.accent, size * 0.46f)
    }
}

@Composable
private fun ListLabel(text: String, top: Dp = 4.dp, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = labelStyle, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp).fillMaxWidth().heightIn(min = 44.dp)
        .clip(shape).background(Palette.field).hcBorder(shape).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MIcon("search", Palette.textMuted, 18.dp, weight = 1.8f)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Search chats", style = bodyStyle.copy(color = Palette.textMuted), maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = bodyStyle, cursorBrush = SolidColor(Palette.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search chats" })
        }
        if (value.isNotEmpty()) IconAction("close", "Clear search", Palette.textMuted, size = 18.dp) { onChange("") }
    }
}

/** One chat in the list: avatar, name and time, then the last message and how many are unread. */
@Composable
private fun PersonRow(name: String, preview: String, time: String, unread: Int, dot: Boolean, avatar: @Composable () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        avatar()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (time.isNotEmpty()) Text(time, style = metaStyle.copy(color = if (unread > 0) Palette.gold else Palette.textMuted),
                    maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(preview, style = previewStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (unread > 0) Box(Modifier.padding(start = 8.dp).heightIn(min = 20.dp).widthIn(min = 20.dp).clip(RoundedCornerShape(10.dp))
                    .background(Palette.gold).padding(horizontal = 6.dp).semantics { contentDescription = "$unread unread" },
                    contentAlignment = Alignment.Center) {
                    Text(if (unread > 99) "99+" else "$unread", style = tinyStyle.copy(fontWeight = FontWeight.SemiBold, color = Palette.onGold))
                } else if (dot) Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(Palette.gold)
                    .semantics { contentDescription = "Waiting on you" })
            }
        }
    }
}

@Composable
private fun ChatList(app: Muninn, onAllowNearby: () -> Unit) {
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
    val room by app.chat.nearby.collectAsState()
    var query by remember { mutableStateOf("") }
    // Everyone reachable right now: close by first, then the same Wi-Fi. (key, name, route)
    val around = buildList {
        close.filter { it.connected && it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.name, "close")) }
        wifi.filter { it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.chatName ?: it.alias, "wifi")) }
    }.distinctBy { it.first }
    // Close-by people already chatting with (not contacts yet) sit with the chats.
    val strangers = around.filter { !app.chat.isContact(it.first) && convos[it.first].isNullOrEmpty() && it.first !in blocked }
        // Contacts, plus anyone else there's a conversation with (met close by, or removed as a contact).
    val chatRows = (contacts.map { ChatRow(it.chatKey, it.shown, it.name) } +
        convos.filter { (k, v) -> v.isNotEmpty() && !app.chat.isContact(k) }.map { (k, _) -> ChatRow(k, met[k]?.name ?: app.chat.nameOf(k)) })
        .filter { it.key !in blocked }
        .sortedByDescending { convos[it.key]?.lastOrNull()?.time ?: 0L }
    // Search: a chat's nickname, the name they chose, or its last message.
    val q = query.trim()
    val shownRows = if (q.isEmpty()) chatRows else chatRows.filter { c ->
        c.name.contains(q, ignoreCase = true) || c.alt.contains(q, ignoreCase = true) || convos[c.key]?.lastOrNull()?.let { preview(it).contains(q, ignoreCase = true) } == true
    }
    val shownStrangers = if (q.isEmpty()) strangers else strangers.filter { it.second.contains(q, ignoreCase = true) }
    val roomPreview = if (app.identity.nearbyChat) "${around.size} around you · you're “${app.identity.username}”" else "Off"
    val showRoom = q.isEmpty() || "Nearby room".contains(q, ignoreCase = true) || roomPreview.contains(q, ignoreCase = true)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SearchBox(query) { query = it }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (closeError != null) Text(closeError!!, style = Type.small.copy(color = Palette.danger),
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onAllowNearby() }.padding(bottom = 8.dp))
                    // Messages only arrive right away if Android lets Muninn run in the background.
                    val ctx = LocalContext.current
                    var free by remember { mutableStateOf(io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx)) }
                    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); free = io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx) } }
                    if (app.identity.stayConnected && !free) Panel(Modifier.padding(bottom = 8.dp)) {
                        Text("Get messages right away", style = Type.big)
                        Text("Android's battery saver pauses Muninn while the phone sleeps, so messages and their notifications arrive late. Allow Muninn to run in the background to fix that.", style = Type.small)
                        MButton("Allow", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { io.github.ravenor907.muninn.net.MessageService.askUnrestricted(ctx) }
                    }
                }
            }
            if (requests.isNotEmpty()) item { ListLabel("Wants to chat") }
            items(requests, key = { "r" + it.peerKey }) { r ->
                Panel(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
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
            item {
                ListLabel("Chats") {
                    // Online: the internet relays are connected. Said in words too, never by the dot's color alone.
                    val on = nostrUp > 0
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Palette.online else Palette.textFaint))
                    Text(if (on) "Online" else "Offline", style = metaStyle, modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (showRoom) item(key = "room") {
                PersonRow("Nearby room", roomPreview, room.lastOrNull()?.let { when_(it.time) } ?: "", 0, false, { RoomAvatar(48.dp) }) { ChatNav.open = "nearby" }
            }
            items(shownRows, key = { "c" + it.key }) { c ->
                val msgs = convos[c.key].orEmpty()
                val last = msgs.lastOrNull()
                PersonRow(c.name, last?.let { preview(it) } ?: routeText(app.chat.route(c.key)), last?.let { when_(it.time) } ?: "",
                    if (c.key in unread) unreadCount(msgs) else 0, app.chat.shouldAskKeep(c.key), { Avatar(c.name, 48.dp, 18.sp) }) { ChatNav.open = c.key }
            }
            if (chatRows.isEmpty()) item {
                Text("No chats yet. Tap anyone under Around you to message them, no asking needed. To message someone far away, tap + to add them or to share your code.",
                    style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (shownRows.isEmpty() && q.isNotEmpty()) item {
                Text("No chats match “$q”.", style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (shownStrangers.isNotEmpty()) item {
                ListLabel("Around you", top = 18.dp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    shownStrangers.forEach { (k, name, r) ->
                        val shape = RoundedCornerShape(20.dp)
                        Row(Modifier.heightIn(min = 40.dp).clip(shape).border(1.dp, if (Access.contrast) Palette.lineMuted else Palette.line, shape)
                            .clickable(role = Role.Button, onClickLabel = "Message $name") { app.chat.meet(k, name); ChatNav.open = k }
                            .semantics { stateDescription = routeText(r) }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            MIcon(routeIcon(r), Palette.accent, 16.dp, weight = 1.8f)
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = bodyStyle.copy(fontSize = 15.sp), maxLines = 1)
                        }
                    }
                }
            }
            // Room for the + button over the end of the list.
            item { Spacer(Modifier.height(96.dp)) }
        }
        // New chat: a small menu with the two ways to start one.
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp)) {
            val shape = RoundedCornerShape(18.dp)
            Box(Modifier.size(58.dp).clip(shape).background(Palette.gold).clickable(role = Role.Button) { ChatNav.menu = true }
                .semantics { contentDescription = "New chat" }, contentAlignment = Alignment.Center) {
                MIcon("plus", Palette.onGold, 26.dp, weight = 2.2f)
            }
            DropdownMenu(expanded = ChatNav.menu, onDismissRequest = { ChatNav.menu = false }, modifier = Modifier.background(Palette.panel)) {
                DropdownMenuItem(text = { Text("Add someone", style = bodyStyle) }, leadingIcon = { MIcon("plus", Palette.accent, 20.dp) },
                    onClick = { ChatNav.menu = false; ChatNav.open = "add" })
                DropdownMenuItem(text = { Text("My code", style = bodyStyle) }, leadingIcon = { MIcon("user", Palette.accent, 20.dp) },
                    onClick = { ChatNav.menu = false; ChatNav.open = "code" })
            }
        }
    }
}

/**
 * A conversation's slim top bar: back, avatar, name and one short status line. Tapping the name or the
 * dots opens [menu]: the full status and the chat's actions.
 */
@Composable
private fun ThreadBar(name: String, status: String, statusIcon: String, statusTint: Color, avatar: @Composable () -> Unit,
                      menu: @Composable (close: () -> Unit) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", "Back to chats", Palette.text, size = 24.dp) { ChatNav.open = "" }
            Row(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Chat details") { showMenu = true }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                avatar()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MIcon(statusIcon, statusTint, 13.dp, weight = 2f)
                        Spacer(Modifier.width(5.dp))
                        Text(status, style = metaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box {
                Column(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { showMenu = true }.semantics { contentDescription = "Chat options" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.5.dp, Alignment.CenterVertically)) {
                    repeat(3) { Box(Modifier.size(3.5.dp).clip(CircleShape).background(Palette.text)) }
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(Palette.panel).widthIn(max = 320.dp)) {
                    menu { showMenu = false }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
    }
}

@Composable
private fun MenuNote(text: String) {
    Text(text, style = Type.small.copy(color = Palette.textSoft), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

@Composable
private fun DayLabel(t: Long) {
    Text(dayLabel(t), style = tinyStyle, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
private fun NearbyRoom(app: Muninn) {
    val msgs by app.chat.nearby.collectAsState()
    var text by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(app.identity.nearbyChat) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.lastOrNull()?.id) { if (msgs.isNotEmpty()) if (Access.reduceMotion) list.scrollToItem(msgs.size - 1) else list.animateScrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        ThreadBar("Nearby room", if (on) "You're “${app.identity.username}”" else "Off", "network", Palette.accent, { RoomAvatar(40.dp) }) {
            MenuNote("Everyone close by or on this Wi-Fi with Muninn or Huginn sees this. You're “${app.identity.username}”. Hold a message to delete it or mute someone.")
        }
        if (!on) {
            Column(Modifier.padding(16.dp)) {
                Text("Nearby chat is off, so nobody can see or message you here.", style = Type.normal)
                MButton("Turn on Nearby chat", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { app.identity.nearbyChat = true; on = true; app.net.announce() }
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            items(msgs.size, key = { msgs[it].id }) { i ->
                val m = msgs[i]
                Column {
                    if (i == 0 || dayIndex(msgs[i - 1].time) != dayIndex(m.time)) DayLabel(m.time)
                    Bubble(m, showName = true) {
                        Ui.sure(Ask("Delete this message?", "It's deleted on this phone only.", "Delete", danger = true,
                            extra = if (m.mine) null else "Mute ${m.name}", onExtra = { app.chat.block(m.peerKey) },
                            onConfirm = { app.chat.deleteNearbyMessage(m.id) }))
                    }
                }
            }
        }
        if (msgs.isEmpty()) Text("Say hello.", style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
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
    val name = c?.shown ?: m?.name ?: app.chat.nameOf(key)
    var renaming by remember { mutableStateOf(false) }
    val msgs = convos[key].orEmpty()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.lastOrNull()?.id) { app.chat.markRead(key); if (msgs.isNotEmpty()) if (Access.reduceMotion) list.scrollToItem(msgs.size - 1) else list.animateScrollToItem(msgs.size - 1) }
    // Redraw as they come into or go out of range (route, and when to ask "Keep in touch?").
    @Suppress("UNUSED_VARIABLE") val closeNow by app.close.list.collectAsState()
    @Suppress("UNUSED_VARIABLE") val wifiNow by app.net.devices.collectAsState()
    val route = app.chat.route(key)
    val verified = app.chat.isVerified(key)
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
    Column(Modifier.fillMaxSize().imePadding()) {
        ThreadBar(name, "Encrypted · ${routeShort(route)}", "shield", Palette.online, { Avatar(name, 40.dp, 16.sp) }) { close ->
            MenuNote("${routeText(route)} · end-to-end encrypted · ${if (verified) "verified" else "not verified"}")
            if (!verified) DropdownMenuItem(text = { Text("Check code", style = bodyStyle) }, leadingIcon = { MIcon("shield", Palette.accent, 20.dp) },
                onClick = {
                    close()
                    Ui.sure(Ask("Check it's really $name", "Code: ${app.chat.codeWith(key)}\n\nAsk them to open your chat and tap Check code. If their screen shows the same code, nobody is in between.",
                        "It matches", onConfirm = { app.chat.verify(key, name) }))
                })
            // A name for them on this phone only: it's never sent.
            if (c != null) DropdownMenuItem(text = { Text("Rename", style = bodyStyle) }, leadingIcon = { MIcon("user", Palette.accent, 20.dp) },
                onClick = { close(); renaming = true })
        }
        if (renaming && c != null) RenameDialog(c) { nick -> if (nick != null) app.chat.rename(key, nick); renaming = false }
        // Their security key changed: say so until the code has been checked.
        if (c?.keyChanged == true) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(io.github.ravenor907.muninn.net.ChatWire.keyChangedText(name), style = Type.small.copy(color = Palette.danger), modifier = Modifier.weight(1f))
            MButton("Check code") {
                Ui.sure(Ask("Check it's really $name", "Code: ${app.chat.codeWith(key)}\n\nAsk them to open your chat and tap Check code. If their screen shows the same code, nobody is in between.",
                    "It matches", onConfirm = { app.chat.verify(key, name) }))
            }
        }
        if (c == null && m != null && app.chat.shouldAskKeep(key)) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            Text(if (m.theySaid) "$name wants to keep in touch" else "Keep in touch with $name?", style = Type.big)
            Text("If you both say yes, you can message each other from anywhere, not just close by.", style = Type.small)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Yes", Modifier.weight(1f), Kind.Accent) { app.chat.keepInTouch(key) }
                MButton("Not now", Modifier.weight(1f)) { app.chat.notNow(key) }
            }
        } else if (c == null && m?.iSaid == true) Text("You said yes to keeping in touch. Waiting for $name.", style = Type.small,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            items(msgs.size, key = { msgs[it].id }) { i ->
                val m = msgs[i]
                Column {
                    if (i == 0 || dayIndex(msgs[i - 1].time) != dayIndex(m.time)) DayLabel(m.time)
                    Bubble(m, showName = false, theirName = name, photo = if (m.photo.isNotEmpty()) ({ app.chat.photo(m.photo) }) else null, onTap = { viewing = m }) {
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
        }
        if (msgs.isEmpty()) Text("No messages yet.", style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        picked?.let { uri ->
            Panel(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
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
        Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.dangerFill)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Palette.danger, CircleShape))
            Text("  ${Voice.length(shown.toInt())}", style = Type.big)
            Spacer(Modifier.weight(1f))
            Text(if (dragged < -120f) "Let go to cancel" else "‹ Slide left to cancel", style = Type.small.copy(color = if (dragged < -120f) Palette.danger else Palette.textSoft))
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = if (onPhoto != null) 6.dp else 12.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom) {
        if (onPhoto != null) { IconAction("image", "Send a picture", Palette.textMuted, size = 24.dp) { onPhoto() }; Spacer(Modifier.width(4.dp)) }
        val shape = RoundedCornerShape(22.dp)
        Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(shape).background(Palette.field).hcBorder(shape).padding(horizontal = 16.dp, vertical = 11.dp),
            contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(hint, style = bodyStyle.copy(color = Palette.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(text, { onText(it.take(Chat.MAX)) }, textStyle = bodyStyle, cursorBrush = SolidColor(Palette.accent), maxLines = 6,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint })
        }
        Spacer(Modifier.width(8.dp))
        if (voice != null && text.isBlank()) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (recording) Palette.danger else Palette.gold)
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
                contentAlignment = Alignment.Center) { MIcon("mic", if (recording) Palette.ground else Palette.onGold, 20.dp, weight = 2f) }
        } else {
            val ready = text.isNotBlank()
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (ready) Palette.gold else Palette.accentFill)
                .clickable(enabled = ready, role = Role.Button) { onSend() }.semantics { contentDescription = "Send" },
                contentAlignment = Alignment.Center) { MIcon("send", if (ready) Palette.onGold else Palette.textFaint, 20.dp, weight = 2f) }
        }
    }
}

@Composable
private fun MyCode(app: Muninn) {
    val context = LocalContext.current
    var code by remember { mutableStateOf(app.chat.myCode()) }
    var left by remember { mutableStateOf(0L) }
    LaunchedEffect(code) { while (true) { left = Contacts.expires(code) - System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Section("My code") { MButton("Back", icon = "back") { ChatNav.open = "" } }
        Text("Anyone with this code can reach you as “${app.identity.username}” for the next hour. After that it stops working, so a leaked code can't be used to spam you. People who already have you keep working.", style = Type.normal)
        Text(if (left > 0) "Good for ${(left + 59_999) / 60_000} more minutes" else "This code has expired",
            style = Type.small.copy(color = if (left > 0) Palette.accent else Palette.danger), modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) { Qr(Contacts.link(code), 240) }
        Text("They scan it with their phone's camera: it opens Muninn, or shows where to download it if they don't have it yet. Or send it as a message:", style = Type.small)
        MButton("New code", Modifier.fillMaxWidth().padding(top = 10.dp)) { code = app.chat.myCode() }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Share code", Modifier.weight(1f), Kind.Accent, icon = "send") {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Message me on Muninn, the private messenger. Tap to add me (or get Muninn first). The link works for an hour:\n${Contacts.link(code)}"), "Share your Muninn code"))
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
            Contacts.expires(text) in 1 until System.currentTimeMillis() -> Text("That code has expired (they last an hour). Ask them for a new one.", style = Type.small.copy(color = Palette.danger))
            found.chatKey == app.identity.publicKey -> Text("That's your own code.", style = Type.small.copy(color = Palette.danger))
            else -> {
                Text("Found: ${found.name}", style = Type.big)
                // Someone already here with a different key: adding this is a key change, so say it first.
                val replaces = app.chat.keyChangeFor(found)
                replaces?.let { old ->
                    Text("You already have ${old.shown} with a different security key. If you add this code, check the code with them before trusting new messages.",
                        style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
                }
                MButton("Add ${found.name}", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) {
                    app.chat.addContact(found)
                    // A key change has its own warning (from Chat): don't cover it.
                    if (replaces == null) Ui.say("${found.name} added. Say hi: they'll get it wherever they are.")
                    ChatNav.open = found.chatKey
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Chat.Msg, showName: Boolean, theirName: String? = null, photo: (() -> ByteArray?)? = null, onTap: () -> Unit = {}, onLong: () -> Unit) {
    val tapNow by rememberUpdatedState(onTap)
    val longNow by rememberUpdatedState(onLong)
    val align = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart
    // The corner nearest the sender is the sharp one.
    val shape = if (m.mine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    val status = when {
        !m.mine -> ""
        m.state == "unsending" -> "Removing from their phone"
        m.unsent.isNotEmpty() -> ""
        m.failed -> "Didn't send"
        m.state == "read" -> "Read"
        m.state == "delivered" -> "Delivered"
        m.state == "sent" -> "Sent"
        m.state == "sending" -> "Sending"
        else -> ""
    }
    val meta = listOf(clock(m.time), status).filter { it.isNotEmpty() }.joinToString(" · ")
    val metaStyle = tinyStyle.copy(color = if (m.failed) Palette.danger else Palette.textMuted)
    val isVoice = m.unsent.isEmpty() && m.voice > 0 && photo != null
    val isPhoto = m.unsent.isEmpty() && photo != null && m.voice == 0
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Box(Modifier.fillMaxWidth(0.8f), contentAlignment = align) {
            Column(
                Modifier.clip(shape).background(if (m.mine) Palette.bubbleMine else Palette.field).hcBorder(shape)
                    .pointerInput(m.id) { detectTapGestures(onTap = { if (photo != null && m.voice == 0) tapNow() }, onLongPress = { longNow() }) },
            ) {
                val name: @Composable () -> Unit = {
                    if (showName && !m.mine) Text(m.name, style = tinyStyle.copy(fontWeight = FontWeight.SemiBold, color = Palette.goldBright), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                when {
                    isVoice -> Column(Modifier.padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 6.dp)) {
                        name()
                        // Mine: length and receipt ("0:04 · Read"). Theirs: length and when it came.
                        VoiceNote(m, photo!!, Voice.length(m.voice) + " · " + (if (m.mine) status.ifEmpty { clock(m.time) } else clock(m.time)), metaStyle)
                    }
                    isPhoto -> Column(Modifier.padding(4.dp)) {
                        Box(Modifier.padding(horizontal = 8.dp)) { name() }
                        val bmp by produceState<ImageBitmap?>(null, m.id) { value = withContext(Dispatchers.IO) { decode(photo!!(), 720) } }
                        val pic = RoundedCornerShape(12.dp)
                        Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(260f / 170f).clip(pic).background(Palette.groundDeep),
                            contentAlignment = Alignment.Center) {
                            bmp?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                ?: Text("Photo", style = Type.small)
                        }
                        Text(meta, style = metaStyle, modifier = Modifier.align(Alignment.End).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp))
                    }
                    else -> Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        name()
                        // The time (and receipt) sits inline after the text, on its last line.
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (m.unsent.isNotEmpty()) Text(if (m.mine) "Message deleted by you" else "Message deleted by ${theirName ?: m.unsent}",
                                style = bodyStyle.copy(color = Palette.textFaint, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                                modifier = Modifier.weight(1f, fill = false))
                            else Text(m.text, style = bodyStyle, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            Text(meta, style = metaStyle, maxLines = 1)
                        }
                    }
                }
            }
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

/** A voice message: a gold play/pause button, a thin progress bar and [label]. Plays from memory only. */
@Composable
private fun VoiceNote(m: Chat.Msg, sound: () -> ByteArray?, label: String, labelStyle: TextStyle) {
    val playing by Voice.playing.collectAsState()
    val progress by Voice.progress.collectAsState()
    val mine = playing == m.id
    LaunchedEffect(mine) { while (mine) { Voice.tick(); kotlinx.coroutines.delay(100) } }
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.gold).clickable(role = Role.Button) {
            if (mine) Voice.stopPlaying()
            else scope.launch { val b = withContext(Dispatchers.IO) { sound() }; if (b != null) Voice.play(m.id, b) else Ui.say("This voice message can't be played.", true) }
        }.semantics { contentDescription = if (mine) "Pause voice message" else "Play voice message" },
            contentAlignment = Alignment.Center) { MIcon(if (mine) "pausefill" else "play", Palette.onGold, 14.dp, filled = true) }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.track)) {
            Box(Modifier.fillMaxWidth(if (mine) progress.coerceIn(0f, 1f) else 0f).height(4.dp).background(Palette.gold))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = labelStyle, maxLines = 1)
    }
}

/** Rename: a nickname kept on this phone only. Empty goes back to the name they chose. */
@Composable
private fun RenameDialog(c: io.github.ravenor907.muninn.net.Contact, done: (String?) -> Unit) {
    var text by remember(c.chatKey) { mutableStateOf(c.nickname ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { done(null) },
        containerColor = Palette.panel,
        title = { Text("Rename ${c.name}", style = Type.big) },
        text = {
            Column {
                Text("Only you see this name. Leave it empty to use “${c.name}”.", style = Type.normal)
                Field(text, { text = it.take(40) }, c.name, Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { MButton("Save", kind = Kind.Accent) { done(text) } },
        dismissButton = { MButton("Cancel") { done(null) } },
    )
}
