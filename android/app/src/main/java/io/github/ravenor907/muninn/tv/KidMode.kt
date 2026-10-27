package io.github.ravenor907.muninn.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Contact
import io.github.ravenor907.muninn.net.Contacts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Whether this install is a Kids' TV. Decided once, on the first start on a TV (Android TV or Google TV):
 * a fresh install becomes a Kids' TV; a TV that already has a lock or contacts (someone used Muninn on it
 * before) stays a normal Muninn. Kept outside the vault and outside Muninn's own settings, so neither a
 * disconnect nor Erase all turns it off.
 */
object KidMode {
    private const val PREFS = "muninn-tv"
    /** "kids", "normal", or null while not decided (or not a TV). Compose state, so the screen follows. */
    private var mode by mutableStateOf<String?>(null)
    @Volatile private var loaded = false
    @Volatile private var tv: Boolean? = null

    fun isTv(context: Context): Boolean {
        tv?.let { return it }
        val ui = try { context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION } catch (e: Exception) { false }
        val leanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return (ui || leanback).also { tv = it }
    }

    /** True if this install runs as a Kids' TV. Cheap: MainActivity asks on every composition. */
    fun on(app: Muninn): Boolean {
        if (app.ready.value) Tv.install(app)
        if (!loaded) { mode = prefs(app).getString("mode", null); loaded = true }
        mode?.let { return it == "kids" }
        if (!isTv(app)) return false
        if (app.vault.hasPassword) return decide(app, "normal")
        // No password but not open: the vault's key store key is gone. TvRoot starts the TV over.
        if (!app.ready.value) return true
        return decide(app, if (app.contacts.all.value.isEmpty()) "kids" else "normal")
    }

    /** Before pairing only: run this TV as an ordinary Muninn (with a lock and Settings) from now on. */
    fun useNormally(app: Muninn) { decide(app, "normal") }

