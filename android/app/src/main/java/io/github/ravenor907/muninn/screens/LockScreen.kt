package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.Brand
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.PatternPad
import io.github.ravenor907.muninn.ui.patternSecret
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState

/** Shown while Muninn is locked with a password. Nothing else is readable until it's typed. */
@Composable
fun LockScreen(app: Muninn, done: (Boolean) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val ask by Ui.ask.collectAsState()
    val scope = rememberCoroutineScope()
    val prefs = remember { app.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE) }
    val pattern = remember { app.vault.usesPattern }
    // After every 5 wrong tries, wait 30 seconds (kept across restarts), so guessing is slow.
    var waitUntil by remember { mutableStateOf(prefs.getLong("lockUntil", 0L)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(waitUntil) { while (System.currentTimeMillis() < waitUntil) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }; now = System.currentTimeMillis() }
    val waiting = now < waitUntil
    fun tryIt(secret: String = pw) {
        if (waiting || busy) return
        busy = true; wrong = false
        scope.launch {
            val ok = withContext(Dispatchers.Default) { app.vault.unlock(secret) }   // the key stretching takes a moment
            busy = false
            if (ok) { pw = ""; prefs.edit().putInt("lockFails", 0).apply(); done(true) }
            else {
                wrong = true
                val fails = prefs.getInt("lockFails", 0) + 1
                prefs.edit().putInt("lockFails", fails).apply()
                // Every 5 wrong tries the wait doubles: 30 s, 1 min, 2 min ... up to an hour.
                if (fails % 5 == 0) {
                    val wait = minOf(3_600_000L, 30_000L shl minOf(fails / 5 - 1, 7))
                    waitUntil = System.currentTimeMillis() + wait; prefs.edit().putLong("lockUntil", waitUntil).apply()
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding()) {
        Brand {}
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel {
                Text("Muninn is locked", style = Type.big)
                Text(if (pattern) "Draw your pattern. Your messages, contacts and keys stay encrypted until you do."
                    else "Type your password. Your messages, contacts and keys stay encrypted until you do.", style = Type.normal)
                if (pattern) {
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        PatternPad(error = wrong, enabled = !waiting && !busy) { dots -> tryIt(patternSecret(dots)) }
                    }
                } else {
                    Field(pw, { pw = it; wrong = false }, "Password", Modifier.padding(top = 10.dp), secret = true,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                when {
                    waiting -> Text("Too many tries. Try again in ${(waitUntil - now + 999) / 1000} s.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
                    busy -> Text("Opening…", style = Type.normal, modifier = Modifier.padding(top = 6.dp))
                    wrong -> Text("That's not it. Try again.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
                }
                if (!pattern) MButton(if (busy) "Opening…" else "Unlock", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = pw.isNotEmpty() && !busy && !waiting) { tryIt() }
            }
            Text("Forgot it? There's no way back in without it. You can erase Muninn and start fresh.", style = Type.small)
            MButton("Erase everything", Modifier.fillMaxWidth(), Kind.Danger) {
                Ui.sure(Ask("Erase everything?", "Every message, contact and key on this phone is deleted for good. Files you received stay.",
                    "Hold to erase", danger = true, hold = true) { app.vault.eraseAll(); android.os.Process.killProcess(android.os.Process.myPid()) })
            }
        }
    }
    ask?.let { AskDialog(it) }
}

/**
 * Choose and confirm a lock: a 9-dot pattern (at least 4 dots) or a password (at least 4 characters).
 * Used on first open (required) and for Settings › Change lock. [onCancel] null = can't skip.
 */
@Composable
fun LockSetup(app: Muninn, onCancel: (() -> Unit)? = null, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var usePattern by remember { mutableStateOf(true) }
    var first by remember { mutableStateOf<List<Int>?>(null) }
    var note by remember { mutableStateOf("") }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    fun save(secret: String, pattern: Boolean) {
        saving = true
        scope.launch {
            withContext(Dispatchers.Default) { app.vault.setPassword(secret, pattern) }
            saving = false
            Ui.say(if (pattern) "Pattern set. Don't forget it: it can't be recovered." else "Password set. Don't lose it: it can't be recovered.")
            onDone()
        }
    }
    Column {
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Pattern", Modifier.weight(1f), if (usePattern) Kind.Accent else Kind.Normal) { usePattern = true; first = null; note = "" }
            MButton("Password", Modifier.weight(1f), if (!usePattern) Kind.Accent else Kind.Normal) { usePattern = false; first = null; note = "" }
        }
        if (usePattern) {
            Text(when {
                saving -> "Saving…"
                note.isNotEmpty() -> note
                first == null -> "Draw a pattern: connect at least 4 dots."
                else -> "Draw it again to confirm."
            }, style = Type.normal.copy(color = if (note.isNotEmpty()) Palette.danger else Palette.text), modifier = Modifier.padding(top = 10.dp))
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                PatternPad(error = note.isNotEmpty(), enabled = !saving) { dots ->
                    val f = first
                    when {
                        dots.size < 4 -> note = "Connect at least 4 dots."
                        f == null -> { first = dots; note = "" }
                        f != dots -> { first = null; note = "That didn't match. Start over." }
                        else -> save(patternSecret(dots), true)
                    }
                }
            }
        } else {
            Field(pw1, { pw1 = it }, "New password", Modifier.padding(top = 10.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
            Field(pw2, { pw2 = it }, "Type it again", Modifier.padding(top = 8.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (pw2.isNotEmpty() && pw1 != pw2) Text("They don't match yet.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
            MButton(if (saving) "Saving…" else "Save password", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = pw1.length >= 4 && pw1 == pw2 && !saving) { save(pw1, false) }
        }
        if (onCancel != null) MButton("Cancel", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !saving) { onCancel() }
    }
}

/** First open (and installs from before the lock was required): set a lock before anything else. */
@Composable
fun FirstLock(app: Muninn, done: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding()
        .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        Brand {}
        Column(Modifier.padding(16.dp)) {
            Panel {
                Text("Protect Muninn", style = Type.big)
                Text("Muninn opens with your pattern or password. Everything it keeps (messages, photos, voice, contacts, keys) is encrypted with it: " +
                    "without it, nobody can read them, not even Muninn. There's no way to recover it, so pick one you'll remember.", style = Type.normal)
                LockSetup(app, onCancel = null) { done() }
            }
        }
    }
}
