package io.github.ravenor907.muninn.tv

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.Voice
import io.github.ravenor907.muninn.screens.Qr
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.delay

// The kids' TV screens (mockups TVHome and TVChat): 1920 x 1080 is 960 x 540 dp, so mockup pixels are halved.
// Everything works with the D-pad, OK and Back alone: every action is a focusable tile, and focus shows as a
// gold ring (never by color alone: the ring is 4 dp wide and the tile grows a little).

private val TileFocus = Color(0xFF1D1A14)
private val TheirBubble = Color(0xFF1D1A14)
private val BlueTint = Color(0xFF2D3340)
private val BlueText = Color(0xFFB9C6E0)

internal fun tvText(size: TextUnit, color: Color = Palette.text, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Type.body, fontSize = size, fontWeight = weight, color = color)

internal fun tvTitle(size: TextUnit) = TextStyle(fontFamily = Type.display, fontWeight = FontWeight.Bold, fontSize = size, letterSpacing = 1.sp, color = Palette.text)

/** The whole app on a Kids' TV: pairing until a parent sets it up, then the kids' screens. No lock, no sign in. */
@Composable
fun TvRoot(app: Muninn) {
    val ready by app.ready.collectAsState()
    Box(Modifier.fillMaxSize().background(Palette.ground)) {
        if (!ready) TvStarting(app)
        else {
            val st by KidTv.state.collectAsState()
            val wiping by KidTv.wiping.collectAsState()
            val cfg = st.cfg
            when {
                wiping -> TvNote("This TV was disconnected", "Starting over…")
                st.parents.isEmpty() || cfg == null -> TvPairing(app)
                else -> TvKids(app, cfg)
            }
        }
        TvToast(Modifier.align(Alignment.BottomCenter))
        val ask by Ui.ask.collectAsState()
        ask?.let { AskDialog(it) }
    }
}

@Composable
private fun TvStarting(app: Muninn) {
    TvNote("Starting…", "")
    LaunchedEffect(Unit) {
        delay(1500)
        // Without a password the vault opens with the key store's key. If that key is gone, nothing saved
        // can be read: start the TV over so a parent can pair it again.
        if (!app.ready.value) { if (app.vault.openDefault()) app.start() else KidTv.startOver(app) }
    }
}

@Composable
private fun TvNote(title: String, detail: String) {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = tvTitle(30.sp), textAlign = TextAlign.Center)
        if (detail.isNotEmpty()) Text(detail, style = tvText(19.sp, Palette.textMuted), modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun TvToast(modifier: Modifier) {
    val toast by Ui.toast.collectAsState()
    LaunchedEffect(toast) { if (toast != null) { delay(4500); Ui.toast.value = null } }
    toast?.let { (text, bad) ->
        Row(modifier.padding(24.dp).clip(RoundedCornerShape(13.dp)).background(if (bad) Palette.dangerFill else Palette.raised).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (bad) { MIcon("info", Palette.danger, 24.dp); Spacer(Modifier.width(12.dp)) }
            Text(text, style = tvText(18.sp, if (bad) Palette.danger else Palette.text))
        }
    }
}

@Composable
private fun TvHeader() {
    val context = LocalContext.current
    val clock by produceState("") {
        while (true) { value = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date()); delay(20_000) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(9.dp))
        Text("MUNINN", style = tvTitle(17.sp).copy(letterSpacing = 3.sp), modifier = Modifier.weight(1f))
        Text(clock, style = tvText(15.sp, Palette.textMuted))
    }
}

// ------------------------------------------------------------------ pairing

