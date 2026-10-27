package io.github.ravenor907.muninn.tv

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * "Kids' TV" on a parent's phone (mockup PhoneKidsTV), opened from the TV contact's chat options:
 * quick messages (edit, delete, drag to reorder, add), who the TV can reach, speech to text with the
 * remote's mic, and Disconnect. Every change goes to the TV as a new signed config.
 */
@Composable
fun ParentTvScreen(app: Muninn, tvKey: String, onBack: () -> Unit) {
    val tvs by TvParent.tvs.collectAsState()
    val t = tvs[tvKey]
    BackHandler { onBack() }
    if (t == null || t.disconnecting) { LaunchedEffect(Unit) { onBack() }; return }
    val contacts by app.contacts.all.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Quick?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    /** People to put in a family group (just invited, or picked under Family groups). */
    var grouping by remember { mutableStateOf<List<String>?>(null) }
    var micLevel by remember(t.mic) { mutableFloatStateOf(t.mic.toFloat()) }
    val k2 = TvParent.k2(app, tvKey)
    var order by remember(t.quick) { mutableStateOf(t.quick) }
    var dragId by remember { mutableStateOf("") }
    var dragY by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 60.dp.toPx() }
    fun commit(list: List<Quick>) { order = list; TvParent.change(tvKey) { it.copy(quick = list) } }
    fun move(id: String, by: Int) {
        val i = order.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in order.indices) return
        commit(order.toMutableList().apply { add(j, removeAt(i)) })
    }

    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", "Back", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("Kids' TV", style = Type.big)
                val (on, text) = when {
                    t.upToDate -> true to "Connected · stays on until you disconnect it"
                    t.pending.isNotEmpty() -> false to "Sending your changes to the TV…"
                    t.applied == 0L -> false to "Waiting for the TV to answer"
                    else -> false to "The TV hasn't answered. It gets your changes when it's back online."
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Palette.online else Palette.textFaint))
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = Type.small.copy(fontSize = 13.sp, color = if (on) Palette.online else Palette.textMuted))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Label("Quick messages")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                order.forEach { q ->
                    key(q.id) {
                        val dragging = dragId == q.id
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = if (dragging) dragY else 0f }
                                .clip(RoundedCornerShape(12.dp)).background(if (dragging) Palette.raised else Palette.field)
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction("Move up") { move(q.id, -1); true },
                                        CustomAccessibilityAction("Move down") { move(q.id, 1); true },
                                    )
                                }
                                .padding(start = 4.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The handle: drag it up or down.
                            Box(Modifier.size(40.dp, 52.dp).pointerInput(q.id) {
                                detectVerticalDragGestures(
                                    onDragStart = { dragId = q.id; dragY = 0f },
                                    onDragEnd = { dragId = ""; dragY = 0f; commit(order) },
                                    onDragCancel = { dragId = ""; dragY = 0f; commit(order) },
                                ) { change, dy ->
                                    change.consume()
                                    dragY += dy
                                    val i = order.indexOfFirst { it.id == q.id }
                                    if (dragY > rowPx / 2 && i in 0 until order.lastIndex) {
                                        order = order.toMutableList().apply { add(i + 1, removeAt(i)) }; dragY -= rowPx
                                    } else if (dragY < -rowPx / 2 && i > 0) {
                                        order = order.toMutableList().apply { add(i - 1, removeAt(i)) }; dragY += rowPx
                                    }
                                }
                            }, contentAlignment = Alignment.Center) { Grip() }
                            Text(q.text, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconAction("text", "Edit ${q.text}", Palette.accent, size = 18.dp) { editing = q }
                            IconAction("delete", "Delete ${q.text}", Palette.danger, size = 18.dp) { commit(order.filterNot { it.id == q.id }) }
                        }
                    }
                }
                if (order.size < TvRules.MAX_QUICK) Row(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Palette.lineMuted, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { editing = Quick("", "") },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    MIcon("plus", Palette.goldBright, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Add a message", style = Type.normal.copy(color = Palette.goldBright))
                }
            }
            Text("Changes show on the TV right away. Drag to reorder.", style = Type.small.copy(fontSize = 13.sp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))

            Label("On the TV")
            Column(Modifier.padding(horizontal = 16.dp)) {
                val names = TvParent.people(app, t).joinToString(", ") { it.n }
                Setting("People they can reach", names) { choosing = true }
                Setting("Your name on the TV", t.myName.ifBlank { "Parent" }) { naming = true }
                ToggleRow("Talk with the remote's mic", "Speech to text on the TV, sent as a normal private message.", t.dictation) { on ->
                    TvParent.change(tvKey) { it.copy(dictation = on) }
                }
                ToggleRow("Calls", "The kids can call the people they can reach, and those people can call the TV.", t.calls) { on ->
                    TvParent.change(tvKey) { it.copy(calls = on) }
                }
                Text("Microphone level", style = Type.big.copy(fontSize = 16.sp), modifier = Modifier.padding(top = 10.dp))
                Text(when {
                    micLevel < 1f -> "As the TV hears it, no boost"
                    else -> "Louder by about ${(TvMic.gainDb(micLevel.toInt()) + 0.5f).toInt()} dB. Turn it up if the TV barely hears the kids."
                }, style = Type.small.copy(fontSize = 13.sp))
                Slider(micLevel, { micLevel = it }, valueRange = 0f..TvRules.MAX_MIC.toFloat(), steps = 9,
                    onValueChangeFinished = { val v = micLevel.toInt(); TvParent.change(tvKey) { it.copy(mic = v) } },
                    colors = SliderDefaults.colors(thumbColor = Palette.gold, activeTrackColor = Palette.gold, inactiveTrackColor = Palette.lineMuted),
                    modifier = Modifier.semantics { contentDescription = "Microphone level ${micLevel.toInt()} of 100" })
                if (!k2) Text("Calls and the microphone level reach the TV once it runs the newest Muninn.", style = Type.small.copy(fontSize = 13.sp))
                Setting("Share the TV's code", "So people you add can add the TV too") {
                    TvParent.shareText(app, tvKey)?.let { text ->
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share the TV's code"))
                    }
                }
                Text("The TV has no lock: anyone in the room can read what's on it.", style = Type.small.copy(fontSize = 13.sp),
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            }

            Label("Family")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Setting("Invite someone", "Talk with the kids on the TV") { inviting = true }
                Setting("Family groups", "Add people to a group") { grouping = emptyList() }
                Text("Invite someone you have as a contact: the TV adds them and their Muninn or Huginn adds the TV. Nothing to do on the TV.",
                    style = Type.small.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            }

            Label("Updates")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Setting("Update TV", if (t.app.isNotEmpty()) "Muninn ${t.app} on the TV" else "Look for a new Muninn now") {
                    if (TvParent.updateTv(app, tvKey)) Ui.say("The TV is looking for a new Muninn. It installs it by itself.")
                    else Ui.say("This TV's Muninn is too old to be asked. Update it once on the TV.", true)
                }
                Text("The TV also looks for updates every day by itself.", style = Type.small.copy(fontSize = 13.sp),
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            }
        }

        MButton("Disconnect this TV", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp), Kind.Danger) {
            Ui.sure(Ask("Disconnect this TV?", "It erases its messages and contacts and goes back to the pairing screen. A parent can pair it again any time.",
                "Disconnect", danger = true, hold = true) { TvParent.disconnect(tvKey); onBack() })
        }
    }

    editing?.let { q ->
        QuickDialog(q, onCancel = { editing = null }) { text ->
            commit(if (q.id.isEmpty()) order + Quick(TvRules.quickId(), text) else order.map { if (it.id == q.id) it.copy(text = text) else it })
            editing = null
        }
    }
    if (naming) TextDialog("Your name on the TV", t.myName, TvRules.MAX_NAME, onCancel = { naming = false }) { name ->
        TvParent.change(tvKey) { it.copy(myName = name) }; naming = false
    }
    if (choosing) {
        val choices = contacts.filter { it.chatKey != tvKey && it.chatKey != app.identity.publicKey && !TvParent.isManaged(it.chatKey) }
            .sortedBy { it.shown.lowercase() }
        PeopleDialog(choices.map { it.chatKey to it.shown }, t.reach.toSet(), onCancel = { choosing = false }) { picked ->
            val added = picked - t.reach.toSet()
            TvParent.change(tvKey) { it.copy(reach = choices.map { c -> c.chatKey }.filter { k -> k in picked }) }; choosing = false
            // Newly on the list: their app gets the TV's code too.
            if (added.isNotEmpty()) TvParent.invite(app, tvKey, added)
        }
    }
    if (inviting) {
        val choices = contacts.filter { it.chatKey != tvKey && it.chatKey != app.identity.publicKey && !TvParent.isManaged(it.chatKey) && it.chatKey !in t.reach }
            .sortedBy { it.shown.lowercase() }
        InviteDialog(choices.map { it.chatKey to it.shown }, onCancel = { inviting = false }) { k ->
            inviting = false
            TvParent.invite(app, tvKey, listOf(k))
            Ui.say("${app.chat.nameOf(k)} can talk with the kids now. Their app adds the TV by itself.")
            grouping = listOf(k)
        }
    }
    grouping?.let { start ->
        val people = TvParent.people(app, t).drop(1).map { it.k to it.n }
        FamilyGroupDialog(app, people, start.toSet(), onClose = { grouping = null })
    }
}

