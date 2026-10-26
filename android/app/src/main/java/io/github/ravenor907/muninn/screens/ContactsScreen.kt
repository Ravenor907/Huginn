package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/** Settings › Contacts: everyone you're in touch with or met close by, and the blocked list. Blocking lives here, not in chats. */
@Composable
fun ContactsScreen(app: Muninn, back: () -> Unit) {
    val contacts by app.chat.contacts.all.collectAsState()
    val met by app.chat.met.collectAsState()
    val blocked by app.chat.blocked.collectAsState()
    data class P(val key: String, val name: String, val detail: String, val contact: Boolean)
    // A nickname shows as "Nickname (their name)".
    val people = (contacts.map { P(it.chatKey, ChatWire.listName(it),
        when { it.keyChanged -> "In touch · security key changed"; it.verified -> "In touch · verified"; else -> "In touch" }, true) } +
        met.filterKeys { k -> contacts.none { it.chatKey == k } }.map { (k, m) -> P(k, m.name, "Met close by", false) })
    val active = people.filter { it.key !in blocked }.sortedBy { it.name.lowercase() }
    val off = blocked.map { k -> people.firstOrNull { it.key == k } ?: P(k, app.chat.nameOf(k), "", false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Section("Contacts") { MButton("Back", icon = "back") { back() } }
        LazyColumn(Modifier.weight(1f)) {
            if (active.isEmpty()) item { Text("Nobody yet. People you chat with show up here.", style = Type.normal) }
            items(active, key = { "a" + it.key }) { p ->
                Line(p.name, p.detail, icon = "user") {
                    Ui.sure(Ask(p.name, "Block: nothing from them reaches you, here or in the Nearby room, and their chat is hidden. They aren't told.",
                        "Block", danger = true,
                        extra = if (p.contact) "Remove contact" else null,
                        onExtra = { app.chat.removeContact(p.key); Ui.say("${p.name} removed. Your chat with them stays.") },
                        onConfirm = { app.chat.block(p.key); Ui.say("${p.name} blocked.") }))
                }
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
}
