package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Palette
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * The Contacts tab: everyone you're in touch with or met close by, and the blocked list. Blocking lives
 * here, not in chats, and so do folders for people. Group peers (in a group with you, not contacts) aren't
 * listed. [back]: shows a Back button (null in the tab, where the header names the page).
 */
@Composable
fun ContactsScreen(app: Muninn, back: (() -> Unit)? = null, onChats: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val contacts by app.chat.contacts.all.collectAsState()
    val met by app.chat.met.collectAsState()
    val blocked by app.chat.blocked.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
    val trashed by app.chat.trash.all.collectAsState()
    var showTrash by remember { mutableStateOf(false) }
    if (showTrash) { TrashScreen(app) { showTrash = false }; return }
    data class P(val key: String, val name: String, val detail: String, val contact: Boolean)
    var picked by remember { mutableStateOf<P?>(null) }
    var foldering by remember { mutableStateOf<P?>(null) }
    var renaming by remember { mutableStateOf<P?>(null) }
    fun inFolders(k: String) = folders.filter { k in it.keys }.joinToString(", ") { it.name }.let { if (it.isEmpty()) "" else " · $it" }
    // A nickname shows as "Nickname (their name)". Group peers aren't contacts: they're not listed, and nor is anyone in the trash.
    val real = contacts.filter { !it.groupOnly && it.chatKey !in trashed }
    val people = (real.map { P(it.chatKey, ChatWire.listName(it),
        when { it.keyChanged -> "In touch · security key changed"; it.verified -> "In touch · verified"; else -> "In touch" } + inFolders(it.chatKey), true) } +
        met.filterKeys { k -> real.none { it.chatKey == k } && k !in trashed }.map { (k, m) -> P(k, m.name, "Met close by" + inFolders(k), false) })
    val active = people.filter { it.key !in blocked }.sortedBy { it.name.lowercase() }
    val off = blocked.map { k -> people.firstOrNull { it.key == k } ?: P(k, app.chat.nameOf(k), "", false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (back != null) Section("Contacts") { MButton("Back", icon = "back") { back() } }
        else Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (active.isEmpty()) item {
                Text("Nobody yet. Add someone by scanning their code, and they show up here.", style = Type.normal)
                MButton("Add someone", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, icon = "plus") { ChatNav.open = "add"; onChats() }
            } else item { Text("Tap someone to message or call them. Hold for their role and what they can do.", style = Type.small, modifier = Modifier.padding(bottom = 6.dp)) }
            items(active, key = { "a" + it.key }) { p ->
                Line(p.name, p.detail, icon = "user", onLong = { ChatNav.holdMoves = null; ChatNav.hold = p.key }) { picked = p }
            }
            item {
                Line("Trash", if (trashed.isEmpty()) "Empty" else "${trashed.size} · deleted for good after ${io.github.ravenor907.muninn.net.Trash.DAYS} days",
                    icon = "delete") { showTrash = true }
            }
            if (off.isNotEmpty()) item { Section("Blocked") }
            items(off, key = { "b" + it.key }) { p ->
                Line(p.name, "Blocked · tap to unblock", icon = "mute") {
                    Ui.sure(Ask("Unblock ${p.name}?", "Their messages reach you again, and your chat with them comes back.", "Unblock",
                        onConfirm = { app.chat.block(p.key, false) }))
                }
            }
        }
    }
    picked?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picked = null },
            containerColor = Palette.panel,
            title = { Text(p.name, style = Type.big) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.detail, style = Type.small)
                    MButton("Message", Modifier.fillMaxWidth(), Kind.Accent, icon = "chat") { picked = null; ChatNav.open = p.key; onChats() }
                    if (p.contact && io.github.ravenor907.muninn.net.call.CallManager.canCall(p.key)) MButton("Call", Modifier.fillMaxWidth(), icon = "call") {
                        picked = null; CallActivity.call(context, p.key)
                    }
                    MButton("Role and permissions", Modifier.fillMaxWidth(), icon = "people") { picked = null; ChatNav.holdMoves = null; ChatNav.hold = p.key }
                    // A name for them on this phone only: it's never sent.
                    if (p.contact) MButton("Nickname", Modifier.fillMaxWidth(), icon = "user") { picked = null; renaming = p }
                    MButton("Folders", Modifier.fillMaxWidth(), icon = "folder") { picked = null; foldering = p }
                    if (p.contact) MButton("Move to trash", Modifier.fillMaxWidth(), icon = "delete") {
                        picked = null
                        Ui.sure(Ask("Move ${p.name} to the trash?", TRASH_NOTE, "Move to trash", danger = true,
                            onConfirm = { app.chat.trashContact(p.key); Ui.say("${p.name} is in the trash.") }))
                    }
                    MButton("Block", Modifier.fillMaxWidth(), Kind.Danger) {
                        picked = null
                        Ui.sure(Ask("Block ${p.name}?", "Block: nothing from them reaches you, here or in the Nearby room, and their chat is hidden. They aren't told.",
                            "Block", danger = true, onConfirm = { app.chat.block(p.key); Ui.say("${p.name} blocked.") }))
                    }
                }
            },
            confirmButton = { MButton("Close") { picked = null } },
        )
    }
    foldering?.let { p -> FolderPicker(app, p.key, p.name) { foldering = null } }
    renaming?.let { p ->
        val c = app.chat.contacts.contact(p.key)
        if (c == null) renaming = null
        else RenameDialog(c) { nick ->
            if (nick != null) {
                app.chat.rename(p.key, nick)
                Ui.say(if (ChatWire.nickname(nick) == null) "Back to ${c.name}." else "Renamed to ${ChatWire.nickname(nick)}.")
            }
            renaming = null
        }
    }
}
