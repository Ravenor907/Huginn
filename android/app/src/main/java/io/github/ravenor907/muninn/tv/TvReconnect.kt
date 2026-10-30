package io.github.ravenor907.muninn.tv

import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A parent who wiped or reinstalled their phone gets back onto the Kids' TV (cap k6, docs/protocol-0.6.md 7.11).
 *
 * On the TV a parent holds OK for 5 seconds on "For a parent", and the TV shows a "Connect a parent" code: its
 * contact code with "tv":2 and a fresh invite. The parent's phone scans it and sends `rejoin`. The TV asks every
 * parent it has (`ask`); each parent's phone answers `here` by itself and shows "Approve". One tap sends `approve`,
 * the TV answers that parent with `swap` (who to add, and the parents that never said `here`: the wiped phone's old
 * key), and that parent's phone signs the next config with the new parent in and the old one out. If no parent says
 * `here` within 2 minutes, nobody is left: the TV sends the newcomer `orphan` with its signed config, and takes the
 * newcomer's own next config (it lists only them as parent).
 */
object TvReconnect {
    const val CAP = "k6"
    const val WAIT_MS = 120_000L
    /** The code's marker: 1 is pairing (7.1), 2 is connecting a parent again. */
    const val CODE_TV = 2

    /** Someone who scanned "Connect a parent", on the TV, until a config makes them a parent. */
    data class Joiner(val k: String, val n: String, val p: String, val at: Long, val here: Set<String> = emptySet(), val orphan: Boolean = false)

    /** A TV asking this phone (one of its parents) to approve someone. */
    data class Request(val tv: String, val k: String, val n: String)

    private val _joiner = MutableStateFlow<Joiner?>(null)
    val joiner: StateFlow<Joiner?> = _joiner.asStateFlow()
    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    /** For the parent's screens: TVs waiting on this phone's one tap ([approve]). */
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    /** On the newcomer's phone: TVs this phone asked to be a parent of again (tv key to since). */
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-reconnect").apply { isDaemon = true } }

    // ------------------------------------------------------------------ messages (pure, checked by the vectors)

    fun rejoin(name: String) = JSONObject().put("t", "rejoin").put("n", name).toString()
    fun ask(k: String, n: String) = JSONObject().put("t", "ask").put("k", k).put("n", n).toString()
    fun here(k: String) = JSONObject().put("t", "here").put("k", k).toString()
    fun approve(k: String) = JSONObject().put("t", "approve").put("k", k).toString()
    fun swap(k: String, n: String, p: String, gone: Collection<String>) =
        JSONObject().put("t", "swap").put("k", k).put("n", n).put("p", p).put("gone", JSONArray(gone.sorted())).toString()
    fun orphan(cfg: JSONObject) = JSONObject().put("t", "orphan").put("cfg", cfg).toString()

    /** Well-formed for its type: exactly its fields, keys are base64 text, names 1 to 40 characters. */
    fun ok(o: JSONObject): Boolean = try {
        val t = o.getString("t")
        val keys = o.keys().asSequence().toSet()
        fun key(f: String) = o.get(f).let { it is String && it.isNotEmpty() && it.length <= 200 && Base64.getDecoder().decode(it).isNotEmpty() }
        fun name(f: String) = o.get(f).let { it is String && it.isNotBlank() && it.codePointCount(0, it.length) <= TvRules.MAX_NAME }
        when (t) {
            "rejoin" -> keys == setOf("t", "n") && name("n")
            "ask" -> keys == setOf("t", "k", "n") && key("k") && name("n")
            "here", "approve" -> keys == setOf("t", "k") && key("k")
            "swap" -> keys == setOf("t", "k", "n", "p", "gone") && key("k") && name("n") && o.get("p").let { it is String && (it.isEmpty() || it.length == 64) } &&
                o.getJSONArray("gone").let { a -> (0 until a.length()).all { a.get(it) is String } }
            "orphan" -> keys == setOf("t", "cfg") && o.get("cfg") is JSONObject
            else -> false
        }
    } catch (e: Exception) { false }

