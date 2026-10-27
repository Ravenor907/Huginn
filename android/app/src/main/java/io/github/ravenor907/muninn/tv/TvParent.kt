package io.github.ravenor907.muninn.tv

import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A Kids' TV this phone set up (the contact's local "managed" flag, with what the parent chose). */
data class ManagedTv(
    val key: String,
    /** The last config version sent, and the version the TV last said it has. */
    val v: Long = 0, val applied: Long = 0,
    val quick: List<Quick> = emptyList(),
    /** Contacts (chat keys) the TV may reach, besides this phone. */
    val reach: List<String> = emptyList(),
    val dictation: Boolean = false,
    val parents: List<String> = emptyList(),
    /** How this phone's person shows on the TV ("Mom"). */
    val myName: String = "",
    /** Plaintexts in room "tvconfig" waiting for the TV's "applied", and since when. */
    val pending: List<String> = emptyList(), val pendingV: Long = 0, val since: Long = 0,
    /** Disconnect sent: gone from the screens, removed once the TV answers (or after a day). */
    val disconnecting: Boolean = false,
) {
    val upToDate: Boolean get() = applied >= v && v > 0
}

/**
 * The parent side of the kids' TV: adds a TV from its code, sends signed configs when the parent changes
 * something (a second or so after the last change, so a run of edits is one config), resends until the TV
 * says "applied" (5 s, 10 s, 20 s, 60 s, then every 5 minutes, for a day), and disconnects it.
 */
object TvParent {
    private const val FILE = "tv-parent.json"
    private const val DAY = 86_400_000L
    private val RETRY = longArrayOf(5_000, 10_000, 20_000, 60_000, 300_000)
    private val _tvs = MutableStateFlow<Map<String, ManagedTv>>(emptyMap())
    val tvs: StateFlow<Map<String, ManagedTv>> = _tvs.asStateFlow()
    @Volatile private var app: Muninn? = null
    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-parent").apply { isDaemon = true } }
    private val flushes = ConcurrentHashMap<String, ScheduledFuture<*>>()
    /** Per TV: (sends so far, next send ms). Not saved: after a restart the first resend comes soon. */
    private val tries = ConcurrentHashMap<String, Pair<Int, Long>>()
    @Volatile private var ticking = false

    private fun file(app: Muninn) = File(app.filesDir, FILE)

    fun load(app: Muninn) {
        this.app = app
        synchronized(lock) {
            _tvs.value = try { app.vault.read(file(app))?.let { fromJson(JSONObject(it)) } } catch (e: Exception) { null } ?: emptyMap()
        }
        if (!ticking) {
            ticking = true
            timer.scheduleWithFixedDelay(Runnable { try { tick() } catch (_: Exception) {} }, 2, 2, TimeUnit.SECONDS)
        }
    }

    /** A TV this phone manages (and hasn't disconnected): its chat offers the Kids' TV screen. */
    fun isManaged(key: String): Boolean = _tvs.value[key]?.disconnecting == false