@Composable
private fun TvPairing(app: Muninn) {
    var code by remember { mutableStateOf(try { KidTv.pairingCode(app) } catch (e: Exception) { "" }) }
    // The invite in the code lasts an hour: show a fresh one before it runs out.
    LaunchedEffect(Unit) { while (true) { delay(50 * 60_000L); try { code = KidTv.pairingCode(app) } catch (_: Exception) {} } }
    var after by remember { mutableLongStateOf(app.identity.deleteAfter) }
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        TvHeader()
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Ask a parent to pair this TV", style = tvTitle(30.sp))
                Text("On the parent's phone, point the camera at this code. Muninn opens: tap Add, then Set it up.",
                    style = tvText(19.sp, Palette.textSoft), modifier = Modifier.padding(top = 18.dp))
                Text("The code changes every hour.", style = tvText(15.sp, Palette.textMuted), modifier = Modifier.padding(top = 8.dp))
                Text("This TV has no lock: anyone in the room can read the messages on it.", style = tvText(15.sp, Palette.textMuted),
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(24.dp))
                TvChip("Delete messages after: ${ageName(after)}", first) {
                    after = nextAge(after); app.identity.deleteAfter = after
                }
                Spacer(Modifier.height(12.dp))
                TvChip("Use Muninn normally instead", null, quiet = true) {
                    Ui.sure(Ask("Use Muninn normally?",
                        "This TV then works like Muninn on a phone, with a lock and Settings. It can only become a Kids' TV again if Muninn's data is cleared in the TV's settings.",
                        "Use normally") { KidMode.useNormally(app) })
                }
            }
            if (code.isNotEmpty()) Qr(Contacts.link(code), 260.dp)
        }
    }
    LaunchedEffect(Unit) { delay(80); try { first.requestFocus() } catch (_: Exception) {} }
}

private val AGES = longArrayOf(0L, 86_400_000L, 7 * 86_400_000L, 30 * 86_400_000L)
private fun nextAge(a: Long) = AGES[(AGES.indexOf(a) + 1) % AGES.size]
private fun ageName(a: Long) = when (a) { 0L -> "Never"; 86_400_000L -> "1 day"; 7 * 86_400_000L -> "1 week"; 30 * 86_400_000L -> "30 days"; else -> "${a / 86_400_000L} days" }

@Composable
private fun TvChip(text: String, requester: FocusRequester?, quiet: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(13.dp)
    Box(Modifier.then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .heightIn(min = 52.dp).clip(shape).background(if (quiet && !focused) Color.Transparent else Palette.field)
        .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart) {
        Text(text, style = tvText(18.sp, if (quiet) Palette.textMuted else Palette.text, FontWeight.Medium))
    }
}

// ------------------------------------------------------------------ the kids' screens

@Composable
private fun TvKids(app: Muninn, cfg: TvConfig) {
    var open by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    val person = cfg.contacts.firstOrNull { it.k == open }
    var setup by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // What a parent still has to allow once on the TV (checked again every few seconds: they come back from Android's settings).
    val needs by produceState(TvUpdate.needs(context)) { while (true) { value = TvUpdate.needs(context); if (value.none { it.id == "install" }) TvUpdate.resume(context); delay(3000) } }
    LaunchedEffect(Unit) { if (!TvUpdate.setupShown(context)) { TvUpdate.markSetupShown(context); if (TvUpdate.needs(context).isNotEmpty()) setup = true } }
    // Someone a parent just took off the list: back to the people who are still on it.
    if (person == null) TvHome(app, cfg, last, needs.isNotEmpty(), onSetup = { setup = true }) { open = it; last = it }
    else TvTalk(app, cfg, person) { open = "" }
    if (setup) TvSetup(needs) { setup = false }
    TvUpdatePrompt { setup = true }
}

