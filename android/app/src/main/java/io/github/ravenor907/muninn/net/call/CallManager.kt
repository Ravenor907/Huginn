package io.github.ravenor907.muninn.net.call

import android.content.Context
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Calls for the whole app: at most one 1:1 call at a time, its state for the screens, the call history,
 * and the incoming ring. [init] once the vault is open (Muninn.start), with Chat as the [CallSignal].
 */
object CallManager {
    /** What the call screen shows. Never an address. */
    data class Ui(
        val callId: String, val peerKey: String, val name: String, val outgoing: Boolean, val phase: CallSession.Phase,
        val connectedAt: Long, val muted: Boolean, val speaker: Boolean, val peerMuted: Boolean, val peerSpeaking: Boolean,
        val relay: Boolean, val wifi: Boolean, val bluetooth: Boolean, val endWhy: String, val note: String,
    )

    /** One line of the Calls tab. [result]: "answered", "missed", "declined", "noanswer", "busy", "failed". */
    data class Log(val id: String, val peerKey: String, val name: String, val outgoing: Boolean, val at: Long, val seconds: Long, val result: String)

    private val _call = MutableStateFlow<Ui?>(null)
    /** The call going on (or just ended, for a moment), or null. */
    val call: StateFlow<Ui?> = _call.asStateFlow()
    private val _history = MutableStateFlow<List<Log>>(emptyList())
    val history: StateFlow<List<Log>> = _history.asStateFlow()