/** Pick one contact to invite. */
@Composable
private fun InviteDialog(choices: List<Pair<String, String>>, onCancel: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text("Invite someone", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (choices.isEmpty()) Text("Everyone in your contacts can already reach the TV. Add the person as a contact first.", style = Type.small)
                choices.forEach { (k, name) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(k) }, verticalAlignment = Alignment.CenterVertically) {
                        MIcon("user", Palette.accent, 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { MButton("Cancel", onClick = onCancel) },
    )
}

/**
 * Put people in a family group from the Kids' TV screen: tick the people (who can reach the TV) and a
 * group you run, or make a new one called Family.
 */
@Composable
private fun FamilyGroupDialog(app: Muninn, people: List<Pair<String, String>>, start: Set<String>, onClose: () -> Unit) {
    val groups by app.chat.groups.all.collectAsState()
    val me = app.identity.publicKey
    val mine = groups.values.filter { !it.readOnly && it.state.isAdmin(me) }.sortedBy { it.state.name.lowercase() }
    var picked by remember { mutableStateOf(start) }
    var group by remember { mutableStateOf(mine.firstOrNull { it.state.name.contains("family", ignoreCase = true) }?.g ?: mine.firstOrNull()?.g ?: "") }
    val box = CheckboxDefaults.colors(checkedColor = Palette.gold, checkmarkColor = Palette.onGold, uncheckedColor = Palette.lineMuted)
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.panel,
        title = { Text("Add to a family group", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Who", style = Type.small)
                if (people.isEmpty()) Text("Invite someone first.", style = Type.small)
                people.forEach { (k, name) ->
                    val on = k in picked
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { picked = if (on) picked - k else picked + k },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, null, colors = box)
                        Text(name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("Group", style = Type.small, modifier = Modifier.padding(top = 10.dp))
                mine.forEach { grp ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { group = grp.g },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(group == grp.g, null, colors = box)
                        Text(grp.state.name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { group = "" }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(group.isEmpty(), null, colors = box)
                    Text("A new group called Family", style = Type.normal.copy(color = Palette.text))
                }
            }
        },
        confirmButton = {
            MButton("Add", kind = Kind.Accent, enabled = picked.isNotEmpty()) {
                val g = group.ifEmpty { app.chat.groups.create("Family") ?: "" }
                if (g.isEmpty()) { Ui.say("The group couldn't be made.", true); return@MButton }
                val why = picked.mapNotNull { k -> app.chat.inviteToGroup(g, k) }
                if (why.isEmpty()) Ui.say("Invited. They join when they accept.") else Ui.say(why.first(), true)
                onClose()
            }
        },
        dismissButton = { MButton("Not now", onClick = onClose) },
    )
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = Type.small.copy(fontSize = 13.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
private fun Setting(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = Type.normal.copy(color = Palette.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Six dots: the drag handle. */
@Composable
private fun Grip() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) { Box(Modifier.size(3.dp).clip(CircleShape).background(Palette.textFaint)) }
            }
        }
    }
}

@Composable
private fun QuickDialog(q: Quick, onCancel: () -> Unit, onSave: (String) -> Unit) {
    TextDialog(if (q.id.isEmpty()) "Add a message" else "Edit message", q.text, TvRules.MAX_QUICK_TEXT, onCancel, onSave)
}

@Composable
private fun TextDialog(title: String, start: String, max: Int, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(start) }
    val clean = TvRules.clean(text, max)
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text(title, style = Type.big) },
        text = {
            Column {
                Field(text, { v -> text = if (v.codePointCount(0, v.length) <= max) v else text }, "Up to $max letters")
                Text("${clean.codePointCount(0, clean.length)} of $max", style = Type.small, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { MButton("Save", kind = Kind.Accent, enabled = clean.isNotEmpty()) { if (clean.isNotEmpty()) onSave(clean) } },
        dismissButton = { MButton("Cancel", onClick = onCancel) },
    )
}

@Composable
private fun PeopleDialog(choices: List<Pair<String, String>>, chosen: Set<String>, onCancel: () -> Unit, onDone: (Set<String>) -> Unit) {
    var picked by remember { mutableStateOf(chosen.filter { k -> choices.any { it.first == k } }.toSet()) }
    val room = TvRules.MAX_CONTACTS - 1   // this phone is always on the list
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text("People they can reach", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(true, null, enabled = false)
                    Text("You (always)", style = Type.normal.copy(color = Palette.textMuted))
                }
                if (choices.isEmpty()) Text("Add people as contacts first, then choose them here.", style = Type.small)
                choices.forEach { (k, name) ->
                    val on = k in picked
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) {
                        picked = if (on) picked - k else if (picked.size < room) picked + k else picked
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = Palette.gold, checkmarkColor = Palette.onGold, uncheckedColor = Palette.lineMuted))
                        Text(name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("They need the TV as a contact too: use Share the TV's code.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { MButton("Done", kind = Kind.Accent) { onDone(picked) } },
        dismissButton = { MButton("Cancel", onClick = onCancel) },
    )
}
