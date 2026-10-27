package io.github.ravenor907.muninn.tv

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.net.call.CallManager
import io.github.ravenor907.muninn.net.call.CallSession.Phase
import io.github.ravenor907.muninn.screens.CallActivity
import io.github.ravenor907.muninn.ui.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A call on the Kids' TV, made for the remote: one big focused button (Answer when it rings, Hang up
 * during the call), Back declines a ring. Shown by CallActivity in kids' mode.
 */
@Composable
fun TvCallScreen(onAnswer: () -> Unit, onClose: () -> Unit) {
    val c by CallManager.call.collectAsState()
    val context = LocalContext.current
    val mic = remember { TvAudio.hasMic(context) }
    val call = c
    BackHandler { if (call?.phase == Phase.INCOMING) CallManager.decline() else onClose() }
    Box(Modifier.fillMaxSize().background(Palette.ground), contentAlignment = Alignment.Center) {
        if (call == null) { Text("Call ended", style = tvTitle(34.sp)); return@Box }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) {
            val initial = call.name.trim().let { if (it.isEmpty()) "?" else String(Character.toChars(it.codePointAt(0))).uppercase() }
            Box(Modifier.size(170.dp).clip(CircleShape).background(if (call.peerSpeaking) Palette.gold else Palette.goldTint), contentAlignment = Alignment.Center) {
                Box(Modifier.size(150.dp).clip(CircleShape).background(Palette.goldTint), contentAlignment = Alignment.Center) {
                    Text(initial, style = tvText(64.sp, Palette.goldBright, FontWeight.SemiBold))
                }
            }
            Text(call.name, style = tvTitle(44.sp), modifier = Modifier.padding(top = 22.dp))
            Text(status(call), style = tvText(26.sp, Palette.textSoft), modifier = Modifier.padding(top = 8.dp))
            if (!mic && call.phase != Phase.ENDED) Text("This TV has no microphone, so they can't hear you. Plug one in, or ask a parent.",
                style = tvText(20.sp, Palette.danger), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
            else if (call.note.isNotEmpty() && call.phase != Phase.ENDED) Text(call.note, style = tvText(20.sp, Palette.danger), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp))
            val first = remember { FocusRequester() }
            Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                when (call.phase) {
                    Phase.INCOMING -> {
                        Box(Modifier.width(300.dp)) { BigButton("Answer", icon = "call", requester = first, onClick = onAnswer) }
                        Box(Modifier.width(300.dp)) { BigButton("Not now", icon = "close") { CallManager.decline() } }
                    }
                    Phase.ENDED -> Box(Modifier.width(300.dp)) { BigButton("OK", requester = first) { CallManager.dismiss(); onClose() } }
                    else -> {
                        Box(Modifier.width(300.dp)) { BigButton("Hang up", icon = "close", requester = first) { CallManager.hangup() } }
                        if (call.phase == Phase.LIVE) Box(Modifier.width(300.dp)) {
                            BigButton(if (call.muted) "Unmute" else "Mute", icon = "mute", sub = if (call.muted) "They can't hear you" else null) { CallManager.setMuted(!call.muted) }
                        }
                    }
                }
            }
            LaunchedEffect(call.phase == Phase.INCOMING, call.phase == Phase.ENDED) { delay(80); try { first.requestFocus() } catch (_: Exception) {} }
        }
    }
}

@Composable
private fun status(c: CallManager.Ui): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.phase, c.connectedAt) { while (c.phase == Phase.LIVE) { now = System.currentTimeMillis(); delay(500) } }
    return when (c.phase) {
        Phase.CALLING -> "Calling…"
        Phase.RINGING -> "Ringing…"
        Phase.INCOMING -> "is calling you"
        Phase.CONNECTING -> "Connecting…"
        Phase.RECONNECTING -> "Reconnecting…"
        Phase.LIVE -> ((now - c.connectedAt) / 1000).coerceAtLeast(0).let { s -> "%d:%02d".format(s / 60, s % 60) }
        Phase.ENDED -> when (c.endWhy) {
            "declined" -> if (c.outgoing) "They can't talk right now" else "Call ended"
            "busy" -> "They're on another call"
            "noanswer" -> "No answer. Try again later."
            "nopath", "error" -> "The call couldn't connect"
            "missed" -> "Missed call"
            else -> "Call ended"
        }
    }
}

/**
 * Ringing on the TV. A TV has no ring volume anyone sets, so the raven call plays on the media stream
 * (the volume the TV is at), and takes audio focus for a moment, which pauses or ducks a show or game.
 * The call screen is also brought up over whatever is on (full-screen intent from the call notification,
 * and a direct start, which Android allows when Muninn may show over other apps).
 */
object TvRing {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val gap = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var watching = false
    private var lastCall = ""

    /** Called from the call ringer: true when this is a Kids' TV and the TV rang (or stopped) itself. */
    @Synchronized
    fun instead(c: Context, on: Boolean): Boolean {
        if (!TvMic.active) return false
        if (on) start(c) else stop(c)
        return true
    }

    private fun start(c: Context) {
        if (player != null) return
        val am = c.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        } catch (_: Exception) {}
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(c, Uri.parse("android.resource://${c.packageName}/${R.raw.raven_message}"))
                setOnCompletionListener { mp -> gap.postDelayed({ if (player === mp) try { mp.start() } catch (_: Exception) {} }, 1_000) }
                prepare(); start()
            }
        } catch (_: Exception) { player?.release(); player = null }
    }

    private fun stop(c: Context) {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        focus?.let { try { c.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focus = null
    }

    /** Once, on a Kids' TV: every new incoming call brings the call screen up. */
    fun watch(c: Context) {
        if (watching) return
        watching = true
        val app = c.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            CallManager.call.collect { ui ->
                if (ui != null && ui.phase == Phase.INCOMING && ui.callId != lastCall && TvMic.active) {
                    lastCall = ui.callId
                    try {
                        app.startActivity(Intent(app, CallActivity::class.java).setAction(CallActivity.SHOW)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
