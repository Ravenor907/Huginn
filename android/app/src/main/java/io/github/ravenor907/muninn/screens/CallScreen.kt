package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.net.call.CallManager
import io.github.ravenor907.muninn.net.call.CallSession.Phase
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.delay

/** The colors only this screen uses (the mockup's end-call red and answer green). */
private object CallColors {
    val end get() = if (Access.colorBlind) Color(0xFFB8741A) else Color(0xFFC4553F)
    val answer get() = if (Access.colorBlind) Color(0xFF2F6FB3) else Color(0xFF3E8A55)
    val ring1 get() = if (Access.contrast) Color(0xFF333333) else Color(0xFF1B1813)
    val ring2 get() = if (Access.contrast) Color(0xFF1A1A1A) else Color(0xFF141210)
    val encrypted get() = Palette.online
}

/** Call screen line icons (24-unit paths, drawn like MIcon). */
private object CallIcons {
    const val down = "M6 9l6 6 6-6"
    const val shield = "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"
    const val micOff = "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3 M4 4l16 16"
    const val mic = "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3"
    const val speaker = "M4 9h4l5-4v14l-5-4H4z M16 9a4 4 0 0 1 0 6 M18.5 6.5a8 8 0 0 1 0 11"
    const val message = "M4 5h16v11H9l-5 4z"
    const val hangup = "M3 15c5-5 13-5 18 0l-2.5 2.5-3.5-1.5v-2.5a10 10 0 0 0-6 0V16l-3.5 1.5z"
    const val answer = "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"
}

@Composable
private fun Glyph(path: String, color: Color, size: Dp, weight: Float = 1.8f) {
    val p = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) { drawPath(p, color, style = Stroke(width = weight, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/**
 * A 1:1 call (mockup PhoneCall): who, how long, which paths carry it, mute, speaker, message, end.
 * Incoming, it offers Decline and Answer instead. Never shows an address of any kind.
 * [hideName]: panic-locked or previews off on the lock screen: "Muninn call" instead of the name.
 */
@Composable
fun CallScreen(onBack: () -> Unit, onMessage: (String) -> Unit, onAnswer: () -> Unit, hideName: Boolean = false) {
    val ui by CallManager.call.collectAsState()
    val c = ui
    Column(Modifier.fillMaxSize().background(Palette.ground).systemBarsPadding().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Back to chat" },
                contentAlignment = Alignment.Center) { Glyph(CallIcons.down, Palette.text, 24.dp) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Glyph(CallIcons.shield, CallColors.encrypted, 14.dp, 2f)
                Spacer(Modifier.width(6.dp))
                Text("End-to-end encrypted", style = Type.small.copy(fontSize = 13.sp, color = CallColors.encrypted))
            }
            Spacer(Modifier.width(44.dp))
        }
        if (c == null) {
            Spacer(Modifier.weight(1f))
            Text("No call right now", style = Type.big)
            Spacer(Modifier.weight(1f))
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            val name = if (hideName) "Muninn call" else c.name
            Spacer(Modifier.height(56.dp))
            Avatar(name, c.peerSpeaking && c.phase == Phase.LIVE)
            Spacer(Modifier.height(36.dp))
            Text(name, style = Type.title.copy(fontSize = 30.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold), maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp))
            Spacer(Modifier.height(6.dp))
            Text(status(c), style = Type.normal.copy(fontSize = 17.sp, color = Palette.textMuted), textAlign = TextAlign.Center)
            if (c.peerMuted && c.phase == Phase.LIVE) Text("$name is muted", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            if (c.phase != Phase.INCOMING && c.phase != Phase.ENDED) {
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Internet", "through the relay", c.relay)
                    Chip("Wi-Fi", "same Wi-Fi", c.wifi)
                    Chip("Bluetooth", "close by", c.bluetooth)
                }
                Spacer(Modifier.height(10.dp))
                Text("Every path at once, so walking away doesn't drop the call", style = Type.small.copy(fontSize = 13.sp), textAlign = TextAlign.Center)
            }
            if (c.note.isNotEmpty() && c.phase != Phase.ENDED) Text(c.note, style = Type.small.copy(color = Palette.danger), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp))
        }
        when (c.phase) {
            Phase.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Round(CallIcons.hangup, "Decline", CallColors.end, Color.White, 76.dp) { CallManager.decline() }
                Round(CallIcons.answer, "Answer", CallColors.answer, Color.White, 76.dp, onClick = onAnswer)
            }
            Phase.ENDED -> Spacer(Modifier.height(106.dp))
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Round(if (c.muted) CallIcons.micOff else CallIcons.mic, if (c.muted) "Unmute" else "Mute", if (c.muted) Palette.accent else Palette.coolTint,
                        if (c.muted) Palette.ground else Palette.text, 68.dp, on = c.muted) { CallManager.setMuted(!c.muted) }
                    Round(CallIcons.speaker, "Speaker", if (c.speaker) Palette.accent else Palette.coolTint, if (c.speaker) Palette.ground else Palette.text, 68.dp,
                        on = c.speaker) { CallManager.setSpeaker(!c.speaker) }
                    Round(CallIcons.message, "Message", Palette.coolTint, Palette.text, 68.dp) { onMessage(c.peerKey) }
                }
                Spacer(Modifier.height(30.dp))
                Round(CallIcons.hangup, "End call", CallColors.end, Color.White, 76.dp, label = false) { CallManager.hangup() }
            }
        }
    }
}

