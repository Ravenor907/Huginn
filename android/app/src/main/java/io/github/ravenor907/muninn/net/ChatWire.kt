package io.github.ravenor907.muninn.net

import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Someone this phone can message from anywhere.
 * [nickname] is only ever kept on this phone: it never goes into a message or a contact code.
 * [ratchet]: their app said it speaks the message ratchet ("cap":"r1").
 * [keyChanged]: their security key changed and nobody has checked the code since.
 * [groupOnly]: a group peer (protocol 0.6, 3.7): someone in a group with me who isn't a contact. Kept here so
 *   they can be reached, never shown in the contact list, and private messages from them need the usual asking.
 * [caps]: what their app said it speaks ("caps" in their envelopes: r1 g1 c1 k1), empty until known.
 */
data class Contact(
    val chatKey: String, val name: String, val mailbox: String?, val verified: Boolean, val invite: String? = null,
    val ratchet: Boolean = false, val nickname: String? = null, val keyChanged: Boolean = false,
    val groupOnly: Boolean = false, val caps: Set<String> = emptySet(),
) {
    /** What this phone shows for them. */
    val shown: String get() = nickname ?: name
}

/**
 * The parts of messaging that don't need Android, so they can be tested off the phone:
 * building and opening envelopes (v1 or the ratchet), contacts on disk, key changes,
 * the outbox of sealed envelopes and the ratchet state per contact.
 *
 * Envelope (the same on every route; Huginn builds the same):
 *   { room, fromKey, fromName, fromId, mailbox, id, cap:"r1", caps:"r1 g1 c1 k1", [o], [invite], [i, n, kind, ms],
 *     v1:      nonce, data            (AES-GCM with the static shared key)
 *     ratchet: rh:{b,d,pn,n}, data    (Ratchet ciphertext, standard base64) }
 */
object ChatWire {
    const val CAP = "r1"
    /** Rooms whose payload goes through the ratchet for a contact that has it. Nearby never does. */
    val RATCHET_ROOMS = setOf("private", "receipt", "unsend", "keep", "photo", "group", "gmsg", "gphoto", "greceipt", "call", "tvconfig",
        // Cap m1: replies, reactions and edits. Typing and active status ("live") stay on v1 so they never move the ratchet.
        "rich", "mx")
    /** Rooms with no id of their own to check against the chat: duplicates are caught with [Seen]. */
    val SEEN_ROOMS = setOf("receipt", "unsend", "keep", "group", "greceipt", "mx", "live")
    /** 0.6 group rooms: only ever sent to someone whose app said "g1" (a 0.5 app would answer 400). */
    val GROUP_ROOMS = setOf("group", "gmsg", "gphoto", "greceipt")
    const val DAY = 86_400_000L

    /** The clear part of every envelope. */
    fun base(room: String, fromKey: String, fromName: String, fromId: String, mailbox: String, msgId: String): JSONObject =
        JSONObject().put("room", room).put("fromKey", fromKey).put("fromName", fromName).put("fromId", fromId)
            .put("mailbox", mailbox).put("id", msgId).put("cap", CAP).put("caps", Proto06.CAPS)

    /** Ratchet when [peer] is given (the caller saves it afterwards), else v1. Always the same plaintext bytes. */
    fun seal(o: JSONObject, plain: ByteArray, sk: ByteArray, theirKey: String, peer: Ratchet.Peer?, newKey: () -> KeyPair): JSONObject {
        o.put("cap", CAP)
        if (peer != null) {
            val (h, c) = peer.encrypt(plain, sk, theirKey, newKey)
            o.remove("nonce")
            return o.put("rh", h.toJson()).put("data", ChatCrypto.b64(c))
        }
        val s = ChatCrypto.sealBytes(sk, plain)
        o.remove("rh")
        return o.put("nonce", s.nonce).put("data", s.data)
    }

    fun isRatchet(o: JSONObject) = o.has("rh")

    /**
     * The plaintext, or null if it isn't authentic. With "rh" it needs the contact's [peer]
     * (left unchanged on failure; on success the caller decides whether to keep the new state).
     */
    fun open(o: JSONObject, sk: ByteArray, myStatic: KeyPair, peer: Ratchet.Peer?, newKey: () -> KeyPair): ByteArray? {
        if (!o.has("rh")) {
            if (!o.has("nonce") || !o.has("data")) return null
            return ChatCrypto.openBytes(sk, ChatCrypto.Sealed(o.optString("nonce"), o.optString("data")))
        }
        val p = peer ?: return null
        return try {
            val h = Ratchet.Header.fromJson(o.getJSONObject("rh"))
            p.decrypt(h, ChatCrypto.b64d(o.getString("data")), sk, myStatic, newKey)
        } catch (e: Exception) { null }
    }

