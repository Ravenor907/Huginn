package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
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
class Nostr(context: Context, private val secretHex: () -> String?) {
    private val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val live = ConcurrentHashMap<String, Boolean>()
    private val seen = LinkedHashSet<String>()
    private val working = HashSet<String>()
    private val outbox = mutableListOf<String>()        // events waiting for a relay
    private val subId = "muninn-" + UUID.randomUUID().toString().take(8)

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

    fun connect() {
        for (url in relays) {
            if (sockets.containsKey(url)) continue
            sockets[url] = client.newWebSocket(Request.Builder().url(url).build(), Listener(url))
        }
    }

    fun disconnect() {
        sockets.values.forEach { it.close(1000, null) }
        sockets.clear()
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
            live[url] = true
            _connected.value = live.size
            subscribe(webSocket)
            synchronized(outbox) { outbox.forEach { webSocket.send(it) } }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val a = try { JSONArray(text) } catch (e: Exception) { return }
            when (a.optString(0)) {
                "EVENT" -> a.optJSONObject(2)?.let { handle(it) }
                // Some relays only hand out messages to the mailbox's owner: prove it (NIP-42).
                "AUTH" -> {
                    val secret = secret ?: return
                    val ev = event(22242, JSONArray().put(JSONArray().put("relay").put(url)).put(JSONArray().put("challenge").put(a.optString(1))), "", secret)
                    webSocket.send(JSONArray().put("AUTH").put(ev).toString())
                    subscribe(webSocket)
                }
                "OK" -> if (a.optBoolean(2)) synchronized(outbox) { outbox.removeAll { it.contains(a.optString(1)) } }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = gone()
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone()

        private fun gone() {
            sockets.remove(url)
            live.remove(url)
            _connected.value = live.size
        }
    }

    private fun handle(ev: JSONObject) {
        val id = ev.optString("id")
        // The same event comes from every relay at once: only one copy is handled at a time.
        synchronized(seen) { if (id.isEmpty() || seen.contains(id) || !working.add(id)) return }
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

    /** Sends sealed content to someone's mailbox through every relay. Returns how many relays are connected now. */
    fun publish(mailbox: String, content: String): Int {
        val tags = JSONArray().put(JSONArray().put("p").put(mailbox))
        // A new throwaway key for every message, and a slightly blurred time, so relays can't link messages to a sender.
        val ev = event(KIND, tags, content, Schnorr.newSecret(), System.currentTimeMillis() / 1000 - Random.nextLong(0, 600))
        val msg = JSONArray().put("EVENT").put(ev).toString()
        synchronized(outbox) { outbox.add(msg); while (outbox.size > 200) outbox.removeAt(0) }
        var n = 0
        for ((url, ws) in sockets) if (live[url] == true && ws.send(msg)) n++
        if (sockets.isEmpty()) connect()
        return n
    }

    companion object {
        const val KIND = 1059
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