/** For a parent, once on the TV: what to allow so the TV can ring over a show, notify and update itself. */
@Composable
private fun TvSetup(needs: List<TvUpdate.Need>, onDone: () -> Unit) {
    val context = LocalContext.current
    var asked by remember { mutableStateOf<String?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = null }
    TvDialog(onBack = onDone) {
        Text("For a parent", style = tvTitle(30.sp))
        Text(if (needs.isEmpty()) "All set. This TV can ring, notify and update itself." else "Turn these on once, so calls come up over a show and Muninn keeps itself up to date.",
            style = tvText(18.sp, Palette.textMuted), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
        val first = remember { FocusRequester() }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            needs.forEachIndexed { i, n ->
                BigButton(n.title, sub = n.why, requester = if (i == 0) first else null) {
                    when (n.id) {
                        "mic" -> { asked = n.id; perm.launch(Manifest.permission.RECORD_AUDIO) }
                        "notify" -> { asked = n.id; if (android.os.Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        else -> if (!TvUpdate.open(context, n.id)) Ui.say("This TV doesn't have that setting. Look under Settings, Apps, Muninn.", true)
                    }
                }
            }
            BigButton("Done", requester = if (needs.isEmpty()) first else null, onClick = onDone)
        }
        LaunchedEffect(needs.size) { delay(80); try { first.requestFocus() } catch (_: Exception) {} }
    }
}

/** A new Muninn is downloaded: OK on the remote brings up Android's Install box. */
@Composable
private fun TvUpdatePrompt(onSetup: () -> Unit) {
    val context = LocalContext.current
    val st by TvUpdate.state.collectAsState()
    var later by remember { mutableStateOf(false) }
    LaunchedEffect(st) { later = false }
    if (later) return
    when {
        st.confirm != null -> TvDialog(onBack = { later = true }) {
            MIcon("download", Palette.goldBright, 48.dp)
            Text("A new Muninn is ready", style = tvTitle(28.sp), modifier = Modifier.padding(top = 12.dp))
            Text("Press OK, then Install.", style = tvText(19.sp, Palette.textMuted), modifier = Modifier.padding(top = 8.dp))
            DialogButtons(listOf("Install" to { TvUpdate.confirm(context) }, "Later" to { later = true }))
        }
        st.needsParent -> TvDialog(onBack = { later = true }) {
            Text("A new Muninn is ready", style = tvTitle(28.sp))
            Text("A parent needs to allow updates once: turn on \"Allow from this source\" for Muninn.", style = tvText(19.sp, Palette.textMuted),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            DialogButtons(listOf("Open the setting" to { later = true; if (!TvUpdate.open(context, "install")) onSetup() }, "Later" to { later = true }))
        }
    }
}

/** Parents first, then everyone else, each by name. */
private fun ordered(cfg: TvConfig): List<TvPerson> =
    cfg.contacts.sortedWith(compareBy<TvPerson>({ it.k !in cfg.parents }, { it.n.lowercase() }))

private fun unreadCount(list: List<Chat.Msg>, unread: Boolean): Int {
    if (!unread) return 0
    val i = list.indexOfLast { it.mine }
    return list.drop(i + 1).count { !it.mine && it.unsent.isEmpty() }.coerceAtLeast(1)
}

@Composable
private fun TvHome(app: Muninn, cfg: TvConfig, focusKey: String, setupLeft: Boolean, onSetup: () -> Unit, onOpen: (String) -> Unit) {
    val convs by app.chat.conversations.collectAsState()
    val unread by app.chat.unread.collectAsState()
    val people = remember(cfg) { ordered(cfg) }
    // Back here does nothing: a kid can't back out into anything else.
    BackHandler(enabled = true) {}
    val focusAt = people.indexOfFirst { it.k == focusKey }.coerceAtLeast(0)
    val first = remember { FocusRequester() }
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TvHeader()
        Text("Who do you want to talk to?", style = tvTitle(32.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 30.dp))
        if (people.isEmpty()) {
            Text("No one is set up yet. Ask a parent to choose who this TV can reach.", style = tvText(20.sp, Palette.textMuted),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 40.dp))
        } else {
            LazyRow(Modifier.fillMaxWidth().padding(top = 26.dp), state = list,
                horizontalArrangement = Arrangement.spacedBy(45.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
                itemsIndexed(people, key = { _, p -> p.k }) { i, p ->
                    Tile(p, unreadCount(convs[p.k].orEmpty(), p.k in unread), if (i == focusAt) first else null) { onOpen(p.k) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Latest(people, convs, onOpen)
        if (setupLeft) Box(Modifier.padding(top = 10.dp)) { TvChip("For a parent: finish setting up this TV", null, quiet = true, onClick = onSetup) }
    }
    LaunchedEffect(people) {
        if (people.isEmpty()) return@LaunchedEffect
        try { list.scrollToItem(focusAt) } catch (_: Exception) {}
        delay(80)
        try { first.requestFocus() } catch (_: Exception) {}
    }
}

@Composable
private fun Avatar(p: TvPerson, size: Dp, font: TextUnit) {
    val gold = (p.k.hashCode() and 1) == 0
    val initial = p.n.trim().let { if (it.isEmpty()) "?" else String(Character.toChars(it.codePointAt(0))).uppercase() }
    Box(Modifier.size(size).clip(CircleShape).background(if (gold) Palette.goldTint else BlueTint), contentAlignment = Alignment.Center) {
        Text(initial, style = tvText(font, if (gold) Palette.goldBright else BlueText, FontWeight.SemiBold))
    }
}

@Composable
private fun Tile(p: TvPerson, count: Int, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && !Access.reduceMotion) 1.06f else 1f, label = "tile")
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(220.dp, 250.dp).clip(shape).background(if (focused) TileFocus else Palette.panel)
            .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (count > 0) "${p.n}, $count new" else p.n },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(17.dp)) {
            Avatar(p, 125.dp, 55.sp)
            Text(p.n, style = tvText(32.sp, weight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (count > 0) Box(Modifier.align(Alignment.TopEnd).padding(14.dp).sizeIn(minWidth = 28.dp).height(28.dp)
            .clip(RoundedCornerShape(14.dp)).background(Palette.gold).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(if (count > 9) "9+" else "$count", style = tvText(15.sp, Palette.onGold, FontWeight.SemiBold))
        }
    }
}

/** The newest message from anyone on the list, along the bottom. OK on it opens that person. */
@Composable
private fun Latest(people: List<TvPerson>, convs: Map<String, List<Chat.Msg>>, onOpen: (String) -> Unit) {
    val m = people.flatMap { p -> convs[p.k].orEmpty().filter { !it.mine && it.unsent.isEmpty() } }.maxByOrNull { it.time } ?: return
    val p = people.firstOrNull { it.k == m.peerKey } ?: return
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(13.dp)
    Row(Modifier.widthIn(max = 820.dp).onFocusChanged { focused = it.isFocused }.clip(shape).background(Palette.field)
        .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
        .clickable(role = Role.Button) { onOpen(p.k) }.padding(horizontal = 17.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(p, 28.dp, 14.sp)
        Spacer(Modifier.width(11.dp))
        val what = when { m.voice > 0 -> " sent a voice message"; m.photo.isNotEmpty() -> " sent a photo"; else -> ": " + m.text }
        Text(p.n + what, style = tvText(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ talking to one person

private sealed interface Overlay {
    data class Listening(val partial: String) : Overlay
    data class Heard(val text: String) : Overlay
    data object Recording : Overlay
    data class Problem(val text: String) : Overlay
}

@Composable
private fun TvTalk(app: Muninn, cfg: TvConfig, p: TvPerson, onBack: () -> Unit) {
    val context = LocalContext.current
    val convs by app.chat.conversations.collectAsState()
    val msgs = convs[p.k].orEmpty().filter { it.unsent.isEmpty() }
    val playing by Voice.playing.collectAsState()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val speech = remember { TvSpeech(context) }
    val openedAt = remember { System.currentTimeMillis() }
    val mic = remember { TvAudio.hasMic(context) }
    val canDictate = remember { TvAudio.canDictate(context) }
    val dictate = cfg.dictation && canDictate
    var sent by remember { mutableStateOf("") }

    fun finishRecording() {
        val r = TvRecorder.finish()
        if (r != null) { app.chat.sendVoice(p.k, r.first, r.second); overlay = null; Ui.say("Sent to ${p.n}") }
        else overlay = Overlay.Problem("That was too short. Keep talking a little longer next time.")
    }
    fun cancel() { speech.close(); if (TvRecorder.recording.value) TvRecorder.cancel(); overlay = null }

    DisposableEffect(p.k) {
        app.openChat = p.k
        TvRecorder.onLimit = { finishRecording() }
        onDispose {
            if (app.openChat == p.k) app.openChat = ""
            TvRecorder.onLimit = null
            speech.close()
            if (TvRecorder.recording.value) TvRecorder.cancel()
            Voice.stopPlaying()
        }
    }
    LaunchedEffect(msgs.size) { app.chat.markRead(p.k) }
    // Whether their app takes calls isn't known until it says so: ask once (protocol 0.6, 1.2).
    LaunchedEffect(p.k) { app.chat.probeCaps(p.k) }
    // A voice message that arrives while this screen is open plays out loud by itself.
    val newest = msgs.lastOrNull()
    LaunchedEffect(newest?.id) {
        if (newest != null && !newest.mine && newest.voice > 0 && newest.time >= openedAt && overlay == null)
            app.chat.photo(newest.photo)?.let { Voice.play(newest.id, it) }
    }
    LaunchedEffect(playing) { while (playing.isNotEmpty()) { Voice.tick(); delay(200) } }
    LaunchedEffect(sent) { if (sent.isNotEmpty()) { delay(2000); sent = "" } }
    BackHandler { onBack() }

    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val f = afterMic; afterMic = null
        if (ok) f?.invoke() else overlay = Overlay.Problem("Muninn isn't allowed to use the microphone. Ask a parent to allow it.")
    }
    fun withMic(f: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f()
        else { afterMic = f; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    fun listen() = withMic {
        Voice.stopPlaying()
        overlay = Overlay.Listening("")
        speech.start(
            partial = { if (overlay is Overlay.Listening) overlay = Overlay.Listening(it) },
            done = { overlay = Overlay.Heard(TvRules.clean(it, 2000)) },
            fail = { overlay = Overlay.Problem(it) },
        )
    }
    fun record() = withMic {
        Voice.stopPlaying()
        overlay = if (TvRecorder.start(context)) Overlay.Recording else Overlay.Problem("The microphone isn't working right now.")
    }
    fun call() {
        when {
            !mic -> overlay = Overlay.Problem("This TV has no microphone, so ${p.n} couldn't hear you. Ask a parent to plug one in.")
            io.github.ravenor907.muninn.net.call.Opus.loadError != null -> overlay = Overlay.Problem("Calling doesn't work on this TV.")
            !io.github.ravenor907.muninn.net.call.CallManager.canCall(p.k) -> {
                app.chat.probeCaps(p.k)
                overlay = Overlay.Problem("${p.n} can't take calls yet. Send a message instead, or try again in a minute.")
            }
            else -> withMic { io.github.ravenor907.muninn.screens.CallActivity.call(context, p.k) }
        }
    }

    val calls = cfg.calls
    // The first button the remote lands on; never the Call button while it's off or can't work (no mic).
    val firstId: String? = when { dictate -> "say"; mic -> "rec"; cfg.quick.isNotEmpty() -> cfg.quick.first().id; mic && calls -> "call"; else -> null }
    val first = remember { FocusRequester() }
    fun req(id: String) = if (id == firstId) first else null

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(310.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p, 75.dp, 34.sp)
                Spacer(Modifier.width(15.dp))
                Column {
                    Text(p.n, style = tvTitle(32.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Status(app, p.k)
                }
            }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (msgs.isEmpty()) Text("Pick a message to send to ${p.n}.", style = tvText(17.sp, Palette.textMuted))
                msgs.takeLast(5).forEach { TalkBubble(app, it, p.n, playing) }
            }
        }
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(15.dp, Alignment.CenterVertically), horizontalArrangement = Arrangement.spacedBy(15.dp),
            contentPadding = PaddingValues(6.dp)) {
            if (calls) item(key = "call") {
                BigButton("Call ${p.n}", icon = "call", sub = if (mic) null else "No microphone on this TV", enabled = mic, requester = req("call")) { call() }
            }
            if (dictate) item(key = "say") { BigButton("Say something", icon = "mic", requester = req("say")) { listen() } }
            if (mic) item(key = "rec") { BigButton("Send a voice message", icon = "mic", requester = req("rec")) { record() } }
            items(cfg.quick, key = { it.id }) { q ->
                BigButton(q.text, icon = if (sent == q.id) "check" else null, sub = if (sent == q.id) "Sent" else null, requester = req(q.id)) {
                    app.chat.sendPrivate(p.k, q.text); sent = q.id
                }
            }
        }
    }
    LaunchedEffect(firstId) { if (firstId != null) { delay(80); try { first.requestFocus() } catch (_: Exception) {} } }

    overlay?.let { o ->
        TvDialog(onBack = { cancel() }) {
            when (o) {
                is Overlay.Listening -> {
                    MIcon("mic", Palette.goldBright, 56.dp)
                    Text("Listening… say it now", style = tvTitle(28.sp), modifier = Modifier.padding(top = 14.dp))
                    if (o.partial.isNotEmpty()) Text(o.partial, style = tvText(26.sp, Palette.textSoft), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                    DialogButtons(listOf("Done" to { speech.stop() }))
                }
                is Overlay.Heard -> {
                    Text("“${o.text}”", style = tvText(30.sp, weight = FontWeight.SemiBold), textAlign = TextAlign.Center)
                    DialogButtons(listOf(
                        "Send it" to { app.chat.sendPrivate(p.k, o.text); overlay = null; Ui.say("Sent to ${p.n}") },
                        "Say it again" to { listen() },
                    ))
                }
                Overlay.Recording -> {
                    val ms by produceState(0L) { while (true) { value = TvRecorder.elapsed(); delay(250) } }
                    MIcon("mic", Palette.goldBright, 56.dp)
                    Text("Recording ${Voice.length(ms.toInt())}", style = tvTitle(28.sp), modifier = Modifier.padding(top = 14.dp))
                    Text("Press OK to send it to ${p.n}. Back stops without sending.", style = tvText(19.sp, Palette.textMuted),
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                    DialogButtons(listOf("Send" to { finishRecording() }))
                }
                is Overlay.Problem -> {
                    Text(o.text, style = tvText(24.sp), textAlign = TextAlign.Center)
                    DialogButtons(listOf("OK" to { overlay = null }))
                }
            }
        }
    }
}

@Composable
private fun Status(app: Muninn, key: String) {
    val route by produceState(app.chat.route(key), key) { while (true) { delay(5000); value = app.chat.route(key) } }
    val (on, text) = when (route) {
        "close", "wifi" -> true to "Can hear you now"
        "internet" -> true to "Gets your messages"
        else -> false to "Can't be reached right now"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Palette.online else Palette.textFaint))
        Spacer(Modifier.width(5.dp))
        Text(text, style = tvText(14.sp, if (on) Palette.online else Palette.textMuted))
    }
}

@Composable
private fun TalkBubble(app: Muninn, m: Chat.Msg, name: String, playing: String) {
    val shape = if (m.mine) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
    val bg = if (m.mine) Palette.bubbleMine else TheirBubble
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        when {
            m.voice > 0 -> {
                var focused by remember { mutableStateOf(false) }
                val on = playing == m.id
                Row(Modifier.onFocusChanged { focused = it.isFocused }.clip(shape).background(bg)
                    .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
                    .clickable(role = Role.Button) { if (on) Voice.stopPlaying() else app.chat.photo(m.photo)?.let { Voice.play(m.id, it) } }
                    .padding(start = 7.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Palette.gold), contentAlignment = Alignment.Center) {
                        MIcon(if (on) "pausefill" else "play", Palette.onGold, 14.dp, filled = true)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text("${if (m.mine) "Your" else "$name's"} voice · ${Voice.length(m.voice)}", style = tvText(15.sp, Palette.textSoft))
                }
            }
            m.photo.isNotEmpty() -> {
                val bmp = remember(m.photo) { try { app.chat.photo(m.photo)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } } catch (e: Throwable) { null } }
                if (bmp != null) Image(bmp, contentDescription = "Photo", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(150.dp, 110.dp).clip(shape))
                else Text("Photo", style = tvText(17.sp, Palette.textMuted), modifier = Modifier.clip(shape).background(bg).padding(horizontal = 13.dp, vertical = 9.dp))
            }
            else -> Text(m.text, style = tvText(17.sp), maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(shape).background(bg).padding(horizontal = 13.dp, vertical = 9.dp))
        }
    }
}

@Composable
internal fun BigButton(text: String, icon: String? = null, sub: String? = null, enabled: Boolean = true, requester: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)
    val fg = if (enabled) Palette.text else Palette.textFaint
    Box(
        Modifier.fillMaxWidth().height(95.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape).background(if (enabled) Palette.field else Palette.panelLow)
            .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
            // Stays focusable while it's off, so moving with the D-pad never jumps somewhere unexpected.
            .clickable(role = Role.Button) { if (enabled) onClick() }
            .semantics { if (!enabled) disabled() },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { MIcon(icon, if (enabled) Palette.goldBright else Palette.textFaint, 28.dp); Spacer(Modifier.width(12.dp)) }
            Column {
                Text(text, style = tvText(23.sp, fg, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = tvText(14.sp, Palette.textMuted))
            }
        }
    }
}

/** A dialog window of its own, so focus can't wander to the screen behind it. Back closes it. */
@Composable
internal fun TvDialog(onBack: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(min = 420.dp, max = 720.dp).clip(RoundedCornerShape(20.dp)).background(Palette.panel).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
internal fun DialogButtons(buttons: List<Pair<String, () -> Unit>>) {
    val first = remember { FocusRequester() }
    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        buttons.forEachIndexed { i, (label, act) ->
            Box(Modifier.width(240.dp)) { BigButton(label, requester = if (i == 0) first else null, onClick = act) }
        }
    }
    LaunchedEffect(buttons.map { it.first }) { delay(80); try { first.requestFocus() } catch (_: Exception) {} }
}
