package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.Folders
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/**
 * What the press-and-hold sheet shows about a person, in one place. A private note is kept here, encrypted in the
 * vault ("people.json"), on this phone only. Pinned chats live in ChatFlags and what each person may do in the
 * encrypted contacts (Contact.noCalls, noFiles, hideActive), where Chat enforces them; [all] mirrors both for the
 * screens. Anything an earlier test build kept here moves over on the first load.
 */
object People {
    /** What a person may do with me. All on unless switched off. */
    data class Can(val calls: Boolean = true, val files: Boolean = true, val active: Boolean = true)

    data class State(val pinned: List<String> = emptyList(), val notes: Map<String, String> = emptyMap(), val can: Map<String, Can> = emptyMap())

    val all = MutableStateFlow(State())
    private var app: Muninn? = null
    private fun file(a: Muninn) = File(a.filesDir, "people.json")

    /** Once the vault is open. */
    fun load(a: Muninn) {
        if (app === a) return
        app = a
        val o = try { a.vault.read(file(a))?.let { JSONObject(it) } } catch (e: Exception) { null }
        val notes = o?.optJSONObject("notes")
        all.value = State(notes = notes?.keys()?.asSequence()?.associateWith { notes.optString(it) }.orEmpty())
        // Pins and choices kept here before they had their own place: moved over once.
        val pins = o?.optJSONArray("pinned")
        val can = o?.optJSONObject("can")
        if (pins != null || can != null) {
            if (pins != null) for (i in pins.length() - 1 downTo 0) a.chat.flags.pin(pins.getString(i), true)
            can?.keys()?.forEach { k -> can.getJSONObject(k).let { apply(a, k, Can(it.optBoolean("c", true), it.optBoolean("f", true), it.optBoolean("a", true))) } }
            set(all.value)
        }
        refresh()
    }

    private fun set(s: State) {
        all.value = s
        val a = app ?: return
        val o = JSONObject().put("notes", JSONObject().apply { s.notes.forEach { (k, v) -> put(k, v) } })
        try { a.vault.write(file(a), o.toString()) } catch (_: Exception) {}
    }

    /** Pins and choices as they are now, for the screens. */
    private fun refresh() {
        val a = app ?: return
        all.value = all.value.copy(pinned = a.chat.flags.all.value.pinned,
            can = a.contacts.all.value.filter { it.noCalls || it.noFiles || it.hideActive }.associate { it.chatKey to Can(!it.noCalls, !it.noFiles, !it.hideActive) })
    }

    private fun apply(a: Muninn, key: String, c: Can) {
        a.chat.setCallsAllowed(key, c.calls)
        a.chat.setFilesAllowed(key, c.files)
        a.chat.setShowsActive(key, c.active)
    }

    fun pinned(key: String) = app?.chat?.flags?.pinned(key) == true
    fun pin(key: String, on: Boolean) { app?.chat?.flags?.pin(key, on); refresh() }
    fun note(key: String): String = all.value.notes[key].orEmpty()
    fun setNote(key: String, text: String) = all.value.let { s ->
        val t = text.trim().take(500)
        set(s.copy(notes = if (t.isEmpty()) s.notes - key else s.notes + (key to t)))
    }

    /** What a person may do: from the contact, where Chat enforces it (calls and video, files, active status). */
    fun can(key: String): Can = app?.chat?.let { Can(it.callsAllowed(key), it.filesAllowed(key), it.showsActive(key)) } ?: Can()
    fun setCan(key: String, c: Can) { val a = app ?: return; apply(a, key, c); refresh() }
}

/** The three starting folders, used as a person's role. "None" is in none of them. */
private val ROLES = Folders.DEFAULTS

