package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Messages for people who aren't nearby, through public Nostr relays (no accounts, no server to
 * run). Each phone has a Nostr key; its public half is its mailbox. A message is an event of
 * kind 1059 addressed to the mailbox, signed by a throwaway key so relays can't tell who sent it.
 * The content is already sealed with the chat keys (ChatCrypto), so relays only see scrambled text.
 */
class Nostr(private val context: Context, private val secretHex: () -> String?) {
    private val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
    // No OkHttp ping: Muninn checks every socket itself at once (keepalive), so the radio wakes once, not per relay.
    private val client = Net.client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).build()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val live = ConcurrentHashMap<String, Boolean>()
    private val seen = LinkedHashSet<String>()
    private val working = HashSet<String>()
    private val outbox = mutableListOf<String>()        // events waiting for a relay
    private val subId = "muninn-" + UUID.randomUUID().toString().take(8)
    /** Reconnect by itself when a relay drops (set while Muninn is listening for messages). */
    @Volatile var keepAlive = false
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val retries = ConcurrentHashMap<String, Int>()
    /** How many relays stay open (null: all, 0: none; see PowerLogic), and how often they're checked. */
    @Volatile private var keep: Int? = null
    @Volatile private var pingMs = PowerLogic.FAST_PING_MS
    @Volatile private var chosen: List<String>? = null
    private val connectMs = ConcurrentHashMap<String, Long>()
    private val openedAt = ConcurrentHashMap<String, Long>()
    private val heardAt = ConcurrentHashMap<String, Long>()
    @Volatile private var probedAt = 0L
    /** Short-lived sockets opened only to send (or check the inbox), and what each still has to send. */
    private val burst = ConcurrentHashMap<String, MutableList<String>>()
    private val burstUntil = ConcurrentHashMap<String, Long>()
    private val alarms = context.getSystemService(android.app.AlarmManager::class.java)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val wake by lazy {
        context.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "muninn:relays")
            .apply { setReferenceCounted(false) }
    }
    @Volatile private var fastTick: java.util.concurrent.ScheduledFuture<*>? = null

    /** For the battery report: a check sent ([PING]), an alarm that woke the phone ([WAKE]), a connection made again ([RECONNECT]). */
    @Volatile var counted: (String) -> Unit = {}

    /** Every envelope that arrives for this phone (content of the event). Return false to see it again later. */
    var onEnvelope: ((String) -> Boolean)? = null

    private val _connected = MutableStateFlow(0)
    val connected: StateFlow<Int> = _connected.asStateFlow()

    val relays: List<String>
        get() = prefs.getString("relays", null)?.split('\n')?.filter { it.startsWith("wss://") } ?: DEFAULT_RELAYS

    /** This phone's Nostr secret (in the vault); null while Muninn is locked. */
    private val secret: ByteArray? get() = secretHex()?.let { Schnorr.unhex(it) }
    /** The mailbox: readable even while locked, so the inbox can be checked. */
    val publicKey: String get() = prefs.getString("nostrPub", null) ?: secret!!.let { Schnorr.hex(Schnorr.publicKey(it)) }.also {
        prefs.edit().putString("nostrPub", it).apply()
    }

    init {
        prefs.getString("nostrSeen", "")!!.split(',').filter { it.isNotEmpty() }.forEach { seen.add(it) }
    }

    // ------------------------------------------------------------------ relays

    /** The relays kept open now: all of them, or the healthiest few while saving battery. */
    private fun targets(): List<String> {
        val all = relays
        val n = keep ?: return all
        val c = chosen?.filter { it in all }
        if (c != null && c.size == minOf(n, all.size)) return c
        return PowerLogic.pick(all, health(all), n).also { chosen = it }
    }

    private fun health(all: List<String>) = all.associateWith {
        PowerLogic.Health(retries[it] ?: 0, connectMs[it], live[it] == true)
    }

    fun connect() { for (url in targets()) open(url); tick() }

    /**
     * Saving battery or not: keep [count] relays open (null: all, 0: none) and check them every [ping] ms.
     * Relays left out close; the healthiest ones are kept.
     */
    fun plan(count: Int?, ping: Long) {
        val changed = count != keep || ping != pingMs
        keep = count; pingMs = ping
        chosen = null
        if (!changed) return
        val want = targets().toSet()
        synchronized(sockets) {
            for (url in sockets.keys.toList()) if (url !in want && !burst.containsKey(url)) {
                sockets.remove(url)?.close(1000, null)
                live.remove(url)
            }
        }
        _connected.value = live.size
        if (keepAlive) for (url in want) open(url)
        tick()
    }

    /** Listening live right now (so a separate inbox check isn't needed). */
    fun listening(): Boolean = keepAlive && keep != 0 && targets().any { live[it] == true }

    /** The 15-minute inbox check: every relay for a short while, closing again unless it's one kept open. */
    fun checkInbox() { for (url in relays) openBurst(url, emptyList()) }

    /** Opens [url] just to send [msgs] (or read the inbox), then closes it after a short while. */
    private fun openBurst(url: String, msgs: List<String>) {
        val ws = sockets[url]
        if (ws != null && live[url] == true) msgs.forEach { ws.send(it) }
        else burst.getOrPut(url) { java.util.Collections.synchronizedList(mutableListOf()) }.addAll(msgs)   // sent once it opens
        burstUntil[url] = android.os.SystemClock.elapsedRealtime() + BURST_SECONDS * 1000 - 500
        if (ws == null) open(url)
        timer.schedule(Runnable {
            if (android.os.SystemClock.elapsedRealtime() < (burstUntil[url] ?: 0L)) return@Runnable   // used again meanwhile
            burst.remove(url); burstUntil.remove(url)
            if (keepAlive && url in targets()) return@Runnable   // one of the relays kept open anyway
            synchronized(sockets) { sockets.remove(url)?.close(1000, null); live.remove(url); _connected.value = live.size }
        }, BURST_SECONDS, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ keepalive

    /**
     * One check for every socket together: a tiny request each relay answers at once. A socket that
     * doesn't answer within [PowerLogic.ANSWER_MS] is dropped and opened again (one missed check is
     * enough). While saving battery the check rides on an inexact alarm: Android runs it within the last
     * [PowerLogic.PING_WINDOW_MS] before it's due, together with other apps' wake-ups, so the radio wakes
     * once for all of them. A second alarm that also runs while the phone dozes backs it up, and a timer
     * checks on time while the phone is awake anyway.
     */
    private fun tick() {
        try { alarms?.cancel(alarmIntent); alarms?.cancel(idleIntent) } catch (_: Exception) {}
        fastTick?.cancel(false); fastTick = null
        if (!keepAlive || keep == 0) { probedAt = 0L; return }
        val every = pingMs
        if (every <= PowerLogic.FAST_PING_MS) {
            fastTick = timer.scheduleWithFixedDelay({ probe() }, every, every, TimeUnit.MILLISECONDS)
            return
        }
        val at = android.os.SystemClock.elapsedRealtime() + every
        val window = minOf(PowerLogic.PING_WINDOW_MS, every / 2)
        try {
            val a = alarms!!
            a.setWindow(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at - window, window, alarmIntent)
            // Dozing, the alarm above waits for Android's next maintenance window: this one still runs (a little late).
            a.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, idleIntent)
        } catch (_: Exception) {}
        // Awake anyway (screen on, or another app woke the phone): check on time without waiting for the alarm.
        fastTick = timer.schedule(Runnable { due() }, every, TimeUnit.MILLISECONDS)
    }

    private val idleIntent: android.app.PendingIntent by lazy {
        android.app.PendingIntent.getBroadcast(context, 1, android.content.Intent(context, RelayAlarm::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private val alarmIntent: android.app.PendingIntent by lazy {
        android.app.PendingIntent.getBroadcast(context, 0, android.content.Intent(context, RelayAlarm::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The keepalive alarm went off ([RelayAlarm]). */
    fun onAlarm() {
        counted(WAKE)
        // Long enough for the requests to go out, the answers to come back, and a dead socket to be replaced.
        try { wake.acquire(PowerLogic.ANSWER_MS + 5_000) } catch (_: Exception) {}
        timer.execute { due() }
    }

    /** The alarm or the backup timer, whichever comes first: one check, then the next one is set. */
    private fun due() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (probedAt == 0L || now - probedAt >= pingMs / 2) probe()
        main.post { tick() }
    }

    private fun probe() {
        if (!keepAlive || keep == 0) return
        val before = probedAt
        val now = android.os.SystemClock.elapsedRealtime()
        for (url in targets()) {
            val ws = sockets[url]
            if (ws == null) { open(url); continue }
            if (live[url] != true) continue
            if (PowerLogic.silent(before, heardAt[url] ?: 0L) && (openedAt[url] ?: 0L) < before) { replace(url, ws); continue }
            val filter = JSONObject().put("kinds", JSONArray().put(KIND)).put("since", System.currentTimeMillis() / 1000).put("limit", 0)
            ws.send(JSONArray().put("REQ").put(PROBE).put(filter).toString())
        }
        probedAt = now
        counted(PING)
        // One missed check is enough: look again once the answers should be in. Usually they all are within
        // a second or two; then the phone may sleep again at once.
        timer.schedule(Runnable { if (answered(now)) release() }, 2, TimeUnit.SECONDS)
        timer.schedule(Runnable { verify(now) }, PowerLogic.ANSWER_MS, TimeUnit.MILLISECONDS)
    }

    private fun answered(at: Long) = targets().all { url -> live[url] != true || (heardAt[url] ?: 0L) >= at }

    private fun release() { try { if (wake.isHeld) wake.release() } catch (_: Exception) {} }

    /** Sockets that didn't answer the check made at [at] are dead (often after a network change): start over. */
    private fun verify(at: Long) {
        if (!keepAlive || keep == 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        var replaced = false
        for (url in targets()) {
            val ws = sockets[url] ?: continue
            if (live[url] != true) continue
            if (PowerLogic.dead(at, heardAt[url] ?: 0L, openedAt[url] ?: 0L, now)) { replace(url, ws); replaced = true }
        }
        // A replaced socket keeps the phone awake a few seconds more (the wake lock's own timeout) to connect.
        if (!replaced) release()
    }

    private fun replace(url: String, ws: WebSocket) {
        synchronized(sockets) { if (!sockets.remove(url, ws)) return; live.remove(url); _connected.value = live.size }
        ws.cancel()
        retries.remove(url)
        counted(RECONNECT)
        open(url)
    }

    /** One socket per relay, never two: only opens if there isn't one already. */
    private fun open(url: String) {
        synchronized(sockets) {
            if (sockets.containsKey(url)) return
            openedAt[url] = android.os.SystemClock.elapsedRealtime()
            sockets[url] = client.newWebSocket(Request.Builder().url(url).build(), Listener(url))
        }
    }

    /** The network came back: drop dead connections and connect again now. */
    fun reconnectNow() {
        if (!keepAlive && sockets.isEmpty()) return
        for (url in targets()) if (live[url] != true) {
            synchronized(sockets) { sockets.remove(url)?.cancel() }
            retries.remove(url)
            open(url)
        }
        // Sockets that look open may have died with the old network: ask now (a socket that doesn't answer
        // within seconds is replaced) and ask again shortly, instead of waiting for the next keepalive.
        if (keepAlive && keep != 0) timer.execute {
            probe()
            timer.schedule(Runnable { probe() }, 15, TimeUnit.SECONDS)
        }
    }

    /** Asks every open relay for the inbox again: after unlocking, so what came while locked is read now. */
    fun resubscribe() { for ((url, ws) in sockets) if (live[url] == true) subscribe(ws) }

    fun disconnect() {
        keepAlive = false
        tick()
        synchronized(sockets) { sockets.values.forEach { it.close(1000, null) }; sockets.clear() }
        live.clear()
        _connected.value = 0
    }

    private fun subscribe(ws: WebSocket) {
        // Events can be backdated a little (see publish), so look back two days and skip ones already seen.
        val since = maxOf(0L, prefs.getLong("nostrSince", 0L) - 2 * 86400)
        val filter = JSONObject().put("kinds", JSONArray().put(KIND)).put("#p", JSONArray().put(publicKey)).put("since", since)
        ws.send(JSONArray().put("REQ").put(subId).put(filter).toString())
    }

    private inner class Listener(val url: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (sockets[url] !== webSocket) { webSocket.cancel(); return }   // replaced meanwhile
            // Only reset the back-off once it has stayed up a minute (a relay that accepts and drops at once).
            timer.schedule({ if (sockets[url] === webSocket && live[url] == true) retries.remove(url) }, 60, java.util.concurrent.TimeUnit.SECONDS)
            val now = android.os.SystemClock.elapsedRealtime()
            connectMs[url] = now - (openedAt[url] ?: now)
            heardAt[url] = now
            live[url] = true
            _connected.value = live.size
            subscribe(webSocket)
            synchronized(outbox) { outbox.forEach { webSocket.send(it) } }
            // What was just sent goes to this relay too (relays drop an event they already have).
            synchronized(recent) {
                val now = android.os.SystemClock.elapsedRealtime()
                recent.filter { now - it.first <= RECENT_MS }.forEach { (_, m) -> synchronized(outbox) { if (m !in outbox) webSocket.send(m) } }
            }
            burst[url]?.let { m -> synchronized(m) { m.forEach { webSocket.send(it) }; m.clear() } }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (sockets[url] === webSocket) heardAt[url] = android.os.SystemClock.elapsedRealtime()
            val a = try { JSONArray(text) } catch (e: Exception) { return }
            when (a.optString(0)) {
                "EOSE" -> if (a.optString(1) == PROBE) webSocket.send(JSONArray().put("CLOSE").put(PROBE).toString())
                "EVENT" -> a.optJSONObject(2)?.let { handle(it) }
                // Some relays only hand out messages to the mailbox's owner: prove it (NIP-42).
                "AUTH" -> {
                    val secret = secret ?: return
                    val ev = event(22242, JSONArray().put(JSONArray().put("relay").put(url)).put(JSONArray().put("challenge").put(a.optString(1))), "", secret)
                    webSocket.send(JSONArray().put("AUTH").put(ev).toString())
                    subscribe(webSocket)
                }
                "OK" -> if (a.optBoolean(2)) {
                    synchronized(outbox) { outbox.removeAll { it.contains(a.optString(1)) } }
                    stored.remove(a.optString(1))?.let { done -> try { done() } catch (_: Exception) {} }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = gone(webSocket)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); gone(webSocket) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket)

        /** Only the current socket for this relay counts; an old one closing changes nothing. */
        private fun gone(webSocket: WebSocket) {
            synchronized(sockets) { if (!sockets.remove(url, webSocket)) return }
            live.remove(url)
            _connected.value = live.size
            if (!keepAlive || url !in targets()) return
            // Try again: 2 s, 4 s, 8 s ... up to a minute between tries.
            val n = (retries[url] ?: 0) + 1
            retries[url] = n
            counted(RECONNECT)
            // Saving battery with one relay open (or a few): one that fails gives its place to a healthier one,
            // the only one at once.
            if (keep != null && n >= PowerLogic.swapAfter(keep)) {
                chosen = null
                val now = targets()
                for (u in now) if (u != url) open(u)
                if (url !in now) return
            }
            timer.schedule({ if (keepAlive) open(url) },
                minOf(60L, 1L shl minOf(n, 6)), java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /** Events already announced while locked (not opened yet): say "New message" once, not per relay. */
    private val announced = HashSet<String>()

    private fun handle(ev: JSONObject) {
        val id = ev.optString("id")
        // The same event comes from every relay at once: only one copy is handled at a time.
        synchronized(seen) { if (id.isEmpty() || seen.contains(id) || !working.add(id)) return }
        val locked = secretHex() == null
        if (locked && synchronized(seen) { !announced.add(id) }) { synchronized(seen) { working.remove(id) }; return }
        try {
            // Only trust what the event's own signature covers.
            if (idOf(ev) != id || !Schnorr.verify(Schnorr.unhex(id), Schnorr.unhex(ev.optString("pubkey")), Schnorr.unhex(ev.optString("sig")))) return
            if (onEnvelope?.invoke(ev.optString("content")) != true) return
        } finally { synchronized(seen) { working.remove(id) } }
        synchronized(seen) {
            seen.add(id)
            while (seen.size > 2000) seen.remove(seen.first())
            prefs.edit().putString("nostrSeen", seen.joinToString(",")).putLong("nostrSince", System.currentTimeMillis() / 1000).apply()
        }
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends sealed content to someone's mailbox through the relays open now. Returns how many relays are
     * connected now. [ok] runs once a relay has said it stored the event (its "OK"), not before.
     *
     * Saving battery, nothing extra is opened for it: a message I write opens the conversation window, which
     * connects every relay, and those sockets get it as they open ([recent]). With no relay open at all, one
     * short-lived socket to the healthiest relay carries it.
     */
    fun publish(mailbox: String, content: String, ok: (() -> Unit)? = null): Int {
        val tags = JSONArray().put(JSONArray().put("p").put(mailbox))
        // A new throwaway key for every message, and a slightly blurred time, so relays can't link messages to a sender.
        val ev = event(KIND, tags, content, Schnorr.newSecret(), System.currentTimeMillis() / 1000 - Random.nextLong(0, 600))
        val msg = JSONArray().put("EVENT").put(ev).toString()
        if (ok != null) { if (stored.size > 500) stored.clear(); stored[ev.getString("id")] = ok }
        synchronized(outbox) { outbox.add(msg); while (outbox.size > 200) outbox.removeAt(0) }
        synchronized(recent) {
            val now = android.os.SystemClock.elapsedRealtime()
            recent.addLast(now to msg)
            while (recent.size > 100 || (recent.isNotEmpty() && now - recent.first().first > RECENT_MS)) recent.removeFirst()
        }
        var n = 0
        for ((url, ws) in sockets) if (live[url] == true && ws.send(msg)) n++
        if (keep != null && n == 0) PowerLogic.pick(relays, health(relays), 1).firstOrNull()?.let { openBurst(it, listOf(msg)) }
        if (sockets.isEmpty()) connect()
        return n
    }

    /** Events sent in the last [RECENT_MS]: a relay that connects meanwhile gets them too, even once another said OK. */
    private val recent = ArrayDeque<Pair<Long, String>>()
    /** Event id to what to do once a relay has stored it. */
    private val stored = ConcurrentHashMap<String, () -> Unit>()

    companion object {
        const val KIND = 1059
        private const val PROBE = "muninn-alive"
        const val PING = "ping"
        const val WAKE = "wake"
        const val RECONNECT = "reconnect"
        private const val BURST_SECONDS = 20L
        /** How long a sent event is also given to relays that connect afterwards (the conversation window opening). */
        private const val RECENT_MS = 60_000L
        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.nostr.band")

        /** NIP-01: the event id is the SHA-256 of [0, pubkey, created_at, kind, tags, content] as compact JSON. */
        fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: JSONArray, content: String): String =
            JSONArray().put(0).put(pubkey).put(createdAt).put(kind).put(tags).put(content).toString().replace("\\/", "/")

        fun idOf(ev: JSONObject): String = Schnorr.hex(MessageDigest.getInstance("SHA-256").digest(
            serialize(ev.optString("pubkey"), ev.optLong("created_at"), ev.optInt("kind"), ev.optJSONArray("tags") ?: JSONArray(), ev.optString("content")).toByteArray()))

        fun event(kind: Int, tags: JSONArray, content: String, secret: ByteArray, createdAt: Long = System.currentTimeMillis() / 1000,
                  aux: ByteArray? = null): JSONObject {
            val pub = Schnorr.hex(Schnorr.publicKey(secret))
            val id = MessageDigest.getInstance("SHA-256").digest(serialize(pub, createdAt, kind, tags, content).toByteArray())
            val sig = if (aux != null) Schnorr.sign(id, secret, aux) else Schnorr.sign(id, secret)
            return JSONObject().put("id", Schnorr.hex(id)).put("pubkey", pub).put("created_at", createdAt).put("kind", kind)
                .put("tags", tags).put("content", content).put("sig", Schnorr.hex(sig))
        }
    }
}

/** The relay keepalive alarm (see [Nostr.onAlarm]): a PendingIntent alarm so it also runs while the phone dozes. */
class RelayAlarm : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        (context.applicationContext as? io.github.ravenor907.muninn.Muninn)?.nostr?.onAlarm()
    }
}
