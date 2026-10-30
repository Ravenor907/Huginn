package io.github.ravenor907.muninn.tv

import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A parent who wiped or reinstalled their phone gets back onto the Kids' TV (cap k6, docs/protocol-0.6.md 7.11).
 *
 * On the TV a parent holds OK for 5 seconds on "Connect a parent", and the TV shows a code: its contact code with "tv":2
 * and a token of its own (an hour, once). The parent's phone scans it and sends `rejoin`. The TV then warns every parent
 * (`ask`, on every route, the internet included, again every 6 hours) and shows a banner. Any parent can Approve (one tap:
 * the new phone is added, nobody is taken off) or Block (it stops). If nobody blocks within 24 hours, the TV sends the
 * new phone `orphan` with its signed config, and takes the new phone's next config as its only parent: the old parents
 * go. That takeover is the only path that removes anyone.
 */
object TvReconnect {
    const val CAP = "k6"
    const val TOKEN_MS = 60 * 60_000L
    const val WAIT_MS = 24 * 3_600_000L
    const val ASK_AGAIN_MS = 6 * 3_600_000L
    /** The code's marker: 1 is pairing (7.1), 2 is connecting a parent again. */
    const val CODE_TV = 2
    private const val FILE = "tv-reconnect.json"

    /** Someone who scanned "Connect a parent", on the TV, until a config makes them a parent or a parent blocks them. */
    data class Joiner(val k: String, val n: String, val p: String, val at: Long, val asked: Long = 0, val orphan: Boolean = false)

    /** A TV asking this phone (one of its parents) about someone: Approve or Block. */
    data class Request(val tv: String, val k: String, val n: String)

    private val _joiner = MutableStateFlow<Joiner?>(null)
    /** On the TV: the reconnect waiting (for the banner). */
    val joiner: StateFlow<Joiner?> = _joiner.asStateFlow()
    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    /** For the parent's screens: TVs waiting on this phone's Approve or Block. */
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    @Volatile private var token = ""
    @Volatile private var tokenUntil = 0L
    @Volatile private var app: Muninn? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-reconnect").apply { isDaemon = true } }
    @Volatile private var ticking = false

    // ------------------------------------------------------------------ messages (pure, checked by the vectors)

    fun rejoin(name: String, token: String) = JSONObject().put("t", "rejoin").put("n", name).put("r", token).toString()
    fun ask(k: String, n: String) = JSONObject().put("t", "ask").put("k", k).put("n", n).toString()
    fun approve(k: String) = JSONObject().put("t", "approve").put("k", k).toString()
    fun block(k: String) = JSONObject().put("t", "block").put("k", k).toString()
    fun swap(k: String, n: String, p: String) = JSONObject().put("t", "swap").put("k", k).put("n", n).put("p", p).toString()
    fun orphan(cfg: JSONObject) = JSONObject().put("t", "orphan").put("cfg", cfg).toString()
    fun blocked() = JSONObject().put("t", "blocked").toString()

    /** Well-formed for its type: exactly its fields, keys are base64 text, names 1 to 40 characters. */
    fun ok(o: JSONObject): Boolean = try {
        val t = o.getString("t")
        val keys = o.keys().asSequence().toSet()
        fun key(f: String) = o.get(f).let { it is String && it.isNotEmpty() && it.length <= 200 && Base64.getDecoder().decode(it).isNotEmpty() }
        fun name(f: String) = o.get(f).let { it is String && it.isNotBlank() && it.codePointCount(0, it.length) <= TvRules.MAX_NAME }
        when (t) {
            "rejoin" -> keys == setOf("t", "n", "r") && name("n") && o.get("r").let { it is String && it.length in 16..64 }
            "ask" -> keys == setOf("t", "k", "n") && key("k") && name("n")
            "approve", "block" -> keys == setOf("t", "k") && key("k")
            "swap" -> keys == setOf("t", "k", "n", "p") && key("k") && name("n") && o.get("p").let { it is String && (it.isEmpty() || it.length == 64) }
            "orphan" -> keys == setOf("t", "cfg") && o.get("cfg") is JSONObject
            "blocked" -> keys == setOf("t")
            else -> false
        }
    } catch (e: Exception) { false }

    /** The new phone takes over only when nobody blocked and [WAIT_MS] went by since it asked. */
    fun takeoverDue(at: Long, now: Long, blocked: Boolean): Boolean = !blocked && now - at >= WAIT_MS

