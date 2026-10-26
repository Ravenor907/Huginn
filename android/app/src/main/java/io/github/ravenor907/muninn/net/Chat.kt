package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Texting between Huginn and Muninn, over the LocalSend port.
 *  - Nearby: a public room for everyone on the same network with either app. Usernames only,
 *    no codes. Each copy is sealed for its recipient, so other devices on the Wi-Fi can't read it,
 *    but everyone in the room can.
 *  - Private: one person to another. The first time, both screens show the same 6-digit code;
 *    once both people confirm it, nobody else can read or fake their messages.
 * Wire format (both apps): POST /api/huginn/v1/message
 *   { room: "nearby"|"private", fromKey, fromName, fromId, nonce, data }
 */
class Chat(private val context: Context, private val net: LocalSend) {
    private val id get() = net.identity

    data class Msg(val id: String, val peerKey: String, val name: String, val text: String, val mine: Boolean, val time: Long, val failed: Boolean = false)
    data class PairRequest(val peerKey: String, val name: String, val code: String)

    private val _nearby = MutableStateFlow<List<Msg>>(emptyList())
    val nearby: StateFlow<List<Msg>> = _nearby.asStateFlow()
    private val _private = MutableStateFlow<Map<String, List<Msg>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<Msg>>> = _private.asStateFlow()
    private val _requests = MutableStateFlow<List<PairRequest>>(emptyList())
    val requests: StateFlow<List<PairRequest>> = _requests.asStateFlow()
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val held = ConcurrentHashMap<String, MutableList<Msg>>()   // private messages waiting on a code
    private val recent = ConcurrentHashMap<String, ArrayDeque<Long>>()  // per sender, for the rate limit
    private val pool = Executors.newFixedThreadPool(4)
    private val store = File(context.filesDir, "chats.json")
    private val nearbyStore = File(context.filesDir, "nearby.json")

    init {
        load()
        net.extraRoute = { method, path, body, ip -> if (method == "POST" && path == PATH) receive(body, ip) else null }
    }

