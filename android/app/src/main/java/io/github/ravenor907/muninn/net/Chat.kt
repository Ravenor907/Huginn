package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Muninn's messaging. Three ways to reach someone, picked automatically, best first:
 *  1. Close by: Bluetooth / Wi-Fi Direct (Close), no network needed.
 *  2. Same Wi-Fi: the LocalSend port (also how Huginn chats).
 *  3. Anywhere: Nostr relays, for contacts (people met nearby or who shared a code).
 * Rooms:
 *  - Nearby: public, for everyone close by or on the same Wi-Fi. Usernames, no codes.
 *  - Private: one person. Sealed end to end with the chat keys whichever way it travels.
 *    Anyone close by can message straight away, no asking. After 10 messages, or once they're
 *    out of range, both get "Keep in touch?"; when both say yes they become contacts and can
 *    message from anywhere (the internet route is only for contacts).
 * One message format everywhere: { room, fromKey, fromName, mailbox, nonce, data }.
 */
class Chat(private val context: Context, private val net: LocalSend, private val close: Close, private val nostr: Nostr, val contacts: Contacts,
           private val vault: Vault) {
    private val id get() = net.identity

    data class Msg(val id: String, val peerKey: String, val name: String, val text: String, val mine: Boolean, val time: Long,
                   val failed: Boolean = false, val via: String = "",
                   /** Mine: "sending", "sent", "delivered", "read". Theirs: "read" once a read receipt went back. */
                   val state: String = "",
                   /** A photo's id (stored encrypted in photos/); text is empty then. */
                   val photo: String = "")
    /** Someone met close by who isn't a contact (yet). */
    data class Met(val name: String, val mailbox: String?, val iSaid: Boolean = false, val theySaid: Boolean = false, val dismissed: Boolean = false,
                   val verified: Boolean = false)
    data class Request(val peerKey: String, val name: String, val code: String, val mailbox: String?, val viaInternet: Boolean)

    private val _nearby = MutableStateFlow<List<Msg>>(emptyList())
    val nearby: StateFlow<List<Msg>> = _nearby.asStateFlow()
    private val _private = MutableStateFlow<Map<String, List<Msg>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<Msg>>> = _private.asStateFlow()
    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    private val _met = MutableStateFlow<Map<String, Met>>(emptyMap())
    val met: StateFlow<Map<String, Met>> = _met.asStateFlow()
    private val _unread = MutableStateFlow<Set<String>>(emptySet())
    val unread: StateFlow<Set<String>> = _unread.asStateFlow()
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** A private message arrived (name, text, key) — for notifications while Muninn is in the background. */
    val arrivals = MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 16)

    private val unsentKeeps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val held = ConcurrentHashMap<String, MutableList<Msg>>()
    private val recent = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val pool = Executors.newFixedThreadPool(8)
    /** Background work that can't take the app down: a failed send just stays unsent and is retried. */
    private fun bg(f: () -> Unit) = pool.execute { try { f() } catch (e: Exception) { android.util.Log.w("Muninn", "chat", e) } }
    private val retrier = Executors.newSingleThreadExecutor()
    private val store = File(context.filesDir, "chats.json")
    private val nearbyStore = File(context.filesDir, "nearby.json")
    private val metStore = File(context.filesDir, "met.json")
    private val photosDir = File(context.filesDir, "photos")

    init {
        load()
        // Devices trusted before contacts existed become contacts.
        id.trusted().forEach { (k, n) -> if (contacts.get(k) == null) contacts.put(Contacts.Contact(k, n, null, true)) }
        net.extraRoute = { method, path, body, _ -> if (method == "POST" && path == PATH) receive(body, "wifi") else null }
        close.onMessage = { _, o -> if (o.optString("t") == "msg") receive(o.toString(), "close") }
        // Someone said yes while out of range: say it again when they're back.
        bg { while (true) { Thread.sleep(30_000); try { resendKeeps() } catch (_: Exception) {} } }
        retrier.execute { while (true) { Thread.sleep(1_000); try { retryLoop() } catch (_: Exception) {} } }
        nostr.onEnvelope = { content ->
            try { receive(String(Base64.getDecoder().decode(content)), "internet"); true } catch (_: Exception) { true }
        }
        // Auto-delete: check every minute for messages past the chosen age.
        bg { while (true) { Thread.sleep(60_000); try { prune() } catch (_: Exception) {} } }
    }

    /** What close-by phones learn about this one. */
    fun hello(): JSONObject = JSONObject().put("name", id.username).put("chatKey", id.publicKey).put("mailbox", nostr.publicKey)
        .put("device", id.deviceName)

    fun myCode(): String = Contacts.code(id.username, id.publicKey, nostr.publicKey)

    fun codeWith(peerKey: String): String =
        ChatCrypto.pairingCode(ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(peerKey)))

    fun isContact(peerKey: String) = contacts.get(peerKey) != null

    fun nameOf(peerKey: String): String = contacts.get(peerKey)?.name ?: _met.value[peerKey]?.name
        ?: close.list.value.firstOrNull { it.chatKey == peerKey }?.name
        ?: net.devices.value.firstOrNull { it.chatKey == peerKey }?.let { it.chatName ?: it.alias } ?: "Someone"

    /** Opening a chat with someone close by: no asking, just start. */
    fun meet(peerKey: String, name: String) {
        if (isContact(peerKey) || _met.value.containsKey(peerKey)) return
        putMet(peerKey, Met(name, close.list.value.firstOrNull { it.chatKey == peerKey }?.mailbox))
    }

    /** "Keep in touch?" should show: 10 messages in, or they've gone out of range. */
    fun shouldAskKeep(peerKey: String): Boolean {
        val m = _met.value[peerKey] ?: return false
        if (isContact(peerKey) || m.dismissed || m.iSaid) return false
        return m.theySaid || _private.value[peerKey].orEmpty().size >= KEEP_AFTER || route(peerKey).isEmpty()
    }

    fun keepInTouch(peerKey: String) {
        val m = _met.value[peerKey] ?: return
        putMet(peerKey, m.copy(iSaid = true, dismissed = false))
        bg {
            val body = envelope("keep", peerKey, "yes", UUID.randomUUID().toString())
            if (!deliver(peerKey, body, m.mailbox)) unsentKeeps.add(peerKey) else return@execute
            notices.tryEmit("Couldn't reach ${m.name} yet. Muninn will say yes again when you're close.")
        }
        if (m.theySaid) becomeContacts(peerKey)
        else notices.tryEmit("Asked ${m.name}. Once they say yes too, you can message from anywhere.")
    }

    /** Both screens showed the same code. */
    fun verify(peerKey: String, name: String) {
        contacts.get(peerKey)?.let { contacts.put(it.copy(verified = true)); return }
        putMet(peerKey, (_met.value[peerKey] ?: Met(name, null)).copy(verified = true))
    }

    fun isVerified(peerKey: String) = contacts.get(peerKey)?.verified == true || _met.value[peerKey]?.verified == true

    fun notNow(peerKey: String) { _met.value[peerKey]?.let { putMet(peerKey, it.copy(dismissed = true)) } }

    /** Nothing from them arrives, here or in the Nearby room. Their chat is hidden until unblocked. */
    fun block(peerKey: String, on: Boolean = true) {
        _requests.value = _requests.value.filterNot { it.peerKey == peerKey }
        held.remove(peerKey)
        mute(peerKey, on)
        _blocked.value = id.muted()
    }

    private val _blocked = MutableStateFlow(id.muted())
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    /** Removes a contact (and anyone met close by); the chat stays unless deleted too. */
    fun removeContact(peerKey: String) {
        contacts.remove(peerKey)
        _met.value = _met.value - peerKey; saveMet()
    }

    /** Deletes one message on this phone. */
    fun deleteMessage(peerKey: String, msgId: String) {
        _private.value[peerKey]?.firstOrNull { it.id == msgId }?.photo?.takeIf { it.isNotEmpty() }?.let { File(photosDir, it).delete() }
        val map = _private.value.toMutableMap()
        map[peerKey] = map[peerKey].orEmpty().filterNot { it.id == msgId }
        _private.value = map
        save()
    }

    fun deleteNearbyMessage(msgId: String) {
        _nearby.value = _nearby.value.filterNot { it.id == msgId }
        if (id.keepNearbyHistory) saveNearby()
    }

    private fun becomeContacts(peerKey: String) {
        val m = _met.value[peerKey] ?: return
        contacts.put(Contacts.Contact(peerKey, m.name, m.mailbox, verified = m.verified))
        _met.value = _met.value - peerKey; saveMet()
        notices.tryEmit("You and ${m.name} are in touch. Message each other from anywhere.")
    }

    private fun putMet(k: String, m: Met) { _met.value = _met.value + (k to m); saveMet() }

    /** How a contact can be reached right now: "close", "wifi", "internet" or "". */
    fun route(peerKey: String): String = when {
        close.endpointFor(peerKey) != null -> "close"
        net.devices.value.any { it.chatKey == peerKey } -> "wifi"
        contacts.get(peerKey)?.mailbox != null -> "internet"
        else -> ""
    }

    // ------------------------------------------------------------------ receiving

    private fun receive(body: String, via: String): Pair<Int, String> {
        val o = try { JSONObject(body) } catch (e: Exception) { return 400 to "{}" }
        val fromKey = o.optString("fromKey")
        val name = o.optString("fromName").take(40).ifEmpty { "Someone" }
        if (fromKey.isEmpty() || fromKey == id.publicKey) return 400 to "{}"
        val now = System.currentTimeMillis()
        if (fromKey in id.muted()) return 200 to "{}"   // blocked: dropped quietly
        val room = o.optString("room")
        if (room == "photo") return receivePiece(o, fromKey, name, via)
        // No more than 5 messages in 10 seconds from anyone: nobody can flood a room.
        val q = recent.getOrPut(fromKey) { ArrayDeque() }
        if (room != "receipt") synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
            if (q.size >= 5) return 429 to "{}"
            q.addLast(now)
        }
        val key = try { ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(fromKey)) } catch (e: Exception) { return 400 to "{}" }
        val text = ChatCrypto.open(key, ChatCrypto.Sealed(o.optString("nonce"), o.optString("data")))?.take(MAX) ?: return 403 to "{}"
        val msgId = o.optString("id").ifEmpty { UUID.randomUUID().toString() }
        val msg = Msg(msgId, fromKey, name, text, false, now, via = via)
        val mailbox = o.optString("mailbox").takeIf { it.length == 64 }
        when (room) {
            "nearby" -> {
                if (!id.nearbyChat || via == "internet") return 403 to "{}"
                if (fromKey !in id.muted() && _nearby.value.none { it.id == msgId }) addNearby(msg)
            }
            "private" -> {
                val c = contacts.get(fromKey)
                if (c != null) {
                    if (mailbox != null && c.mailbox != mailbox) contacts.put(c.copy(mailbox = mailbox))
                    if (_private.value[fromKey].orEmpty().none { it.id == msgId }) {
                        addPrivate(fromKey, msg)
                        _unread.value = _unread.value + fromKey
                        arrivals.tryEmit(Triple(c.name, text, fromKey))
                    }
                    receipt(fromKey, listOf(msgId), "delivered", mailbox)
                } else if (fromKey in id.muted()) {
                    return 200 to "{}"
                } else if (via != "internet" || _met.value.containsKey(fromKey)) {
                    // Close by or on the same Wi-Fi (or someone met that way): no asking first.
                    val m = _met.value[fromKey]
                    putMet(fromKey, (m ?: Met(name, mailbox)).copy(name = name, mailbox = mailbox ?: m?.mailbox))
                    if (_private.value[fromKey].orEmpty().none { it.id == msgId }) {
                        addPrivate(fromKey, msg)
                        _unread.value = _unread.value + fromKey
                        arrivals.tryEmit(Triple(name, text, fromKey))
                    }
                    receipt(fromKey, listOf(msgId), "delivered", mailbox)
                } else {
                    // A code shared some other way: they already have this phone's code, so ask.
                    held.getOrPut(fromKey) { mutableListOf() }.add(msg)
                    if (_requests.value.none { it.peerKey == fromKey }) {
                        _requests.value = _requests.value + Request(fromKey, name, ChatCrypto.pairingCode(key), mailbox, via == "internet")
                        notices.tryEmit("$name wants to chat")
                        arrivals.tryEmit(Triple(name, "Wants to chat with you", fromKey))
                    }
                }
            }
            "receipt" -> {
                if (!isContact(fromKey) && !_met.value.containsKey(fromKey)) return 403 to "{}"
                val r = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                val ids = r.optJSONArray("ids")?.let { a -> (0 until minOf(a.length(), 200)).map { a.optString(it) }.toSet() } ?: return 400 to "{}"
                val st = r.optString("s")
                if (st != "delivered" && st != "read") return 400 to "{}"
                val map = _private.value.toMutableMap()
                map[fromKey] = map[fromKey].orEmpty().map { m -> if (m.mine && m.id in ids && rank(st) > rank(m.state)) m.copy(state = st, failed = false) else m }
                _private.value = map
                save()
            }
            "keep" -> {
                if (isContact(fromKey)) return 200 to "{}"
                val m = _met.value[fromKey] ?: if (via != "internet") Met(name, mailbox) else return 403 to "{}"
                if (text != "yes" || m.theySaid) return 200 to "{}"
                putMet(fromKey, m.copy(theySaid = true, mailbox = mailbox ?: m.mailbox))
                if (m.iSaid) becomeContacts(fromKey)
                else { notices.tryEmit("$name wants to keep in touch"); arrivals.tryEmit(Triple(name, "Wants to keep in touch", fromKey)) }
            }
            else -> return 400 to "{}"
        }
        return 200 to "{}"
    }

    fun acceptRequest(r: Request) {
        contacts.put(Contacts.Contact(r.peerKey, r.name, r.mailbox, verified = !r.viaInternet))
        _requests.value = _requests.value.filterNot { it.peerKey == r.peerKey }
        held.remove(r.peerKey)?.let { list -> list.forEach { addPrivate(r.peerKey, it) }; receipt(r.peerKey, list.map { it.id }, "delivered", r.mailbox) }
    }

    // ------------------------------------------------------------------ photos
    // A photo is shrunk on the sending phone (Photos.kt), cut into pieces of 16 KB, and each piece is
    // sealed and sent like a message (room "photo"). The other phone puts them back together and
    // answers "delivered" once it has them all. Pieces fit every route: Nearby's 32 KB limit and relays'.

    private class Incoming(val from: String, val parts: Array<ByteArray?>, val started: Long)
    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val pieceTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun photo(photoId: String): ByteArray? = vault.readBytes(File(photosDir, photoId))

    fun sendPhoto(peerKey: String, jpeg: ByteArray) {
        val name = nameOf(peerKey)
        if (!isContact(peerKey)) meet(peerKey, name)
        val r = route(peerKey)
        val msgId = UUID.randomUUID().toString()
        vault.writeBytes(File(photosDir, msgId), jpeg)
        val mine = Msg(msgId, peerKey, id.username, "", true, System.currentTimeMillis(), via = r, state = "sending", photo = msgId)
        addPrivate(peerKey, mine)
        if (r.isEmpty() && backupMailbox(mine) == null) {
            replace(peerKey, mine.copy(failed = true))
            notices.tryEmit("$name is out of range. Say yes to Keep in touch to send from anywhere.")
            return
        }
        bg { attempt(mine) }
    }

    private fun pieces(m: Msg): List<JSONObject>? {
        val bytes = photo(m.photo) ?: return null
        val key = ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(m.peerKey))
        val parts = Photos.split(bytes)
        return parts.mapIndexed { i, part ->
            val s = ChatCrypto.sealBytes(key, part)
            JSONObject().put("room", "photo").put("fromKey", id.publicKey).put("fromName", id.username).put("fromId", id.fingerprint)
                .put("mailbox", nostr.publicKey).put("id", m.id).put("i", i).put("n", parts.size).put("nonce", s.nonce).put("data", s.data)
        }
    }

    private fun receivePiece(o: JSONObject, fromKey: String, name: String, via: String): Pair<Int, String> {
        val contact = contacts.get(fromKey)
        if (contact == null && via == "internet" && !_met.value.containsKey(fromKey)) return 403 to "{}"
        val msgId = o.optString("id")
        val n = o.optInt("n"); val i = o.optInt("i", -1)
        if (msgId.isEmpty() || n < 1 || n > Photos.MAX_PIECES || i !in 0 until n) return 400 to "{}"
        val mailbox = o.optString("mailbox").takeIf { it.length == 64 }
        // Already have it (a copy from another route, or a resend): just confirm again.
        if (_private.value[fromKey].orEmpty().any { it.id == msgId }) { receipt(fromKey, listOf(msgId), "delivered", mailbox); return 200 to "{}" }
        val now = System.currentTimeMillis()
        val q = pieceTimes.getOrPut(fromKey) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= 1000) return 429 to "{}"   // about 15 MB a minute at most
            q.addLast(now)
        }
        if (incoming.size > 20 && !incoming.containsKey(msgId)) return 429 to "{}"
        val key = try { ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(fromKey)) } catch (e: Exception) { return 400 to "{}" }
        val part = ChatCrypto.openBytes(key, ChatCrypto.Sealed(o.optString("nonce"), o.optString("data"))) ?: return 403 to "{}"
        val inc = incoming.getOrPut(msgId) { Incoming(fromKey, arrayOfNulls(n), now) }
        if (inc.from != fromKey || inc.parts.size != n) return 400 to "{}"
        synchronized(inc) {
            inc.parts[i] = part
            if (inc.parts.any { it == null }) return 200 to "{}"
            incoming.remove(msgId)
        }
        val jpeg = Photos.join(inc.parts.map { it!! }) ?: return 400 to "{}"
        vault.writeBytes(File(photosDir, msgId), jpeg)
        if (contact == null) {
            val m = _met.value[fromKey]
            putMet(fromKey, (m ?: Met(name, mailbox)).copy(name = name, mailbox = mailbox ?: m?.mailbox))
        } else if (mailbox != null && contact.mailbox != mailbox) contacts.put(contact.copy(mailbox = mailbox))
        addPrivate(fromKey, Msg(msgId, fromKey, name, "", false, System.currentTimeMillis(), via = via, photo = msgId))
        _unread.value = _unread.value + fromKey
        arrivals.tryEmit(Triple(contact?.name ?: name, "Photo", fromKey))
        receipt(fromKey, listOf(msgId), "delivered", mailbox)
        return 200 to "{}"
    }

    fun declineRequest(r: Request) {
        _requests.value = _requests.value.filterNot { it.peerKey == r.peerKey }
        held.remove(r.peerKey)
    }

    fun addContact(c: Contacts.Contact) = contacts.put(c)

    /** Someone close by or on the Wi-Fi, after both screens showed the same code. */
    fun addNearbyPerson(chatKey: String, name: String) {
        val mailbox = close.list.value.firstOrNull { it.chatKey == chatKey }?.mailbox
        contacts.put(Contacts.Contact(chatKey, name, mailbox, verified = true))
    }

    /** The chat is on screen: clear the dot and, if allowed, tell them what was read. */
    fun markRead(peerKey: String) {
        _unread.value = _unread.value - peerKey
        if (!id.readReceipts) return
        val fresh = _private.value[peerKey].orEmpty().filter { !it.mine && it.state != "read" }
        if (fresh.isEmpty()) return
        val map = _private.value.toMutableMap()
        map[peerKey] = map[peerKey].orEmpty().map { if (!it.mine && it.state != "read") it.copy(state = "read") else it }
        _private.value = map
        save()
        receipt(peerKey, fresh.map { it.id }, "read", null)
    }

    private fun rank(s: String) = when (s) { "sending" -> 1; "sent" -> 2; "delivered" -> 3; "read" -> 4; else -> 0 }

    /** Tells the sender their messages arrived (always) or were read (only with read receipts on). Sealed like any message. */
    private fun receipt(peerKey: String, ids: List<String>, state: String, mailbox: String?) {
        if (ids.isEmpty()) return
        bg {
            val body = JSONObject().put("ids", JSONArray(ids.takeLast(200))).put("s", state).toString()
            deliver(peerKey, envelope("receipt", peerKey, body, UUID.randomUUID().toString()),
                mailbox ?: contacts.get(peerKey)?.mailbox ?: _met.value[peerKey]?.mailbox)
        }
    }

    // ------------------------------------------------------------------ sending

    fun sendNearby(text: String) {
        val t = text.trim().take(MAX)
        if (t.isEmpty() || !id.nearbyChat) return
        val mine = Msg(UUID.randomUUID().toString(), id.publicKey, id.username, t, true, System.currentTimeMillis())
        addNearby(mine)
        val muted = id.muted()
        bg {
            var reached = 0
            val done = mutableSetOf<String>()
            for (p in close.list.value) {
                val k = p.chatKey ?: continue
                if (!p.connected || k in muted || !done.add(k)) continue
                close.send(p.endpoint, envelope("nearby", k, t, mine.id).put("t", "msg")); reached++
            }
            for (d in net.devices.value) {
                val k = d.chatKey ?: continue
                if (d.chatName == null || k in muted || !done.add(k)) continue
                if (net.post(d, PATH, envelope("nearby", k, t, mine.id).toString()) == 200) reached++
            }
            if (reached == 0) notices.tryEmit("Nobody nearby got that yet. They need Muninn or Huginn open close by or on the same Wi-Fi.")
        }
    }

    fun sendPrivate(peerKey: String, text: String) {
        val t = text.trim().take(MAX)
        if (t.isEmpty()) return
        val name = nameOf(peerKey)
        if (!isContact(peerKey)) meet(peerKey, name)
        val r = route(peerKey)
        val mine = Msg(UUID.randomUUID().toString(), peerKey, id.username, t, true, System.currentTimeMillis(), via = r, state = "sending")
        addPrivate(peerKey, mine)
        if (r.isEmpty() && backupMailbox(mine) == null) {
            replace(peerKey, mine.copy(failed = true))
            notices.tryEmit(if (isContact(peerKey)) "$name can't be reached: they're not nearby and have no internet mailbox yet."
                else "$name is out of range. Say yes to Keep in touch to message from anywhere.")
            return
        }
        bg { attempt(mine) }
    }

    // ------------------------------------------------------------------ getting it there
    // Every message goes out on every route at once (close by, Wi-Fi and the internet); the other
    // phone keeps the first copy and drops the rest. Until a "delivered" receipt comes back it is
    // sent again after 5 s, then less often, for a day. So someone walking out of range still gets it.

    private class Tries(var count: Int, var next: Long)
    private val tries = ConcurrentHashMap<String, Tries>()

    /** Their internet mailbox, if this message may use it: contacts always; someone met close by
     *  only for messages sent while they were in range (a backup as they walk away). */
    private fun backupMailbox(m: Msg): String? =
        contacts.get(m.peerKey)?.mailbox ?: _met.value[m.peerKey]?.mailbox?.takeIf { m.via == "close" || m.via == "wifi" }

    private fun attempt(m: Msg) {
        val t = tries.getOrPut(m.id) { Tries(0, 0) }
        t.count++
        t.next = System.currentTimeMillis() + RETRY.getOrElse(t.count - 1) { RETRY.last() }
        if (m.photo.isNotEmpty()) { attemptPhoto(m, t); return }
        val body = envelope("private", m.peerKey, m.text, m.id).toString()
        var any = false
        close.endpointFor(m.peerKey)?.let { close.send(it, JSONObject(body).put("t", "msg")); any = true; markSent(m, "close") }
        net.devices.value.firstOrNull { it.chatKey == m.peerKey }?.let { d ->
            any = true
            bg { if (net.post(d, PATH, body) == 200) update(m.peerKey, m.id) { if (rank(it.state) < 3) it.copy(state = "delivered", via = "wifi", failed = false) else it } }
        }
        backupMailbox(m)?.let { nostr.publish(it, Base64.getEncoder().encodeToString(body.toByteArray())); any = true; markSent(m, "internet") }
        if (!any && t.count == 1) update(m.peerKey, m.id) { it.copy(failed = true) }
    }

    /** Like a message, but in pieces. The internet only carries it if the close-by and Wi-Fi routes
     *  haven't delivered it within 5 s (so a big photo isn't sent twice for nothing). */
    private fun attemptPhoto(m: Msg, t: Tries) {
        val parts = pieces(m) ?: return
        // Don't start again while a big photo is still on its way (about 200 ms a piece close by).
        t.next = maxOf(t.next, System.currentTimeMillis() + parts.size * 200L)
        var any = false
        close.endpointFor(m.peerKey)?.let { ep -> parts.forEach { close.send(ep, JSONObject(it.toString()).put("t", "msg")) }; any = true; markSent(m, "close") }
        net.devices.value.firstOrNull { it.chatKey == m.peerKey }?.let { d ->
            any = true
            bg { if (parts.all { net.post(d, PATH, it.toString()) == 200 }) update(m.peerKey, m.id) { if (rank(it.state) < 2) it.copy(state = "sent", via = "wifi", failed = false) else it } }
        }
        val mailbox = backupMailbox(m)
        if (mailbox != null && (!any || t.count >= 2) && parts.size <= Photos.INTERNET_PIECES) {
            parts.forEach { nostr.publish(mailbox, Base64.getEncoder().encodeToString(it.toString().toByteArray())) }
            any = true; markSent(m, "internet")
        }
        if (!any && t.count == 1) update(m.peerKey, m.id) { it.copy(failed = true) }
    }

    private fun markSent(m: Msg, how: String) = update(m.peerKey, m.id) { if (rank(it.state) < 2) it.copy(state = "sent", via = if (it.via.isEmpty()) how else it.via, failed = false) else it }

    /** Runs every second: anything of mine not yet delivered (and under a day old) is tried again. */
    private fun retryLoop() {
        val now = System.currentTimeMillis()
        incoming.entries.removeIf { now - it.value.started > 15 * 60_000 }   // half a photo that never finished
        for ((_, list) in _private.value) for (m in list) {
            // State "" is from before receipts existed: those were sent already, leave them be.
            if (!m.mine || m.state.isEmpty() || rank(m.state) >= 3 || now - m.time > DAY) { tries.remove(m.id); continue }
            val t = tries[m.id]
            if (t == null) { tries[m.id] = Tries(1, now + RETRY[0]); continue }   // after a restart: wait one round
            if (now >= t.next) { t.next = now + RETRY[0]; bg { attempt(m) } }
        }
    }

    /** Tap a message that didn't send: try again now. */
    fun retry(peerKey: String, msgId: String) {
        val m = _private.value[peerKey]?.firstOrNull { it.id == msgId } ?: return
        tries.remove(m.id)
        update(peerKey, msgId) { it.copy(failed = false, state = "sending", time = System.currentTimeMillis()) }
        bg { attempt(m) }
    }

    /** Best route first. The internet needs a mailbox: contacts, or a "keep in touch" answer. */
    private fun deliver(peerKey: String, body: JSONObject, mailbox: String?): Boolean = deliverVia(peerKey, body, mailbox).isNotEmpty()

    /** Returns the route that took it ("close", "wifi", "internet"), or "" if none could. */
    private fun deliverVia(peerKey: String, body: JSONObject, mailbox: String?): String {
        close.endpointFor(peerKey)?.let { close.send(it, JSONObject(body.toString()).put("t", "msg")); return "close" }
        net.devices.value.firstOrNull { it.chatKey == peerKey }?.let { if (net.post(it, PATH, body.toString()) == 200) return "wifi" }
        if (mailbox != null) { nostr.publish(mailbox, Base64.getEncoder().encodeToString(body.toString().toByteArray())); return "internet" }
        return ""
    }

    @Synchronized private fun update(peer: String, msgId: String, f: (Msg) -> Msg) {
        val map = _private.value.toMutableMap()
        map[peer] = map[peer].orEmpty().map { if (it.id == msgId) f(it) else it }
        _private.value = map
        save()
    }

    private fun envelope(room: String, theirKey: String, text: String, msgId: String): JSONObject {
        val key = ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(theirKey))
        val s = ChatCrypto.seal(key, text)
        return JSONObject().put("room", room).put("fromKey", id.publicKey).put("fromName", id.username).put("fromId", id.fingerprint)
            .put("mailbox", nostr.publicKey).put("id", msgId).put("nonce", s.nonce).put("data", s.data)
    }

    // ------------------------------------------------------------------ keeping

    fun mute(peerKey: String, on: Boolean) {
        id.setMuted(peerKey, on)
        if (on) _nearby.value = _nearby.value.filterNot { it.peerKey == peerKey && !it.mine }
    }

    fun clearNearby() { _nearby.value = emptyList(); nearbyStore.delete() }

    fun deleteConversation(peerKey: String) {
        _private.value[peerKey].orEmpty().forEach { if (it.photo.isNotEmpty()) File(photosDir, it.photo).delete() }
        _private.value = _private.value - peerKey; save()
    }

    /** Removes messages older than the person's "Delete messages after" choice. */
    fun prune() {
        val age = id.deleteAfter
        if (age <= 0) return
        val cutoff = System.currentTimeMillis() - age
        val n = _nearby.value.filter { it.time >= cutoff }
        if (n.size != _nearby.value.size) { _nearby.value = n; if (id.keepNearbyHistory) saveNearby() }
        _private.value.values.flatten().filter { it.time < cutoff && it.photo.isNotEmpty() }.forEach { File(photosDir, it.photo).delete() }
        val map = _private.value.mapValues { (_, v) -> v.filter { it.time >= cutoff } }
        if (map.values.sumOf { it.size } != _private.value.values.sumOf { it.size }) { _private.value = map; save() }
    }

    /** Erases every conversation on this phone. */
    fun deleteAll() {
        _private.value = emptyMap(); _nearby.value = emptyList()
        photosDir.deleteRecursively()
        save(); nearbyStore.delete()
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
        list.forEach { put(JSONObject().put("id", it.id).put("peer", it.peerKey).put("name", it.name).put("text", it.text).put("mine", it.mine)
            .put("time", it.time).put("failed", it.failed).put("via", it.via).put("state", it.state).put("photo", it.photo)) }
    }
    private fun fromJson(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map {
        Msg(it.getString("id"), it.getString("peer"), it.getString("name"), it.getString("text"), it.getBoolean("mine"), it.getLong("time"),
            it.optBoolean("failed"), it.optString("via"), it.optString("state"), it.optString("photo"))
    }

    @Synchronized private fun save() {
        val o = JSONObject(); _private.value.forEach { (k, v) -> o.put(k, toJson(v)) }
        vault.write(store, o.toString())
    }
    private fun resendKeeps() {
        unsentKeeps.filter { route(it).isNotEmpty() }.forEach { k ->
            if (deliver(k, envelope("keep", k, "yes", UUID.randomUUID().toString()), null)) unsentKeeps.remove(k)
        }
    }

    private fun saveMet() {
        val o = JSONObject()
        _met.value.forEach { (k, m) -> o.put(k, JSONObject().put("n", m.name).put("p", m.mailbox ?: "").put("i", m.iSaid).put("t", m.theySaid).put("d", m.dismissed).put("v", m.verified)) }
        try { vault.write(metStore, o.toString()) } catch (_: Exception) {}
    }

    private fun saveNearby() = vault.write(nearbyStore, toJson(_nearby.value).toString())

    private fun load() {
        try {
            vault.read(store)?.let { text ->
                val o = JSONObject(text)
                _private.value = o.keys().asSequence().associateWith { fromJson(o.getJSONArray(it)) }
            }
            vault.read(metStore)?.let { text ->
                val o = JSONObject(text)
                _met.value = o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let {
                    Met(it.optString("n"), it.optString("p").ifEmpty { null }, it.optBoolean("i"), it.optBoolean("t"), it.optBoolean("d"), it.optBoolean("v")) } }
            }
            if (id.keepNearbyHistory) vault.read(nearbyStore)?.let { _nearby.value = fromJson(JSONArray(it)) }
            prune()
        } catch (_: Exception) {}
    }

    companion object {
        const val PATH = "/api/huginn/v1/message"
        const val MAX = 2000
        const val KEEP_AFTER = 10
        const val DAY = 86_400_000L
        /** Wait before each new try: 5 s, 10 s, 20 s, 1 min, then every 5 min. */
        val RETRY = longArrayOf(5_000, 10_000, 20_000, 60_000, 300_000)
    }
}