@Composable
private fun status(c: CallManager.Ui): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.phase, c.connectedAt) { while (c.phase == Phase.LIVE) { now = System.currentTimeMillis(); delay(500) } }
    return when (c.phase) {
        Phase.CALLING -> "Calling"
        Phase.RINGING -> "Ringing"
        Phase.INCOMING -> "Incoming call"
        Phase.CONNECTING -> "Connecting"
        Phase.RECONNECTING -> "Reconnecting"
        Phase.LIVE -> clock(((now - c.connectedAt) / 1000).coerceAtLeast(0))
        Phase.ENDED -> when (c.endWhy) {
            "declined" -> if (c.outgoing) "Declined" else "Call declined"
            "busy" -> "Busy on another call"
            "noanswer" -> "No answer"
            "nopath" -> "Can't connect"
            "dropped" -> "Call dropped"
            "missed" -> "Missed call"
            "error" -> "Call failed"
            else -> "Call ended"
        }
    }
}

private fun clock(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

@Composable
private fun Avatar(name: String, speaking: Boolean) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    // Two soft rings, as in the mockup; the inner one brightens while they speak.
    Box(Modifier.size(176.dp).clip(CircleShape).background(CallColors.ring2), contentAlignment = Alignment.Center) {
        Box(Modifier.size(156.dp).clip(CircleShape).background(if (speaking) Palette.goldTint else CallColors.ring1), contentAlignment = Alignment.Center) {
            Box(Modifier.size(136.dp).clip(CircleShape).background(Palette.goldTint).semantics { contentDescription = if (speaking) "$name is speaking" else name },
                contentAlignment = Alignment.Center) {
                Text(initial, style = Type.big.copy(fontSize = 52.sp, color = Palette.goldBright, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun Chip(label: String, how: String, live: Boolean) {
    val shape = RoundedCornerShape(15.dp)
    var m = Modifier.heightIn(min = 30.dp).clip(shape)
    m = if (live) m.background(Palette.field) else m.border(1.dp, Palette.accentFill, shape)
    Row(m.padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "$label, $how"; stateDescription = if (live) "connected" else "not connected" },
        verticalAlignment = Alignment.CenterVertically) {
        val dot = Modifier.size(8.dp).clip(CircleShape)
        Box(if (live) dot.background(Palette.online) else dot.border(1.5.dp, Palette.lineMuted, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.small.copy(fontSize = 13.sp, color = if (live) Palette.text else Palette.textMuted))
    }
}

@Composable
private fun Round(icon: String, text: String, bg: Color, fg: Color, size: Dp, on: Boolean? = null, label: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = text; if (on != null) stateDescription = if (on) "on" else "off" }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { Glyph(icon, fg, if (size > 70.dp) 32.dp else 26.dp, 2f) }
        if (label) Text(text, style = Type.small.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
    }
}