    private fun decide(app: Muninn, m: String): Boolean {
        prefs(app).edit().putString("mode", m).apply()
        mode = m
        return m == "kids"
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * What a Kids' TV holds: its parents' keys (empty while pairing) and the config they last sent.
 * [micLocal]: a microphone level set on the TV itself (7.7), used instead of the config's until a parent
 * changes it; [micAt]: when (this TV's clock).
 */
data class KidState(val parents: List<String> = emptyList(), val cfg: TvConfig? = null, val micLocal: Int? = null, val micAt: Long = 0)

/**
 * The TV side of protocol 0.6 section 7: pairing, taking configs, the people it may talk to, and
 * disconnect. Everything is kept in the vault, whose key on a TV is wrapped only by Android's key store:
 * no password, so the TV opens straight to the kids' screen and never asks anyone to sign in.
 */
object KidTv {
    private const val FILE = "tv-kids.json"
    private val _state = MutableStateFlow(KidState())
    val state: StateFlow<KidState> = _state.asStateFlow()
    /** A parent disconnected this TV: erasing, then back to the pairing screen. */
    val wiping = MutableStateFlow(false)
    private val lock = Any()
    // "rich", "mx" and "live" (cap m1): replies, reactions, edits and typing from the same people.
    private val PRIVATE_ROOMS = setOf("private", "photo", "receipt", "unsend", "rich", "mx", "live")

    private fun file(app: Muninn) = File(app.filesDir, FILE)

    fun load(app: Muninn) = synchronized(lock) {
        val o = try { app.vault.read(file(app))?.let { JSONObject(it) } } catch (e: Exception) { null }
        if (o == null) { _state.value = KidState(); return@synchronized }
        val ps = o.optJSONArray("parents")
        val parents = if (ps == null) emptyList() else (0 until ps.length()).map { ps.getString(it) }
        val cfg = o.optJSONObject("cfg")?.let { c -> try { TvRules.check(c); TvRules.parse(c) } catch (e: Exception) { null } }
        val local = if (o.has("micLocal")) o.optInt("micLocal").coerceIn(0, TvRules.MAX_MIC) else null
        _state.value = KidState(parents, cfg, local, o.optLong("micAt"))
        if (KidMode.on(app)) tune(_state.value)
    }

    /** The microphone level (the TV's own, else the parent's) and the call setting take effect on this TV. */
    private fun tune(s: KidState) {
        TvMic.active = s.cfg != null
        TvMic.level = s.micLocal ?: s.cfg?.mic ?: TvRules.DEFAULT_MIC
        micLevel.value = TvMic.level
    }

    /** The level in use, for the TV's own slider. */
    val micLevel = MutableStateFlow(TvRules.DEFAULT_MIC)

    private fun save(app: Muninn, s: KidState) {
        val o = JSONObject().put("parents", JSONArray(s.parents))
        s.cfg?.let { o.put("cfg", it.raw) }
        s.micLocal?.let { o.put("micLocal", it).put("micAt", s.micAt) }
        app.vault.write(file(app), o.toString())
    }

    /**
     * Anyone at the TV moved its microphone slider: it takes effect at once (a call going on too), stays
     * until a parent picks another level, and the parents' phones are told (7.7) so theirs shows it.
     */
    fun setMic(app: Muninn, level: Int) {
        val l = level.coerceIn(0, TvRules.MAX_MIC)
        synchronized(lock) {
            val s = _state.value
            if (s.cfg == null || wiping.value) return
            val next = s.copy(micLocal = l, micAt = System.currentTimeMillis())
            _state.value = next
            try { save(app, next) } catch (_: Exception) {}
            tune(next)
        }
        statusSoon(app)
    }

    private val statusTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-status").apply { isDaemon = true } }
    @Volatile private var statusDue: java.util.concurrent.ScheduledFuture<*>? = null

    /** The status (7.7) to every parent whose app takes it, a moment after the last slider move. */
    private fun statusSoon(app: Muninn, to: List<String>? = null) {
        statusDue?.cancel(false)
        statusDue = statusTimer.schedule({ try { sendStatus(app, to) } catch (_: Exception) {} }, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun sendStatus(app: Muninn, to: List<String>?) {
        val s = _state.value
        val cfg = s.cfg ?: return
        val b = Tv.bridge(app) ?: return
        val ts = if (s.micLocal != null) s.micAt else cfg.ts
        val msg = TvRules.statusMessage(TvMic.level, TvStorage.used(app), ts, own = s.micLocal != null)
        for (k in to ?: s.parents) if ("k3" in (app.contacts.get(k)?.caps ?: emptySet())) try { b.sendTvRoom(k, msg) } catch (_: Exception) {}
    }

    /** The QR code a parent scans: this TV's contact code with a fresh invite (an hour) and "tv":1. */
    fun pairingCode(app: Muninn): String {
        // A fresh TV is named for what it is, not a random Nearby name.
        if (Regex("""Raven \d{4}""").matches(app.identity.username)) app.identity.username = "Kids' TV"
        val code = app.chat.myCode()
        val body = code.removePrefix(Contacts.PREFIX)
        val o = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)).put("tv", 1)
        return Contacts.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray(Charsets.UTF_8))
    }

    /** Kids' mode gate (see [TvHandler.allow]). Calls (k2): only with people on the list, unless a parent turned calls off. */
    fun allow(fromKey: String, room: String): Boolean {
        if (wiping.value) return false
        if (room == "tvconfig") return true
        val s = _state.value
        if (s.parents.isEmpty()) return false
        if (room !in PRIVATE_ROOMS && !(room == "call" && s.cfg?.calls != false)) return false
        return approved(fromKey)
    }

    /** On the parent's list (or a parent): the only people this TV talks to, calls included. */
    fun approved(key: String): Boolean {
        val s = _state.value
        return key in s.parents || s.cfg?.contacts?.any { it.k == key } == true
    }

    /** Room "tvconfig" on the TV: config and disconnect. */
    fun handle(app: Muninn, fromKey: String, mailbox: String?, plain: String, inviteOk: Boolean): Boolean = synchronized(lock) {
        if (wiping.value) return false
        val o = try { JSONObject(plain) } catch (e: Exception) { return false }
        val me = app.identity.publicKey
        val s = _state.value
        when (o.optString("t")) {
            "config" -> {
                val new = o.optJSONObject("cfg") ?: return false
                val parents = if (s.parents.isEmpty()) {
                    // Pairing: only from the one who scanned a code this TV showed in the last hour, signing for themselves.
                    if (!inviteOk || new.opt("by") != fromKey) return false
                    listOf(fromKey)
                } else s.parents
                val (ok, _) = TvRules.accept(me, parents, s.cfg?.raw, new)
                if (!ok) {
                    // Older or the same from a parent (it opened with their key): say where the TV is, so their app stops resending.
                    val cur = s.cfg
                    if (cur != null && fromKey in s.parents) { answer(app, listOf(fromKey), cur.v); return true }
                    return false
                }
                val cfg = try { TvRules.parse(new) } catch (e: Exception) { return false }
                apply(app, cfg, fromKey, mailbox)
                // A parent who picked another microphone level wins over one set on the TV (the last change wins);
                // a config that only carries the level on keeps the TV's own.
                val parentMic = s.cfg == null || cfg.mic != s.cfg.mic
                val next = if (parentMic) KidState(cfg.parents, cfg) else KidState(cfg.parents, cfg, s.micLocal, s.micAt)
                save(app, next)
                _state.value = next
                tune(next)
                // Someone taken off the list (or calls turned off) mid-call: the call ends.
                io.github.ravenor907.muninn.net.call.CallManager.call.value?.let { c ->
                    if (!cfg.calls || !approved(c.peerKey)) io.github.ravenor907.muninn.net.call.CallManager.hangup()
                }
                answer(app, cfg.parents, cfg.v)
                statusSoon(app)
                true
            }
            "update" -> {
                // k2: a parent's "Update TV". Only from a parent; the answer says which Muninn the TV runs now.
                val cur = s.cfg ?: return false
                if (fromKey !in s.parents) return false
                TvUpdate.checkNow(app)
                answer(app, listOf(fromKey), cur.v)
                true
            }
            "usage" -> {
                // k3 (7.7): a parent's phone asks how much room Muninn takes and which level is in use.
                if (s.cfg == null || fromKey !in s.parents) return false
                TvStorage.later { sendStatus(app, listOf(fromKey)) }
                true
            }
            "clear" -> {
                // k3 (7.7): a parent's "Clear history": messages, photos, voice messages and caches. Pairing and contacts stay.
                if (s.cfg == null || fromKey !in s.parents) return false
                TvStorage.later { TvStorage.clear(app); sendStatus(app, null) }
                true
            }
            "disconnect" -> {
                val d = o.optJSONObject("d") ?: return false
                val cur = s.cfg ?: return false
                if (!TvRules.acceptDisconnect(me, s.parents, cur.v, d).first) return false
                wiping.value = true
                answer(app, s.parents, cur.v)   // their apps stop resending; sent before the contacts go
                Thread { try { Thread.sleep(3000); wipe(app) } catch (_: Exception) {} finally { wiping.value = false } }.start()
                true
            }
            else -> false
        }
    }

    /** The TV's contacts become exactly the config's people (and its parents). People taken off go with their chats. */
    private fun apply(app: Muninn, cfg: TvConfig, fromKey: String, mailbox: String?) {
        val keep = cfg.contacts.map { it.k }.toSet() + cfg.parents
        app.contacts.all.value.filter { it.chatKey !in keep }.forEach {
            app.chat.deleteConversation(it.chatKey)
            app.chat.removeContact(it.chatKey)
        }
        for (p in cfg.contacts) upsert(app, p.k, p.n, p.p.takeIf { it.length == 64 })
        for (k in cfg.parents) if (cfg.contacts.none { it.k == k })
            upsert(app, k, app.contacts.get(k)?.name ?: "Parent", if (k == fromKey) mailbox else null)
    }

    private fun upsert(app: Muninn, key: String, name: String, mailbox: String?) {
        val c = app.contacts.get(key)
        if (c == null) app.contacts.put(Contact(key, name, mailbox, verified = true))
        else app.contacts.set(c.copy(name = name, mailbox = mailbox ?: c.mailbox, nickname = null))
    }

    private fun answer(app: Muninn, to: List<String>, v: Long) {
        val b = Tv.bridge(app) ?: return
        val version = try { app.updater.current } catch (e: Exception) { null }
        to.forEach { try { b.sendTvRoom(it, TvRules.appliedMessage(v, version)) } catch (_: Exception) {} }
    }

    /** Disconnect: erase the chats, contacts, config and parent keys, then back to the pairing screen. */
    private fun wipe(app: Muninn) = synchronized(lock) {
        app.chat.deleteAll()
        app.contacts.all.value.forEach { app.chat.removeContact(it.chatKey) }
        file(app).delete()
        _state.value = KidState()
        tune(KidState())
    }

    /** The vault can't be opened (its key store key is gone): nothing on it is readable, so start the TV over. */
    fun startOver(app: Muninn) {
        app.vault.eraseAll()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** Connects Chat to the kids' TV: the TV side in kids' mode, the parent side everywhere else. */
object Tv : TvHandler {
    @Volatile private var app: Muninn? = null

    /** Once the vault is open (Muninn.start, or the first screen after it). Safe to call again. */
    fun install(app: Muninn) {
        val b = app.chat as? TvBridge
        if (this.app === app && (b == null || b.tvHandler === this)) return
        var first = false
        synchronized(this) {
            if (this.app !== app) { this.app = app; first = true; KidTv.load(app); TvParent.load(app) }
            b?.tvHandler = this
        }
        if (first && KidMode.on(app)) kids(app)
    }

    /**
     * A Kids' TV keeps listening with Muninn in the background (the foreground service, as on a phone, also
     * started after the TV boots), brings an incoming call up over whatever is on, and updates itself.
     */
    private fun kids(app: Muninn) {
        if (!app.identity.stayConnected) app.identity.stayConnected = true
        io.github.ravenor907.muninn.net.MessageService.start(app)
        TvRing.watch(app)
        TvUpdate.start(app)
        TvStorage.start(app)
    }

    fun bridge(app: Muninn): TvBridge? = if (app.ready.value) app.chat as? TvBridge else null

    override fun allow(fromKey: String, room: String): Boolean {
        val a = app ?: return true
        return if (KidMode.on(a)) KidTv.allow(fromKey, room) else true
    }

    override fun onTvRoom(fromKey: String, mailbox: String?, plain: String, inviteOk: Boolean): Boolean {
        val a = app ?: return false
        return if (KidMode.on(a)) KidTv.handle(a, fromKey, mailbox, plain, inviteOk) else TvParent.handle(a, fromKey, plain)
    }
}
