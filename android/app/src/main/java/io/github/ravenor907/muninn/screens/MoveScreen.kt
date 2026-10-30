package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Move
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A move code that came in from a scan (MainActivity), waiting for the person to say yes. */
object MoveUi { val code = MutableStateFlow<String?>(null) }

/**
 * Two ways to use another device, on the code screen: "Use on both" (linked devices, as before) and "Move here"
 * (everything moves to this device, and the old one is locked out). Also "Move from this device", for the old one.
 */
@Composable
fun MoveSection(app: Muninn) {
    var showing by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Section("Move here")
    Panel {
        Text("Moving to a new phone, tablet or computer? Everything comes here: you, your contacts, groups and chats, straight over your home Wi-Fi. " +
            "Your contacts don't have to add you again. The old device is locked out, and can become a linked device of this one.", style = Type.small)
        MButton("Move here", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { showing = true }
    }
    Section("Move from this device")
    Panel {
        Text("On the new device, open Move here and copy its code here. Both must be on the same Wi-Fi.", style = Type.small)
        Field(pasted, { pasted = it }, "Paste the move code", Modifier.padding(top = 8.dp), single = false)
        MButton("Next", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, enabled = pasted.isNotBlank()) {
            val text = pasted
            scope.launch { withContext(Dispatchers.IO) { Move.take(app, text) }?.let { Ui.say(it, true) } ?: run { pasted = "" } }
        }
    }
    if (showing) MoveHereDialog(app) { showing = false }
}

/** On the new device: the code, then the 4 emoji, then the parts coming in. */
@Composable
fun MoveHereDialog(app: Muninn, onDone: () -> Unit) {
    val code = remember { Move.showCode(app) }
    val inc by Move.incoming.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = {}, containerColor = Palette.panel,
        title = { Text(when (inc?.state) { "hello", "receiving" -> "Moving from ${inc?.from}"; "done" -> "Moved here"; else -> "Move here" }, style = Type.big) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val i = inc
                when (i?.state) {
                    "hello", "receiving" -> {
                        Text("Check these are the same on both screens:", style = Type.normal)
                        Text(i.emoji, style = Type.big, modifier = Modifier.padding(vertical = 12.dp))
                        if (i.of > 0) Text("${i.have} of ${i.of} parts here", style = Type.small)
                        if (i.confirmed) Text("Taking it in as soon as everything is here. Muninn then opens again as you.", style = Type.small, textAlign = TextAlign.Center)
                    }
                    "done" -> Text("Everything is here. Muninn opens again in a moment as you.", style = Type.normal)
                    else -> {
                        Text("On your old device: Settings › Linked devices › Move from this device, and paste this code (or scan it). Keep both on the same Wi-Fi.",
                            style = Type.small)
                        Box(Modifier.padding(vertical = 10.dp)) { Qr(io.github.ravenor907.muninn.net.Contacts.link(code), 220.dp) }
                        MButton("Copy", Modifier.fillMaxWidth(), icon = "copy") {
                            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Move code", code))
                            Ui.say("Code copied.")
                        }
                    }
                }
            }
        },
        confirmButton = {
            val i = inc
            if ((i?.state == "hello" || i?.state == "receiving") && !i.confirmed) MButton("They match", kind = Kind.Accent) { Move.confirmHere(app) }
        },
        dismissButton = { if (inc?.state != "done") MButton("Cancel") { Move.cancelHere(app); onDone() } })
}

/**
 * On the old device, over everything: the emoji check before the move, the lock while it moves, and "Moved to [name]"
 * after. True when it covers the screen (nothing else may show).
 */
@Composable
fun MoveCover(app: Muninn): Boolean {
    val out by Move.outgoing.collectAsState()
    val pending by MoveUi.code.collectAsState()
    val scope = rememberCoroutineScope()
    pending?.let { c ->
        AlertDialog(onDismissRequest = { MoveUi.code.value = null }, containerColor = Palette.panel,
            title = { Text("Move everything to another device?", style = Type.big) },
            text = { Text("You, your contacts, groups and chats go to the device that showed this code. This one is then locked out.", style = Type.normal) },
            confirmButton = { MButton("Next", kind = Kind.Accent) {
                MoveUi.code.value = null
                scope.launch { withContext(Dispatchers.IO) { Move.take(app, c) }?.let { Ui.say(it, true) } }
            } },
            dismissButton = { MButton("Cancel") { MoveUi.code.value = null } })
    }
    val o = out ?: return false
    when (o.state) {
        "checking" -> {
            AlertDialog(onDismissRequest = {}, containerColor = Palette.panel,
                title = { Text("Moving to ${o.to}", style = Type.big) },
                text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Check these are the same on both screens, then tap They match on both:", style = Type.normal)
                    Text(o.emoji, style = Type.big, modifier = Modifier.padding(vertical = 12.dp))
                    Text("This device is then locked out: it can't send, read or receive as you.", style = Type.small)
                } },
                confirmButton = { MButton("They match", kind = Kind.Accent) { scope.launch { withContext(Dispatchers.IO) { Move.go(app) }?.let { Ui.say(it, true) } } } },
                dismissButton = { MButton("Cancel") { Move.cancelOut(app) } })
            return false
        }
        "moving" -> Cover("Moving to ${o.to}", if (o.of > 0) "${o.sent} of ${o.of} parts there. ${o.note}" else o.note.ifEmpty { "Getting ready…" },
            "Keep both on the same Wi-Fi. This device stays locked until the move is done.") {}
        "moved" -> Cover("Moved to ${o.to}", "You, your contacts and chats are on ${o.to} now. Nothing of yours is left here.", "") {
            MButton("Use as a linked device", Modifier.fillMaxWidth().padding(top = 16.dp), Kind.Accent) {
                scope.launch { withContext(Dispatchers.IO) { Move.becomeLinked(app) }?.let { Ui.say(it, true) } ?: Ui.say("Linked. Your chats show up here in a moment.") }
            }
            MButton("Start fresh", Modifier.fillMaxWidth().padding(top = 8.dp)) { Move.startFresh(app) }
        }
        else -> return false
    }
    return true
}

@Composable
private fun Cover(title: String, line: String, note: String, buttons: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.ground).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(title, style = Type.big, textAlign = TextAlign.Center)
            Text(line, style = Type.normal, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            if (note.isNotEmpty()) Text(note, style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            buttons()
        }
    }
}
