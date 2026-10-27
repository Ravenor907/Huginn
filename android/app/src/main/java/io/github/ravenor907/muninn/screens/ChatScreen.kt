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
import io.github.ravenor907.muninn.net.Folders
import io.github.ravenor907.muninn.net.Groups
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.call.RoomLogic
import io.github.ravenor907.muninn.net.call.RoomManager
import io.github.ravenor907.muninn.ui.Section
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.selected
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Ui
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.hcBorder
import java.util.Calendar
import java.text.DateFormat
import java.util.Date

/** [name] is what's shown (a nickname if set); [alt] is the name they chose, so search finds either. [group]: a group's id. */
private data class ChatRow(val key: String, val name: String, val alt: String = name, val group: String? = null)

/**
 * Where a conversation is open: "" none, "nearby" the room, "code", "add", "newgroup", a contact's key,
 * "g:<group id>" a group, or "g:<group id>/settings" its settings.
 */
object ChatNav {
    var open by mutableStateOf("")
    /** A code handed in from a scanned QR, waiting on Add someone. */
    var incoming by mutableStateOf("")
    /** The + menu on the chat list (Add someone, My code) is showing. */
    var menu by mutableStateOf(false)
    /** From this width on, Muninn uses the tablet layout (rail, chat list, conversation); narrower, a conversation takes the whole screen. */
    val WIDE = 600.dp
    /** True while the tablet layout is showing: the list stays beside the conversation, so no back arrow. */
    var wide by mutableStateOf(false)
    /** Tablets: what the rail has picked, "" for direct messages or a group's id. */
    var space by mutableStateOf("")
    /** The folder chip picked on the chat list ("" is All). */
    var folder by mutableStateOf("")
    /** Each group's open text channel. */
    val channel = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun openGroup(g: String) { open = Folders.groupKey(g); if (wide) space = g }
    /** The group [open] points at (conversation or settings), or null. */
    fun groupOf(open: String): String? = if (open.startsWith("g:")) open.removePrefix("g:").substringBefore('/') else null
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

/** Phones: the chat list, or one conversation (or My code / Add someone) over the whole screen. */
@Composable
fun ChatScreen(app: Muninn, onAllowNearby: () -> Unit = {}) {
    val open = ChatNav.open
    Box(Modifier.fillMaxSize()) {
        when {
            open.isEmpty() -> ChatList(app, onAllowNearby)
            else -> ChatMain(app)
        }
    }
}

/** Tablets: the middle column, the list of direct messages. */
@Composable
fun ChatColumn(app: Muninn, onAllowNearby: () -> Unit = {}) = ChatList(app, onAllowNearby)

/** Tablets: the main column, and phones once a chat is open: the open conversation, or a note to pick one. */
@Composable
fun ChatMain(app: Muninn) {
    val open = ChatNav.open
    Box(Modifier.fillMaxSize()) {
        // Back from a group's settings goes to the group, from anything else to the list.
        if (open.isNotEmpty()) BackHandler { ChatNav.open = if (open.endsWith("/settings")) open.removeSuffix("/settings") else "" }
        val g = ChatNav.groupOf(open)
        if (g != null) androidx.compose.runtime.key(open) { if (open.endsWith("/settings")) GroupSettings(app, g) else GroupConversation(app, g) }
        else when (open) {
            "" -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MIcon("chat", Palette.textFaint, 40.dp)
                Text("Pick a chat on the left.", style = Type.normal.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 12.dp))
            }
            "code" -> MyCode(app)
            "add" -> AddSomeone(app)
            "newgroup" -> NewGroup(app)
            "nearby" -> NearbyRoom(app)
            else -> androidx.compose.runtime.key(open) { Conversation(app, open) }
        }
    }
}

