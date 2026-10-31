package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.net.call.CallManager
import io.github.ravenor907.muninn.net.call.CallSession
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import java.text.DateFormat
import java.util.Date

/**
 * The Calls tab: the call going on (tap to go back to it), then every call made and missed, newest first.
 * Tap a row to call back. [onOpenChat]: long-press opens the chat with that person, when the host passes it.
 */
@Composable
fun CallsScreen(onOpenChat: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val history by CallManager.history.collectAsState()
    val now by CallManager.call.collectAsState()
    if (history.isEmpty() && now == null) { Empty(); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        now?.takeIf { it.phase != CallSession.Phase.ENDED }?.let { c ->
            item(key = "now") {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape).background(Palette.goldTint)
                    .clickable(role = Role.Button) { CallActivity.show(context) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MIcon("call", Palette.goldBright, 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Return to call with ${c.name}", style = Type.normal.copy(color = Palette.goldBright), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
            }
        }
        if (history.isNotEmpty()) item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("RECENT", style = Type.section.copy(color = Palette.accent), modifier = Modifier.weight(1f))
                IconAction("delete", "Clear call history", Palette.textMuted) {
                    Ui.sure(Ask("Clear call history?", "This removes every call from the list on this phone.", "Clear", danger = true) { CallManager.clearHistory() })
                }
            }
        }
        items(history, key = { it.id }) { l -> HistoryRow(l, { CallActivity.call(context, l.peerKey) }, onOpenChat?.let { o -> { o(l.peerKey) } }) }
    }
}

@Composable
private fun HistoryRow(l: CallManager.Log, onCall: () -> Unit, onChat: (() -> Unit)?) {
    val missed = l.result == "missed"
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onChat ?: onCall)
        .padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.goldTint), contentAlignment = Alignment.Center) {
            // Sized to the disc, so the initial stays inside it at any font size.
            val letter = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toSp() }
            Text(l.name.trim().firstOrNull()?.uppercase() ?: "?", style = Type.big.copy(color = Palette.goldBright, fontSize = letter, lineHeight = letter), maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.name, style = Type.big.copy(color = if (missed) Palette.danger else Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(describe(l), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction("call", "Call ${l.name}", Palette.accent, onClick = onCall)
    }
}

private fun describe(l: CallManager.Log): String {
    val dir = when (l.result) {
        "missed" -> "Missed"
        "declined" -> if (l.outgoing) "Declined" else "You declined"
        "noanswer" -> "No answer"
        "busy" -> "Busy"
        "failed" -> "Couldn't connect"
        else -> if (l.outgoing) "Outgoing" else "Incoming"
    }
    val len = if (l.seconds > 0) " · " + (if (l.seconds >= 3600) "%d:%02d:%02d".format(l.seconds / 3600, l.seconds / 60 % 60, l.seconds % 60)
        else "%d:%02d".format(l.seconds / 60, l.seconds % 60)) else ""
    return dir + len + " · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(l.at))
}

@Composable
private fun Empty() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.coolTint), contentAlignment = Alignment.Center) {
            MIcon("call", Palette.accent, 32.dp)
        }
        Text("No calls yet", style = Type.big, modifier = Modifier.padding(top = 16.dp))
        Text("Call someone from their chat with the call button. Calls are end-to-end encrypted, and the calls you make and get show up here.",
            style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 360.dp))
    }
}