/**
 * Press and hold a person or a chat (the chat list, Contacts, a group's members): one sheet, the same everywhere.
 * Their role (Family, Work, Folk or None), what they can do, where the chat sits, and the rest.
 * [key]: a contact's or met person's chat key, or "g:" and a group's id. [moves]: from the chat list, so it can move up and down.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HoldSheet(app: Muninn, key: String, moves: ListMoves?, done: () -> Unit) {
    val folders by app.chat.folders.all.collectAsState()
    val flagState by app.chat.flags.all.collectAsState()
    val people by People.all.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // names and checks show at once
    val groups by app.chat.groups.all.collectAsState()
    val group = if (key.startsWith("g:")) groups[key.removePrefix("g:")] else null
    val contact = if (group == null) app.chat.contacts.contact(key) else null
    val name = group?.state?.name ?: app.chat.nameOf(key)
    val verified = group == null && app.chat.isVerified(key)
    var nick by remember(key) { mutableStateOf(contact?.nickname ?: "") }
    var note by remember(key) { mutableStateOf(People.note(key)) }
    var muting by remember { mutableStateOf(false) }
    var coloring by remember { mutableStateOf(false) }
    var introducing by remember { mutableStateOf(false) }
    if (muting) { MuteDialog(app, key, name) { muting = false }; return }
    if (coloring) { ColorDialog(app, key) { coloring = false }; return }
    if (introducing) { IntroduceDialog(app, key, name) { introducing = false; done() }; return }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun save() {
        if (contact != null && nick.trim() != (contact.nickname ?: "")) app.chat.rename(key, nick)
        if (note.trim() != People.note(key)) People.setNote(key, note)
    }
    ModalBottomSheet(onDismissRequest = { save(); done() }, sheetState = sheet, containerColor = Palette.panel,
        shape = Corner, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding()) {
            // Who: their initial, name and whether the 4 emoji were checked.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Palette.goldTint), contentAlignment = Alignment.Center) {
                    Text(name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?",
                        style = Type.big.copy(color = Palette.goldBright))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(name, style = Type.big, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when {
                        group != null -> "${group.state.members.size} people"
                        verified -> "Verified with 4 emoji"
                        contact != null -> "In touch, not checked yet"
                        else -> "Met close by"
                    }, style = Type.small)
                }
            }
            if (contact != null) {
                Field(nick, { nick = it.take(40) }, "Nickname (only you see it)", Modifier.padding(top = 12.dp))
                if (nick.isEmpty()) Text("Empty uses the name they chose: ${contact.name}.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            }

            SheetLabel("Role")
            val inRole = ROLES.firstOrNull { r -> folders.any { it.name.equals(r, true) && key in it.keys } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (ROLES + "None").forEach { r ->
                    val on = if (r == "None") inRole == null else inRole == r
                    SheetChip(r, on, Modifier.weight(1f)) { setRole(app, key, if (r == "None") null else r) }
                }
            }
            Text("Family and Work chats can keep messages for up to 30 days. Roles are only on this phone.", style = Type.small, modifier = Modifier.padding(top = 6.dp))

            if (contact != null) {
                SheetLabel("What they can do")
                val can = people.can[key] ?: People.can(key)
                var ra by remember(key) { mutableStateOf(app.power.isRightAway(key)) }
                ToggleRow("Always right away", "Their messages arrive and ring at once, even when saving battery", ra) {
                    app.power.setRightAway(key, it); ra = it
                }
                ToggleRow("Calls and video", "They can call you", can.calls) { People.setCan(key, can.copy(calls = it)) }
                ToggleRow("Send me files", "Photos stay on; this is for other files", can.files) { People.setCan(key, can.copy(files = it)) }
                ToggleRow("See when I'm active", "A dot shows them when Muninn is open", can.active) { People.setCan(key, can.copy(active = it)) }
            }

            if (contact != null || group != null || app.chat.conversations.value[key].orEmpty().isNotEmpty()) {
                SheetLabel("Where this chat sits")
                val pinned = key in people.pinned
                val muted = (flagState.muted[key] ?: 0L) > System.currentTimeMillis()
                val archived = key in flagState.archived
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetChip(if (pinned) "Pinned" else "Pin", pinned) { People.pin(key, !pinned) }
                    if (moves != null) {
                        SheetChip("Move up", false, enabled = moves.up != null) { moves.up?.invoke() }
                        SheetChip("Move down", false, enabled = moves.down != null) { moves.down?.invoke() }
                    }
                    SheetChip(if (muted) "Muted" else "Mute", muted) {
                        if (muted) { app.chat.flags.mute(key, 0); Ui.say("$name isn't muted any more.") } else muting = true
                    }
                    SheetChip(if (archived) "Archived" else "Archive", archived) {
                        app.chat.flags.archive(key, !archived)
                        Ui.say(if (archived) "$name is back on the chat list." else "$name is archived. It comes back when a message arrives.")
                    }
                    SheetChip("Mark unread", key in flagState.unread) { app.chat.flags.markUnread(key, key !in flagState.unread) }
                    SheetChip("Chat color", false) { coloring = true }
                }
            }

            if (contact != null) {
                SheetLabel("Note")
                Field(note, { note = it.take(500) }, "Only you see this", single = false)
            }

            Spacer(Modifier.size(16.dp))
            if (contact != null) {
                MButton("Introduce $name to…", Modifier.fillMaxWidth(), icon = "people") { save(); introducing = true }
                Spacer(Modifier.size(8.dp))
                MButton("Start a group with $name", Modifier.fillMaxWidth(), icon = "chat") { save(); done(); ChatNav.open = ""; ChatNav.pick = setOf(key) }
                Spacer(Modifier.size(8.dp))
            }
            if (group == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton(if (verified) "Check 4 emoji again" else "Check 4 emoji", Modifier.weight(1f), icon = "shield") {
                    save(); done()
                    Ui.sure(Ask("Check it's really $name", "${app.chat.emojiWith(key)}\n\nNumber: ${app.chat.codeWith(key)}\n\nAsk them to open your chat and tap Check 4 emoji. " +
                        "If their screen shows the same 4 pictures (or the same number on Huginn), nobody is in between.",
                        "They match", onConfirm = { app.chat.verify(key, name) }))
                }
                MButton("Block", Modifier.weight(1f), Kind.Danger, icon = "mute") {
                    save(); done()
                    Ui.sure(Ask("Block $name?", "Nothing from them reaches you, here or in the Nearby room, and their chat is hidden. They aren't told.",
                        "Block", danger = true, onConfirm = { app.chat.block(key); Ui.say("$name blocked.") }))
                }
            }
            if (contact != null) MButton("Move to trash", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "delete") {
                save(); done()
                Ui.sure(Ask("Move ${contact.shown} to the trash?", TRASH_NOTE, "Move to trash", danger = true,
                    onConfirm = { app.chat.trashContact(key); Ui.say("${contact.shown} is in the trash.") }))
            }
            MButton("Done", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { save(); done() }
        }
    }
}

/** Puts [key] in the [role] folder (making it if it was deleted) and takes it out of the other two. Null: none. */
private fun setRole(app: Muninn, key: String, role: String?) {
    val fs = app.chat.folders.all.value
    ROLES.forEach { r -> fs.filter { it.name.equals(r, true) }.forEach { f -> if (r != role && key in f.keys) app.chat.folders.put(f.id, key, false) } }
    if (role == null) return
    val id = fs.firstOrNull { it.name.equals(role, true) }?.id ?: app.chat.folders.add(role) ?: return
    app.chat.folders.put(id, key, true)
}

@Composable
private fun SheetLabel(text: String) {
    Text(text.uppercase(), style = Type.section.copy(color = Palette.accent),
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).semantics { heading() })
}

/** A sharp-cornered choice: gold-tinted when on. */
@Composable
private fun SheetChip(text: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).clip(Corner).background(if (on) Palette.goldTint else Color.Transparent)
        .border(1.dp, if (on) Palette.gold else Palette.line, Corner)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { selected = on }
        .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.normal.copy(fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (!enabled) Palette.textFaint else if (on) Palette.goldBright else Palette.textSoft), maxLines = 1)
    }
}