/** The slim top bar for a full-screen page: back arrow and a title, then a hairline. */
@Composable
private fun PageBar(title: String, backLabel: String = "Back to chats", back: () -> Unit = { ChatNav.open = "" }) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", backLabel, Palette.text, size = 24.dp) { back() }
            Spacer(Modifier.width(6.dp))
            Text(title, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
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

/** One chat in the list: avatar, name and time, then the last message and how many are unread. [onLong]: hold to rename or for folders. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PersonRow(name: String, preview: String, time: String, unread: Int, dot: Boolean, avatar: @Composable () -> Unit,
                      onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onLongClickLabel = if (onLong != null) "Options" else null, onLongClick = onLong, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
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
    val groups by app.chat.groups.all.collectAsState()
    val gmsgs by app.chat.groups.messages.collectAsState()
    val gunread by app.chat.groups.unreadGroups.collectAsState()
    val ginvites by app.chat.groups.pending.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
    var query by remember { mutableStateOf("") }
    var foldersFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Everyone reachable right now: close by first, then the same Wi-Fi. (key, name, route)
    val around = buildList {
        close.filter { it.connected && it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.name, "close")) }
        wifi.filter { it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.chatName ?: it.alias, "wifi")) }
    }.distinctBy { it.first }
    // Close-by people already chatting with (not contacts yet) sit with the chats.
    val strangers = around.filter { !app.chat.isContact(it.first) && convos[it.first].isNullOrEmpty() && it.first !in blocked }
    fun lastTime(r: ChatRow): Long = if (r.group != null) gmsgs[r.group]?.lastOrNull()?.time ?: groups[r.group]?.state?.ts ?: 0L
        else convos[r.key]?.lastOrNull()?.time ?: 0L
    // Contacts (never group peers), anyone else there's a conversation with, and groups, newest first.
    val chatRows = (contacts.filter { !it.groupOnly }.map { ChatRow(it.chatKey, it.shown, it.name) } +
        convos.filter { (k, v) -> v.isNotEmpty() && !app.chat.isContact(k) }.map { (k, _) -> ChatRow(k, met[k]?.name ?: app.chat.nameOf(k)) } +
        groups.values.map { ChatRow(Folders.groupKey(it.g), it.state.name, group = it.g) })
        .filter { it.key !in blocked }
        .sortedByDescending { lastTime(it) }
    // A folder chip: only what's in it.
    val folder = folders.firstOrNull { it.id == ChatNav.folder }
    val inFolder = if (folder == null) chatRows else chatRows.filter { it.key in folder.keys }
    // Search: a chat's nickname, the name they chose, or its last message.
    val q = query.trim()
    fun lastText(r: ChatRow): String? = if (r.group != null) gmsgs[r.group]?.lastOrNull()?.let { groupPreview(app, it) } else convos[r.key]?.lastOrNull()?.let { preview(it) }
    val shownRows = if (q.isEmpty()) inFolder else inFolder.filter { c ->
        c.name.contains(q, ignoreCase = true) || c.alt.contains(q, ignoreCase = true) || lastText(c)?.contains(q, ignoreCase = true) == true
    }
    val shownStrangers = if (q.isEmpty() && folder == null) strangers else if (folder != null) emptyList() else strangers.filter { it.second.contains(q, ignoreCase = true) }
    val roomPreview = if (app.identity.nearbyChat) "${around.size} around you · you're “${app.identity.username}”" else "Off"
    val showRoom = folder == null && (q.isEmpty() || "Nearby room".contains(q, ignoreCase = true) || roomPreview.contains(q, ignoreCase = true))

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SearchBox(query) { query = it }
                FolderChips(app, folders)
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
            if (requests.isNotEmpty() || ginvites.isNotEmpty()) item { ListLabel("Wants to chat") }
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
            // Group invites from contacts: nothing changes until they're accepted.
            items(ginvites, key = { "i" + it.g + it.inv }) { inv ->
                Panel(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    Text("${app.chat.contacts.get(inv.from)?.shown ?: inv.fromName} invited you to ${inv.name}", style = Type.big)
                    Text("${inv.state.members.size} ${if (inv.state.members.size == 1) "person" else "people"} in it so far. Everyone in a group can see your name and message you there.", style = Type.small)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton("Join", kind = Kind.Accent) {
                            val err = app.chat.groups.acceptInvite(inv)
                            if (err != null) Ui.say(err, true) else Ui.say("Asked to join ${inv.name}. It opens here once ${inv.fromName}'s phone answers.")
                        }
                        MButton("Decline") { app.chat.groups.declineInvite(inv) }
                    }
                }
            }
            item {
                ListLabel(folder?.name ?: "Chats") {
                    // Online: the internet relays are connected. Said in words too, never by the dot's color alone.
                    val on = nostrUp > 0
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Palette.online else Palette.textFaint))
                    Text(if (on) "Online" else "Offline", style = metaStyle, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (showRoom) item(key = "room") {
                PersonRow("Nearby room", roomPreview, room.lastOrNull()?.let { when_(it.time) } ?: "", 0, false, { RoomAvatar(48.dp) }) { ChatNav.open = "nearby" }
            }
            items(shownRows, key = { "c" + it.key }) { c ->
                if (c.group != null) {
                    val grp = groups[c.group]
                    val msgs = gmsgs[c.group].orEmpty()
                    val last = msgs.lastOrNull()
                    val others = grp?.state?.members.orEmpty().filter { it.k != app.identity.publicKey }.map { memberName(app, it) }
                    PersonRow(c.name, last?.let { groupPreview(app, it) } ?: when {
                            grp?.readOnly == true -> "You're not in it any more"
                            others.isEmpty() -> "Just you so far: invite people"
                            else -> "${others.size + 1} people"
                        }, last?.let { when_(it.time) } ?: "",
                        if (c.group in gunread) groupUnread(msgs) else 0, false, { GroupAvatar(c.name, others, 48.dp) },
                        onLong = { foldersFor = c.key to c.name }) { ChatNav.openGroup(c.group) }
                } else {
                    val msgs = convos[c.key].orEmpty()
                    val last = msgs.lastOrNull()
                    PersonRow(c.name, last?.let { preview(it) } ?: routeText(app.chat.route(c.key)), last?.let { when_(it.time) } ?: "",
                        if (c.key in unread) unreadCount(msgs) else 0, app.chat.shouldAskKeep(c.key), { Avatar(c.name, 48.dp, 18.sp) },
                        onLong = { foldersFor = c.key to c.name }) { ChatNav.open = c.key }
                }
            }
            if (chatRows.isEmpty()) item {
                Text("No chats yet. Tap anyone under Around you to message them, no asking needed. To message someone far away, tap + to add them or to share your code.",
                    style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (shownRows.isEmpty() && q.isNotEmpty()) item {
                Text("No chats match “$q”.", style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (shownRows.isEmpty() && folder != null) item {
                Text("Nothing in ${folder.name} yet. Hold a chat to put it in a folder.", style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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
        // New: a small menu with the ways to start a chat or a group.
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp)) {
            val shape = RoundedCornerShape(18.dp)
            Box(Modifier.size(58.dp).clip(shape).background(Palette.gold).clickable(role = Role.Button) { ChatNav.menu = true }
                .semantics { contentDescription = "New" }, contentAlignment = Alignment.Center) {
                MIcon("plus", Palette.onGold, 26.dp, weight = 2.2f)
            }
            DropdownMenu(expanded = ChatNav.menu, onDismissRequest = { ChatNav.menu = false }, modifier = Modifier.background(Palette.panel)) {
                DropdownMenuItem(text = { Text("Add someone", style = bodyStyle) }, leadingIcon = { MIcon("plus", Palette.accent, 20.dp) },
                    onClick = { ChatNav.menu = false; ChatNav.open = "add" })
                DropdownMenuItem(text = { Text("New group", style = bodyStyle) }, leadingIcon = { MIcon("people", Palette.accent, 20.dp) },
                    onClick = { ChatNav.menu = false; ChatNav.open = "newgroup" })
                DropdownMenuItem(text = { Text("My code", style = bodyStyle) }, leadingIcon = { MIcon("user", Palette.accent, 20.dp) },
                    onClick = { ChatNav.menu = false; ChatNav.open = "code" })
            }
        }
    }
    foldersFor?.let { (key, name) -> FolderPicker(app, key, name) { foldersFor = null } }
}

/**
 * A conversation's slim top bar: back, avatar, name and one short status line. Tapping the name or the
 * dots opens [menu]: the full status and the chat's actions.
 */
@Composable
private fun ThreadBar(name: String, status: String, statusIcon: String, statusTint: Color, avatar: @Composable () -> Unit,
                      actions: @Composable () -> Unit = {}, menu: @Composable (close: () -> Unit) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Tablets keep the chat list beside the conversation, so there's nothing to go back to.
            if (!ChatNav.wide) IconAction("back", "Back to chats", Palette.text, size = 24.dp) { ChatNav.open = "" }
            else Spacer(Modifier.width(12.dp))
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
            actions()
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
    // Calls start faster when the relay login is already here.
    LaunchedEffect(key) { io.github.ravenor907.muninn.net.call.CallManager.prepare(key) }
    val convos by app.chat.conversations.collectAsState()
    val c = app.chat.contacts.get(key)
    val met by app.chat.met.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // redraw when they become a contact
    val m = met[key]
    val name = c?.shown ?: m?.name ?: app.chat.nameOf(key)
    var renaming by remember { mutableStateOf(false) }
    var folderPick by remember { mutableStateOf(false) }
    var tvSettings by remember { mutableStateOf(false) }
    val tvs by io.github.ravenor907.muninn.tv.TvParent.tvs.collectAsState()
    val isTv = tvs[key]?.disconnecting == false
    // What their app speaks isn't known yet (no message since they upgraded): ask once (protocol 0.6, 1.2).
    LaunchedEffect(key) { app.chat.probeCaps(key) }
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
    if (tvSettings) {
        io.github.ravenor907.muninn.tv.ParentTvScreen(app, key) { tvSettings = false }
        return
    }
    viewing?.let { v ->
        BackHandler { viewing = null }
        PhotoViewer(app, v) { viewing = null }
        return
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        ThreadBar(name, "Encrypted · ${routeShort(route)}", "shield", Palette.online, { Avatar(name, 40.dp, 16.sp) }, actions = {
            if (c != null) {
                val callable = io.github.ravenor907.muninn.net.call.CallManager.canCall(key)
                IconAction("call", if (callable) "Call $name" else "Calling needs Muninn or Huginn 0.6", if (callable) Palette.text else Palette.textSoft) {
                    if (callable) io.github.ravenor907.muninn.screens.CallActivity.call(context, key)
                    else Ui.say("$name needs Muninn or Huginn 0.6 to take calls.")
                }
            }
        }) { close ->
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
            DropdownMenuItem(text = { Text("Folders", style = bodyStyle) }, leadingIcon = { MIcon("folder", Palette.accent, 20.dp) },
                onClick = { close(); folderPick = true })
            if (isTv) DropdownMenuItem(text = { Text("Kids' TV", style = bodyStyle) }, leadingIcon = { MIcon("gear", Palette.accent, 20.dp) },
                onClick = { close(); tvSettings = true })
        }
        if (folderPick) FolderPicker(app, key, name) { folderPick = false }
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
    Column(Modifier.fillMaxSize()) {
        PageBar("My code")
        // Scrolls, so with larger size and text settings the buttons can always be reached.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // The code fits the width and at most 40% of the height, so it never pushes the buttons far away.
            val qr = minOf(maxWidth - 32.dp - 20.dp, maxHeight * 0.4f, 260.dp).coerceAtLeast(120.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text("Anyone with this code can reach you as “${app.identity.username}” for the next hour. After that it stops working, so a leaked code can't be used to spam you. People who already have you keep working.",
                    style = Type.normal, modifier = Modifier.padding(top = 12.dp))
                Text(if (left > 0) "Good for ${(left + 59_999) / 60_000} more minutes" else "This code has expired",
                    style = Type.small.copy(color = if (left > 0) Palette.accent else Palette.danger), modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) { Qr(Contacts.link(code), qr) }
                Text("They scan it with their phone's camera: it opens Muninn, or shows where to download it if they don't have it yet. Or send it as a message:", style = Type.small)
                MButton("Share link", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, icon = "send") {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Message me on Muninn, the private messenger. Tap to add me (or get Muninn first). The link works for an hour:\n${Contacts.link(code)}"), "Share your Muninn code"))
                }
                MButton("Copy", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "copy") {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Muninn code", code))
                    Ui.say("Code copied.")
                }
                MButton("New code", Modifier.fillMaxWidth().padding(top = 8.dp)) { code = app.chat.myCode() }
                // Someone without Muninn, right here: hand them the app itself, no internet needed.
                Text("They don't have Muninn yet? Send it to their phone straight from yours, by Quick Share or Bluetooth. Then they scan your code.",
                    style = Type.small, modifier = Modifier.padding(top = 16.dp))
                MButton("Share the app", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "phone") { io.github.ravenor907.muninn.ui.ShareApp.share(context) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AddSomeone(app: Muninn) {
    val context = LocalContext.current
    var text by remember {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        mutableStateOf(ChatNav.incoming.ifEmpty { if (Contacts.unlink(clip).contains(Contacts.PREFIX) || clip.contains(Proto06.CODE_PREFIX)) clip else "" }.also { ChatNav.incoming = "" })
    }
    val found = Contacts.parse(text)
    val groupCode = Proto06.parseCode(text)
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("Add someone")
        // Scrolls, so the Add button stays reachable with larger size and text settings or the keyboard up.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Paste the code or link they sent you (the code starts with MUNINN1, or MUNINNG1 for a group). Someone close by? They show up under Around you, no code needed.", style = Type.normal, modifier = Modifier.padding(top = 12.dp))
            Field(text, { text = it }, "Paste their code", Modifier.padding(vertical = 10.dp), single = false)
            when {
                text.isBlank() -> {}
                groupCode != null -> {
                    Text("Group: ${groupCode.gn}", style = Type.big)
                    Text("${groupCode.an.ifBlank { "An admin" }} shared it. Once you're in, everyone in the group sees your name and can message you there.",
                        style = Type.small, modifier = Modifier.padding(top = 4.dp))
                    if (groupCode.x * 1000 < System.currentTimeMillis())
                        Text("That group code has expired (they last an hour). Ask for a new one.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
                    else MButton("Join ${groupCode.gn}", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) {
                        val err = app.chat.groups.joinByCode(groupCode)
                        if (err != null) Ui.say(err, true)
                        else { Ui.say("Asked to join. ${groupCode.gn} shows up in your chats once ${groupCode.an.ifBlank { "the admin" }}'s phone answers."); ChatNav.open = "" }
                    }
                }
                found == null -> Text("That isn't a Muninn code. Ask them to tap My code › Share link.", style = Type.small.copy(color = Palette.danger))
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
                        // A Kids' TV's code: offer to set it up (no lock on the TV, so ask first).
                        io.github.ravenor907.muninn.tv.TvParent.adoptIfTv(app, text)
                        // A key change has its own warning (from Chat): don't cover it.
                        if (replaces == null) Ui.say("${found.name} added. Say hi: they'll get it wherever they are.")
                        ChatNav.open = found.chatKey
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Bubble(m: Chat.Msg, showName: Boolean, theirName: String? = null, photo: (() -> ByteArray?)? = null, statusText: String? = null,
                   onTap: () -> Unit = {}, onLong: () -> Unit) {
    val tapNow by rememberUpdatedState(onTap)
    val longNow by rememberUpdatedState(onLong)
    val align = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart
    // The corner nearest the sender is the sharp one.
    val shape = if (m.mine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    val status = when {
        statusText != null -> statusText
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
internal fun RenameDialog(c: io.github.ravenor907.muninn.net.Contact, done: (String?) -> Unit) {
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

// ------------------------------------------------------------------ groups (protocol 0.6, section 3)


/** How a member is shown: "You", a contact's nickname, or the name they have in the group. */
internal fun memberName(app: Muninn, m: Proto06.Member): String =
    if (m.k == app.identity.publicKey) "You" else app.chat.contacts.contact(m.k)?.shown ?: m.n.ifBlank { "Someone" }

/** What the chat list shows under a group's name: who said it, and what. */
private fun groupPreview(app: Muninn, m: Groups.GMsg): String {
    val who = if (m.mine) "You" else app.chat.contacts.contact(m.from)?.shown ?: m.name
    val what = when {
        m.unsent.isNotEmpty() -> "Message deleted"
        m.voice > 0 -> "Voice message · ${Voice.length(m.voice)}"
        m.photo.isNotEmpty() -> "Photo"
        else -> m.text.replace('\n', ' ')
    }
    return "$who: $what"
}

/** Unread in a group: theirs since my last message (at least 1 once the group is marked unread). */
private fun groupUnread(msgs: List<Groups.GMsg>): Int {
    var n = 0
    for (m in msgs.asReversed()) { if (m.mine) break; if (m.unsent.isEmpty() && !m.seen) n++ }
    return maxOf(n, 1)
}

/** A group message as a bubble: the sender's nickname if they're a contact. */
private fun asMsg(app: Muninn, m: Groups.GMsg) = Chat.Msg(m.id, m.from,
    if (m.mine) app.identity.username else app.chat.contacts.contact(m.from)?.shown ?: m.name, m.text, m.mine, m.time,
    state = m.state, photo = m.photo, unsent = m.unsent, voice = m.voice)

/** Two members' initials, one over the other; a group of one or two shows a single circle. */
@Composable
private fun GroupAvatar(name: String, others: List<String>, size: Dp) {
    if (others.size < 2) {
        Box(Modifier.size(size).clip(CircleShape).background(Palette.coolTint), contentAlignment = Alignment.Center) {
            Text(initial(others.firstOrNull() ?: name), style = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.36f).sp, color = Palette.accent))
        }
        return
    }
    val small = size * 0.68f
    Box(Modifier.size(size).semantics { contentDescription = "Group" }) {
        Box(Modifier.align(Alignment.TopStart).size(small).clip(CircleShape).background(Palette.ground).padding(2.dp).clip(CircleShape)
            .background(Palette.goldTint), contentAlignment = Alignment.Center) {
            Text(initial(others[0]), style = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = (small.value * 0.4f).sp, color = Palette.goldBright))
        }
        Box(Modifier.align(Alignment.BottomEnd).size(small).clip(CircleShape).background(Palette.ground).padding(2.dp).clip(CircleShape)
            .background(Palette.coolTint), contentAlignment = Alignment.Center) {
            Text(initial(others[1]), style = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = (small.value * 0.4f).sp, color = Palette.accent))
        }
    }
}

/** A round chip (folders, channels). [onLong]: hold to rename or delete. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Chip(text: String, on: Boolean, icon: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.heightIn(min = 40.dp).clip(shape)
        .then(if (on) Modifier.background(Palette.gold) else Modifier.border(1.dp, if (Access.contrast) Palette.lineMuted else Palette.line, shape))
        .combinedClickable(role = Role.Tab, onLongClickLabel = if (onLong != null) "Rename or delete" else null, onLongClick = onLong, onClick = onClick)
        .semantics { selected = on }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { MIcon(icon, if (on) Palette.onGold else Palette.textMuted, 16.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, style = bodyStyle.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) Palette.onGold else Palette.text), maxLines = 1)
    }
}

/** The folder chips under the search box: All, each folder, and + for a new one. */
@Composable
private fun FolderChips(app: Muninn, folders: List<Folders.Folder>) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Folders.Folder?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip("All", folders.none { it.id == ChatNav.folder }) { ChatNav.folder = "" }
        folders.forEach { f -> Chip(f.name, ChatNav.folder == f.id, onLong = { editing = f }) { ChatNav.folder = if (ChatNav.folder == f.id) "" else f.id } }
        val shape = RoundedCornerShape(20.dp)
        Box(Modifier.size(40.dp).clip(shape).border(1.dp, Palette.lineMuted, shape).clickable(role = Role.Button) { adding = true }
            .semantics { contentDescription = "New folder" }, contentAlignment = Alignment.Center) {
            MIcon("plus", Palette.accent, 14.dp, weight = 2f)
        }
    }
    if (adding) NameDialog("New folder", "", "Add", "Folder name", note = "Only on this phone. Hold a chat to put it in a folder.") { n ->
        adding = false
        if (n != null) app.chat.folders.add(n)?.let { ChatNav.folder = it }
    }
    editing?.let { f ->
        NameDialog("Folder", f.name, "Save", "Folder name", extra = "Delete folder", onExtra = {
            editing = null
            Ui.sure(Ask("Delete ${f.name}?", "Only the folder goes. The chats in it stay.", "Delete", danger = true,
                onConfirm = { app.chat.folders.delete(f.id); if (ChatNav.folder == f.id) ChatNav.folder = "" }))
        }) { n -> editing = null; if (n != null) app.chat.folders.rename(f.id, n) }
    }
}

/** A name in a dialog (new folder, rename folder). [done] gets null for Cancel. */
@Composable
private fun NameDialog(title: String, start: String, confirm: String, hint: String, note: String = "", max: Int = Folders.MAX_NAME,
                       extra: String? = null, onExtra: () -> Unit = {}, done: (String?) -> Unit) {
    var text by remember { mutableStateOf(start) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { done(null) },
        containerColor = Palette.panel,
        title = { Text(title, style = Type.big) },
        text = {
            Column {
                if (note.isNotEmpty()) Text(note, style = Type.normal)
                Field(text, { text = it.take(max) }, hint, Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { MButton(confirm, kind = Kind.Accent, enabled = text.isNotBlank()) { done(text.trim()) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Cancel") { done(null) }
                if (extra != null) MButton(extra, kind = Kind.Danger) { onExtra() }
            }
        },
    )
}

/** Which folders [key] (a contact's key, or "g:" + group) is in. Folders stay on this phone. */
@Composable
internal fun FolderPicker(app: Muninn, key: String, name: String, done: () -> Unit) {
    val folders by app.chat.folders.all.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // the new name shows at once
    var newName by remember { mutableStateOf("") }
    // Holding a chat or a person: their nickname is here too (a contact only, never a group).
    val contact = app.chat.contacts.contact(key)
    var renaming by remember { mutableStateOf(false) }
    if (renaming && contact != null) {
        RenameDialog(contact) { nick -> if (nick != null) app.chat.rename(key, nick); renaming = false }
        return
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Folders for $name", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (contact != null) MButton("Rename", Modifier.fillMaxWidth().padding(bottom = 8.dp), icon = "user") { renaming = true }
                Text("Folders are only on this phone. Nobody else sees them.", style = Type.small, modifier = Modifier.padding(bottom = 6.dp))
                folders.forEach { f ->
                    val on = key in f.keys
                    Line(f.name, if (on) "In this folder" else "", icon = if (on) "check" else "folder", selected = on) { app.chat.folders.put(f.id, key, !on) }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(newName, { newName = it.take(Folders.MAX_NAME) }, "New folder", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    MButton("Add", enabled = newName.isNotBlank()) {
                        app.chat.folders.add(newName)?.let { app.chat.folders.put(it, key, true) }
                        newName = ""
                    }
                }
            }
        },
        confirmButton = { MButton("Done", kind = Kind.Accent) { done() } },
    )
}

@Composable
private fun NoGroup() {
    Column(Modifier.fillMaxSize()) {
        PageBar("Group")
        Text("This group isn't on this phone any more.", style = Type.normal, modifier = Modifier.padding(16.dp))
    }
}

/** + New › New group: a name, then its settings to invite people. */
@Composable
private fun NewGroup(app: Muninn) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("New group")
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Everyone in a group gets every message, each one sealed separately to them, end to end. Up to ${Proto06.MAX_MEMBERS} people.",
                style = Type.normal, modifier = Modifier.padding(top = 12.dp))
            Field(name, { name = it.take(Proto06.MAX_NAME) }, "Group name", Modifier.padding(vertical = 10.dp))
            MButton("Create", Modifier.fillMaxWidth(), Kind.Accent, enabled = name.isNotBlank()) {
                val g = app.chat.groups.create(name)
                if (g == null) Ui.say("Give the group a name.", true)
                else {
                    if (ChatNav.wide) ChatNav.space = g
                    ChatNav.open = Folders.groupKey(g) + "/settings"
                    Ui.say("${name.trim()} is ready. Invite people next.")
                }
            }
            Text("Next you can invite contacts, or share a group code with anyone you want in it.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Phones: a group's channels as chips under its top bar; a voice room's chip joins it (and says who's in). */
@Composable
private fun ChannelChips(s: Proto06.State, current: String, onText: (String) -> Unit, onVoice: (String) -> Unit) {
    RoomManager.presence.collectAsState().value   // re-reads who is in which room when it changes
    val room by RoomManager.room.collectAsState()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.channels.forEach { c ->
            if (c.t == "text") Chip("# ${c.n}", c.id == current) { onText(c.id) }
            else {
                val n = RoomManager.whoIn(s.g, c.id).size
                Chip(if (n > 0) "${c.n} · $n" else c.n, room?.let { it.g == s.g && it.ch == c.id } == true, icon = "audio") { onVoice(c.id) }
            }
        }
        RoomManager.groupCalls(s.g).forEach { (id, who) ->
            Chip("Group call · ${who.size}", room?.let { it.g == s.g && it.ch == id } == true, icon = "call") { onVoice(id) }
        }
    }
}

/** Who's in a voice room or group call, under its row: small faces, ringed while they talk. */
@Composable
private fun RoomFaces(people: List<RoomManager.Person>) {
    if (people.isEmpty()) return
    Row(Modifier.padding(start = 38.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        people.take(8).forEach { p ->
            Box(Modifier.size(28.dp).clip(CircleShape).then(if (p.speaking) Modifier.border(2.dp, Palette.goldBright, CircleShape) else Modifier)
                .background(Palette.goldTint).semantics { contentDescription = p.name + if (p.speaking) ", speaking" else "" }, contentAlignment = Alignment.Center) {
                Text(initial(p.name), style = Type.small.copy(fontSize = 12.sp, color = Palette.goldBright))
            }
        }
    }
}

/** Ring some members of a group into a group call: tick who, then Call. */
@Composable
private fun CallSomeDialog(app: Muninn, s: Proto06.State, done: () -> Unit) {
    val context = LocalContext.current
    val me = app.identity.publicKey
    val people = s.members.filter { it.k != me }.sortedBy { memberName(app, it).lowercase() }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Call some people", style = Type.big) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item { Text("Up to ${RoomLogic.MAX_PEOPLE} people talk at once.", style = Type.small, modifier = Modifier.padding(bottom = 6.dp)) }
                items(people, key = { it.k }) { m ->
                    val old = RoomManager.needsUpdate(m.k)
                    Line(memberName(app, m), if (old) "Needs an update to join calls" else if (m.k in chosen) "Will be rung" else "Tap to ring",
                        icon = "user", selected = m.k in chosen) { if (!old) chosen = if (m.k in chosen) chosen - m.k else chosen + m.k }
                }
            }
        },
        confirmButton = { MButton("Call", kind = Kind.Accent, enabled = chosen.isNotEmpty()) { done(); RoomActivity.call(context, s.g, chosen.toList()) } },
        dismissButton = { MButton("Cancel") { done() } },
    )
}

@Composable
private fun GroupConversation(app: Muninn, g: String) {
    val all by app.chat.groups.all.collectAsState()
    val chats by app.chat.groups.messages.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // names follow nicknames
    val grp = all[g]
    if (grp == null) { NoGroup(); return }
    val s = grp.state
    val key = Folders.groupKey(g)
    val texts = s.channels.filter { it.t == "text" }
    val ch = ChatNav.channel[g]?.takeIf { id -> texts.any { it.id == id } } ?: Proto06.MAIN
    val chName = s.channel(ch)?.n ?: "general"
    // A removed channel's messages show in main.
    val msgs = chats[g].orEmpty().filter { it.ch == ch || (ch == Proto06.MAIN && s.channel(it.ch)?.t != "text") }
    var text by remember(ch) { mutableStateOf("") }
    var folderPick by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.lastOrNull()?.id) { app.chat.groups.markRead(g); if (msgs.isNotEmpty()) if (Access.reduceMotion) list.scrollToItem(msgs.size - 1) else list.animateScrollToItem(msgs.size - 1) }
    val context = LocalContext.current
    // While it's on screen, new messages here just buzz; its old notification goes.
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
            else withContext(Dispatchers.IO) { app.chat.sendGroupPhoto(g, ch, jpeg) }
            preparing = false; picked = null
        }
    }
    viewing?.let { v ->
        BackHandler { viewing = null }
        PhotoViewer(app, v) { viewing = null }
        return
    }
    val others = s.members.filter { it.k != app.identity.publicKey }.map { memberName(app, it) }
    val inRoom by RoomManager.room.collectAsState()
    var callSome by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        ThreadBar(if (texts.size > 1 || ChatNav.wide) "${s.name} · #$chName" else s.name,
            "${s.members.size} ${if (s.members.size == 1) "person" else "people"} · encrypted", "shield", Palette.online,
            { GroupAvatar(s.name, others, 40.dp) }, actions = {
                if (!grp.readOnly && s.members.size > 1) IconAction("call", "Call everyone in ${s.name}", Palette.text) {
                    Ui.sure(Ask("Call everyone in ${s.name}?", "It rings everyone whose app can join. Up to ${RoomLogic.MAX_PEOPLE} people talk at once.", "Call",
                        onConfirm = { RoomActivity.call(context, g, emptyList()) }))
                }
            }) { close ->
            MenuNote("Each message is sealed separately to every member, end to end. Up to ${Proto06.MAX_MEMBERS} people.")
            if (!grp.readOnly && s.members.size > 2) DropdownMenuItem(text = { Text("Call some people", style = bodyStyle) }, leadingIcon = { MIcon("call", Palette.accent, 20.dp) },
                onClick = { close(); callSome = true })
            DropdownMenuItem(text = { Text("Group settings", style = bodyStyle) }, leadingIcon = { MIcon("gear", Palette.accent, 20.dp) },
                onClick = { close(); ChatNav.open = "$key/settings" })
            DropdownMenuItem(text = { Text("Folders", style = bodyStyle) }, leadingIcon = { MIcon("folder", Palette.accent, 20.dp) },
                onClick = { close(); folderPick = true })
        }
        if (folderPick) FolderPicker(app, key, s.name) { folderPick = false }
        if (callSome) CallSomeDialog(app, s) { callSome = false }
        // Tablets list the channels in their own column.
        RoomManager.presence.collectAsState().value
        val calls = RoomManager.groupCalls(g)
        if (!ChatNav.wide && (s.channels.size > 1 || calls.isNotEmpty())) ChannelChips(s, ch, { ChatNav.channel[g] = it }) { RoomActivity.join(context, g, it) }
        inRoom?.let { r -> Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp), onClick = { RoomActivity.show(context) }) {
            Text("In ${r.title}${if (r.groupName.isNotEmpty()) " · ${r.groupName}" else ""}", style = Type.big)
            Text(if (r.muted) "You're muted · tap to open" else "Tap to open", style = Type.small)
        } }
        if (grp.readOnly) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            Text(if (grp.gone == "removed") "You were removed from ${s.name}" else "You left ${s.name}", style = Type.big)
            Text("You can still read what's here. Nothing new arrives.", style = Type.small)
            MButton("Delete it from this phone", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Danger) {
                Ui.sure(Ask("Delete ${s.name}?", "Its messages and photos are deleted from this phone.", "Delete", danger = true,
                    onConfirm = { if (app.chat.deleteGroup(g)) { ChatNav.open = ""; ChatNav.space = "" } }))
            }
        } else if (s.members.size == 1) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            Text("Just you so far", style = Type.big)
            Text("Invite contacts, or share a group code with anyone you want in it.", style = Type.small)
            MButton("Invite people", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { ChatNav.open = "$key/settings" }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            items(msgs.size, key = { msgs[it].id }) { i ->
                val gm = msgs[i]
                val m = asMsg(app, gm)
                Column {
                    if (i == 0 || dayIndex(msgs[i - 1].time) != dayIndex(gm.time)) DayLabel(gm.time)
                    Bubble(m, showName = true, photo = if (gm.photo.isNotEmpty()) ({ app.chat.photo(gm.photo) }) else null,
                        statusText = if (gm.mine) Groups.status(gm) else null, onTap = { viewing = m }) {
                        if (gm.mine && gm.unsent.isEmpty() && !grp.readOnly) Ui.sure(Ask("Unsend this message?",
                            "Unsend removes it from everyone's phone in ${s.name}. They'll see “Message deleted by ${app.identity.username}”.",
                            "Unsend", danger = true, extra = "Delete for me", onExtra = { app.chat.groups.deleteMessage(g, gm.id) },
                            onConfirm = { app.chat.groups.unsend(g, gm.id) }))
                        else Ui.sure(Ask("Delete this message?", "It's deleted on this phone only.", "Delete", danger = true,
                            onConfirm = { app.chat.groups.deleteMessage(g, gm.id) }))
                    }
                }
            }
        }
        if (msgs.isEmpty()) Text("No messages in #$chName yet.", style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        picked?.let { uri ->
            Panel(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UriThumb(uri, Modifier.size(72.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (preparing) "Getting it ready…" else "Send this photo to ${s.name}?", style = Type.big)
                        Text("Location and camera details are removed.", style = Type.small)
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("Cancel", Modifier.weight(1f), enabled = !preparing) { picked = null }
                    MButton("High quality", Modifier.weight(1f), enabled = !preparing) { sendPhoto(uri, true) }
                    MButton("Send", Modifier.weight(1f), Kind.Accent, enabled = !preparing) { sendPhoto(uri, false) }
                }
            }
        }
        if (!grp.readOnly) Composer(text, { text = it }, "Message #$chName", onPhoto = {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, voice = VoiceHooks(
            allowed = { androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            ask = { micAsk.launch(android.Manifest.permission.RECORD_AUDIO) },
            send = { aac, ms -> scope.launch(Dispatchers.IO) { app.chat.sendGroupVoice(g, ch, aac, ms) } },
        )) {
            if (!app.chat.sendGroupText(g, ch, text)) Ui.say("That didn't send. A message can be up to ${Chat.MAX} characters.", true)
            text = ""
        }
    }
}

/** Tablets: the middle column for a group: its TEXT channels, then its VOICE rooms, and me at the bottom. */
@Composable
fun ChannelColumn(app: Muninn, g: String) {
    val all by app.chat.groups.all.collectAsState()
    val grp = all[g] ?: return
    val s = grp.state
    val current = ChatNav.channel[g] ?: Proto06.MAIN
    val here = ChatNav.groupOf(ChatNav.open) == g && !ChatNav.open.endsWith("/settings")
    val voice = s.channels.filter { it.t == "voice" }
    val context = LocalContext.current
    RoomManager.presence.collectAsState().value   // re-reads who is in which room when it changes
    val room by RoomManager.room.collectAsState()
    val calls = RoomManager.groupCalls(g)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 16.dp)) {
            Text("TEXT", style = labelStyle.copy(fontSize = 12.sp), modifier = Modifier.padding(start = 8.dp, bottom = 6.dp).semantics { heading() })
            s.channels.filter { it.t == "text" }.forEach { c ->
                ChannelRow(c.n, voice = false, on = here && c.id == current) { ChatNav.channel[g] = c.id; ChatNav.open = Folders.groupKey(g) }
            }
            if (voice.isNotEmpty() || calls.isNotEmpty()) {
                Text("VOICE", style = labelStyle.copy(fontSize = 12.sp), modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp).semantics { heading() })
                voice.forEach { c ->
                    ChannelRow(c.n, voice = true, on = room?.let { it.g == g && it.ch == c.id } == true) { RoomActivity.join(context, g, c.id) }
                    RoomFaces(RoomManager.whoIn(g, c.id))
                }
            }
            calls.forEach { (id, who) ->
                ChannelRow("Group call", voice = true, on = room?.let { it.g == g && it.ch == id } == true) { RoomActivity.join(context, g, id) }
                RoomFaces(who)
            }
            if (grp.readOnly) Text(if (grp.gone == "removed") "You were removed from this group." else "You left this group.",
                style = Type.small, modifier = Modifier.padding(start = 8.dp, top = 18.dp))
        }
        Row(Modifier.fillMaxWidth().background(Palette.groundDeep).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(app.identity.username, 36.dp, 15.sp)
            Text(app.identity.username, style = nameStyle.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp))
            if (room != null) MButton("Open", kind = Kind.Accent) { RoomActivity.show(context) }
            else if (!grp.readOnly && s.members.size > 1) MButton("Call", icon = "call") { RoomActivity.call(context, g, emptyList()) }
        }
    }
}

