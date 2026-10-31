package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.Avatar
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/** The status choices on Your profile: what's sent, what it's called, and one short line about it. */
internal enum class Status(val key: String, val title: String, val hint: String) {
    ONLINE("online", "Online", ""),
    AWAY("away", "Away", "after 10 min idle"),
    DND("dnd", "Do not disturb", "no sounds"),
    INVISIBLE("invisible", "Invisible", "look offline");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: ONLINE
    }
}

/** The colours a person can pick for the initial-and-colour fallback, as hex. */
private val COLOURS = listOf("#C9A964", "#3D6A9E", "#8C5A4A", "#4C8A5A", "#7A4F9E", "#8A8A8A")

private fun colourOf(hex: String): Color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Palette.goldTint }

/** The colour of a status's square and dot. */
private fun statusColour(s: Status): Color = when (s) {
    Status.ONLINE -> Palette.online
    Status.AWAY -> if (Access.colorBlind) Palette.gold else Color(0xFFE0B44C)
    Status.DND -> Palette.danger
    Status.INVISIBLE -> Palette.textFaint
}

/**
 * Settings › Your profile: your picture, name and status, as your contacts see them. Nothing here goes to anyone
 * but your contacts, end-to-end encrypted like a message.
 */
@Composable
fun ProfileScreen(app: Muninn) {
    val id = app.identity
    val profile = app.profile
    val status by profile.myStatus.collectAsState()
    val custom by profile.myCustomStatus.collectAsState()
    val colour by profile.myColour.collectAsState()
    val pictures by profile.pictures.collectAsState()
    var name by remember { mutableStateOf(id.username) }
    var statusText by remember(custom) { mutableStateOf(custom) }
    var picking by remember { mutableStateOf(false) }
    val current = Status.of(status)

    // The big picture, name and status, centred like a card.
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Change picture") { picking = true }
            .semantics { contentDescription = "Your picture. Tap to change it" }) {
            Avatar("me", id.username, 112.dp, showDot = true)
        }
        Text(id.username, style = Type.big.copy(fontSize = 22.sp), modifier = Modifier.padding(top = 10.dp))
        Text(if (custom.isNotBlank()) "${current.title} · $custom" else current.title, style = Type.small, modifier = Modifier.padding(top = 2.dp))
        Text("Only your contacts see this", style = Type.small.copy(color = Palette.textFaint), modifier = Modifier.padding(top = 2.dp))
    }

    Section("Picture")
    MButton("Change picture", Modifier.fillMaxWidth(), Kind.Accent, icon = "image") { picking = true }
    if (pictures["me"] != null) MButton("Remove picture", Modifier.fillMaxWidth().padding(top = 6.dp), Kind.Danger) {
        profile.clearMyPicture(); Ui.say("Picture removed. Your contacts see your initial now.")
    }
    Text("Saved small (256 × 256) and sent only to your contacts, end-to-end encrypted like a message. No picture leaves your phone for anyone else.",
        style = Type.small, modifier = Modifier.padding(top = 4.dp))
    if (picking) PicturePickerSheet(app) { picking = false }

    Text("Colour of your initial", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 14.dp))
    Row(Modifier.padding(top = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        COLOURS.forEachIndexed { i, hex ->
            val on = colour.equals(hex, true)
            Box(Modifier.size(40.dp).clip(CircleShape).background(colourOf(hex))
                .border(if (on) 3.dp else 0.dp, if (on) Palette.text else Color.Transparent, CircleShape)
                .selectable(selected = on, role = Role.RadioButton) { profile.setMyColour(hex) }
                .semantics { contentDescription = "Colour ${i + 1} of ${COLOURS.size}" + if (on) ", chosen" else "" },
                contentAlignment = Alignment.Center) {
                if (on) MIcon("check", Palette.text, 20.dp)
            }
        }
    }
    Text("Shows with your initial when there's no picture.", style = Type.small, modifier = Modifier.padding(top = 4.dp))

    Section("Name")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(name, { name = it.take(24) }, "A name", Modifier.weight(1f))
        MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = name.isNotBlank() && name.trim() != id.username) {
            id.username = name; app.net.announce(); app.close.readvertise(); Ui.say("You're “${id.username}” now.")
        }
    }
    Text("What people see. It doesn't have to be your real name.", style = Type.small, modifier = Modifier.padding(top = 4.dp))

    Section("Status")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(statusText, { statusText = it.take(80) }, "Set a custom status…", Modifier.weight(1f))
        MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = statusText.trim() != custom) {
            profile.setCustomStatus(statusText.trim()); Ui.say(if (statusText.isBlank()) "Status cleared." else "Status saved.")
        }
    }
    Column(Modifier.padding(top = 8.dp).selectableGroup()) {
        Status.entries.forEach { s ->
            val on = s == current
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Corner).background(if (on) Palette.accentFill else Color.Transparent)
                .selectable(selected = on, role = Role.RadioButton) { profile.setStatus(s.key) }
                .semantics { contentDescription = s.title + (if (s.hint.isNotEmpty()) ", ${s.hint}" else "") + if (on) ", chosen" else "" }
                .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(Corner).background(statusColour(s)))
                Text(s.title, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (s.hint.isNotEmpty()) Text(s.hint, style = Type.small)
            }
        }
    }
    Text(when (current) {
        Status.ONLINE -> "Contacts see a green dot. After 10 minutes without a touch you show as Away, and Online again at the next one."
        Status.AWAY -> "Contacts see an amber dot."
        Status.DND -> "Contacts see a red dot. Messages don't make a sound and calls don't ring out loud; they still show."
        Status.INVISIBLE -> "Contacts see you as offline. You still get everything."
    }, style = Type.small, modifier = Modifier.padding(top = 4.dp))

    Section("More")
    ProfileRow("My code", "For someone to scan and add you") { SettingsNav.goChat = "code" }
    ProfileRow("Link a computer", "Use Muninn on a tablet or second phone too") { SettingsNav.folder = "link" }
    Spacer(Modifier.height(8.dp))
}

/** The picture picker, over the page while open. */
@Composable
private fun PicturePickerSheet(app: Muninn, onDone: () -> Unit) {
    io.github.ravenor907.muninn.ui.PicturePicker(onDone)
}

/** A row that opens something: its name, a line about it, and an arrow. */
@Composable
private fun ProfileRow(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.normal.copy(color = Palette.text))
            Text(detail, style = Type.small)
        }
        MIcon("forward", Palette.textMuted, 20.dp)
    }
}
