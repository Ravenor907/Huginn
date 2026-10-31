package io.github.ravenor907.muninn.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Limits
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

// Message limits (cap d1, shared/docs/protocol-0.6.md section 16): what the screens show and change.

/** The chat list, once: every chat deletes messages after its limit. */
@Composable
fun LimitsNote(modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.getSharedPreferences("muninn", Context.MODE_PRIVATE)
    var show by remember { mutableStateOf(prefs.getBoolean(LIMITS_NOTE, false)) }
    if (!show) return
    Panel(modifier.fillMaxWidth()) {
        Text("Messages disappear after 7 days", style = Type.big)
        Text("Every chat deletes messages, photos, voice clips, videos and files older than its limit, on every phone. " +
            "Family and Work groups, and people in your Family or Work folder, can keep them up to 30 days.", style = Type.small)
        MButton("Got it", Modifier.padding(top = 8.dp), Kind.Accent) { show = false; prefs.edit().putBoolean(LIMITS_NOTE, false).apply() }
    }
}

/** Set once by Chat, on a start with chats already on this phone and no note shown yet. */
const val LIMITS_NOTE = "limitsNote"

/** A 1:1 chat's limit: mine (for a Family or Work contact), theirs, and which one counts. */
@Composable
fun ChatLimitDialog(app: Muninn, key: String, name: String, done: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // redraw when their limit arrives
    @Suppress("UNUSED_VARIABLE") val folders by app.chat.folders.all.collectAsState()
    LaunchedEffect(key) { app.chat.probeCaps(key) }
    val c = app.chat.contacts.get(key)
    val close = app.chat.isClose(key)
    val mine = app.chat.myDays(key)
    val days = app.chat.chatDays(key)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text(Limits.words(days), style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Older messages, photos, voice clips, videos and files in this chat are deleted on both phones.", style = Type.normal)
                if (close && c != null) {
                    Text("You keep them for", style = Type.small)
                    io.github.ravenor907.muninn.ui.ChoiceRow(Limits.CHOICES.size) { i, m ->
                        val d = Limits.CHOICES[i]
                        MButton("$d days", m, if (mine == d) Kind.Accent else Kind.Normal) { app.chat.setKeep(key, d) }
                    }
                    Text("If you and $name pick differently, the shorter one counts.", style = Type.small)
                } else Text("Put $name in your Family or Work folder to keep messages up to 30 days.", style = Type.small)
                when {
                    c == null -> {}
                    app.chat.hasD1(key) -> Text("$name keeps them for ${c.days.takeIf { it > 0 } ?: Limits.DEFAULT} days.", style = Type.small)
                    c.caps.isNotEmpty() -> Text("$name's app is older: it keeps messages until it updates. This phone still deletes them.",
                        style = Type.small.copy(color = Palette.textSoft))
                }
            }
        },
        confirmButton = { MButton("Done", kind = Kind.Accent) { done() } },
    )
}

/** Group settings: the group's type and limit. Only admins change it; the same for everyone in the group. */
@Composable
fun GroupLimitPanel(app: Muninn, g: String, s: Proto06.State, admin: Boolean) {
    val days = Limits.groupDays(s)
    Section("Messages")
    Text("${Limits.kindName(s.kind)} group · ${Limits.words(days)}", style = Type.normal)
    if (admin) {
        Text("Type", style = Type.small, modifier = Modifier.padding(top = 8.dp))
        val kinds = listOf("") + Limits.KINDS
        io.github.ravenor907.muninn.ui.ChoiceRow(kinds.size, Modifier.padding(top = 4.dp), minWidth = 88.dp) { i, m ->
            val k = kinds[i]
            MButton(Limits.kindName(k), m, if (s.kind == k) Kind.Accent else Kind.Normal) {
                if (s.kind != k) app.chat.groups.setLimit(g, k, if (k.isEmpty()) Limits.DEFAULT else days)?.let { Ui.say(it, true) }
            }
        }
        if (s.kind.isNotEmpty()) {
            Text("Keep messages for", style = Type.small, modifier = Modifier.padding(top = 8.dp))
            io.github.ravenor907.muninn.ui.ChoiceRow(Limits.CHOICES.size, Modifier.padding(top = 4.dp)) { i, m ->
                val d = Limits.CHOICES[i]
                MButton("$d days", m, if (days == d) Kind.Accent else Kind.Normal) {
                    if (days != d) app.chat.groups.setLimit(g, s.kind, d)?.let { Ui.say(it, true) }
                }
            }
        } else Text("Family and Work groups can keep messages up to 30 days.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
    }
    val me = app.identity.publicKey
    val older = s.members.filter { it.k != me && app.chat.contacts.get(it.k)?.caps?.let { c -> c.isNotEmpty() && Limits.CAP !in c } == true }
    if (older.isNotEmpty()) Text("Older apps keep messages until they update: " + older.joinToString(", ") { memberName(app, it) } + ".",
        style = Type.small.copy(color = Palette.textSoft), modifier = Modifier.padding(top = 4.dp))
}

/** New group: Standard, Family or Work (Family and Work can keep messages longer, set in its settings). */
@Composable
fun GroupKindPicker(kind: String, onKind: (String) -> Unit) {
    Text("Type", style = Type.small, modifier = Modifier.padding(top = 4.dp))
    val kinds = listOf("") + Limits.KINDS
    io.github.ravenor907.muninn.ui.ChoiceRow(kinds.size, Modifier.padding(top = 4.dp, bottom = 4.dp), minWidth = 88.dp) { i, m ->
        val k = kinds[i]
        MButton(Limits.kindName(k), m, if (kind == k) Kind.Accent else Kind.Normal) { onKind(k) }
    }
    Text(if (kind.isEmpty()) "Messages disappear after 7 days." else "Messages disappear after 7 days; admins can make it up to 30.", style = Type.small)
}