@Composable
private fun ChannelRow(name: String, voice: Boolean, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape).background(if (on) Palette.raised else Color.Transparent)
        .clickable(role = Role.Tab, onClick = onClick).semantics { selected = on }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (voice) MIcon("audio", Palette.textMuted, 18.dp) else Text("#", style = bodyStyle.copy(fontSize = 19.sp, color = Palette.textMuted))
        Spacer(Modifier.width(8.dp))
        Text(name, style = bodyStyle.copy(color = if (on) Palette.text else Palette.textSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A group's settings: name, members (admins, owner), invites and group codes, channels, folders, leave. */
@Composable
private fun GroupSettings(app: Muninn, g: String) {
    val all by app.chat.groups.all.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
    val grp = all[g]
    if (grp == null) { NoGroup(); return }
    val s = grp.state
    val key = Folders.groupKey(g)
    val me = app.identity.publicKey
    val admin = s.isAdmin(me) && !grp.readOnly
    var name by remember(g) { mutableStateOf(s.name) }
    var member by remember { mutableStateOf<Proto06.Member?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var channel by remember { mutableStateOf<Proto06.Channel?>(null) }
    var addingChannel by remember { mutableStateOf(false) }
    var folderPick by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("Group settings", "Back to ${s.name}") { ChatNav.open = key }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("Name")
            if (admin) {
                Field(name, { name = it.take(Proto06.MAX_NAME) }, "Group name")
                MButton("Save name", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = name.isNotBlank() && name.trim() != s.name) {
                    app.chat.groups.rename(g, name)?.let { Ui.say(it, true) } ?: Ui.say("Renamed to ${name.trim()}.")
                }
            } else Text(s.name, style = Type.big)
            Section("Members · ${s.members.size} of ${Proto06.MAX_MEMBERS}")
            s.members.sortedWith(compareBy({ it.k != s.owner }, { !s.isAdmin(it.k) }, { memberName(app, it).lowercase() })).forEach { m ->
                val role = when { m.k == s.owner -> "Owner"; s.isAdmin(m.k) -> "Admin"; else -> "Member" }
                val how = when { m.k == me -> ""; app.chat.isContact(m.k) -> " · contact"; else -> " · in this group only" }
                Line(memberName(app, m), role + how, icon = "user") { if (m.k != me) member = m }
            }
            if (admin) {
                MButton("Invite contacts", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, icon = "plus") { inviting = true }
                MButton("Share a group code", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "link") {
                    code = app.chat.groups.code(g) ?: run { Ui.say("Only admins can make a group code.", true); null }
                }
            }
            Section("Channels")
            s.channels.forEach { c ->
                Line(if (c.t == "voice") c.n else "# ${c.n}", when {
                    c.t == "voice" -> "Voice room · up to ${RoomLogic.MAX_PEOPLE} people"
                    c.id == Proto06.MAIN -> "Text · the main channel"
                    else -> "Text"
                }, icon = if (c.t == "voice") "audio" else "chat") { if (admin) channel = c }
            }
            if (admin && s.channels.size < Proto06.MAX_CHANNELS) MButton("Add a channel", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "plus") { addingChannel = true }
            Section("Folders")
            Line(folders.filter { key in it.keys }.joinToString(", ") { it.name }.ifEmpty { "Not in a folder" }, "Only on this phone", icon = "folder") { folderPick = true }
            Section("Leave")
            if (!grp.readOnly) MButton("Leave ${s.name}", Modifier.fillMaxWidth(), Kind.Danger) {
                if (s.owner == me && s.members.size > 1) Ui.say("You own ${s.name}. Make someone else the owner first (tap them under Members).", true)
                else Ui.sure(Ask("Leave ${s.name}?", "You stop getting its messages. What's here stays on this phone until you delete it.", "Leave", danger = true,
                    onConfirm = { app.chat.groups.leave(g)?.let { Ui.say(it, true) } ?: Ui.say("You left ${s.name}.") }))
            } else MButton("Delete it from this phone", Modifier.fillMaxWidth(), Kind.Danger) {
                Ui.sure(Ask("Delete ${s.name}?", "Its messages and photos are deleted from this phone.", "Delete", danger = true,
                    onConfirm = { if (app.chat.deleteGroup(g)) { ChatNav.open = ""; ChatNav.space = "" } }))
            }
            Text("Changes are signed by an admin and checked on every member's phone.", style = Type.small, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
    member?.let { m -> MemberDialog(app, g, s, m) { member = null } }
    if (inviting) InviteDialog(app, g, s) { inviting = false }
    code?.let { c -> GroupCodeDialog(app, g, s, c) { code = null } }
    if (addingChannel) ChannelDialog(app, g, null) { addingChannel = false }
    channel?.let { c -> ChannelDialog(app, g, c) { channel = null } }
    if (folderPick) FolderPicker(app, key, s.name) { folderPick = false }
}

@Composable
private fun MemberDialog(app: Muninn, g: String, s: Proto06.State, m: Proto06.Member, done: () -> Unit) {
    val me = app.identity.publicKey
    val admin = s.isAdmin(me)
    val name = memberName(app, m)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text(name, style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((if (m.k == s.owner) "Owner" else if (s.isAdmin(m.k)) "Admin" else "Member") +
                    (if (app.chat.isContact(m.k)) " · a contact" else " · in this group only"), style = Type.small)
                if (app.chat.isContact(m.k)) MButton("Message", Modifier.fillMaxWidth(), icon = "chat") { done(); ChatNav.space = ""; ChatNav.open = m.k }
                MButton("Check code", Modifier.fillMaxWidth(), icon = "shield") {
                    done()
                    Ui.sure(Ask("Check it's really $name", "Code: ${app.chat.codeWith(m.k)}\n\nAsk them to show the code for you. If it's the same, nobody is in between.",
                        "It matches", onConfirm = { app.chat.verify(m.k, name) }))
                }
                if (admin && m.k != s.owner) {
                    if (s.isAdmin(m.k)) MButton("Remove as admin", Modifier.fillMaxWidth()) {
                        done(); app.chat.groups.setAdmin(g, m.k, false)?.let { Ui.say(it, true) } ?: Ui.say("$name isn't an admin any more.")
                    } else MButton("Make admin", Modifier.fillMaxWidth()) {
                        done(); app.chat.groups.setAdmin(g, m.k, true)?.let { Ui.say(it, true) } ?: Ui.say("$name is an admin now.")
                    }
                    MButton("Remove from group", Modifier.fillMaxWidth(), Kind.Danger) {
                        done()
                        Ui.sure(Ask("Remove $name?", "They stop getting messages from ${s.name}, and their phone says they were removed.", "Remove", danger = true,
                            onConfirm = { app.chat.groups.remove(g, m.k)?.let { Ui.say(it, true) } }))
                    }
                }
                if (s.owner == me) MButton("Make $name the owner", Modifier.fillMaxWidth()) {
                    done()
                    Ui.sure(Ask("Hand ${s.name} over to $name?", "They become the owner. You stay an admin, and can leave after.", "Hand over",
                        onConfirm = { app.chat.groups.handOver(g, m.k)?.let { Ui.say(it, true) } }))
                }
            }
        },
        confirmButton = { MButton("Close") { done() } },
    )
}

@Composable
private fun InviteDialog(app: Muninn, g: String, s: Proto06.State, done: () -> Unit) {
    val contacts by app.chat.contacts.all.collectAsState()
    val people = contacts.filter { !it.groupOnly && !s.has(it.chatKey) }.sortedBy { it.shown.lowercase() }
    // Whose app can join: ask the ones not known yet (their answers update this list).
    LaunchedEffect(Unit) { people.forEach { app.chat.probeCaps(it.chatKey) } }
    var sent by remember { mutableStateOf(setOf<String>()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Invite to ${s.name}", style = Type.big) },
        text = {
            if (people.isEmpty()) Text("Everyone you're in touch with is in it already. Share a group code to add someone else.", style = Type.normal)
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(people, key = { it.chatKey }) { c ->
                    val ready = "g1" in c.caps
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.shown, style = Type.normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when { c.chatKey in sent -> "Invited. They join once they accept."; ready -> "Can join"; else -> "Needs Muninn or Huginn 0.6" },
                                style = Type.small, maxLines = 2)
                        }
                        Spacer(Modifier.width(8.dp))
                        MButton(if (c.chatKey in sent) "Invited" else "Invite", enabled = c.chatKey !in sent, kind = if (ready) Kind.Accent else Kind.Normal) {
                            val err = app.chat.inviteToGroup(g, c.chatKey)
                            if (err != null) Ui.say(err, true) else sent = sent + c.chatKey
                        }
                    }
                }
            }
        },
        confirmButton = { MButton("Done", kind = Kind.Accent) { done() } },
    )
}

