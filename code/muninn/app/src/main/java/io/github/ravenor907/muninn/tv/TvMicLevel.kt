package io.github.ravenor907.muninn.tv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import kotlinx.coroutines.delay

/**
 * The microphone level on the TV itself, for anyone in the room (no parent needed): Left and Right on
 * the remote (or Quieter and Louder) move it in steps of 10, it takes effect at once, a call going on
 * included, and the parents' phones show it (7.7). A meter shows what the microphone hears: the call's
 * own sound during a call, else a short test that listens while this is open.
 */
@Composable
fun TvMicDialog(app: Muninn, onClose: () -> Unit) {
    val level by KidTv.micLevel.collectAsState()
    val context = LocalContext.current
    fun set(v: Int) = KidTv.setMic(app, v.coerceIn(0, TvRules.MAX_MIC))
    val inCall = io.github.ravenor907.muninn.net.call.CallManager.call.value != null
    DisposableEffect(Unit) {
        val test = if (!inCall && MicTest.allowed(context)) MicTest.start() else false
        onDispose { if (test) MicTest.stop() }
    }
    var meter by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { meter = TvMic.meter; delay(80) } }
    TvDialog(onBack = onClose) {
        MIcon("mic", Palette.goldBright, 48.dp)
        Text("Microphone level", style = tvTitle(30.sp), modifier = Modifier.padding(top = 10.dp))
        Text("Press Left or Right. Louder helps when they can't hear you; quieter when you sound too loud.",
            style = tvText(18.sp, Palette.textMuted), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
        val bar = remember { FocusRequester() }
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(14.dp)
        Box(Modifier.width(520.dp).height(64.dp).focusRequester(bar).onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { set(level - 10); true }
                    Key.DirectionRight -> { set(level + 10); true }
                    else -> false
                }
            }
            .focusable()
            .semantics { contentDescription = "Microphone level $level of 100. Left is quieter, right is louder." }
            .clip(shape).background(Palette.field)
            .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
            .padding(10.dp)) {
            Box(Modifier.fillMaxWidth(level / 100f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Palette.gold))
        }
        Text("$level", style = tvTitle(34.sp), modifier = Modifier.padding(top = 10.dp))
        // What the microphone hears right now.
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val lit = (meter * 20).toInt()
            for (i in 0 until 20) Box(Modifier.width(18.dp).height(14.dp).clip(RoundedCornerShape(3.dp))
                .background(if (i < lit) (if (i >= 17) Palette.danger else Palette.online) else Palette.panelLow))
        }
        Text(if (inCall || MicTest.running) "Talk to see what the microphone hears." else "Muninn needs the microphone to show what it hears.",
            style = tvText(15.sp, Palette.textMuted), modifier = Modifier.padding(top = 6.dp))
        DialogButtons(listOf("Quieter" to { set(level - 10) }, "Louder" to { set(level + 10) }, "Done" to onClose))
        LaunchedEffect(Unit) { delay(120); try { bar.requestFocus() } catch (_: Exception) {} }
    }
}

/** Listens while the level dialog is open (not during a call or a recording), only to move its meter. */
private object MicTest {
    @Volatile var running = false; private set
    @Volatile private var thread: Thread? = null

    fun allowed(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        !TvRecorder.recording.value

    fun start(): Boolean {
        if (running) return true
        val rec = TvMic.open(16_000, 320) ?: return false
        running = true
        thread = Thread({
            TvMic.audioThread()
            val proc = TvMic.clipProcessor(16_000)
            val gain = TvMic.gainFor(proc)
            val raw = ShortArray(320); val pcm = ShortArray(320)
            try {
                rec.startRecording()
                while (running) {
                    val n = TvMic.readFully(rec, raw, 320)
                    if (n <= 0) break
                    if (proc != null && n == 320) proc.capture(raw, n, pcm) else System.arraycopy(raw, 0, pcm, 0, n)
                    gain.process(pcm, n)
                    TvMic.measure(pcm, n)
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release(); proc?.close()
                running = false
            }
        }, "muninn-tv-mictest").apply { isDaemon = true; start() }
        return true
    }

    fun stop() { running = false; thread?.join(500); thread = null }
}
