package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
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
                if (fails % 5 == 0) { waitUntil = System.currentTimeMillis() + 30_000; prefs.edit().putLong("lockUntil", waitUntil).apply() }
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