@Composable
private fun GroupCodeDialog(app: Muninn, g: String, s: Proto06.State, code: String, done: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Group code", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Anyone with this code can join ${s.name} for the next hour, even if they aren't your contact. In Muninn or Huginn they tap + › Add someone and paste it.",
                    style = Type.normal)
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Qr(code, 220.dp) }
                MButton("Share", Modifier.fillMaxWidth(), Kind.Accent, icon = "send") {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Join ${s.name} on Muninn. Open Muninn, tap + › Add someone and paste this (it works for an hour):\n$code"), "Share the group code"))
                }
                MButton("Copy", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "copy") {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Muninn group code", code))
                    Ui.say("Group code copied.")
                }
                MButton("Stop this group's codes", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    app.chat.groups.revokeCodes(g); Ui.say("Those group codes don't work any more."); done()
                }
            }
        },
        confirmButton = { MButton("Done") { done() } },
    )
}

@Composable
private fun ChannelDialog(app: Muninn, g: String, edit: Proto06.Channel?, done: () -> Unit) {
    var name by remember { mutableStateOf(edit?.n ?: "") }
    var voice by remember { mutableStateOf(edit?.t == "voice") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text(if (edit == null) "Add a channel" else if (edit.t == "voice") "Voice room" else "Channel", style = Type.big) },
        text = {
            Column {
                Field(name, { name = it.take(Proto06.MAX_CHANNEL_NAME) }, "Channel name")
                if (edit == null) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("Text", Modifier.weight(1f), if (!voice) Kind.Accent else Kind.Normal) { voice = false }
                    MButton("Voice room", Modifier.weight(1f), if (voice) Kind.Accent else Kind.Normal) { voice = true }
                }
                if (voice) Text("Voice rooms open once calls arrive in Muninn. You can set them up now.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            MButton(if (edit == null) "Add" else "Save", kind = Kind.Accent, enabled = name.isNotBlank()) {
                val err = if (edit == null) app.chat.groups.addChannel(g, name, voice) else app.chat.groups.renameChannel(g, edit.id, name)
                if (err != null) Ui.say(err, true)
                done()
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Cancel") { done() }
                if (edit != null && edit.id != Proto06.MAIN) MButton("Remove", kind = Kind.Danger) {
                    done()
                    Ui.sure(Ask("Remove ${edit.n}?", "Messages already in it show in the main channel.", "Remove", danger = true,
                        onConfirm = { app.chat.groups.removeChannel(g, edit.id)?.let { Ui.say(it, true) } }))
                }
            }
        },
    )
}
