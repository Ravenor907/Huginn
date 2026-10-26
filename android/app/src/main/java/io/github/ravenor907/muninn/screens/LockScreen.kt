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
    fun tryIt() {
        busy = true; wrong = false
        scope.launch {
            val ok = withContext(Dispatchers.Default) { app.vault.unlock(pw) }   // the key stretching takes a moment
            busy = false
            if (ok) { pw = ""; done(true) } else wrong = true
        }
    }
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding()) {
        Brand {}
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel {
                Text("Muninn is locked", style = Type.big)
                Text("Type your password. Your messages, contacts and keys stay encrypted until you do.", style = Type.normal)
                Field(pw, { pw = it; wrong = false }, "Password", Modifier.padding(top = 10.dp), secret = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (wrong) Text("That's not it. Try again.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
                MButton(if (busy) "Opening…" else "Unlock", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = pw.isNotEmpty() && !busy) { tryIt() }
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