    fun codeWith(peerKey: String): String =
        ChatCrypto.pairingCode(ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(peerKey)))

    fun isTrusted(peerKey: String) = id.trusted().containsKey(peerKey)

    // ------------------------------------------------------------------ receiving

    private fun receive(body: String, ip: String): Pair<Int, String> {
        val o = try { JSONObject(body) } catch (e: Exception) { return 400 to "{}" }
        val fromKey = o.optString("fromKey")
        val name = o.optString("fromName").take(40).ifEmpty { "Someone" }
        if (fromKey.isEmpty() || fromKey == id.publicKey) return 400 to "{}"
        // No more than 5 messages in 10 seconds from anyone: nobody can flood the room.
        val now = System.currentTimeMillis()
        val q = recent.getOrPut(fromKey) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
            if (q.size >= 5) return 429 to "{}"
            q.addLast(now)
        }
        val key = try { ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(fromKey)) } catch (e: Exception) { return 400 to "{}" }
        val text = ChatCrypto.open(key, ChatCrypto.Sealed(o.optString("nonce"), o.optString("data")))?.take(MAX) ?: return 403 to "{}"
        val msg = Msg(UUID.randomUUID().toString(), fromKey, name, text, false, now)
        when (o.optString("room")) {
            "nearby" -> {
                if (!id.nearbyChat) return 403 to "{}"
                if (fromKey !in id.muted()) addNearby(msg)
            }
            "private" -> {
                if (isTrusted(fromKey)) {
                    addPrivate(fromKey, msg)
                } else {
                    held.getOrPut(fromKey) { mutableListOf() }.add(msg)
                    if (_requests.value.none { it.peerKey == fromKey }) {
                        _requests.value = _requests.value + PairRequest(fromKey, name, ChatCrypto.pairingCode(key))
                        notices.tryEmit("$name wants to chat privately")
                    }
                }
            }
            else -> return 400 to "{}"
        }
        return 200 to "{}"
    }

    /** Both screens showed the same code: trust them and show what they already sent. */
    fun acceptPair(r: PairRequest) {
        id.trust(r.peerKey, r.name)
        _requests.value = _requests.value.filterNot { it.peerKey == r.peerKey }
        held.remove(r.peerKey)?.forEach { addPrivate(r.peerKey, it) }
    }

    fun declinePair(r: PairRequest) {
        _requests.value = _requests.value.filterNot { it.peerKey == r.peerKey }
        held.remove(r.peerKey)
    }

    // ------------------------------------------------------------------ sending

    fun sendNearby(text: String) {
        val t = text.trim().take(MAX)
        if (t.isEmpty() || !id.nearbyChat) return
        val mine = Msg(UUID.randomUUID().toString(), id.publicKey, id.username, t, true, System.currentTimeMillis())
        addNearby(mine)
        val people = net.devices.value.filter { it.canChat && it.chatName != null && it.chatKey !in id.muted() }
        pool.execute {
            var reached = 0
            for (d in people) if (deliver(d, "nearby", t) == 200) reached++
            if (people.isNotEmpty() && reached == 0) notices.tryEmit("Nobody in Nearby got that. Check you're on the same Wi-Fi.")
        }
    }

    /** Only call once the person has confirmed the code (see codeWith). */
    fun sendPrivate(peerKey: String, name: String, text: String) {
        val t = text.trim().take(MAX)
        if (t.isEmpty()) return
        if (!isTrusted(peerKey)) id.trust(peerKey, name)
        val mine = Msg(UUID.randomUUID().toString(), peerKey, id.username, t, true, System.currentTimeMillis())
        addPrivate(peerKey, mine)
        pool.execute {
            val dev = net.devices.value.firstOrNull { it.chatKey == peerKey }
            val code = if (dev == null) -1 else deliver(dev, "private", t)
            if (code != 200) {
                replace(peerKey, mine.copy(failed = true))
                notices.tryEmit(if (dev == null) "$name isn't nearby right now." else "$name didn't get that. Try again.")
            }
        }
    }

    private fun deliver(d: LocalSend.Device, room: String, text: String): Int {
        val key = ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(d.chatKey ?: return -1))
        val s = ChatCrypto.seal(key, text)
        val body = JSONObject().put("room", room).put("fromKey", id.publicKey).put("fromName", if (room == "nearby") id.username else id.deviceName)
            .put("fromId", id.fingerprint).put("nonce", s.nonce).put("data", s.data)
        return net.post(d, PATH, body.toString())
    }

    // ------------------------------------------------------------------ keeping

    fun mute(peerKey: String, on: Boolean) {
        id.setMuted(peerKey, on)
        if (on) _nearby.value = _nearby.value.filterNot { it.peerKey == peerKey && !it.mine }
    }

    fun clearNearby() { _nearby.value = emptyList(); nearbyStore.delete() }

    fun deleteConversation(peerKey: String) {
        _private.value = _private.value - peerKey
        save()
    }

    private fun addNearby(m: Msg) {
        _nearby.value = (_nearby.value + m).takeLast(300)
        if (id.keepNearbyHistory) saveNearby()
    }

    private fun addPrivate(peer: String, m: Msg) {
        val map = _private.value.toMutableMap()
        map[peer] = (map[peer].orEmpty() + m).takeLast(1000)
        _private.value = map
        save()
    }

    private fun replace(peer: String, m: Msg) {
        val map = _private.value.toMutableMap()
        map[peer] = map[peer].orEmpty().map { if (it.id == m.id) m else it }
        _private.value = map
        save()
    }

    private fun toJson(list: List<Msg>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("peer", it.peerKey).put("name", it.name).put("text", it.text).put("mine", it.mine).put("time", it.time).put("failed", it.failed)) }
    }
    private fun fromJson(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map {
        Msg(it.getString("id"), it.getString("peer"), it.getString("name"), it.getString("text"), it.getBoolean("mine"), it.getLong("time"), it.optBoolean("failed"))
    }

    @Synchronized private fun save() {
        val o = JSONObject(); _private.value.forEach { (k, v) -> o.put(k, toJson(v)) }
        store.writeText(o.toString())
    }
    private fun saveNearby() = nearbyStore.writeText(toJson(_nearby.value).toString())

    private fun load() {
        try {
            if (store.exists()) {
                val o = JSONObject(store.readText())
                _private.value = o.keys().asSequence().associateWith { fromJson(o.getJSONArray(it)) }
            }
            if (id.keepNearbyHistory && nearbyStore.exists()) _nearby.value = fromJson(JSONArray(nearbyStore.readText()))
        } catch (_: Exception) {}
    }

    companion object {
        const val PATH = "/api/huginn/v1/message"
        const val MAX = 2000
    }
}
