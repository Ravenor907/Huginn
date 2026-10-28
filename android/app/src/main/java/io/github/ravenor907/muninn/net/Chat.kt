package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * One message format everywhere: { room, fromKey, fromName, mailbox, cap, caps, nonce, data } (v1), or with
 * "rh" in place of "nonce" when it went through the message ratchet (ChatWire has the details).
 * Groups (protocol 0.6, section 3) live in [groups]: each group message is sealed to every member
 * separately and goes out the same ways. Over the internet everything goes through the
 * Nostr relays, which only ever see a sealed envelope for a mailbox.
 */
class Chat(private val context: Context, private val net: LocalSend, private val close: Close, private val ble: Ble, private val nostr: Nostr, val contacts: Contacts,
           private val vault: Vault) : io.github.ravenor907.muninn.tv.TvBridge, io.github.ravenor907.muninn.net.call.CallSignal {
    private val id get() = net.identity
    /**
     * A call signal (offer, answer, hang up...): sealed like a private message, sent now on every route at
     * once (docs/protocol-0.6.md 5.1, as Huginn does), never kept in the Outbox. Sealed once: the other side
     * keeps the first copy and drops the rest.
     */
    override fun send(contactKey: String, json: String) {
        bg {
            val body = envelope("call", contactKey, json, UUID.randomUUID().toString()).toString()
            close.endpointFor(contactKey)?.let { close.send(it, JSONObject(body).put("t", "msg")) }
            ble.send(contactKey, body)
            net.devices.value.firstOrNull { it.chatKey == contactKey }?.let { net.post(it, PATH, body) }
            internet(contactKey, listOf(body), contacts.get(contactKey)?.mailbox)
        }
    }
    @Volatile override var tvHandler: io.github.ravenor907.muninn.tv.TvHandler? = null
    override fun sendTvRoom(peerKey: String, plain: String) {
        bg { deliver(peerKey, envelope("tvconfig", peerKey, plain, UUID.randomUUID().toString()), contacts.get(peerKey)?.mailbox) }
    }

    data class Msg(val id: String, val peerKey: String, val name: String, val text: String, val mine: Boolean, val time: Long,
                   val failed: Boolean = false, val via: String = "",
                   /** Mine: "sending", "sent", "delivered", "read". Theirs: "read" once a read receipt went back. */
                   val state: String = "",
                   /** A photo's id (stored encrypted in photos/); text is empty then. */
                   val photo: String = "",
                   /** Unsent: who took it back ("You" on the sender's phone, their name on the other). */
                   val unsent: String = "",
                   /** A voice message: its length in ms (the sound itself is stored like a photo, under [photo]). */
                   val voice: Int = 0,
                   /** A reply: the id of the message it quotes (cap m1). */
                   val re: String = "",
                   /** Reactions (cap m1): who to their latest one; an empty one is a removed reaction. */
                   val reactions: Map<String, Rich.Reaction> = emptyMap(),
                   /** Edited (cap m1): when the text shown was written (the editor's clock), 0 if never edited. */
                   val edited: Long = 0)
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
    /** A private message arrived (name, text, key): for notifications while Muninn is in the background. */
    val arrivals = MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 16)
    /** Someone unsent a message in this chat (key): clear its notification. */
    val recalls = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Cap m1: who is typing to me, until when (ms). */
    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    val typing: StateFlow<Map<String, Long>> = _typing.asStateFlow()
    /** Cap m1: whose app said it's open, until when (ms). Empty while my own Active status is off. */
    private val _active = MutableStateFlow<Map<String, Long>>(emptyMap())
    val active: StateFlow<Map<String, Long>> = _active.asStateFlow()
    /** Cap m1: reactions and edits on their way (envelope id to who and when), until a receipt names them. */
    private val mxOut = ConcurrentHashMap<String, Pair<String, Long>>()
    private val mxStore = File(context.filesDir, "mxout.json")
    private val liveTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val typingSent = ConcurrentHashMap<String, Long>()
    @Volatile private var foreground = false
    @Volatile private var activeSent = 0L

    /** "Yes" to Keep in touch that didn't get there yet, by person: the same sealed envelope goes again. */
    private val unsentKeeps = ConcurrentHashMap<String, String>()
    /** Messages from someone asking to chat, by id (resends don't pile up), until accepted. */
    private val held = ConcurrentHashMap<String, MutableMap<String, Msg>>()
    /** One lock for every change to the chats, so copies arriving at once on different routes can't race. */
    private val lock = Any()
    /** Ids deleted on this phone in the last two days: a late copy won't bring them back. */
    private val deleted = ConcurrentHashMap<String, Long>()
    private val deletedStore = File(context.filesDir, "deleted.json")
    private val recent = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val tries = ConcurrentHashMap<String, Tries>()
    private val pool = Executors.newFixedThreadPool(8)
    /** Background work that can't take the app down: a failed send just stays unsent and is retried. */
    private fun bg(f: () -> Unit) = pool.execute { try { f() } catch (e: Exception) { android.util.Log.w("Muninn", "chat", e) } }
    private val retrier = Executors.newSingleThreadExecutor()
    private val store = File(context.filesDir, "chats.json")
    private val nearbyStore = File(context.filesDir, "nearby.json")
    private val metStore = File(context.filesDir, "met.json")
    private val photosDir = File(context.filesDir, "photos")
    /** Invites handed out in my codes (token to expiry ms): declared before init, which loads them. */
    private val invites = HashMap<String, Long>()
    private val invitesStore = File(context.filesDir, "invites.json")
    /** Sealed envelopes per message id, so every retry and route sends the very same bytes. */
    private val outbox = Outbox(File(context.filesDir, "outbox"), { vault.read(it) }, { f, t -> vault.write(f, t) })
    /** Message ratchet state per contact. Erase all wipes it with everything else. */
    private val ratchets = Ratchets(File(context.filesDir, "ratchet"), { vault.read(it) }, { f, t -> vault.write(f, t) })
    private val seen = Seen()
    /** Caps probes sent (key to when): at most one per contact an hour. */
    private val probed = ConcurrentHashMap<String, Long>()
    private val groupDir = File(context.filesDir, "groups")
    /** Group chats: state, members, channels and messages. Chat is how its envelopes travel. */
    val groups: Groups = Groups(id.keys, id.publicKey, GroupHost(), { vault.read(File(groupDir, it)) }, { f, t -> groupDir.mkdirs(); vault.write(File(groupDir, f), t) })
    /** Contact folders: local only. */
    val folders = Folders({ vault.read(File(context.filesDir, "folders.json")) }, { vault.write(File(context.filesDir, "folders.json"), it) })
    /** Contacts removed from the list, kept 30 days so they can come back: local only. */
    val trash = Trash({ vault.read(File(context.filesDir, "trash.json")) }, { vault.write(File(context.filesDir, "trash.json"), it) })
    private val groupTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val callTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()

    init {
        load()
        // Devices trusted before contacts existed become contacts.
        // Never one that would stand in for a contact already here (that would be a key change, not an old friend).
        id.trusted().forEach { (k, n) ->
            val c = Contact(k, n, null, true)
            if (contacts.get(k) == null && ChatWire.keyChangeFor(contacts.all.value, c) == null) contacts.put(c)
        }
        outbox.sweep()
        net.extraRoute = { method, path, body, _ -> if (method == "POST" && path == PATH) receive(body, "wifi") else null }
        close.onMessage = { _, o -> if (o.optString("t") == "msg") receive(o.toString(), "close") }
        // Huginn close by, over Bluetooth LE (it can't use Nearby): the same envelopes.
        ble.onMessage = { _, json -> receive(json, "close") }
        // Someone said yes while out of range: say it again when they're back.
        bg { while (true) { Thread.sleep(30_000); try { resendKeeps() } catch (_: Exception) {} } }
        retrier.execute { while (true) { Thread.sleep(1_000); try { retryLoop() } catch (_: Exception) {} } }
        nostr.onEnvelope = { content ->
            try { unwrap(content)?.let { receive(it, "internet") }; true } catch (_: Exception) { true }
        }
        // Auto-delete: check every minute for messages past the chosen age, and for contacts 30 days in the trash.
        bg { emptyOldTrash() }
        bg { while (true) { Thread.sleep(60_000); try { prune(); outbox.sweep(); emptyOldTrash() } catch (_: Exception) {} } }
    }

    /**
     * What close-by phones learn about this one. Hidden from people nearby, no name goes out: the chat key and
     * mailbox still do, or contacts couldn't find this phone close by (so a stranger can still tell it's the
     * same phone as before, just not whose).
     */
    fun hello(): JSONObject = JSONObject().put("name", if (id.visibleNearby) id.username else Identity.HIDDEN_NAME)
        .put("chatKey", id.publicKey).put("mailbox", nostr.publicKey)
        .put("device", if (id.visibleNearby) id.deviceName else Identity.HIDDEN_NAME)

    /** A new code each time it's shown, with an invite good for an hour. */
    fun myCode(): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val until = System.currentTimeMillis() + INVITE_MS
        synchronized(invites) {
            invites[token] = until
            invites.entries.removeIf { it.value < System.currentTimeMillis() }
            saveInvites()
        }
        return Contacts.code(id.username, id.publicKey, nostr.publicKey, token, until)
    }

    private fun saveInvites() { try { vault.write(invitesStore, JSONObject(invites as Map<*, *>).toString()) } catch (_: Exception) {} }
    private fun inviteOk(token: String): Boolean = token.isNotEmpty() && synchronized(invites) { (invites[token] ?: 0L) > System.currentTimeMillis() }

    /** Over the internet the whole message is sealed to their chat key with a one-off key, so relays
     *  see only which inbox it's for: not who sent it, not their name. */
    private fun wrap(peerKey: String, body: String): String {
        val eph = ChatCrypto.newKeyPair()
        val key = ChatCrypto.sharedKey(eph.private, eph.public, ChatCrypto.publicFrom(peerKey))
        val s = ChatCrypto.sealBytes(key, body.toByteArray())
        val o = JSONObject().put("e", ChatCrypto.b64(eph.public.encoded)).put("n", s.nonce).put("d", s.data)
        return Base64.getEncoder().encodeToString(o.toString().toByteArray())
    }

    private fun unwrap(content: String): String? {
        val raw = String(Base64.getDecoder().decode(content))
        val o = JSONObject(raw)
        if (!o.has("e")) return raw   // from a version before sealing
        val key = ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(o.getString("e")))
        return ChatCrypto.openBytes(key, ChatCrypto.Sealed(o.getString("n"), o.getString("d")))?.let { String(it) }
    }

    fun codeWith(peerKey: String): String =
        ChatCrypto.pairingCode(ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(peerKey)))

    /** A real contact (a group peer isn't one: they're only reachable for group things). */
    fun isContact(peerKey: String) = contacts.contact(peerKey) != null
    private fun contact(peerKey: String) = contacts.contact(peerKey)

    /** Whether they can be put in a group: their app said "g1". Unknown yet: ask (at most once an hour). */
    fun canGroup(peerKey: String): Boolean {
        val c = contacts.get(peerKey) ?: return false
        if ("g1" in c.caps) return true
        probeCaps(peerKey)
        return false
    }

    /** A receipt with no ids that asks for caps (1.2): a 0.6 app answers, a 0.5 app does nothing. */
    fun probeCaps(peerKey: String) {
        val c = contacts.get(peerKey) ?: return
        if ("g1" in c.caps) return
        val now = System.currentTimeMillis()
        if (now - (probed[peerKey] ?: 0L) < 3_600_000L) return
        probed[peerKey] = now
        bg {
            val body = JSONObject().put("ids", JSONArray()).put("s", "delivered").put("q", "caps").toString()
            deliver(peerKey, envelope("receipt", peerKey, body, UUID.randomUUID().toString()), c.mailbox)
        }
    }

    /** The name this phone shows for them: a contact's nickname if one was set, else the name they chose. */
    fun nameOf(peerKey: String): String = contacts.get(peerKey)?.shown ?: _met.value[peerKey]?.name
        ?: close.list.value.firstOrNull { it.chatKey == peerKey }?.name
        ?: ble.peers.value.firstOrNull { it.chatKey == peerKey }?.name
        ?: net.devices.value.firstOrNull { it.chatKey == peerKey }?.let { it.chatName ?: it.alias } ?: "Someone"

    /** Opening a chat with someone close by: no asking, just start. */
    fun meet(peerKey: String, name: String) {
        if (isContact(peerKey) || _met.value.containsKey(peerKey)) return
        putMet(peerKey, Met(name, close.list.value.firstOrNull { it.chatKey == peerKey }?.mailbox
            ?: ble.peers.value.firstOrNull { it.chatKey == peerKey }?.mailbox))
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
            if (deliver(peerKey, body, m.mailbox)) return@bg
            unsentKeeps[peerKey] = body.toString()
            notices.tryEmit("Couldn't reach ${m.name} yet. Muninn will say yes again when you're close.")
        }
        if (m.theySaid) becomeContacts(peerKey)
        else notices.tryEmit("Asked ${m.name}. Once they say yes too, you can message from anywhere.")
    }

    /** Both screens showed the same code. */
    fun verify(peerKey: String, name: String) {
        contacts.get(peerKey)?.let { contacts.set(it.copy(verified = true, keyChanged = false)); return }
        putMet(peerKey, (_met.value[peerKey] ?: Met(name, null)).copy(verified = true))
    }

    fun isVerified(peerKey: String) = contacts.get(peerKey)?.verified == true || _met.value[peerKey]?.verified == true

    /** A nickname for a contact, on this phone only (never sent). Empty goes back to their own name. */
    fun rename(peerKey: String, nickname: String) = contacts.rename(peerKey, nickname)

    fun notNow(peerKey: String) { _met.value[peerKey]?.let { putMet(peerKey, it.copy(dismissed = true)) } }

    /** Nothing from them arrives, here or in the Nearby room. Their chat is hidden until unblocked. */
    fun block(peerKey: String, on: Boolean = true) {
        _requests.update { l -> l.filterNot { it.peerKey == peerKey } }
        held.remove(peerKey)
        mute(peerKey, on)
        _blocked.value = id.muted()
    }

    private val _blocked = MutableStateFlow(id.muted())
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    /** Removes a contact (and anyone met close by) for good; the chat stays unless deleted too. */
    fun removeContact(peerKey: String) {
        val was = contacts.get(peerKey)
        contacts.remove(peerKey)
        folders.forget(peerKey)
        trash.drop(peerKey)
        // Still in a group together: they stay reachable for the group, as a group peer.
        if (was != null && groups.sharesGroup(peerKey)) contacts.peer(peerKey, was.name, was.mailbox ?: "")
        synchronized(ratchets.lock(peerKey)) { ratchets.drop(peerKey) }
        synchronized(lock) { _met.update { it - peerKey }; saveMet() }
    }

    /**
     * Removing a contact puts them in the trash: hidden with their chat, and nothing from them gets through
     * (dropped quietly, like blocked). Restore brings everything back; after 30 days they go for good.
     */
    fun trashContact(peerKey: String) {
        trash.put(peerKey, System.currentTimeMillis())
        _unread.update { it - peerKey }
        _typing.update { it - peerKey }
        _active.update { it - peerKey }
    }

    fun restoreContact(peerKey: String) = trash.drop(peerKey)

    /** Out of the trash for good: the contact and the chat with them. */
    fun deleteForGood(peerKey: String) {
        deleteConversation(peerKey)
        removeContact(peerKey)
    }

    fun emptyTrash() = trash.all.value.keys.toList().forEach { deleteForGood(it) }

    private fun emptyOldTrash() = trash.expired(System.currentTimeMillis()).forEach { deleteForGood(it) }

    /** Deletes one message on this phone. */
    fun deleteMessage(peerKey: String, msgId: String) {
        forget(listOf(msgId))
        outbox.drop(msgId); outbox.drop(UNSEND + msgId); tries.remove(msgId)
        edit { map ->
            map[peerKey]?.firstOrNull { it.id == msgId }?.photo?.takeIf { it.isNotEmpty() }?.let { File(photosDir, it).delete() }
            map[peerKey] = map[peerKey].orEmpty().filterNot { it.id == msgId }
            true
        }
    }

    /** Takes a message back from both phones. Both show "Message deleted by ...". Sent until they confirm. */
    fun unsend(peerKey: String, msgId: String) {
        var target: Msg? = null
        edit { map ->
            val list = map[peerKey] ?: return@edit false
            val m = list.firstOrNull { it.id == msgId && it.mine && it.unsent.isEmpty() } ?: return@edit false
            if (m.photo.isNotEmpty()) File(photosDir, m.photo).delete()
            target = m
            map[peerKey] = list.map { if (it.id == msgId) it.copy(text = "", photo = "", voice = 0, unsent = "You", state = "unsending", failed = false) else it }
            true
        }
        val m = target ?: return
        outbox.drop(m.id)   // the message itself isn't sent again
        tries[m.id] = Tries(0, 0)
        bg { attemptUnsend(m) }
    }

    private fun attemptUnsend(m: Msg) {
        val t = tries.getOrPut(m.id) { Tries(0, 0) }
        t.count++
        t.next = System.currentTimeMillis() + RETRY.getOrElse(t.count - 1) { RETRY.last() }
        // One envelope for the whole unsend: every retry sends the same bytes.
        val body = outbox.getOrMake(UNSEND + m.id) { listOf(envelope("unsend", m.peerKey, m.id, UUID.randomUUID().toString()).toString()) }
            ?.firstOrNull() ?: return
        close.endpointFor(m.peerKey)?.let { close.send(it, JSONObject(body).put("t", "msg")) }
        ble.send(m.peerKey, body)
        net.devices.value.firstOrNull { it.chatKey == m.peerKey }?.let { d -> bg { net.post(d, PATH, body) } }
        internet(m.peerKey, listOf(body), contact(m.peerKey)?.mailbox ?: _met.value[m.peerKey]?.mailbox)
    }

    fun deleteNearbyMessage(msgId: String) {
        forget(listOf(msgId))
        synchronized(lock) { _nearby.update { l -> l.filterNot { it.id == msgId } }; if (id.keepNearbyHistory) saveNearby() }
    }

    private fun forget(ids: List<String>) {
        val now = System.currentTimeMillis()
        ids.forEach { deleted[it] = now }
        deleted.entries.removeIf { now - it.value > 2 * DAY }
        try { vault.write(deletedStore, JSONObject(HashMap<String, Long>(deleted) as Map<*, *>).toString()) } catch (_: Exception) {}
    }

    /** Already here (or deleted here): a copy from another route, or a resend. */
    private fun known(peerKey: String, msgId: String) =
        deleted.containsKey(msgId) || held[peerKey]?.containsKey(msgId) == true || _private.value[peerKey].orEmpty().any { it.id == msgId }

    private fun becomeContacts(peerKey: String) {
        val m = _met.value[peerKey] ?: return
        synchronized(lock) { _met.update { it - peerKey }; saveMet() }
        putContact(Contact(peerKey, m.name, m.mailbox, verified = m.verified))
        notices.tryEmit("You and ${m.name} are in touch. Message each other from anywhere.")
    }

    private fun putMet(k: String, m: Met) = synchronized(lock) { _met.update { it + (k to m) }; saveMet() }

    /** How a contact can be reached right now: "close", "wifi", "internet" or "". */
    fun route(peerKey: String): String = when {
        close.endpointFor(peerKey) != null || ble.peers.value.any { it.chatKey == peerKey } -> "close"
        net.devices.value.any { it.chatKey == peerKey } -> "wifi"
        contact(peerKey)?.mailbox != null -> "internet"
        else -> ""
    }

    // ------------------------------------------------------------------ receiving

    private fun receive(body: String, via: String): Pair<Int, String> {
        val o = try { JSONObject(body) } catch (e: Exception) { return 400 to "{}" }
        val fromKey = o.optString("fromKey")
        val name = o.optString("fromName").take(40).ifEmpty { "Someone" }
        if (fromKey.isEmpty() || fromKey == id.publicKey) return 400 to "{}"
        if (fromKey in id.muted() || trash.has(fromKey)) return 200 to "{}"   // blocked or in the trash: dropped quietly
        val room = o.optString("room")
        // A Kids' TV only talks to the people its parents listed, and only about what it allows.
        // Before the TV side is hooked up, a Kids' TV still takes nothing but configs (which it checks itself).
        val tv = tvHandler
        if (if (tv != null) !tv.allow(fromKey, room) else room != "tvconfig" && io.github.ravenor907.muninn.tv.KidMode.saved(context)) return 403 to "{}"
        if (room == "photo") return receivePiece(o, fromKey, name, via)
        if (room == "gphoto") return receiveGroupPiece(o, fromKey, name)
        val mailbox = o.optString("mailbox").takeIf { it.length == 64 }
        if (room in GROUP_ROOMS) return receiveGroup(o, room, fromKey, name, mailbox)
        val sentId = o.optString("id")
        val dup = { duplicate(room, fromKey, sentId, mailbox) }
        // Copies (another route, or a resend) are dropped before anything is opened.
        dup()?.let { return it }
        return opened(o, fromKey, dup) { plain -> handle(o, room, fromKey, name, mailbox, sentId, via, String(plain, Charsets.UTF_8)) }
    }

    /** Already handled: a copy from another route, or a resend. Confirms again where that helps. */
    private fun duplicate(room: String, fromKey: String, sentId: String, mailbox: String?): Pair<Int, String>? {
        if (sentId.isEmpty()) return null
        if ((room == "private" || room == "rich") && known(fromKey, sentId)) {
            if (held[fromKey]?.containsKey(sentId) != true) receipt(fromKey, listOf(sentId), "delivered", mailbox)
            return 200 to "{}"
        }
        if (room == "nearby" && (deleted.containsKey(sentId) || _nearby.value.any { it.id == sentId })) return 200 to "{}"
        if (room == "gmsg") groups.knownEnvelope(sentId)?.let { g ->
            if (g.isNotEmpty()) groups.ack(fromKey, g, listOf(sentId.substringBefore('-')), "delivered")
            return 200 to "{}"
        }
        if (room in ChatWire.SEEN_ROOMS) seen.get("$fromKey|$sentId")?.let { note ->
            if (room == "unsend" && note.isNotEmpty()) receipt(fromKey, listOf(note), "unsent", mailbox)
            // A reaction or edit sent again: my receipt didn't get there.
            if (room == "mx") receipt(fromKey, listOf(sentId), "delivered", mailbox)
            // A group message they sent again: they didn't get my receipt, so confirm once more.
            if (room == "group" && note.isNotEmpty()) groups.ack(fromKey, note, listOf(sentId.substringBefore('-')), "delivered")
            return 200 to "{}"
        }
        return null
    }

    private fun sharedKey(theirKey: String) = ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(theirKey))

    /**
     * Opens an envelope (v1, or the ratchet when it has "rh") and hands the plaintext to [then].
     * Ratchet state is kept only when [then] took the message (200): one turned away (too many at once)
     * can come again and still open. [dup] is checked again once this contact's ratchet is ours.
     */
    private fun opened(o: JSONObject, fromKey: String, dup: () -> Pair<Int, String>?, then: (ByteArray) -> Pair<Int, String>): Pair<Int, String> {
        val sk = try { sharedKey(fromKey) } catch (e: Exception) { return 400 to "{}" }
        if (!ChatWire.isRatchet(o)) {
            val plain = ChatWire.open(o, sk, id.keys, null, ChatCrypto::newKeyPair) ?: return 403 to "{}"
            authentic(o, fromKey)
            return then(plain)
        }
        synchronized(ratchets.lock(fromKey)) {
            dup()?.let { return it }
            val peer = ratchets.get(fromKey)
            // Fails quietly like a bad v1 message; the ratchet is left as it was.
            val plain = ChatWire.open(o, sk, id.keys, peer, ChatCrypto::newKeyPair) ?: return 403 to "{}"
            val r = try { authentic(o, fromKey); then(plain) } catch (e: Exception) { ratchets.forget(fromKey); throw e }
            if (r.first == 200) try { ratchets.save(fromKey, peer) } catch (e: Exception) { ratchets.forget(fromKey) }
            else ratchets.forget(fromKey)
            return r
        }
    }

    /** Proven to be from them: a contact whose app speaks the ratchet gets it from now on. */
    private fun authentic(o: JSONObject, fromKey: String) {
        val c = contacts.get(fromKey) ?: return
        // What their app speaks: tokens only ever add up (a changed key starts a new contact with none).
        val caps = c.caps + ChatWire.capsOf(o)
        val mailbox = if (c.groupOnly) o.optString("mailbox").takeIf { it.length == 64 } ?: c.mailbox else c.mailbox
        val now = c.copy(ratchet = c.ratchet || ChatWire.capable(o), caps = caps, mailbox = mailbox)
        if (now != c) contacts.set(now)
    }

    private fun handle(o: JSONObject, envRoom: String, fromKey: String, name: String, mailbox: String?, sentId: String, via: String,
                       plain: String): Pair<Int, String> {
        val now = System.currentTimeMillis()
        // Cap m1: a reply ("rich") is a private message with the id it quotes; everything else is the same.
        var opened = plain
        var re = ""
        if (envRoom == "rich") {
            val p = try { JSONObject(plain) } catch (e: Exception) { return 400 to "{}" }
            if (!Rich.ok("rich", p)) return 400 to "{}"
            opened = p.getString("text"); re = p.optString("re")
        }
        val room = if (envRoom == "rich") "private" else envRoom
        // No more than 5 messages in 10 seconds from anyone: nobody can flood a room. Counted only once
        // a message proved it's really from them, so nobody can use it to block a real contact.
        if (recent.size > 500) recent.clear()
        val q = recent.getOrPut(fromKey) { ArrayDeque() }
        if (room == "tvconfig" || room == "call" || room == "mx" || room == "live") { if (!groupRate(room, fromKey)) return 429 to "{}" }
        else if (room != "receipt") synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
            if (q.size >= 5) return 429 to "{}"
            q.addLast(now)
        }
        val text = if (room == "receipt" || room == "mx" || room == "live") opened else opened.take(MAX)
        val msgId = sentId.ifEmpty { UUID.randomUUID().toString() }
        val msg = Msg(msgId, fromKey, name, text, false, now, via = via, re = re)
        // A message from them ends their "typing".
        if (room == "private" && fromKey in _typing.value) _typing.update { it - fromKey }
        when (room) {
            "mx" -> {
                if (contacts.contact(fromKey) == null && !_met.value.containsKey(fromKey)) return 403 to "{}"
                val p = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                if (!Rich.ok("mx", p)) return 400 to "{}"
                takeMx(fromKey, name, p)
                // Confirmed by its envelope id, so the sender stops resending it.
                receipt(fromKey, listOf(sentId), "delivered", mailbox)
            }
            "live" -> {
                if (contacts.contact(fromKey) == null && !_met.value.containsKey(fromKey)) return 403 to "{}"
                val p = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                if (!Rich.ok("live", p)) return 400 to "{}"
                if (Rich.fresh(Rich.ts(p), now)) {
                    val on = p.getBoolean("on")
                    if (p.getString("t") == "typing") _typing.update { if (on) it + (fromKey to now + Rich.TYPING_MS) else it - fromKey }
                    else if (id.activeStatus) _active.update { if (on) it + (fromKey to now + Rich.ACTIVE_MS) else it - fromKey }
                }
            }
            "call" -> { if (contacts.get(fromKey) == null) return 403 to "{}"; onSignal(fromKey, opened); return 200 to "{}" }
            "tvconfig" -> if (tvHandler?.onTvRoom(fromKey, mailbox, opened, inviteOk(o.optString("invite"))) != true) return 403 to "{}"
            "nearby" -> {
                if (!id.nearbyRoom || via == "internet") return 403 to "{}"
                addNearby(msg)
            }
            "private" -> {
                val c = contact(fromKey)
                if (c != null) {
                    if (mailbox != null && c.mailbox != mailbox) contacts.put(c.copy(mailbox = mailbox))
                    if (addPrivate(fromKey, msg)) {
                        _unread.update { it + fromKey }
                        arrivals.tryEmit(Triple(c.shown, text, fromKey))
                    }
                    receipt(fromKey, listOf(msgId), "delivered", mailbox)
                } else if (fromKey in id.muted()) {
                    return 200 to "{}"
                } else if (via != "internet" && !id.visibleNearby) {
                    // Hidden from people nearby: only contacts get through close by or on the Wi-Fi.
                    return 403 to "{}"
                } else if (via != "internet" || _met.value.containsKey(fromKey)) {
                    // Close by or on the same Wi-Fi (or someone met that way): no asking first.
                    val m = _met.value[fromKey]
                    putMet(fromKey, (m ?: Met(name, mailbox)).copy(name = name, mailbox = mailbox ?: m?.mailbox))
                    if (addPrivate(fromKey, msg)) {
                        _unread.update { it + fromKey }
                        arrivals.tryEmit(Triple(name, text, fromKey))
                    }
                    receipt(fromKey, listOf(msgId), "delivered", mailbox)
                } else if (!inviteOk(o.optString("invite"))) {
                    // From far away with no valid invite (an old or leaked code): dropped quietly.
                    return 403 to "{}"
                } else {
                    // A code shared some other way: they already have this phone's code, so ask.
                    held.getOrPut(fromKey) { java.util.Collections.synchronizedMap(LinkedHashMap()) }.putIfAbsent(msgId, msg)
                    var isNew = false
                    _requests.update { list -> if (list.any { it.peerKey == fromKey }) list else { isNew = true; list + Request(fromKey, name, ChatCrypto.pairingCode(sharedKey(fromKey)), mailbox, via == "internet") } }
                    if (isNew) {
                        notices.tryEmit("$name wants to chat")
                        arrivals.tryEmit(Triple(name, "Wants to chat with you", fromKey))
                    }
                }
            }
            "receipt" -> {
                // Group peers send receipts too (a caps probe, or its answer).
                if (contacts.get(fromKey) == null && !_met.value.containsKey(fromKey)) return 403 to "{}"
                val r = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                // A caps probe (1.2): the answer's envelope carries my caps.
                if (r.optString("q") == "caps") bg {
                    deliver(fromKey, envelope("receipt", fromKey, JSONObject().put("ids", JSONArray()).put("s", "delivered").toString(),
                        UUID.randomUUID().toString()), mailbox ?: contacts.get(fromKey)?.mailbox)
                }
                val ids = r.optJSONArray("ids")?.let { a -> (0 until minOf(a.length(), 200)).map { a.optString(it) }.toSet() } ?: return 400 to "{}"
                val st = r.optString("s")
                if (st != "delivered" && st != "read" && st != "unsent") return 400 to "{}"
                // A reaction or edit (cap m1) got there: stop resending it.
                if (st == "delivered" && ids.any { mxOut[it]?.first == fromKey }) {
                    ids.forEach { if (mxOut[it]?.first == fromKey) { mxOut.remove(it); outbox.drop(it); tries.remove(it) } }
                    saveMx()
                }
                // Got there: my sealed envelopes to them needn't be kept for resending any more.
                _private.value[fromKey].orEmpty().forEach { m ->
                    if (m.mine && m.id in ids) { if (st == "unsent") outbox.drop(UNSEND + m.id) else outbox.drop(m.id) }
                }
                edit { map ->
                    val before = map[fromKey] ?: return@edit false
                    map[fromKey] = before.map { m ->
                        when {
                            !m.mine || m.id !in ids -> m
                            st == "unsent" -> if (m.state == "unsending") m.copy(state = "unsent").also { tries.remove(m.id) } else m
                            m.unsent.isEmpty() && rank(st) > rank(m.state) -> m.copy(state = st, failed = false)
                            else -> m
                        }
                    }
                    true
                }
            }
            "unsend" -> {
                if (!isContact(fromKey) && !_met.value.containsKey(fromKey)) return 403 to "{}"
                val gone = text.trim()
                var found = false
                edit { map ->
                    val list = map[fromKey] ?: return@edit false
                    val m = list.firstOrNull { it.id == gone && !it.mine } ?: return@edit false
                    found = true
                    if (m.unsent.isNotEmpty()) return@edit false
                    if (m.photo.isNotEmpty()) File(photosDir, m.photo).delete()
                    map[fromKey] = list.map { if (it.id == gone) it.copy(text = "", photo = "", voice = 0, unsent = name) else it }
                    true
                }
                // Not here yet (still on its way): make sure it never shows.
                if (!found) { forget(listOf(gone)); held[fromKey]?.remove(gone) }
                recalls.tryEmit(fromKey)
                receipt(fromKey, listOf(gone), "unsent", mailbox)
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
        if (room in ChatWire.SEEN_ROOMS && sentId.isNotEmpty()) seen.add("$fromKey|$sentId", if (room == "unsend") text.trim() else "")
        return 200 to "{}"
    }

    fun acceptRequest(r: Request) {
        putContact(Contact(r.peerKey, r.name, r.mailbox, verified = !r.viaInternet))
        _requests.update { l -> l.filterNot { it.peerKey == r.peerKey } }
        held.remove(r.peerKey)?.let { m ->
            val list = synchronized(m) { m.values.toList() }
            list.forEach { addPrivate(r.peerKey, it) }
            receipt(r.peerKey, list.map { it.id }, "delivered", r.mailbox)
        }
    }

    // ------------------------------------------------------------------ photos
    // A photo is shrunk on the sending phone (Photos.kt), cut into pieces of 16 KB, and each piece is
    // sealed and sent like a message (room "photo"). The other phone puts them back together and
    // answers "delivered" once it has them all. Pieces fit every route: Nearby's 32 KB limit and relays'.

    private class Incoming(val from: String, val parts: Array<ByteArray?>, val started: Long)
    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val pieceTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun photo(photoId: String): ByteArray? = vault.readBytes(File(photosDir, photoId))

    fun sendPhoto(peerKey: String, jpeg: ByteArray) = sendBlob(peerKey, jpeg, 0)

    /** A recorded voice message (AAC, no container, so nothing but sound). */
    fun sendVoice(peerKey: String, aac: ByteArray, ms: Int) = sendBlob(peerKey, aac, ms.coerceAtLeast(1))

    private fun sendBlob(peerKey: String, jpeg: ByteArray, voiceMs: Int) {
        val name = nameOf(peerKey)
        if (!isContact(peerKey)) meet(peerKey, name)
        val r = route(peerKey)
        val msgId = UUID.randomUUID().toString()
        vault.writeBytes(File(photosDir, msgId), jpeg)
        val mine = Msg(msgId, peerKey, id.username, "", true, System.currentTimeMillis(), via = r, state = "sending", photo = msgId, voice = voiceMs)
        tries[mine.id] = Tries(0, System.currentTimeMillis() + RETRY[0])
        addPrivate(peerKey, mine)
        if (r.isEmpty() && backupMailbox(mine) == null) {
            replace(peerKey, mine.copy(failed = true))
            notices.tryEmit("$name is out of range. Say yes to Keep in touch to send from anywhere.")
            return
        }
        bg { attempt(mine) }
    }

    /** The photo's sealed pieces: made (and encrypted) once, then the same bytes on every try and route. */
    private fun pieces(m: Msg): List<String>? = outbox.getOrMake(m.id) {
        val bytes = photo(m.photo) ?: return@getOrMake null
        val parts = Photos.split(bytes)
        seal("photo", m.peerKey, parts.mapIndexed { i, part ->
            ChatWire.base("photo", id.publicKey, id.username, id.fingerprint, nostr.publicKey, m.id).put("i", i).put("n", parts.size)
                .apply { if (m.voice > 0) put("kind", "voice").put("ms", m.voice) } to part
        }).map { it.toString() }
    }

    private fun receivePiece(o: JSONObject, fromKey: String, name: String, via: String): Pair<Int, String> {
        val contact = contacts.contact(fromKey)
        if (contact == null && via == "internet" && !_met.value.containsKey(fromKey)) return 403 to "{}"
        val msgId = o.optString("id")
        val n = o.optInt("n"); val i = o.optInt("i", -1)
        if (msgId.isEmpty() || n < 1 || n > Photos.MAX_PIECES || i !in 0 until n) return 400 to "{}"
        val mailbox = o.optString("mailbox").takeIf { it.length == 64 }
        // Already have it (a copy from another route, or a resend): confirm again, or just drop a piece already here.
        val dup = {
            if (known(fromKey, msgId)) { receipt(fromKey, listOf(msgId), "delivered", mailbox); 200 to "{}" }
            else if (incoming[msgId]?.let { it.from == fromKey && it.parts.size == n && it.parts[i] != null } == true) 200 to "{}"
            else null
        }
        dup()?.let { return it }
        val now = System.currentTimeMillis()
        val q = pieceTimes.getOrPut(fromKey) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= 1000) return 429 to "{}"   // about 15 MB a minute at most
            q.addLast(now)
        }
        if (incoming.size > 20 && !incoming.containsKey(msgId)) return 429 to "{}"
        return opened(o, fromKey, dup) { part -> takePiece(o, fromKey, name, via, msgId, n, i, mailbox, part) }
    }

    private fun takePiece(o: JSONObject, fromKey: String, name: String, via: String, msgId: String, n: Int, i: Int, mailbox: String?,
                          part: ByteArray): Pair<Int, String> {
        val contact = contacts.contact(fromKey)
        val inc = incoming.getOrPut(msgId) { Incoming(fromKey, arrayOfNulls(n), System.currentTimeMillis()) }
        if (inc.from != fromKey || inc.parts.size != n) return 400 to "{}"
        synchronized(inc) {
            inc.parts[i] = part
            if (inc.parts.any { it == null }) return 200 to "{}"
            incoming.remove(msgId)
        }
        val voiceMs = if (o.optString("kind") == "voice") o.optInt("ms").coerceIn(1, Voice.MAX_MS) else 0
        val joined = inc.parts.map { it!! }
        val jpeg = (if (voiceMs > 0) Voice.check(joined) else Photos.join(joined)) ?: return 400 to "{}"
        vault.writeBytes(File(photosDir, msgId), jpeg)
        if (contact == null) {
            val m = _met.value[fromKey]
            putMet(fromKey, (m ?: Met(name, mailbox)).copy(name = name, mailbox = mailbox ?: m?.mailbox))
        } else if (mailbox != null && contact.mailbox != mailbox) contacts.put(contact.copy(mailbox = mailbox))
        if (addPrivate(fromKey, Msg(msgId, fromKey, name, "", false, System.currentTimeMillis(), via = via, photo = msgId, voice = voiceMs))) {
            _unread.update { it + fromKey }
            arrivals.tryEmit(Triple(contact?.shown ?: name, if (voiceMs > 0) "Voice message" else "Photo", fromKey))
        }
        receipt(fromKey, listOf(msgId), "delivered", mailbox)
        return 200 to "{}"
    }

    fun declineRequest(r: Request) {
        _requests.update { l -> l.filterNot { it.peerKey == r.peerKey } }
        held.remove(r.peerKey)
    }

    fun addContact(c: Contact) = putContact(c)

    /** The contact [c] would replace, if adding it changes someone's security key (for a warning before adding). */
    fun keyChangeFor(c: Contact): Contact? = ChatWire.keyChangeFor(contacts.all.value, c)

    /** Someone close by or on the Wi-Fi, after both screens showed the same code. */
    fun addNearbyPerson(chatKey: String, name: String) {
        val mailbox = close.list.value.firstOrNull { it.chatKey == chatKey }?.mailbox
        putContact(Contact(chatKey, name, mailbox, verified = true))
    }

    /**
     * Every way someone becomes a contact comes through here. If they stand in for a contact already
     * here with another key (same mailbox; a name alone never counts), it's a key change: never
     * taken silently. The contact (and chat, and nickname) stays, now unverified, the ratchet starts
     * over, and both a notice and a line in the chat say so until the code is checked.
     */
    private fun putContact(c: Contact) {
        // Adding someone in the trash again brings them back.
        trash.drop(c.chatKey)
        val old = ChatWire.keyChangeFor(contacts.all.value, c)
        if (old == null) { contacts.put(c); return }
        val now = ChatWire.afterKeyChange(old, c)
        contacts.replaceKey(old.chatKey, now)
        synchronized(ratchets.lock(old.chatKey)) { ratchets.drop(old.chatKey) }
        synchronized(ratchets.lock(c.chatKey)) { ratchets.drop(c.chatKey) }
        moveChat(old.chatKey, c.chatKey)
        notices.tryEmit(ChatWire.keyChangedText(now.shown))
    }

    /** The chat with [from] continues under their new key [to]. Anything of mine still on its way stops
     *  (it was sealed to the old key): it shows "Didn't send", and Try again sends it to the new key. */
    private fun moveChat(from: String, to: String) {
        edit { map ->
            val old = map.remove(from) ?: return@edit false
            val moved = old.map { m ->
                val pending = m.mine && m.unsent.isEmpty() && m.state.isNotEmpty() && rank(m.state) < 3
                if (pending || m.state == "unsending") { outbox.drop(m.id); outbox.drop(UNSEND + m.id); tries.remove(m.id) }
                when {
                    pending -> m.copy(peerKey = to, state = "", failed = true)
                    m.state == "unsending" -> m.copy(peerKey = to, state = "unsent")
                    else -> m.copy(peerKey = to)
                }
            }
            map[to] = (moved + map[to].orEmpty()).distinctBy { it.id }.sortedBy { it.time }.takeLast(1000)
            true
        }
        if (from in _unread.value) _unread.update { it - from + to }
        synchronized(lock) { if (_met.value.containsKey(to)) { _met.update { it - to }; saveMet() } }
    }

    /** The chat is on screen: clear the dot and, if allowed, tell them what was read. */
    fun markRead(peerKey: String) {
        _unread.update { it - peerKey }
        if (!id.readReceipts) return
        var fresh = emptyList<Msg>()
        edit { map ->
            fresh = map[peerKey].orEmpty().filter { !it.mine && it.state != "read" }
            if (fresh.isEmpty()) return@edit false
            map[peerKey] = map[peerKey].orEmpty().map { if (!it.mine && it.state != "read") it.copy(state = "read") else it }
            true
        }
        receipt(peerKey, fresh.map { it.id }, "read", null)
    }

    private fun rank(s: String) = when (s) { "sending" -> 1; "sent" -> 2; "delivered" -> 3; "read" -> 4; else -> 0 }

    /** Tells the sender their messages arrived (always) or were read (only with read receipts on). Sealed like any message. */
    private fun receipt(peerKey: String, ids: List<String>, state: String, mailbox: String?) {
        if (ids.isEmpty()) return
        bg {
            // 40 ids a receipt keeps each one small on every route.
            ids.takeLast(200).chunked(40).forEach { part ->
                val body = JSONObject().put("ids", JSONArray(part)).put("s", state).toString()
                deliver(peerKey, envelope("receipt", peerKey, body, UUID.randomUUID().toString()),
                    mailbox ?: contacts.get(peerKey)?.mailbox ?: _met.value[peerKey]?.mailbox)
            }
        }
    }

    // ------------------------------------------------------------------ sending

    fun sendNearby(text: String) {
        val t = text.trim().take(MAX)
        if (t.isEmpty() || !id.nearbyRoom) return
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
            for (p in ble.peers.value) {
                val k = p.chatKey ?: continue
                if (k in muted || !done.add(k)) continue
                if (ble.send(k, envelope("nearby", k, t, mine.id).toString())) reached++
            }
            for (d in net.devices.value) {
                val k = d.chatKey ?: continue
                if (d.chatName == null || k in muted || !done.add(k)) continue
                if (net.post(d, PATH, envelope("nearby", k, t, mine.id).toString()) == 200) reached++
            }
            if (reached == 0) notices.tryEmit("Nobody nearby got that yet. They need Muninn or Huginn open close by or on the same Wi-Fi.")
        }
    }

    /** [re]: the id of the message this answers (a reply, cap m1; to an older app it goes as plain text). */
    fun sendPrivate(peerKey: String, text: String, re: String = "") {
        val t = text.trim().take(MAX)
        if (t.isEmpty()) return
        val name = nameOf(peerKey)
        if (!isContact(peerKey)) meet(peerKey, name)
        val r = route(peerKey)
        typingSent.remove(peerKey)
        val mine = Msg(UUID.randomUUID().toString(), peerKey, id.username, t, true, System.currentTimeMillis(), via = r, state = "sending",
            re = re.takeIf { Rich.isMsgId(it) } ?: "")
        tries[mine.id] = Tries(0, System.currentTimeMillis() + RETRY[0])
        addPrivate(peerKey, mine)
        if (r.isEmpty() && backupMailbox(mine) == null) {
            replace(peerKey, mine.copy(failed = true))
            notices.tryEmit(if (isContact(peerKey)) "$name can't be reached: they're not nearby and have no internet mailbox yet."
                else "$name is out of range. Say yes to Keep in touch to message from anywhere.")
            return
        }
        bg { attempt(mine) }
    }

    // ------------------------------------------------------------------ cap m1: reactions, edits, typing, active status
    // (docs/protocol-0.6.md, section 11). Only ever sent to someone whose app said "m1".

    /** Their app speaks m1: replies, reactions, edits, typing and active status. */
    fun hasM1(peerKey: String) = contacts.get(peerKey)?.caps?.contains(Rich.CAP) == true

    /** My reaction to message [msgId] in the chat with [peerKey] ("" takes it away). Null when done, else what to say. */
    fun react(peerKey: String, msgId: String, e: String): String? {
        if (!Rich.emojiOk(e)) return "That reaction can't be sent."
        val ts = System.currentTimeMillis()
        var done = false
        edit { map ->
            val list = map[peerKey] ?: return@edit false
            val m = list.firstOrNull { it.id == msgId && it.unsent.isEmpty() } ?: return@edit false
            val all = Rich.react(m.reactions, id.publicKey, e, ts) ?: return@edit false
            map[peerKey] = list.map { if (it.id == msgId) it.copy(reactions = all) else it }
            done = true
            true
        }
        if (!done) return null
        if (!hasM1(peerKey)) { probeCaps(peerKey); return "${nameOf(peerKey)} needs a newer Muninn or Huginn to see reactions." }
        queueMx(peerKey, Rich.reactMsg(msgId, e, ts))
        return null
    }

    /** Changes the text of one of my messages, on both sides ("(edited)"). Null when done, else what to say. */
    fun editMessage(peerKey: String, msgId: String, text: String): String? {
        val t = text.trim().take(MAX)
        if (t.isEmpty()) return "A message can't be empty. Unsend it instead."
        if (!hasM1(peerKey)) { probeCaps(peerKey); return "${nameOf(peerKey)} needs a newer Muninn or Huginn to see edits." }
        val ts = System.currentTimeMillis()
        var done = false
        edit { map ->
            val list = map[peerKey] ?: return@edit false
            val m = list.firstOrNull { it.id == msgId && it.mine && it.unsent.isEmpty() && it.photo.isEmpty() } ?: return@edit false
            if (m.text == t) return@edit false
            map[peerKey] = list.map { if (it.id == msgId) it.copy(text = t, edited = maxOf(ts, m.edited + 1)) else it }
            done = true
            true
        }
        if (done) queueMx(peerKey, Rich.editMsg(msgId, t, _private.value[peerKey]?.firstOrNull { it.id == msgId }?.edited ?: ts))
        return null
    }

    /** A reaction or edit from them, already checked (Rich.check). */
    private fun takeMx(fromKey: String, name: String, p: JSONObject) {
        val msgId = p.getString("msg")
        val ts = Rich.ts(p)
        if (p.getString("t") == "react") {
            val e = p.getString("e")
            var mine: Msg? = null
            edit { map ->
                val list = map[fromKey] ?: return@edit false
                val m = list.firstOrNull { it.id == msgId && it.unsent.isEmpty() } ?: return@edit false
                val all = Rich.react(m.reactions, fromKey, e, ts) ?: return@edit false
                map[fromKey] = list.map { if (it.id == msgId) it.copy(reactions = all) else it }
                if (m.mine && e.isNotEmpty()) mine = m
                true
            }
            mine?.let { m -> arrivals.tryEmit(Triple(contacts.get(fromKey)?.shown ?: name, "Reacted $e to “${preview(m)}”", fromKey)) }
        } else {
            val t = p.getString("text").take(MAX)
            edit { map ->
                val list = map[fromKey] ?: return@edit false
                val m = list.firstOrNull { it.id == msgId && !it.mine && it.unsent.isEmpty() && it.photo.isEmpty() } ?: return@edit false
                if (!Rich.editWins(fromKey, fromKey, ts, m.edited)) return@edit false
                map[fromKey] = list.map { if (it.id == msgId) it.copy(text = t, edited = ts) else it }
                true
            }
        }
    }

    private fun preview(m: Msg) = when { m.voice > 0 -> "Voice message"; m.photo.isNotEmpty() -> "Photo"; else -> m.text.take(60) }

    /** Seals one reaction or edit once and keeps it until their receipt names its envelope id (a day at most). */
    private fun queueMx(peerKey: String, plain: String) {
        val env = UUID.randomUUID().toString()
        mxOut[env] = peerKey to System.currentTimeMillis()
        tries[env] = Tries(0, 0)
        saveMx()
        bg {
            outbox.getOrMake(env) { listOf(envelope("mx", peerKey, plain, env).toString()) }
            attemptMx(env)
        }
    }

    private fun attemptMx(env: String) {
        val (k, _) = mxOut[env] ?: return
        val t = tries.getOrPut(env) { Tries(0, 0) }
        t.count++
        t.next = System.currentTimeMillis() + RETRY.getOrElse(t.count - 1) { RETRY.last() }
        val body = outbox.get(env) ?: run { mxOut.remove(env); saveMx(); return }
        spread(k, body, false, t.count, contacts.get(k)?.mailbox ?: _met.value[k]?.mailbox)
    }

    private fun saveMx() {
        try { vault.write(mxStore, JSONObject().apply { mxOut.forEach { (e, v) -> put(e, JSONObject().put("k", v.first).put("t", v.second)) } }.toString()) } catch (_: Exception) {}
    }

    /** Typing in the chat with [peerKey]: "on" at most every 3 s while the text changes, "off" once it's cleared. */
    fun typingTo(peerKey: String, on: Boolean) {
        if (!hasM1(peerKey) || peerKey in id.muted()) return
        val now = System.currentTimeMillis()
        val last = typingSent[peerKey] ?: 0L
        if (on && now - last < Rich.TYPING_EVERY_MS) return
        if (!on && (last == 0L || now - last > Rich.TYPING_MS)) { typingSent.remove(peerKey); return }
        if (on) typingSent[peerKey] = now else typingSent.remove(peerKey)
        sendLive(peerKey, Rich.liveMsg("typing", on, now))
    }

    /** Muninn came on screen or went away: say so to recent contacts (only with Active status on). */
    fun foreground(on: Boolean) {
        if (foreground == on) return
        foreground = on
        if (!id.activeStatus) return
        bg { sendActive(on) }
    }

    /** Active status switched in Settings: off tells everyone once and stops; nobody's dot shows either. */
    fun setActiveStatus(on: Boolean) {
        if (id.activeStatus == on) return
        id.activeStatus = on
        if (!on) { _active.value = emptyMap(); bg { sendActive(false) } }
        else if (foreground) bg { sendActive(true) }
    }

    private fun sendActive(on: Boolean) {
        val now = System.currentTimeMillis()
        if (on) activeSent = now
        val plain = Rich.liveMsg("active", on, now)
        val muted = id.muted()
        // Contacts with m1, the ones chatted with most lately first, at most 20 (each is a relay event).
        val recentFirst = contacts.all.value.filter { !it.groupOnly && Rich.CAP in it.caps && it.chatKey !in muted && !trash.has(it.chatKey) }
            .sortedByDescending { _private.value[it.chatKey]?.lastOrNull()?.time ?: 0L }.take(20)
        recentFirst.forEach { c -> sendLive(c.chatKey, plain) }
    }

    /** Typing and active status: sealed with v1 (never the ratchet), sent once on every route, never retried. */
    private fun sendLive(peerKey: String, plain: String) = bg {
        val body = envelope("live", peerKey, plain, UUID.randomUUID().toString()).toString()
        spread(peerKey, listOf(body), false, 1, contacts.get(peerKey)?.mailbox)
    }

    /** Replying from a notification: a chat ([key] a contact) or a group ("g:<id>", into the channel of its last message). */
    fun replyFromNotification(key: String, text: String) {
        val g = if (key.startsWith("g:")) key.removePrefix("g:") else null
        if (g != null) {
            val last = groups.messages.value[g]?.lastOrNull { !it.mine }
            groups.markRead(g)
            sendGroupText(g, last?.ch ?: Proto06.MAIN, text)
        } else {
            markRead(key)
            sendPrivate(key, text)
        }
    }

    // ------------------------------------------------------------------ getting it there
    // Every message goes out on every route at once (close by, Wi-Fi and the internet); the other
    // phone keeps the first copy and drops the rest. Until a "delivered" receipt comes back it is
    // sent again after 5 s, then less often, for a day. So someone walking out of range still gets it.

    private class Tries(@Volatile var count: Int, @Volatile var next: Long)

    /** Their internet mailbox, if this message may use it: contacts always; someone met close by
     *  only for messages sent while they were in range (a backup as they walk away). */
    private fun backupMailbox(m: Msg): String? =
        contact(m.peerKey)?.mailbox ?: _met.value[m.peerKey]?.mailbox?.takeIf { m.via == "close" || m.via == "wifi" }

    private fun attempt(m: Msg) {
        val t = tries.getOrPut(m.id) { Tries(0, 0) }
        t.count++
        t.next = System.currentTimeMillis() + RETRY.getOrElse(t.count - 1) { RETRY.last() }
        if (m.photo.isNotEmpty()) { attemptPhoto(m, t); return }
        // Sealed once; every try and every route sends these same bytes.
        val body = outbox.getOrMake(m.id) {
            // A reply goes as "rich" to an app that has m1; the same text without the quote to any other.
            val rich = m.re.isNotEmpty() && hasM1(m.peerKey)
            listOf(envelope(if (rich) "rich" else "private", m.peerKey, if (rich) Rich.richText(m.text, m.re) else m.text, m.id).toString())
        }?.firstOrNull() ?: return
        var any = false
        close.endpointFor(m.peerKey)?.let { close.send(it, JSONObject(body).put("t", "msg")); any = true; markSent(m, "close") }
        if (ble.send(m.peerKey, body)) { any = true; markSent(m, "close") }
        net.devices.value.firstOrNull { it.chatKey == m.peerKey }?.let { d ->
            any = true
            bg { if (net.post(d, PATH, body) == 200) update(m.peerKey, m.id) { if (it.unsent.isEmpty() && rank(it.state) < 2) it.copy(state = "sent", via = "wifi", failed = false) else it } }
            // "Sent" only: a 200 just means some device answered; "Delivered" comes from their sealed receipt.
        }
        if (internet(m.peerKey, listOf(body), backupMailbox(m))) { any = true; markSent(m, "internet") }
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
        // Bluetooth LE is slow (about 22 KB a piece): give it time before trying again.
        if (ble.peers.value.any { it.chatKey == m.peerKey } && parts.all { ble.send(m.peerKey, it.toString()) }) {
            any = true; markSent(m, "close"); t.next = maxOf(t.next, System.currentTimeMillis() + parts.size * 3000L)
        }
        net.devices.value.firstOrNull { it.chatKey == m.peerKey }?.let { d ->
            any = true
            bg { if (parts.all { net.post(d, PATH, it.toString()) == 200 }) update(m.peerKey, m.id) { if (it.unsent.isEmpty() && rank(it.state) < 2) it.copy(state = "sent", via = "wifi", failed = false) else it } }
        }
        val mailbox = backupMailbox(m)
        if ((!any || t.count >= 2) && parts.size <= Photos.INTERNET_PIECES && internet(m.peerKey, parts, mailbox)) {
            any = true; markSent(m, "internet")
        }
        if (!any && t.count == 1) update(m.peerKey, m.id) { it.copy(failed = true) }
    }

    private fun markSent(m: Msg, how: String) = update(m.peerKey, m.id) { if (it.unsent.isEmpty() && rank(it.state) < 2) it.copy(state = "sent", via = if (it.via.isEmpty()) how else it.via, failed = false) else it }

    /** Runs every second: anything of mine not yet delivered (and under a day old) is tried again. */
    private fun retryLoop() {
        val now = System.currentTimeMillis()
        groups.due().forEach { o -> bg { sendGroupOut(o) } }
        incoming.entries.removeIf { now - it.value.started > 15 * 60_000 }   // half a photo that never finished
        // Cap m1: reactions and edits still on their way, typing and active status that ran out.
        for ((env, v) in mxOut) {
            if (now - v.second > DAY) { mxOut.remove(env); outbox.drop(env); tries.remove(env); saveMx(); continue }
            val t = tries[env]
            if (t == null) { tries[env] = Tries(1, now + RETRY[0]); continue }
            if (now >= t.next) { t.next = now + RETRY[0]; bg { attemptMx(env) } }
        }
        if (_typing.value.values.any { it < now }) _typing.update { m -> m.filterValues { it >= now } }
        if (_active.value.values.any { it < now }) _active.update { m -> m.filterValues { it >= now } }
        if (foreground && id.activeStatus && now - activeSent > Rich.ACTIVE_EVERY_MS) { activeSent = now; bg { sendActive(true) } }
        for ((_, list) in _private.value) for (m in list) {
            if (m.mine && m.state == "unsending") {
                val t = tries.getOrPut(m.id) { Tries(1, now + RETRY[0]) }
                // About a day of tries; after that stop (their app may be too old to answer).
                if (t.count > 300) { update(m.peerKey, m.id) { it.copy(state = "unsent") }; tries.remove(m.id); outbox.drop(UNSEND + m.id); continue }
                if (now >= t.next) { t.next = now + RETRY[0]; bg { attemptUnsend(m) } }
                continue
            }
            // State "" is from before receipts existed: those were sent already, leave them be.
            if (!m.mine || m.state.isEmpty() || m.unsent.isNotEmpty() || rank(m.state) >= 3 || now - m.time > DAY) {
                // Done with it (delivered, or a day of trying): its sealed copy goes too.
                if (tries.remove(m.id) != null) outbox.drop(m.id)
                continue
            }
            val t = tries[m.id]
            if (t == null) { tries[m.id] = Tries(1, now + RETRY[0]); continue }   // after a restart: wait one round
            if (now >= t.next) { t.next = now + RETRY[0]; bg { attempt(m) } }
        }
    }

    /** The refresh button: everything still waiting goes again on the next round, not when its timer says. */
    fun retryAllNow() { for (t in tries.values) t.next = 0L }

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
        if (ble.send(peerKey, body.toString())) return "close"
        net.devices.value.firstOrNull { it.chatKey == peerKey }?.let { if (net.post(it, PATH, body.toString()) == 200) return "wifi" }
        if (internet(peerKey, listOf(body.toString()), mailbox)) return "internet"
        return ""
    }

    /** The internet: Nostr, their mailbox, which waits for them while they're offline. False without a mailbox. */
    private fun internet(peerKey: String, bodies: List<String>, mailbox: String?): Boolean {
        if (mailbox == null) return false
        bodies.forEach { nostr.publish(mailbox, wrap(peerKey, it)) }
        return true
    }

    private fun update(peer: String, msgId: String, f: (Msg) -> Msg) {
        edit { map ->
            val before = map[peer] ?: return@edit false
            map[peer] = before.map { if (it.id == msgId) f(it) else it }
            true
        }
    }

    /** Every change to the chats goes through here: one at a time, then saved. Returns whether it changed. */
    private fun edit(f: (MutableMap<String, List<Msg>>) -> Boolean): Boolean = synchronized(lock) {
        val map = _private.value.toMutableMap()
        if (!f(map)) return@synchronized false
        _private.value = map
        save()
        true
    }

    private fun envelope(room: String, theirKey: String, text: String, msgId: String): JSONObject {
        val o = ChatWire.base(room, id.publicKey, id.username, id.fingerprint, nostr.publicKey, msgId)
            // The invite from their code, until they accept: proves they handed it out within the hour.
            .apply { contacts.get(theirKey)?.invite?.let { put("invite", it) } }
        return seal(room, theirKey, listOf(o to text.toByteArray(Charsets.UTF_8))).first()
    }

    /**
     * Seals each (envelope, plaintext) for [theirKey]: through the message ratchet when they're a contact
     * whose app has it and the room allows (never Nearby), else v1. The ratchet is saved before any of it
     * can leave, so a message key is never used twice.
     */
    private fun seal(room: String, theirKey: String, items: List<Pair<JSONObject, ByteArray>>): List<JSONObject> {
        val sk = sharedKey(theirKey)
        if (!ChatWire.useRatchet(room, contacts.get(theirKey)))
            return items.map { (o, plain) -> ChatWire.seal(o, plain, sk, theirKey, null, ChatCrypto::newKeyPair) }
        synchronized(ratchets.lock(theirKey)) {
            val peer = ratchets.get(theirKey)
            try {
                val out = items.map { (o, plain) -> ChatWire.seal(o, plain, sk, theirKey, peer, ChatCrypto::newKeyPair) }
                ratchets.save(theirKey, peer)
                return out
            } catch (e: Exception) {
                ratchets.forget(theirKey)
                throw e
            }
        }
    }

    // ------------------------------------------------------------------ groups (protocol 0.6, section 3)

    private inner class GroupHost : Groups.Host {
        override val myName get() = id.username
        override val myMailbox get() = nostr.publicKey
        override val readReceipts get() = id.readReceipts
        override fun send(o: Groups.Out) = bg { sendGroupOut(o) }
        override fun drop(env: String) = outbox.drop(env)
        override fun isContact(k: String) = this@Chat.isContact(k)
        override fun peer(k: String, name: String, mailbox: String) = contacts.peer(k, name, mailbox)
        override fun notice(text: String) { notices.tryEmit(text) }
        // Same flow as private messages, keyed "g:<group id>": the notification opens the group.
        override fun arrived(g: String, title: String, text: String) { arrivals.tryEmit(Triple(title, text, Folders.groupKey(g))) }
        override fun deleteBlob(id: String) { File(photosDir, id).delete() }
        override fun hasM1(k: String) = this@Chat.hasM1(k)
    }

    /** Invites a contact to group [g]. Null when sent, else what to tell the person. */
    fun inviteToGroup(g: String, peerKey: String): String? {
        if (!canGroup(peerKey)) return "${nameOf(peerKey)} needs Muninn or Huginn 0.6 to join groups."
        return groups.invite(g, peerKey)
    }

    fun sendGroupText(g: String, ch: String, text: String, re: String = "") = groups.sendText(g, ch, text.trim().take(MAX), re)

    /**
     * Messenger style: a new group with [keys] (contacts) in it, named after them. Contacts whose app has m1 go
     * straight in; others with groups (g1) get an invite; anyone older is left out. Returns the group (null if
     * none could be made) and what to tell the person.
     */
    fun startGroup(keys: List<String>): Pair<String?, String> {
        val people = keys.distinct().mapNotNull { contacts.contact(it) }
        if (people.isEmpty()) return null to "Pick at least one contact."
        // Cut to the 40 characters a group name may have (by code points, in Groups.create).
        val name = (listOf(id.username) + people.map { it.shown }).joinToString(", ")
        val g = groups.create(name) ?: return null to "That group couldn't be made."
        var invited = 0
        val left = mutableListOf<String>()
        for (c in people) {
            when {
                Rich.CAP in c.caps -> if (groups.addDirect(g, c.chatKey, c.name, c.mailbox ?: "") != null) left += c.shown
                "g1" in c.caps -> if (groups.invite(g, c.chatKey) == null) invited++ else left += c.shown
                else -> { probeCaps(c.chatKey); left += c.shown }
            }
        }
        val parts = buildList {
            if (invited > 0) add("$invited invited (they join once they accept)")
            if (left.isNotEmpty()) add("${left.joinToString(", ")} need${if (left.size == 1) "s" else ""} a newer Muninn or Huginn")
        }
        return g to (if (parts.isEmpty()) "Group made. Rename it any time in its settings." else parts.joinToString(". ") + ".")
    }

    fun sendGroupPhoto(g: String, ch: String, jpeg: ByteArray) = sendGroupBlob(g, ch, jpeg, 0)
    fun sendGroupVoice(g: String, ch: String, aac: ByteArray, ms: Int) = sendGroupBlob(g, ch, aac, ms.coerceAtLeast(1))

    private fun sendGroupBlob(g: String, ch: String, bytes: ByteArray, voiceMs: Int) {
        val blob = "g-" + UUID.randomUUID().toString()
        vault.writeBytes(File(photosDir, blob), bytes)
        if (!groups.sendBlob(g, ch, blob, voiceMs)) File(photosDir, blob).delete()
    }

    /** Leaves the group or, once out of it, deletes it here; its folder entries go too. */
    fun deleteGroup(g: String): Boolean = groups.delete(g).also { if (it) folders.forget(Folders.groupKey(g)) }

    /** Seals one group envelope (or a photo's pieces) for its member and sends it on every route. */
    private fun sendGroupOut(o: Groups.Out) {
        val c = contacts.get(o.to) ?: return
        // A 0.5 app answers 400 to group rooms: only ever to someone whose app said g1 (group peers always can).
        if (!c.groupOnly && "g1" !in c.caps) { probeCaps(o.to); return }
        val bodies = (if (!o.retry) listOf(envelope(o.room, o.to, o.plain, o.env).toString())
            else outbox.getOrMake(o.env) { if (o.room == "gphoto") groupPieces(o) else listOf(envelope(o.room, o.to, o.plain, o.env).toString()) }) ?: return
        spread(o.to, bodies, o.room == "gphoto", o.tries, c.mailbox)
    }

    /** A group photo or voice message: the 0.5 photo pieces plus g, ch and gid in the clear (3.9). */
    private fun groupPieces(o: Groups.Out): List<String>? {
        val bytes = photo(o.photo) ?: return null
        val parts = Photos.split(bytes)
        return seal("gphoto", o.to, parts.mapIndexed { i, part ->
            ChatWire.base("gphoto", id.publicKey, id.username, id.fingerprint, nostr.publicKey, o.env).put("i", i).put("n", parts.size)
                .put("g", o.g).put("ch", o.ch).put("gid", o.id)
                .apply { if (o.voice > 0) put("kind", "voice").put("ms", o.voice) } to part
        }).map { it.toString() }
    }

    /** Every route at once. A photo goes over the internet only when nothing close by took it (or on a later try). */
    private fun spread(k: String, bodies: List<String>, big: Boolean, tries: Int, mailbox: String?) {
        var local = false
        close.endpointFor(k)?.let { ep -> bodies.forEach { close.send(ep, JSONObject(it).put("t", "msg")) }; local = true }
        if (ble.peers.value.any { it.chatKey == k } && bodies.all { ble.send(k, it) }) local = true
        net.devices.value.firstOrNull { it.chatKey == k }?.let { d -> local = true; bg { bodies.forEach { net.post(d, PATH, it) } } }
        if (!big || ((!local || tries >= 2) && bodies.size <= Photos.INTERNET_PIECES)) internet(k, bodies, mailbox)
    }

    private fun receiveGroup(o: JSONObject, room: String, fromKey: String, name: String, mailbox: String?): Pair<Int, String> {
        val sentId = o.optString("id")
        val dup = { duplicate(room, fromKey, sentId, mailbox) }
        dup()?.let { return it }
        // Who may send what (contacts, members, a join with a live token) is decided by Groups once it's open.
        return opened(o, fromKey, dup) { plain ->
            val p = try { JSONObject(String(plain, Charsets.UTF_8)) } catch (e: Exception) { return@opened 400 to "{}" }
            if (!groupRate(room, fromKey)) return@opened 429 to "{}"
            val r = groups.receive(room, fromKey, name, mailbox, p)
            if (r == 200 && room in ChatWire.SEEN_ROOMS && sentId.isNotEmpty()) seen.add("$fromKey|$sentId", if (room == "group") p.optString("g") else "")
            r to "{}"
        }
    }

    /** gmsg: 5 in 10 s per sender, like private messages. group: 20 in 10 s. greceipt: not limited (2.4). */
    private fun groupRate(room: String, k: String): Boolean {
        val (map, max) = when (room) { "gmsg" -> recent to 5; "group", "tvconfig", "mx" -> groupTimes to 20; "call" -> callTimes to 30; "live" -> liveTimes to 30; else -> return true }
        val now = System.currentTimeMillis()
        if (map.size > 500) map.clear()
        val q = map.getOrPut(k) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
            if (q.size >= max) return false
            q.addLast(now)
        }
        return true
    }

    private fun receiveGroupPiece(o: JSONObject, fromKey: String, name: String): Pair<Int, String> {
        val g = o.optString("g"); val ch = o.optString("ch"); val gid = o.optString("gid"); val env = o.optString("id")
        val n = o.optInt("n"); val i = o.optInt("i", -1)
        if (!Proto06.isLogicalId(gid) || env != Proto06.envelopeId(gid, id.publicKey) || n < 1 || n > Photos.MAX_PIECES || i !in 0 until n) return 400 to "{}"
        if (!groups.isMember(g, fromKey)) return 403 to "{}"
        val key = "g|$fromKey|$gid"
        val dup = {
            if (groups.knownEnvelope(env) != null) { groups.ack(fromKey, g, listOf(gid), "delivered"); 200 to "{}" }
            else if (incoming[key]?.let { it.parts.size == n && it.parts[i] != null } == true) 200 to "{}"
            else null
        }
        dup()?.let { return it }
        val now = System.currentTimeMillis()
        val q = pieceTimes.getOrPut(fromKey) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= 1000) return 429 to "{}"
            q.addLast(now)
        }
        if (incoming.size > 20 && !incoming.containsKey(key)) return 429 to "{}"
        return opened(o, fromKey, dup) { part ->
            val inc = incoming.getOrPut(key) { Incoming(fromKey, arrayOfNulls(n), System.currentTimeMillis()) }
            if (inc.from != fromKey || inc.parts.size != n) return@opened 400 to "{}"
            synchronized(inc) {
                inc.parts[i] = part
                if (inc.parts.any { it == null }) return@opened 200 to "{}"
                incoming.remove(key)
            }
            val voiceMs = if (o.optString("kind") == "voice") o.optInt("ms").coerceIn(1, Voice.MAX_MS) else 0
            val joined = inc.parts.map { it!! }
            val bytes = (if (voiceMs > 0) Voice.check(joined) else Photos.join(joined)) ?: return@opened 400 to "{}"
            val blob = "g-$gid"
            if (groups.knownEnvelope(env) == null) {
                vault.writeBytes(File(photosDir, blob), bytes)
                if (!groups.receivedBlob(fromKey, name, g, ch, gid, blob, voiceMs) && groups.knownEnvelope(env) == null) File(photosDir, blob).delete()
            }
            200 to "{}"
        }
    }

    // ------------------------------------------------------------------ keeping

    fun mute(peerKey: String, on: Boolean) {
        id.setMuted(peerKey, on)
        if (on) synchronized(lock) { _nearby.update { l -> l.filterNot { it.peerKey == peerKey && !it.mine } } }
    }

    fun clearNearby() = synchronized(lock) { _nearby.value = emptyList(); nearbyStore.delete() }

    fun deleteConversation(peerKey: String) {
        edit { map ->
            val gone = map.remove(peerKey) ?: return@edit false
            forget(gone.map { it.id })
            gone.forEach { outbox.drop(it.id); outbox.drop(UNSEND + it.id) }
            gone.forEach { if (it.photo.isNotEmpty()) File(photosDir, it.photo).delete() }
            true
        }
    }

    /** Removes messages older than the person's "Delete messages after" choice. */
    fun prune() {
        val age = id.deleteAfter
        if (age <= 0) return
        val cutoff = System.currentTimeMillis() - age
        groups.pruneOlder(cutoff)
        synchronized(lock) {
            val n = _nearby.value.filter { it.time >= cutoff }
            if (n.size != _nearby.value.size) { _nearby.value = n; if (id.keepNearbyHistory) saveNearby() }
        }
        edit { map ->
            var changed = false
            for (k in map.keys.toList()) {
                val (old, keep) = map[k]!!.partition { it.time < cutoff }
                if (old.isEmpty()) continue
                old.forEach { if (it.photo.isNotEmpty()) File(photosDir, it.photo).delete() }
                map[k] = keep; changed = true
            }
            changed
        }
    }

    /** Erases every conversation on this phone. */
    fun deleteAll() {
        synchronized(lock) {
            _private.value = emptyMap(); _nearby.value = emptyList()
            groups.clear()
            photosDir.deleteRecursively()
            outbox.clear()
            mxOut.clear(); mxStore.delete()
            save(); nearbyStore.delete()
        }
    }

    /**
     * Kids' TV storage cap: keeps the newest [keep] messages per person, drops voice messages older
     * than [voiceBefore] (ms since the epoch), then the oldest photos and voice messages until the stored
     * ones take at most [maxBlobBytes]. Photos bigger than [shrinkOver] bytes go through [shrink] (null
     * keeps them as they are). Returns the messages removed.
     */
    fun trimHistory(keep: Int, voiceBefore: Long, maxBlobBytes: Long, shrinkOver: Int, shrink: (ByteArray) -> ByteArray?): Int {
        var removed = 0
        val gone = ArrayList<String>()
        edit { map ->
            var changed = false
            for (k in map.keys.toList()) {
                val list = map[k]!!
                val (old, rest) = list.partition { it.voice > 0 && it.time < voiceBefore }
                val kept = rest.takeLast(keep)
                val drop = old + rest.dropLast(keep)
                if (drop.isEmpty()) continue
                drop.forEach { if (it.photo.isNotEmpty()) File(photosDir, it.photo).delete(); gone += it.id }
                map[k] = kept; changed = true; removed += drop.size
            }
            // Oldest blobs first until they fit.
            val blobs = map.values.flatten().filter { it.photo.isNotEmpty() }.sortedBy { it.time }
            var total = blobs.sumOf { File(photosDir, it.photo).length() }
            for (m in blobs) {
                if (total <= maxBlobBytes) break
                total -= File(photosDir, m.photo).length()
                File(photosDir, m.photo).delete(); gone += m.id
                map[m.peerKey] = map[m.peerKey].orEmpty().filterNot { it.id == m.id }
                changed = true; removed++
            }
            changed
        }
        if (gone.isNotEmpty()) { forget(gone); gone.forEach { outbox.drop(it); outbox.drop(UNSEND + it); tries.remove(it) } }
        // Big photos made smaller, outside the lock (decoding takes a moment).
        for (m in _private.value.values.flatten()) {
            if (m.photo.isEmpty() || m.voice > 0) continue
            val f = File(photosDir, m.photo)
            if (f.length() <= shrinkOver) continue
            val bytes = try { photo(m.photo) } catch (e: Exception) { null } ?: continue
            val small = try { shrink(bytes) } catch (e: Throwable) { null } ?: continue
            if (small.size < bytes.size) try { vault.writeBytes(f, small) } catch (_: Exception) {}
        }
        return removed
    }

    private fun addNearby(m: Msg) = synchronized(lock) {
        if (_nearby.value.any { it.id == m.id }) return@synchronized
        _nearby.value = (_nearby.value + m).takeLast(300)
        if (id.keepNearbyHistory) saveNearby()
    }

    /** False if it's already there or was deleted here: copies from other routes are dropped. */
    private fun addPrivate(peer: String, m: Msg): Boolean = edit { map ->
        val list = map[peer].orEmpty()
        if (deleted.containsKey(m.id) || list.any { it.id == m.id }) return@edit false
        map[peer] = (list + m).takeLast(1000)
        true
    }

    private fun replace(peer: String, m: Msg) = update(peer, m.id) { m }

    private fun toJson(list: List<Msg>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("peer", it.peerKey).put("name", it.name).put("text", it.text).put("mine", it.mine)
            .put("time", it.time).put("failed", it.failed).put("via", it.via).put("state", it.state).put("photo", it.photo).put("unsent", it.unsent).put("voice", it.voice)
            .apply { if (it.re.isNotEmpty()) put("re", it.re); if (it.reactions.isNotEmpty()) put("rx", Rich.reactionsToJson(it.reactions)); if (it.edited > 0) put("ed", it.edited) }) }
    }
    private fun fromJson(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map {
        Msg(it.getString("id"), it.getString("peer"), it.getString("name"), it.getString("text"), it.getBoolean("mine"), it.getLong("time"),
            it.optBoolean("failed"), it.optString("via"), it.optString("state"), it.optString("photo"), it.optString("unsent"), it.optInt("voice"),
            re = it.optString("re"), reactions = Rich.reactionsFromJson(it.optJSONObject("rx")), edited = it.optLong("ed"))
    }

    private fun save() = synchronized(lock) {
        val o = JSONObject(); _private.value.forEach { (k, v) -> o.put(k, toJson(v)) }
        vault.write(store, o.toString())
    }
    private fun resendKeeps() {
        unsentKeeps.toList().forEach { (k, body) ->
            val mailbox = contacts.get(k)?.mailbox ?: _met.value[k]?.mailbox
            // The same sealed "yes" as the first time, not a new one.
            if (deliver(k, JSONObject(body), mailbox)) unsentKeeps.remove(k)
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
            vault.read(invitesStore)?.let { text -> val o = JSONObject(text); o.keys().forEach { invites[it] = o.optLong(it) } }
            vault.read(metStore)?.let { text ->
                val o = JSONObject(text)
                _met.value = o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let {
                    Met(it.optString("n"), it.optString("p").ifEmpty { null }, it.optBoolean("i"), it.optBoolean("t"), it.optBoolean("d"), it.optBoolean("v")) } }
            }
            vault.read(deletedStore)?.let { text -> val o = JSONObject(text); o.keys().forEach { deleted[it] = o.optLong(it) } }
            vault.read(mxStore)?.let { text -> val o = JSONObject(text); o.keys().forEach { e -> o.getJSONObject(e).let { mxOut[e] = it.getString("k") to it.optLong("t") } } }
            if (id.keepNearbyHistory) vault.read(nearbyStore)?.let { _nearby.value = fromJson(JSONArray(it)) }
            prune()
        } catch (_: Exception) {}
    }

    companion object {
        const val PATH = "/api/huginn/v1/message"
        const val MAX = 2000
        const val KEEP_AFTER = 10
        const val INVITE_MS = 60 * 60_000L
        const val DAY = ChatWire.DAY
        /** Outbox key for a message's unsend envelope. */
        private const val UNSEND = "unsend:"
        /** Wait before each new try: 5 s, 10 s, 20 s, 1 min, then every 5 min. */
        val RETRY = longArrayOf(5_000, 10_000, 20_000, 60_000, 300_000)
        private val GROUP_ROOMS = setOf("group", "gmsg", "greceipt")
    }
}
