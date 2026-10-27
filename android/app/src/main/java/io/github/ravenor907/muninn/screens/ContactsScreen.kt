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
fun ContactsScreen(app: Muninn, back: (() -> Unit)? = null) {
    val contacts by app.chat.contacts.all.collectAsState()
    val met by app.chat.met.collectAsState()
    val blocked by app.chat.blocked.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
    data class P(val key: String, val name: String, val detail: String, val contact: Boolean)
    var picked by remember { mutableStateOf<P?>(null) }
    var foldering by remember { mutableStateOf<P?>(null) }
    fun inFolders(k: String) = folders.filter { k in it.keys }.joinToString(", ") { it.name }.let { if (it.isEmpty()) "" else " · $it" }
    // A nickname shows as "Nickname (their name)". Group peers aren't contacts: they're not listed.
    val real = contacts.filter { !it.groupOnly }
    val people = (real.map { P(it.chatKey, ChatWire.listName(it),
        when { it.keyChanged -> "In touch · security key changed"; it.verified -> "In touch · verified"; else -> "In touch" } + inFolders(it.chatKey), true) } +
        met.filterKeys { k -> real.none { it.chatKey == k } }.map { (k, m) -> P(k, m.name, "Met close by" + inFolders(k), false) })
    val active = people.filter { it.key !in blocked }.sortedBy { it.name.lowercase() }
    val off = blocked.map { k -> people.firstOrNull { it.key == k } ?: P(k, app.chat.nameOf(k), "", false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (back != null) Section("Contacts") { MButton("Back", icon = "back") { back() } }
        else Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (active.isEmpty()) item { Text("Nobody yet. People you chat with show up here.", style = Type.normal) }
            items(active, key = { "a" + it.key }) { p ->
                Line(p.name, p.detail, icon = "user", onLong = { foldering = p }) { picked = p }
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
                    MButton("Folders", Modifier.fillMaxWidth(), icon = "folder") { picked = null; foldering = p }
                    if (p.contact) MButton("Remove contact", Modifier.fillMaxWidth()) {
                        picked = null
                        Ui.sure(Ask("Remove ${p.name}?", "They're no longer a contact. Your chat with them stays.", "Remove", danger = true,
                            onConfirm = { app.chat.removeContact(p.key); Ui.say("${p.name} removed. Your chat with them stays.") }))
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
}