    /** The chat key in a contact code that says "tv":1, or null for anyone else's code. */
    fun tvKeyIn(text: String): String? {
        val i = text.indexOf(Contacts.PREFIX)
        if (i < 0) return null
        val body = text.substring(i + Contacts.PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return try {
            val o = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8))
            if (o.optInt("tv") == 1) o.getString("k") else null
        } catch (e: Exception) { null }
    }

    /**
     * Call right after a contact was added from [codeText]. If it's a Kids' TV's code, asks the parent
     * and sends the TV its first config. True if it was a TV's code.
     */
    fun adoptIfTv(app: Muninn, codeText: String): Boolean {
        val key = tvKeyIn(codeText) ?: return false
        if (isManaged(key) || app.contacts.get(key) == null) return true
        Ui.sure(Ask(
            "Set up this Kids' TV?",
            "The TV opens straight to the people you choose, with no lock and no sign in, so anyone in the room can read what's on it. " +
                "You pick who it can reach and its quick messages. It stays on until you disconnect it from this phone.",
            "Set it up",
        ) { adopt(app, key) })
        return true
    }

    private fun adopt(app: Muninn, key: String) {
        val me = app.identity.publicKey
        put(ManagedTv(key, quick = TvRules.DEFAULT_QUICK.map { Quick(TvRules.quickId(), it) }, parents = listOf(me),
            myName = TvRules.clean(app.identity.username, TvRules.MAX_NAME)))
        flush(key)
        Ui.say("Setting up the TV. It shows the people you choose in a moment.")
    }

    /** Changes what the TV shows; the new config goes out a moment after the last change. */
    fun change(key: String, f: (ManagedTv) -> ManagedTv) {
        synchronized(lock) {
            val t = _tvs.value[key] ?: return
            if (t.disconnecting) return
            put(f(t))
        }
        flushes.remove(key)?.cancel(false)
        flushes[key] = timer.schedule(Runnable { try { flush(key) } catch (_: Exception) {} }, 1200, TimeUnit.MILLISECONDS)
    }

    /** People the TV may reach: this phone first, then the chosen contacts (still in Contacts). */
    fun people(app: Muninn, t: ManagedTv): List<TvPerson> {
        val me = TvPerson(app.identity.publicKey, TvRules.clean(t.myName, TvRules.MAX_NAME).ifEmpty { "Parent" }, app.nostr.publicKey)
        val others = t.reach.filter { it != t.key && it != me.k }.mapNotNull { k ->
            app.contacts.get(k)?.let { TvPerson(k, TvRules.clean(it.shown, TvRules.MAX_NAME).ifEmpty { "Someone" }, it.mailbox ?: "") }
        }
        return (listOf(me) + others).take(TvRules.MAX_CONTACTS)
    }

    private fun flush(key: String) {
        val app = app ?: return
        val plain: String
        synchronized(lock) {
            val t = _tvs.value[key] ?: return
            if (t.disconnecting) return
            val me = app.identity.publicKey
            val v = maxOf(t.v, t.applied) + 1
            val quick = t.quick.filter { it.text.isNotBlank() }.take(TvRules.MAX_QUICK)
            val cfg = TvRules.config(app.identity.keys.private, key, v, me, t.parents + me, quick, people(app, t), t.dictation, System.currentTimeMillis())
            try { TvRules.check(cfg) } catch (e: IllegalArgumentException) { Ui.say("That can't go to the TV (${e.message}).", true); return }
            plain = TvRules.configMessage(cfg)
            put(t.copy(v = v, pending = listOf(plain), pendingV = v, since = System.currentTimeMillis()))
            tries[key] = 1 to System.currentTimeMillis() + RETRY[0]
        }
        send(app, key, listOf(plain))
    }

    /** Disconnect: the TV erases everything and goes back to pairing. Signed for the TV's version and the last one sent. */
    fun disconnect(key: String) {
        val app = app ?: return
        val msgs: List<String>
        synchronized(lock) {
            val t = _tvs.value[key] ?: return
            flushes.remove(key)?.cancel(false)
            val me = app.identity.publicKey
            val now = System.currentTimeMillis()
            msgs = listOf(t.applied, t.v).filter { it >= 1 }.distinct()
                .map { TvRules.disconnectMessage(TvRules.disconnect(app.identity.keys.private, key, it, me, now)) }
            if (msgs.isEmpty()) { forget(app, key); return }
            put(t.copy(disconnecting = true, pending = msgs, since = now))
            tries[key] = 1 to now + RETRY[0]
        }
        send(app, key, msgs)
    }

    /** Room "tvconfig" on a parent's phone: the TV's {"t":"applied","v":n}. */
    fun handle(app: Muninn, fromKey: String, plain: String): Boolean {
        val o = try { JSONObject(plain) } catch (e: Exception) { return false }
        if (o.optString("t") != "applied") return false
        val v = o.optLong("v", -1)
        if (v < 0) return false
        synchronized(lock) {
            // Another parent's TV answers every parent: nothing to do here, but nothing wrong either.
            val t = _tvs.value[fromKey] ?: return true
            if (t.disconnecting) { forget(app, fromKey); return true }
            val done = v >= t.pendingV
            put(t.copy(applied = maxOf(t.applied, v), v = maxOf(t.v, v), pending = if (done) emptyList() else t.pending))
            if (done) tries.remove(fromKey)
        }
        return true
    }

    /** Shares the TV's contact code, so someone the parent added can add the TV on their side. */
    fun shareText(app: Muninn, key: String): String? {
        val c = app.contacts.get(key) ?: return null
        val code = Contacts.code(c.name, key, c.mailbox ?: "")
        return "Add our Kids' TV in Muninn so it can reach you: ${Contacts.link(code)}"
    }

    private fun tick() {
        val app = app ?: return
        val now = System.currentTimeMillis()
        for (t in _tvs.value.values) {
            if (t.pending.isEmpty()) continue
            if (now - t.since > DAY) {
                // A day without an answer: a disconnected TV is let go; a config waits for the next change.
                synchronized(lock) { if (t.disconnecting) forget(app, t.key) else put(t.copy(pending = emptyList())) }
                tries.remove(t.key)
                continue
            }
            val (n, next) = tries[t.key] ?: (0 to 0L)
            if (now < next) continue
            tries[t.key] = (n + 1) to now + RETRY[minOf(n, RETRY.size - 1)]
            send(app, t.key, t.pending)
        }
    }

    private fun send(app: Muninn, key: String, msgs: List<String>) {
        val b = Tv.bridge(app) ?: return
        msgs.forEach { try { b.sendTvRoom(key, it) } catch (_: Exception) {} }
    }

    /** The TV is gone: drop it, its chat and the contact. */
    private fun forget(app: Muninn, key: String) {
        synchronized(lock) {
            _tvs.value = _tvs.value - key
            save(app)
        }
        tries.remove(key)
        try { app.chat.deleteConversation(key); app.chat.removeContact(key) } catch (_: Exception) {}
    }

    private fun put(t: ManagedTv) = synchronized(lock) {
        _tvs.value = _tvs.value + (t.key to t)
        app?.let { save(it) }
    }

    private fun save(app: Muninn) {
        try { app.vault.write(file(app), toJson(_tvs.value).toString()) } catch (_: Exception) {}
    }

    private fun toJson(m: Map<String, ManagedTv>): JSONObject = JSONObject().apply {
        for (t in m.values) put(t.key, JSONObject().put("v", t.v).put("applied", t.applied)
            .put("quick", JSONArray().apply { t.quick.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) } })
            .put("reach", JSONArray(t.reach)).put("dictation", t.dictation).put("parents", JSONArray(t.parents))
            .put("myName", t.myName).put("pending", JSONArray(t.pending)).put("pendingV", t.pendingV).put("since", t.since)
            .put("disconnecting", t.disconnecting))
    }

    private fun fromJson(o: JSONObject): Map<String, ManagedTv> = o.keys().asSequence().mapNotNull { k ->
        try {
            val x = o.getJSONObject(k)
            val q = x.optJSONArray("quick") ?: JSONArray()
            fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            k to ManagedTv(k, x.optLong("v"), x.optLong("applied"),
                (0 until q.length()).map { q.getJSONObject(it).let { e -> Quick(e.getString("id"), e.getString("text")) } },
                strs(x.optJSONArray("reach")), x.optBoolean("dictation"), strs(x.optJSONArray("parents")), x.optString("myName"),
                strs(x.optJSONArray("pending")), x.optLong("pendingV"), x.optLong("since"), x.optBoolean("disconnecting"))
        } catch (e: Exception) { null }
    }.toMap()
}
