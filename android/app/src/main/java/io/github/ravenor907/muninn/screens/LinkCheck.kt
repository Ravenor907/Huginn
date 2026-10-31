package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.net.Link
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type

/**
 * Linked devices (docs/protocol-0.6.md section 15): before anything is mirrored, both screens show the 4 emoji of the
 * two devices' key and the person says they match. Until then nothing goes either way.
 */
@Composable
fun LinkCheck() {
    val companions by Link.companions.collectAsState()
    val phone by Link.phone.collectAsState()
    val d = companions.firstOrNull { !it.ok } ?: phone?.takeIf { !it.ok } ?: return
    val emoji = Link.emojiWith(d.key)
    AlertDialog(onDismissRequest = {}, containerColor = Palette.panel,
        title = { Text("Check the link with ${d.name}", style = Type.big) },
        text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("These must be the same on both screens:", style = Type.normal)
            Text(emoji, style = Type.big, modifier = Modifier.padding(vertical = 12.dp))
            Text(if (phone?.key == d.key) "Your phone's chats show here only after you tap They match."
                else "${d.name} sees your chats and can send as you only after you tap They match.", style = Type.small)
        } },
        confirmButton = { MButton("They match", kind = Kind.Accent) { Link.confirm(d.key) } },
        dismissButton = { MButton("Don't link") { Link.unlink(d.key) } })
}

/** On the chat list while this phone's chats are mirrored somewhere: never silent. */
@Composable
fun MirroredNote(modifier: Modifier = Modifier) {
    val companions by Link.companions.collectAsState()
    val on = companions.filter { it.ok }
    if (on.isEmpty()) return
    Panel(modifier.fillMaxWidth()) {
        Text("Mirrored to " + on.joinToString(", ") { it.name }, style = Type.normal.copy(color = Palette.accent))
        Text("They show your chats and can send as you. Unlink in Settings › Linked devices.", style = Type.small)
    }
}