    /** Set by whoever owns Bluetooth LE / Nearby to carry media close by. Optional. */
    @Volatile var bluetooth: CallBluetooth? = null

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "muninn-call").apply { isDaemon = true } }
    private val work = Executors.newCachedThreadPool { r -> Thread(r, "muninn-call-work").apply { isDaemon = true } }
    @Volatile private var app: Muninn? = null
    @Volatile private var signal: CallSignal? = null
    @Volatile private var session: CallSession? = null
    /** Call ids already seen, so a late retry of an ended call doesn't ring again. */
    private val seen = LinkedHashMap<String, Long>()
    private const val MAX_HISTORY = 200
    private const val PREFS = "muninn"

    fun init(app: Muninn, signal: CallSignal) {
        this.app = app; this.signal = signal
        exec.execute { _history.value = load(app) }
    }

    /** Can [peerKey] get calls? Only 0.6 apps with "c1" (1.3); the button otherwise says why not. */
    fun canCall(peerKey: String): Boolean = app?.let { a -> try { "c1" in (a.contacts.get(peerKey)?.caps ?: emptySet()) } catch (e: Exception) { false } } == true

    /** Settings › "Internet calls" (default on). Off: only Wi-Fi and Bluetooth, the relay is never asked. */
    fun internetCalls(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("internetCalls", true)
    fun setInternetCalls(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("internetCalls", on).apply()

    // ------------------------------------------------------------------ actions (from the screens)

    /** Starts a call to [peerKey]. False if a call is already going on or calling isn't set up yet. */
    fun start(peerKey: String): Boolean {
        val a = app ?: return false
        if (session != null || Opus.loadError != null) return false
        val s = CallSession(CallCrypto.newCallId(), peerKey, true, host, exec, work)
        session = s
        remember(s.callId)
        publish(s)
        CallService.start(a)
        exec.execute { s.startOutgoing() }
        return true
    }

    @Volatile private var preparedAt = 0L

    /**
     * A conversation with [peerKey] is open: fetch the relay login now (it is cached for two hours), so
     * pressing Call doesn't wait for the relay Worker. At most one try a minute; nothing is fetched when
     * internet calls are off or the contact can't take calls.
     */
    fun prepare(peerKey: String) {
        val a = app ?: return
        val now = System.currentTimeMillis()
        if (now - preparedAt < 60_000 || !internetCalls(a) || !RelayConfig.configured || !canCall(peerKey)) return
        preparedAt = now
        work.execute { try { host.relayLogin() } catch (_: Exception) {} }
    }

    fun answer() { exec.execute { session?.let { s -> app?.let { Ringer.stop(it) }; s.answer(); app?.let { CallService.start(it) } } } }
    fun decline() { exec.execute { session?.decline() } }
    fun hangup() { exec.execute { session?.hangup() } }
    fun setMuted(on: Boolean) { exec.execute { session?.setMuted(on) } }
    fun setSpeaker(on: Boolean) { exec.execute { session?.setSpeaker(on) } }
    /** Clears an ended call from the screen. */
    fun dismiss() { if (_call.value?.phase == CallSession.Phase.ENDED) _call.value = null }

    fun deleteLog(id: String) = exec.execute { _history.value = _history.value.filterNot { it.id == id }; app?.let { save(it) } }
    fun clearHistory() = exec.execute { _history.value = emptyList(); app?.let { save(it) } }

    // ------------------------------------------------------------------ signaling (from Chat)

    /** Every opened room "call" plaintext from a contact or group peer. */
    fun onSignal(fromKey: String, json: String) {
        exec.execute {
            val a = app ?: return@execute
            val s = try { CallLogic.parse(fromKey, json) } catch (e: CallLogic.Bad) { return@execute }
            if (s.voiceRoom) return@execute   // voice rooms (section 6) aren't built yet
            val cur = session
            if (cur != null && cur.callId == s.call) { if (cur.peerKey == fromKey) cur.onSignal(s); return@execute }
            if (s.t != "offer") return@execute
            if (s.codec != CallCrypto.CODEC) { send(fromKey, CallLogic.simple("decline", s.call)); return@execute }
            if (!CallLogic.fresh(s.ts, System.currentTimeMillis())) {
                // Sat on a relay too long: a missed call, no ringing.
                if (remember(s.call)) addLog(Log(s.call, fromKey, nameOf(fromKey), false, s.ts, 0, "missed")).also { Ringer.missed(a, nameOf(fromKey), fromKey) }
                return@execute
            }
            if (cur != null) {
                // Already in a call: busy, 3 times over 2 s.
                val busy = CallLogic.simple("busy", s.call)
                send(fromKey, busy); exec.schedule(Runnable { send(fromKey, busy) }, 1, TimeUnit.SECONDS); exec.schedule(Runnable { send(fromKey, busy) }, 2, TimeUnit.SECONDS)
                if (remember(s.call)) addLog(Log(s.call, fromKey, nameOf(fromKey), false, System.currentTimeMillis(), 0, "missed"))
                return@execute
            }
            if (!remember(s.call)) return@execute   // an old call's late retry
            if (a.chat.blocked.value.contains(fromKey)) return@execute
            val ns = CallSession(s.call, fromKey, false, host, exec, work)
            session = ns
            ns.startIncoming(s)
            publish(ns)
            Ringer.ring(a, ns.callId, nameOf(fromKey), fromKey)
        }
    }

    /** True the first time a call id is seen. */
    private fun remember(id: String): Boolean = synchronized(seen) {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > 10 * 60_000 }
        if (seen.containsKey(id)) false else { seen[id] = now; true }
    }

    private fun send(peerKey: String, o: JSONObject) { try { signal?.send(peerKey, o.toString()) } catch (_: Exception) {} }

    private fun nameOf(k: String) = try { app?.chat?.nameOf(k) ?: "Someone" } catch (e: Exception) { "Someone" }

    private fun publish(s: CallSession) {
        _call.value = Ui(s.callId, s.peerKey, nameOf(s.peerKey), s.outgoing, s.phase, s.connectedAt, s.muted, s.speaker, s.peerMuted,
            s.peerSpeaking, s.relay, s.wifi, s.bluetooth, s.endWhy, s.note)
        app?.let { CallService.update(it) }
    }

    // ------------------------------------------------------------------ what a session gets from the app

    private val host = object : CallSession.Host {
        override val chatPrivate get() = app!!.identity.keys.private
        override fun send(peerKey: String, json: JSONObject) = this@CallManager.send(peerKey, json)
        override fun lanAddressOf(peerKey: String): InetAddress? = try {
            app?.net?.devices?.value?.firstOrNull { it.chatKey == peerKey }?.ip?.let { CallLogic.literal(it) }
        } catch (e: Exception) { null }
        override fun relayLogin(): TurnCreds.Login? {
            val a = app ?: return null
            return TurnCreds.get(a.identity.keys.public.encoded, { url, body -> post(url, body) })
        }
        override fun resolve(host: String) = TurnClient.lookup(host)
        override fun internetAllowed() = app?.let { internetCalls(it) } != false
        override fun newMedia(s: CallSession) = CallMedia(app!!, s.outgoing, s.callId, s.peerKey, bluetooth, object : CallMedia.Events {
            override fun onFirstMedia() = s.mediaFirst()
            override fun onPaths(relay: Boolean, wifi: Boolean, bluetooth: Boolean) = s.mediaPaths(relay, wifi, bluetooth)
            override fun onSpeaking(peer: Boolean) = s.mediaSpeaking(peer)
        })
        override fun changed(s: CallSession) { if (session === s) publish(s) }
        override fun ended(s: CallSession) {
            if (session !== s) return
            session = null
            val a = app
            publish(s)
            if (a != null) {
                Ringer.stop(a)
                CallService.stop(a)
                val result = when (s.endWhy) {
                    "missed" -> "missed"
                    "declined" -> "declined"
                    "noanswer" -> "noanswer"
                    "busy" -> "busy"
                    "nopath", "error" -> if (s.connectedAt > 0) "answered" else "failed"
                    else -> if (s.connectedAt > 0) "answered" else if (s.outgoing) "noanswer" else "missed"
                }
                val secs = if (s.connectedAt > 0) (System.currentTimeMillis() - s.connectedAt) / 1000 else 0
                addLog(Log(s.callId, s.peerKey, nameOf(s.peerKey), s.outgoing, s.startedAt, secs, result))
                if (result == "missed") Ringer.missed(a, nameOf(s.peerKey), s.peerKey)
            }
            // The ended state stays on screen for a moment, then goes.
            exec.schedule(Runnable { if (_call.value?.callId == s.callId && session == null) _call.value = null }, 2500, TimeUnit.MILLISECONDS)
        }
    }

    /** HTTPS POST to the relay Worker. (status, body) or null if it couldn't connect. */
    private fun post(url: String, body: String): Pair<Int, String>? {
        return try {
            Net.client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build().newCall(
                Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
            ).execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------------ history (sealed in the vault)

    private fun file(c: Context) = File(c.filesDir, "calls.json")

    private fun addLog(l: Log) {
        _history.value = (listOf(l) + _history.value.filterNot { it.id == l.id }).take(MAX_HISTORY)
        app?.let { save(it) }
    }

    private fun save(a: Muninn) {
        val arr = JSONArray()
        for (l in _history.value) arr.put(JSONObject().put("id", l.id).put("k", l.peerKey).put("n", l.name).put("out", l.outgoing)
            .put("at", l.at).put("s", l.seconds).put("r", l.result))
        try { a.vault.write(file(a), arr.toString()) } catch (_: Exception) {}
    }

    private fun load(a: Muninn): List<Log> = try {
        val arr = JSONArray(a.vault.read(file(a)) ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Log(it.getString("id"), it.getString("k"), it.optString("n", "Someone"), it.optBoolean("out"), it.optLong("at"), it.optLong("s"), it.optString("r"))
        }
    } catch (e: Exception) { emptyList() }
}