    /** The TV's "Connect a parent" code: its contact code (no invite) with "tv":2 and a fresh token of its own. */
    fun code(app: Muninn): String {
        this.app = app
        token = java.util.UUID.randomUUID().toString().replace("-", "")
        tokenUntil = System.currentTimeMillis() + TOKEN_MS
        val code = Contacts.code(app.identity.username, app.identity.publicKey, app.nostr.publicKey)
        val o = JSONObject(String(Base64.getUrlDecoder().decode(code.removePrefix(Contacts.PREFIX)), Charsets.UTF_8)).put("tv", CODE_TV).put("r", token)
        return Contacts.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray(Charsets.UTF_8))
    }

    /** (TV key, token) in a "Connect a parent" code, else null. */
    fun keyIn(text: String): Pair<String, String>? {
        val i = text.indexOf(Contacts.PREFIX)
        if (i < 0) return null
        val body = text.substring(i + Contacts.PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return try {
            val o = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8))
            if (o.optInt("tv") == CODE_TV && o.optString("r").length >= 16) o.getString("k") to o.getString("r") else null
        } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------------ on the TV

    private fun file(a: Muninn) = File(a.filesDir, FILE)

    /** On the TV once the vault is open: a reconnect under way goes on, and the clock starts. */
    fun load(a: Muninn) {
        app = a
        _joiner.value = try {
            a.vault.read(file(a))?.let { JSONObject(it) }?.let { Joiner(it.getString("k"), it.getString("n"), it.optString("p"), it.getLong("at"), it.optLong("asked"), it.optBoolean("orphan")) }
        } catch (e: Exception) { null }
        if (!ticking) { ticking = true; timer.scheduleWithFixedDelay({ try { tick() } catch (_: Exception) {} }, 1, 1, TimeUnit.MINUTES) }
    }

    private fun save(a: Muninn, j: Joiner?) {
        _joiner.value = j
        try {
            if (j == null) file(a).delete()
            else a.vault.write(file(a), JSONObject().put("k", j.k).put("n", j.n).put("p", j.p).put("at", j.at).put("asked", j.asked).put("orphan", j.orphan).toString())
        } catch (_: Exception) {}
    }

    /** Mailbox of the person reconnecting, so the TV can reach them over the internet once they're a parent. */
    fun mailboxFor(k: String): String? = _joiner.value?.takeIf { it.k == k }?.p?.takeIf { it.length == 64 }

    /** The newcomer may sign the TV's next config by themselves: 24 hours went by and nobody blocked. */
    fun orphanFrom(k: String) = _joiner.value?.let { it.k == k && it.orphan } == true

    /** A config made someone a parent: the reconnect is done. */
    fun configured(parents: Collection<String>) { val a = app ?: return; _joiner.value?.let { if (it.k in parents) save(a, null) } }

    /** Room tvconfig on a paired TV: rejoin, approve and block. Null: not one of these. */
    fun onTv(app: Muninn, fromKey: String, mailbox: String?, o: JSONObject, parents: List<String>, cfg: JSONObject?): Boolean? {
        val t = o.optString("t")
        if (t != "rejoin" && t != "approve" && t != "block") return null
        if (!ok(o) || cfg == null) return false
        this.app = app
        val b = Tv.bridge(app) ?: return false
        when (t) {
            "rejoin" -> {
                // Only with the token of a code this TV showed after the 5-second hold, once, within the hour.
                if (token.isEmpty() || o.getString("r") != token || System.currentTimeMillis() > tokenUntil || fromKey in parents) return false
                token = ""
                if (_joiner.value != null) return true   // one at a time
                val now = System.currentTimeMillis()
                val j = Joiner(fromKey, TvRules.clean(o.getString("n"), TvRules.MAX_NAME).ifBlank { "Parent" }, mailbox ?: "", now, asked = now)
                save(app, j)
                upsert(app, j)
                parents.forEach { try { b.sendTvRoomEverywhere(it, ask(j.k, j.n)) } catch (_: Exception) {} }
            }
            "approve" -> {
                val j = _joiner.value ?: return true
                if (fromKey !in parents || o.getString("k") != j.k) return false
                b.sendTvRoomEverywhere(fromKey, swap(j.k, j.n, j.p))
            }
            "block" -> {
                val j = _joiner.value ?: return true
                if (fromKey !in parents || o.getString("k") != j.k) return false
                save(app, null)
                try { b.sendTvRoomEverywhere(j.k, blocked()) } catch (_: Exception) {}
                if (!KidTv.approved(j.k)) { app.chat.deleteConversation(j.k); app.chat.removeContact(j.k) }
            }
        }
        return true
    }

    /** Every minute on the TV: warn the parents again, and after 24 hours with no block, the new phone takes over. */
    private fun tick() {
        val a = app ?: return
        val j = _joiner.value ?: return
        val b = Tv.bridge(a) ?: return
        val s = KidTv.state.value
        val now = System.currentTimeMillis()
        if (!j.orphan && takeoverDue(j.at, now, false)) {
            val cfg = s.cfg?.raw ?: return
            save(a, j.copy(orphan = true))
            upsert(a, j)
            b.sendTvRoomEverywhere(j.k, orphan(cfg))
        } else if (j.orphan && now - j.asked > ASK_AGAIN_MS) {
            // The new phone hasn't sent its config yet: once more.
            s.cfg?.raw?.let { save(a, j.copy(asked = now)); b.sendTvRoomEverywhere(j.k, orphan(it)) }
        } else if (!j.orphan && now - j.asked > ASK_AGAIN_MS) {
            save(a, j.copy(asked = now))
            s.parents.forEach { try { b.sendTvRoomEverywhere(it, ask(j.k, j.n)) } catch (_: Exception) {} }
        }
    }

    /** The newcomer as a contact of the TV for now, so the TV can answer them. */
    private fun upsert(app: Muninn, j: Joiner) {
        val c = app.contacts.get(j.k)
        if (c == null) app.contacts.put(io.github.ravenor907.muninn.net.Contact(j.k, j.n, j.p.ifEmpty { null }, verified = true))
        else if (j.p.isNotEmpty() && c.mailbox != j.p) app.contacts.set(c.copy(mailbox = j.p))
    }

    // ------------------------------------------------------------------ on a parent's phone

    private fun askedFile(a: Muninn) = File(a.filesDir, "tv-rejoin.json")
    private fun askedTvs(a: Muninn): JSONObject = try { a.vault.read(askedFile(a))?.let { JSONObject(it) } } catch (e: Exception) { null } ?: JSONObject()

    /** After adding a TV from its "Connect a parent" code: ask to be its parent again. True if it was such a code. */
    fun askToRejoin(app: Muninn, codeText: String): Boolean {
        val (key, r) = keyIn(codeText) ?: return false
        if (TvParent.isManaged(key)) { Ui.say("You already look after this TV."); return true }
        if (app.contacts.get(key) == null) return true
        val asked = askedTvs(app).put(key, System.currentTimeMillis())
        try { app.vault.write(askedFile(app), asked.toString()) } catch (_: Exception) {}
        Tv.bridge(app)?.sendTvRoomEverywhere(key, rejoin(TvRules.clean(app.identity.username, TvRules.MAX_NAME).ifBlank { "Parent" }, r))
        Ui.say("Asked the TV. Its parents are told: one of them can approve at once. If nobody says no, this phone looks after it in 24 hours.")
        return true
    }

    /** Room tvconfig from a TV on a parent's phone: ask, swap, orphan and blocked. Null: not one of these. */
    fun onParent(app: Muninn, fromKey: String, o: JSONObject): Boolean? {
        val t = o.optString("t")
        if (t != "ask" && t != "swap" && t != "orphan" && t != "blocked") return null
        if (!ok(o)) return false
        when (t) {
            "ask" -> {
                if (!TvParent.isManaged(fromKey)) return false
                val r = Request(fromKey, o.getString("k"), o.getString("n"))
                _requests.value = _requests.value.filterNot { it.tv == fromKey } + r
                val tvName = app.contacts.get(fromKey)?.shown ?: "the Kids' TV"
                try { app.notify(r.n, "Wants to look after $tvName. Approve or block it.", fromKey) } catch (_: Exception) {}
                Ui.sure(io.github.ravenor907.muninn.ui.Ask("Let ${r.n} look after $tvName?",
                    "Someone at the TV scanned its Connect a parent code. If no parent blocks it, that phone looks after the TV in 24 hours " +
                        "and the other parents are taken off. Block it if you don't know about this.",
                    "Approve", extra = "Block", onExtra = { answer(app, r, false) }) { answer(app, r, true) })
            }
            "swap" -> {
                if (!TvParent.isManaged(fromKey)) return false
                TvParent.addParentBack(fromKey, TvPerson(o.getString("k"), TvRules.clean(o.getString("n"), TvRules.MAX_NAME).ifBlank { "Parent" }, o.getString("p")))
                _requests.value = _requests.value.filterNot { it.tv == fromKey }
            }
            "orphan" -> {
                val asked = askedTvs(app)
                if (!asked.has(fromKey)) return false
                val cfg = try { val c = o.getJSONObject("cfg"); TvRules.check(c); TvRules.parse(c) } catch (e: Exception) { return false }
                if (cfg.tv != fromKey || !Canon.verify(cfg.by, TvRules.CONFIG, cfg.raw)) return false
                asked.remove(fromKey)
                try { app.vault.write(askedFile(app), asked.toString()) } catch (_: Exception) {}
                TvParent.takeOver(app, fromKey, cfg)
            }
            "blocked" -> {
                val asked = askedTvs(app)
                if (!asked.has(fromKey)) return false
                asked.remove(fromKey)
                try { app.vault.write(askedFile(app), asked.toString()) } catch (_: Exception) {}
                Ui.say("A parent of ${app.contacts.get(fromKey)?.shown ?: "the TV"} said no.", true)
            }
        }
        return true
    }

    /** One tap on the parent's phone: Approve (the new phone is added) or Block (it stops). */
    fun answer(app: Muninn, r: Request, yes: Boolean) {
        Tv.bridge(app)?.sendTvRoomEverywhere(r.tv, if (yes) approve(r.k) else block(r.k))
        _requests.value = _requests.value.filterNot { it.tv == r.tv }
    }
}