    /** The parents to take off when [approver] approves: every parent but those who said here (and the approver). */
    fun gone(parents: Collection<String>, here: Collection<String>, approver: String): List<String> =
        parents.filter { it !in here && it != approver }.sorted()

    /** The TV's "Connect a parent" code: its contact code with "tv":2 and a fresh invite (an hour). */
    fun code(app: Muninn): String {
        val code = app.chat.myCode()
        val o = JSONObject(String(Base64.getUrlDecoder().decode(code.removePrefix(Contacts.PREFIX)), Charsets.UTF_8)).put("tv", CODE_TV)
        return Contacts.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray(Charsets.UTF_8))
    }

    /** The TV key in a "Connect a parent" code, else null. */
    fun keyIn(text: String): String? {
        val i = text.indexOf(Contacts.PREFIX)
        if (i < 0) return null
        val body = text.substring(i + Contacts.PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return try {
            val o = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8))
            if (o.optInt("tv") == CODE_TV) o.getString("k") else null
        } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------------ on the TV

    /** Mailbox of the person reconnecting, so the TV can reach them over the internet once they're a parent. */
    fun mailboxFor(k: String): String? = _joiner.value?.takeIf { it.k == k }?.p?.takeIf { it.length == 64 }

    /** The newcomer may sign the TV's next config by themselves: nobody else was left. */
    fun orphanFrom(k: String) = _joiner.value?.let { it.k == k && it.orphan } == true

    /** A config made someone a parent: the reconnect is done. */
    fun configured(parents: Collection<String>) { _joiner.value?.let { if (it.k in parents) _joiner.value = null } }

    /** Room tvconfig on a paired TV: rejoin, here and approve. Null: not one of these. */
    fun onTv(app: Muninn, fromKey: String, mailbox: String?, o: JSONObject, inviteOk: Boolean, parents: List<String>, cfg: JSONObject?): Boolean? {
        val t = o.optString("t")
        if (t != "rejoin" && t != "here" && t != "approve") return null
        if (!ok(o) || cfg == null) return false
        val b = Tv.bridge(app) ?: return false
        when (t) {
            "rejoin" -> {
                // Only with a live invite from a code this TV showed (the 5-second hold), from someone who isn't a parent.
                if (!inviteOk || fromKey in parents) return false
                val j = Joiner(fromKey, TvRules.clean(o.getString("n"), TvRules.MAX_NAME).ifBlank { "Parent" }, mailbox ?: "", System.currentTimeMillis())
                _joiner.value = j
                // The newcomer isn't a contact on the TV: reached through the mailbox in their envelope.
                upsert(app, j)
                parents.forEach { try { b.sendTvRoom(it, ask(j.k, j.n)) } catch (_: Exception) {} }
                timer.schedule(Runnable { try { noOneLeft(app, j.k) } catch (_: Exception) {} }, WAIT_MS, TimeUnit.MILLISECONDS)
            }
            "here" -> {
                val j = _joiner.value ?: return true
                if (fromKey !in parents || o.getString("k") != j.k) return false
                _joiner.value = j.copy(here = j.here + fromKey)
            }
            "approve" -> {
                val j = _joiner.value ?: return true
                if (fromKey !in parents || o.getString("k") != j.k) return false
                val gone = gone(parents, j.here, fromKey)
                b.sendTvRoom(fromKey, swap(j.k, j.n, j.p, gone))
            }
        }
        return true
    }

    /** No parent said here: the newcomer gets the TV's config and signs the next one as its only parent. */
    private fun noOneLeft(app: Muninn, k: String) {
        val j = _joiner.value ?: return
        if (j.k != k || j.here.isNotEmpty()) return
        val cfg = KidTv.state.value.cfg?.raw ?: return
        _joiner.value = j.copy(orphan = true)
        Tv.bridge(app)?.sendTvRoom(k, orphan(cfg))
    }

    /** The newcomer as a contact of the TV for now (the next config's parents keep them; anyone left off goes). */
    private fun upsert(app: Muninn, j: Joiner) {
        val c = app.contacts.get(j.k)
        if (c == null) app.contacts.put(io.github.ravenor907.muninn.net.Contact(j.k, j.n, j.p.ifEmpty { null }, verified = true))
        else if (j.p.isNotEmpty() && c.mailbox != j.p) app.contacts.set(c.copy(mailbox = j.p))
    }

    // ------------------------------------------------------------------ on a parent's phone

    /** After adding a TV from its "Connect a parent" code: ask to be its parent again. True if it was such a code. */
    fun askToRejoin(app: Muninn, codeText: String): Boolean {
        val key = keyIn(codeText) ?: return false
        if (TvParent.isManaged(key)) { Ui.say("You already look after this TV."); return true }
        if (app.contacts.get(key) == null) return true
        asked[key] = System.currentTimeMillis()
        Tv.bridge(app)?.sendTvRoom(key, rejoin(TvRules.clean(app.identity.username, TvRules.MAX_NAME).ifBlank { "Parent" }))
        Ui.say("Asked the TV. Another parent approves with one tap; if nobody else is left, this phone takes over in about 2 minutes.")
        return true
    }

    /** Room tvconfig from a TV on a parent's phone: ask, swap and orphan. Null: not one of these. */
    fun onParent(app: Muninn, fromKey: String, o: JSONObject): Boolean? {
        val t = o.optString("t")
        if (t != "ask" && t != "swap" && t != "orphan") return null
        if (!ok(o)) return false
        when (t) {
            "ask" -> {
                if (!TvParent.isManaged(fromKey)) return false
                val k = o.getString("k")
                // Here, by itself: this phone still looks after the TV, so it isn't the wiped one.
                Tv.bridge(app)?.sendTvRoom(fromKey, here(k))
                _requests.value = _requests.value.filterNot { it.tv == fromKey } + Request(fromKey, k, o.getString("n"))
                val tvName = app.contacts.get(fromKey)?.shown ?: "the Kids' TV"
                val r = _requests.value.last()
                try { app.notify(r.n, "Wants to look after $tvName again", fromKey) } catch (_: Exception) {}
                // One tap: the same question wherever Muninn is open.
                Ui.sure(io.github.ravenor907.muninn.ui.Ask("Let ${r.n} look after $tvName again?",
                    "Someone at the TV held OK for 5 seconds and scanned its Connect a parent code. Their old phone is taken off the TV.",
                    "Approve") { approve(app, r) })
            }
            "swap" -> {
                if (!TvParent.isManaged(fromKey)) return false
                val k = o.getString("k")
                val goneArr = o.getJSONArray("gone")
                val gone = (0 until goneArr.length()).map { goneArr.getString(it) }.toSet() - app.identity.publicKey
                TvParent.swapParent(fromKey, TvPerson(k, TvRules.clean(o.getString("n"), TvRules.MAX_NAME).ifBlank { "Parent" }, o.getString("p")), gone)
                _requests.value = _requests.value.filterNot { it.tv == fromKey }
            }
            "orphan" -> {
                if (asked.remove(fromKey) == null) return false
                val cfg = try { val c = o.getJSONObject("cfg"); TvRules.check(c); TvRules.parse(c) } catch (e: Exception) { return false }
                if (cfg.tv != fromKey || !Canon.verify(cfg.by, TvRules.CONFIG, cfg.raw)) return false
                TvParent.takeOver(app, fromKey, cfg)
            }
        }
        return true
    }

    /** One tap on the parent's phone: let the person in [r] look after the TV again. */
    fun approve(app: Muninn, r: Request) {
        Tv.bridge(app)?.sendTvRoom(r.tv, approve(r.k))
        _requests.value = _requests.value.filterNot { it.tv == r.tv }
    }

    fun dismiss(r: Request) { _requests.value = _requests.value.filterNot { it.tv == r.tv } }
}