    fun capable(o: JSONObject) = o.optString("cap") == CAP

    /** The 0.6 tokens an envelope says its app speaks ("caps"), plus r1 from the old field. */
    fun capsOf(o: JSONObject): Set<String> = Proto06.caps(o.optString("caps")) + (if (capable(o)) setOf(CAP) else emptySet())

    /** Never show a network address: anything that looks like an IPv4 or IPv6 address becomes [instead]. */
    fun noIp(text: String, instead: String = "a device"): String =
        text.replace(IPV6_BRACKET, instead).replace(IPV4, instead).replace(IPV6_FULL, instead).replace(IPV6_SHORT, instead)
            .replace("/$instead", instead)

    private val IPV4 = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?![\w.])""")
    private val IPV6_BRACKET = Regex("""\[[0-9A-Fa-f:.%\w]*:[0-9A-Fa-f:.%\w]*](?::\d{1,5})?""")
    private val IPV6_FULL = Regex("""(?<![\w:])(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}(?:%\w+)?(?![\w:])""")
    /** With "::" somewhere and at least one hex group (fe80::1, 2001:db8::5, ::1). */
    private val IPV6_SHORT = Regex("""(?<![\w:])(?=[0-9A-Fa-f:]*[0-9A-Fa-f])(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?(?:%\w+)?(?![\w:])""")

    /** Whether a payload for [room] to [c] goes through the ratchet: only for a contact whose app said "r1". */
    fun useRatchet(room: String, c: Contact?) = room in RATCHET_ROOMS && c?.ratchet == true

    // ------------------------------------------------------------------ contacts

    fun contactToJson(c: Contact): JSONObject = JSONObject().put("k", c.chatKey).put("n", c.name).put("p", c.mailbox ?: "")
        .put("v", c.verified).put("i", c.invite ?: "").put("r", c.ratchet).put("nick", c.nickname ?: "").put("kc", c.keyChanged)
        .put("go", c.groupOnly).put("cp", c.caps.sorted().joinToString(" "))

    fun contactFromJson(o: JSONObject) = Contact(o.getString("k"), o.getString("n"), o.optString("p").ifEmpty { null }, o.optBoolean("v"),
        o.optString("i").ifEmpty { null }, o.optBoolean("r"), o.optString("nick").ifEmpty { null }, o.optBoolean("kc"),
        o.optBoolean("go"), Proto06.caps(o.optString("cp")))

    /** Adding [c] again: never downgrade, and a changed key stays unverified until the code is checked. */
    fun merge(old: Contact, c: Contact): Contact {
        val kc = old.keyChanged || c.keyChanged
        return c.copy(verified = !kc && (c.verified || old.verified), mailbox = c.mailbox ?: old.mailbox, name = c.name.ifBlank { old.name },
            invite = c.invite ?: old.invite, ratchet = c.ratchet || old.ratchet, nickname = c.nickname ?: old.nickname, keyChanged = kc,
            // Adding a group peer as a contact just clears groupOnly; a contact never turns into a group peer.
            groupOnly = old.groupOnly && c.groupOnly, caps = old.caps + c.caps)
    }

    /**
     * The contact whose key [c] would replace: someone already here with the same mailbox but a
     * different key. Names never count (anyone can pick any name): same name, other key is just
     * another person. Null when it's a new person or the same key.
     */
    fun keyChangeFor(all: List<Contact>, c: Contact): Contact? {
        if (all.any { it.chatKey == c.chatKey }) return null
        val m = c.mailbox ?: return null
        // A group peer isn't someone you added, so another key with its mailbox isn't a key change of a contact.
        return all.firstOrNull { it.mailbox == m && !it.groupOnly }
    }

    /** The contact after their key changed: same person on this phone (nickname kept), not trusted, no ratchet yet. */
    fun afterKeyChange(old: Contact, c: Contact) = Contact(c.chatKey, c.name.ifBlank { old.name }, c.mailbox, verified = false,
        invite = c.invite, ratchet = false, nickname = old.nickname, keyChanged = true)

    fun keyChangedText(name: String) = "$name's security key changed. Check the code with them before trusting new messages."

    /** Nickname from the Rename box: empty means their own name. */
    fun nickname(text: String): String? = text.trim().take(40).ifEmpty { null }

    /** Search on the chat list: the nickname or the name they chose. */
    fun matches(c: Contact, q: String) = c.name.contains(q, ignoreCase = true) || c.nickname?.contains(q, ignoreCase = true) == true

    /** The contacts screen: "Nickname (their name)". */
    fun listName(c: Contact) = if (c.nickname != null && c.nickname != c.name) "${c.nickname} (${c.name})" else c.name

    // ------------------------------------------------------------------ contact codes

    const val PREFIX = "MUNINN1:"

    fun code(name: String, chatKey: String, mailbox: String, invite: String? = null, expires: Long = 0, once: Boolean = false): String {
        val json = JSONObject().put("n", name).put("k", chatKey).put("p", mailbox)
            .apply { if (invite != null) put("i", invite).put("x", expires / 1000); if (once) put("1", true) }.toString()
        return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun fileName(key: String) = hex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray())).take(40)
}

/**
 * Sealed envelopes waiting for "delivered", by message id: every retry and every route sends these
 * exact bytes, never a new encryption. Kept (encrypted, through [write]) for a day.
 */
class Outbox(private val dir: File, private val read: (File) -> String?, private val write: (File, String) -> Unit,
             private val ttl: Long = ChatWire.DAY) {
    private val memo = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private val lock = Any()

    private fun file(id: String) = File(dir, ChatWire.fileName(id))

    fun get(id: String, now: Long = System.currentTimeMillis()): List<String>? {
        memo[id]?.let { (t, e) -> if (now - t <= ttl) return e; drop(id); return null }
        val o = try { JSONObject(read(file(id)) ?: return null) } catch (e: Exception) { return null }
        val t = o.optLong("t")
        if (o.optString("id") != id || now - t > ttl) { drop(id); return null }
        val a = o.getJSONArray("e")
        val e = (0 until a.length()).map { a.getString(it) }
        memo[id] = t to e
        return e
    }

    /** The cached envelopes for [id], or [make]s them once and keeps them. Never two encryptions for one id. */
    fun getOrMake(id: String, now: Long = System.currentTimeMillis(), make: () -> List<String>?): List<String>? = synchronized(lock) {
        get(id, now)?.let { return it }
        val e = make() ?: return null
        memo[id] = now to e
        try {
            dir.mkdirs()
            val a = org.json.JSONArray(); e.forEach { a.put(it) }
            write(file(id), JSONObject().put("id", id).put("t", now).put("e", a).toString())
        } catch (_: Exception) {}
        e
    }

    fun drop(id: String) { memo.remove(id); file(id).delete() }

    /** Anything older than a day goes. */
    fun sweep(now: Long = System.currentTimeMillis()) {
        memo.entries.removeIf { now - it.value.first > ttl }
        dir.listFiles()?.forEach { if (now - it.lastModified() > ttl) it.delete() }
    }

    fun clear() { memo.clear(); dir.deleteRecursively() }
}

/** Envelope ids already handled (receipts, unsends, keeps), so a copy from another route is dropped before opening. */
class Seen(private val ttl: Long = 2 * ChatWire.DAY) {
    private val map = ConcurrentHashMap<String, Pair<Long, String>>()
    /** What was noted when it was first handled, or null if it's new. */
    fun get(key: String, now: Long = System.currentTimeMillis()): String? = map[key]?.takeIf { now - it.first <= ttl }?.second
    fun add(key: String, note: String = "", now: Long = System.currentTimeMillis()) {
        map[key] = now to note
        if (map.size > 5000) map.entries.removeIf { now - it.value.first > ttl }
    }
}

/**
 * Ratchet state per contact, saved (encrypted, through [write]) after every change that counts.
 * Callers hold [lock] for the contact around get, use and save, so sending and receiving never interleave.
 */
class Ratchets(private val dir: File, private val read: (File) -> String?, private val write: (File, String) -> Unit) {
    private val live = ConcurrentHashMap<String, Ratchet.Peer>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun lock(key: String): Any = locks.getOrPut(key) { Any() }
    private fun file(key: String) = File(dir, ChatWire.fileName(key))

    fun get(key: String): Ratchet.Peer = live.getOrPut(key) {
        try { read(file(key))?.let { Ratchet.Peer.fromJson(JSONObject(it)) } } catch (e: Exception) { null } ?: Ratchet.Peer()
    }

    fun save(key: String, p: Ratchet.Peer) {
        dir.mkdirs()
        write(file(key), p.toJson().toString())
        live[key] = p
    }

    /** Back to what was last saved (a message that was opened but not accepted can come again). */
    fun forget(key: String) { live.remove(key) }

    /** Erased: removing a contact, or their key changed. */
    fun drop(key: String) { live.remove(key); file(key).delete() }
}
