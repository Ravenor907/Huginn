package io.github.ravenor907.muninn.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Linked devices, companion style (docs/protocol-0.6.md section 15): a tablet or second phone shows this phone's
 * 1:1 chats and sends through it, while this phone is online. Contacts see nothing new: every message still comes from
 * and goes to this phone. Between the two devices everything is sealed like a private message, on every route.
 *
 *  - The companion shows a link code (its own chat key, mailbox and a token good for 10 minutes); the phone takes it.
 *  - Room "link" from the phone: t "hello" {i} (pairing), "chat" {peer, n, msgs} (a chat's latest messages), "msg"
 *    {peer, n, m} (one message, in or out), "unlink".
 *  - Room "link" from the companion: t "send" {peer, text, id} (the phone sends it as its own), "sync" (all chats again),
 *    "unlink".
 */
object Link {
    const val PREFIX = "MUNINNL1:"
    const val TOKEN_MS = 10 * 60_000L
    const val CHAT_MSGS = 25

    /** A device linked to this one: on the phone, a companion; on the companion, the phone. */
    data class Device(val key: String, val mailbox: String, val name: String)
    /** One mirrored message on the companion. */
    data class M(val id: String, val text: String, val mine: Boolean, val time: Long)
    data class MirrorChat(val peer: String, val name: String, val msgs: List<M>)

    interface Host {
        fun myKey(): String
        fun myMailbox(): String
        fun myName(): String
        /** Seal [json] (room "link") for [d] and send it on every route. */
        fun send(d: Device, json: String)
        /** The phone's chats: peer to (name, messages), newest last. */
        fun chats(): Map<String, Pair<String, List<Chat.Msg>>>
        /** The phone sends [text] to its contact [peer] as its own message. False when [peer] isn't a contact. */
        fun sendAsMe(peer: String, text: String): Boolean
        fun read(name: String): String?
        fun write(name: String, text: String)
        fun notice(text: String)
    }

    @Volatile var host: Host? = null
    private val _companions = MutableStateFlow<List<Device>>(emptyList())
    /** On the phone: the devices that mirror it. */
    val companions: StateFlow<List<Device>> = _companions.asStateFlow()
    private val _phone = MutableStateFlow<Device?>(null)
    /** On a companion: the phone it mirrors. */
    val phone: StateFlow<Device?> = _phone.asStateFlow()
    private val _mirror = MutableStateFlow<Map<String, MirrorChat>>(emptyMap())
    val mirror: StateFlow<Map<String, MirrorChat>> = _mirror.asStateFlow()
    @Volatile private var token = ""
    @Volatile private var tokenUntil = 0L

    fun load() {
        val h = host ?: return
        try {
            h.read("link.json")?.let { t ->
                val o = JSONObject(t)
                _companions.value = o.optJSONArray("companions")?.let { a -> (0 until a.length()).map { dev(a.getJSONObject(it)) } }.orEmpty()
                _phone.value = o.optJSONObject("phone")?.let { dev(it) }
            }
            h.read("mirror.json")?.let { t ->
                val o = JSONObject(t)
                _mirror.value = o.keys().asSequence().associateWith { k -> chatOf(o.getJSONObject(k)) }
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val h = host ?: return
        h.write("link.json", JSONObject().put("companions", JSONArray().apply { _companions.value.forEach { put(devJson(it)) } })
            .apply { _phone.value?.let { put("phone", devJson(it)) } }.toString())
    }

    private fun saveMirror() {
        val h = host ?: return
        h.write("mirror.json", JSONObject().apply { _mirror.value.forEach { (k, c) -> put(k, chatJson(c)) } }.toString())
    }

    // ------------------------------------------------------------------ pairing

    /** On the device that will be the companion: a code for the phone to take (new token each time). */
    fun myLinkCode(): String {
        val h = host ?: return ""
        token = java.util.UUID.randomUUID().toString().replace("-", "")
        tokenUntil = System.currentTimeMillis() + TOKEN_MS
        val json = JSONObject().put("k", h.myKey()).put("p", h.myMailbox()).put("n", h.myName()).put("i", token).toString()
        return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    /** On the phone: link the device whose code this is. Null when done, else what to say. */
    fun link(code: String): String? {
        val h = host ?: return "Not ready."
        val i = code.indexOf(PREFIX)
        if (i < 0) return "That isn't a link code. On the other device: Settings › Linked devices › Use as a companion."
        val o = try { JSONObject(String(java.util.Base64.getUrlDecoder().decode(code.substring(i + PREFIX.length).trim().takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }))) }
            catch (e: Exception) { return "That link code is damaged." }
        val k = o.optString("k")
        try { ChatCrypto.publicFrom(k) } catch (e: Exception) { return "That link code is damaged." }
        if (k == h.myKey()) return "That's this device's own code."
        val d = Device(k, o.optString("p"), o.optString("n").take(40).ifBlank { "Linked device" })
        _companions.value = _companions.value.filterNot { it.key == k } + d
        save()
        h.send(d, JSONObject().put("t", "hello").put("i", o.optString("i")).put("n", h.myName()).put("p", h.myMailbox()).toString())
        syncAll(d)
        return null
    }

    fun unlink(key: String) {
        val h = host ?: return
        _companions.value.firstOrNull { it.key == key }?.let { h.send(it, JSONObject().put("t", "unlink").toString()) }
        _phone.value?.takeIf { it.key == key }?.let { h.send(it, JSONObject().put("t", "unlink").toString()); _phone.value = null; _mirror.value = emptyMap(); saveMirror() }
        _companions.value = _companions.value.filterNot { it.key == key }
        save()
    }

    // ------------------------------------------------------------------ the phone's side

    /** Every chat's latest messages to [d] (or to every companion). */
    fun syncAll(d: Device? = null) {
        val h = host ?: return
        val to = if (d != null) listOf(d) else _companions.value
        if (to.isEmpty()) return
        for ((peer, chat) in h.chats()) {
            // Kept well under the 64 KB an envelope may be: long messages are cut here (the phone has them whole).
            val msgs = JSONArray().apply { chat.second.takeLast(CHAT_MSGS).forEach { put(mJson(it).put("x", text(it).take(600))) } }
            val json = JSONObject().put("t", "chat").put("peer", peer).put("n", chat.first).put("msgs", msgs).toString()
            to.forEach { h.send(it, json) }
        }
    }

    /** A message in or out of a chat on the phone: the companions get it too. */
    fun onMessage(peer: String, name: String, m: Chat.Msg) {
        val h = host ?: return
        if (_companions.value.isEmpty()) return
        val json = JSONObject().put("t", "msg").put("peer", peer).put("n", name).put("m", mJson(m)).toString()
        _companions.value.forEach { h.send(it, json) }
    }

    private fun mJson(m: Chat.Msg) = JSONObject().put("id", m.id).put("x", text(m).take(2000)).put("me", m.mine).put("t", m.time)

    private fun text(m: Chat.Msg) = when {
        m.unsent.isNotEmpty() -> "Message deleted"
        m.voice > 0 -> "Voice message"
        m.video > 0 -> "Video message"
        m.file.isNotEmpty() -> "File: ${m.file}"
        m.photo.isNotEmpty() -> "Photo"
        else -> m.text
    }

    // ------------------------------------------------------------------ room "link", either side

    /** From [from], already opened. False: not from a device linked with this one. */
    fun receive(from: String, p: JSONObject): Boolean {
        val h = host ?: return false
        val t = p.optString("t")
        val comp = _companions.value.firstOrNull { it.key == from }
        val ph = _phone.value?.takeIf { it.key == from }
        when {
            // Pairing: the phone answers my link code with its token.
            t == "hello" && ph == null && comp == null -> {
                if (token.isEmpty() || p.optString("i") != token || System.currentTimeMillis() > tokenUntil) return false
                token = ""
                _phone.value = Device(from, p.optString("p"), p.optString("n").take(40).ifBlank { "Your phone" })
                _mirror.value = emptyMap()
                save(); saveMirror()
                h.notice("Linked. Your phone's chats show here while it's online.")
            }
            t == "unlink" && (comp != null || ph != null) -> {
                if (comp != null) { _companions.value = _companions.value - comp }
                if (ph != null) { _phone.value = null; _mirror.value = emptyMap(); saveMirror() }
                save()
            }
            ph != null && t == "chat" -> {
                val c = MirrorChat(p.optString("peer"), p.optString("n").take(40), msgsOf(p.optJSONArray("msgs")))
                if (c.peer.isNotEmpty()) { _mirror.value = _mirror.value + (c.peer to c); saveMirror() }
            }
            ph != null && t == "msg" -> {
                val peer = p.optString("peer")
                val m = p.optJSONObject("m")?.let { mOf(it) } ?: return true
                val old = _mirror.value[peer]
                val list = (old?.msgs.orEmpty().filterNot { it.id == m.id } + m).sortedBy { it.time }.takeLast(200)
                _mirror.value = _mirror.value + (peer to MirrorChat(peer, p.optString("n").ifBlank { old?.name ?: "Someone" }.take(40), list))
                saveMirror()
                if (!m.mine) h.notice("${p.optString("n")}: ${m.text.take(60)}")
            }
            comp != null && t == "send" -> {
                val text = p.optString("text").trim()
                if (text.isNotEmpty()) h.sendAsMe(p.optString("peer"), text.take(2000))
            }
            comp != null && t == "sync" -> syncAll(comp)
            else -> return comp != null || ph != null
        }
        return true
    }

    // ------------------------------------------------------------------ the companion's side

    /** Send [text] to [peer] through the phone. False when no phone is linked. */
    fun sendFromCompanion(peer: String, text: String): Boolean {
        val h = host ?: return false
        val ph = _phone.value ?: return false
        h.send(ph, JSONObject().put("t", "send").put("peer", peer).put("text", text).put("id", java.util.UUID.randomUUID().toString()).toString())
        return true
    }

    fun askSync() { val h = host ?: return; _phone.value?.let { h.send(it, JSONObject().put("t", "sync").toString()) } }

    // ------------------------------------------------------------------ storage

    private fun devJson(d: Device) = JSONObject().put("k", d.key).put("p", d.mailbox).put("n", d.name)
    private fun dev(o: JSONObject) = Device(o.getString("k"), o.optString("p"), o.optString("n"))
    private fun mOf(o: JSONObject) = M(o.optString("id"), o.optString("x"), o.optBoolean("me"), o.optLong("t"))
    private fun msgsOf(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { mOf(a.getJSONObject(it)) }
    private fun chatJson(c: MirrorChat) = JSONObject().put("peer", c.peer).put("n", c.name)
        .put("msgs", JSONArray().apply { c.msgs.forEach { put(JSONObject().put("id", it.id).put("x", it.text).put("me", it.mine).put("t", it.time)) } })
    private fun chatOf(o: JSONObject) = MirrorChat(o.optString("peer"), o.optString("n"), msgsOf(o.optJSONArray("msgs")))
}
