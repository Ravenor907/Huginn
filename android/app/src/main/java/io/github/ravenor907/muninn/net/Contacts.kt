package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * People this phone can message from anywhere. A contact is added by meeting nearby (and checking
 * the 6-digit code) or by sharing a contact code, which carries the name, the chat key and the
 * Nostr mailbox:  MUNINN1:<base64url of {"n":name,"k":chatKey,"p":mailbox,"i":invite,"x":expires}>
 * (a 0.6 code may also carry "o", an onion address; it is ignored).
 * The invite is good for an hour: after that a leaked code can't be used to reach someone
 * who isn't already in touch (their phone drops messages without a valid invite).
 */
class Contacts(context: Context, private val vault: Vault) {
    private val file = File(context.filesDir, "contacts.json")
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Contact>> = _all.asStateFlow()

    fun get(chatKey: String) = _all.value.firstOrNull { it.chatKey == chatKey }

    /** A real contact: not a group peer. */
    fun contact(chatKey: String) = get(chatKey)?.takeIf { !it.groupOnly }

    /**
     * Someone in a group with me who isn't a contact: kept so they can be reached (protocol 0.6, 3.7).
     * Only 0.6 apps can join a group, so they speak the ratchet, groups and calls. A contact stays a contact.
     */
    @Synchronized
    fun peer(chatKey: String, name: String, mailbox: String) {
        try { ChatCrypto.publicFrom(chatKey) } catch (e: Exception) { return }
        val old = get(chatKey)
        if (old != null && !old.groupOnly) return
        val mb = mailbox.takeIf { it.length == 64 } ?: old?.mailbox
        val c = Contact(chatKey, name.take(40).ifBlank { old?.name ?: "Someone" }, mb, verified = false, ratchet = true,
            groupOnly = true, caps = setOf("r1", "g1", "c1"))
        if (old == c) return
        _all.value = _all.value.filterNot { it.chatKey == chatKey } + c
        save()
    }

    /** Adds or updates. Never downgrades: a verified contact stays verified, a known mailbox is kept. */
    @Synchronized
    fun put(c: Contact) {
        val old = get(c.chatKey)
        val merged = if (old == null) c else ChatWire.merge(old, c)
        _all.value = _all.value.filterNot { it.chatKey == c.chatKey } + merged
        save()
    }

    /** Stores [c] exactly as given (verify, rename, ratchet on). */
    @Synchronized
    fun set(c: Contact) {
        if (get(c.chatKey) == null) return
        _all.value = _all.value.map { if (it.chatKey == c.chatKey) c else it }
        save()
    }

    /** Their key changed: the contact at [oldKey] becomes [c] (a new key). */
    @Synchronized
    fun replaceKey(oldKey: String, c: Contact) {
        _all.value = _all.value.filterNot { it.chatKey == oldKey || it.chatKey == c.chatKey } + c
        save()
    }

    @Synchronized
    fun rename(chatKey: String, nickname: String) {
        val c = get(chatKey) ?: return
        set(c.copy(nickname = ChatWire.nickname(nickname)))
    }

    @Synchronized
    fun remove(chatKey: String) {
        _all.value = _all.value.filterNot { it.chatKey == chatKey }
        save()
    }

    private fun save() {
        val a = JSONArray()
        _all.value.forEach { a.put(ChatWire.contactToJson(it)) }
        vault.write(file, a.toString())
    }

    private fun load(): List<Contact> = try {
        val a = JSONArray(vault.read(file) ?: return emptyList())
        (0 until a.length()).map { ChatWire.contactFromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    companion object {
        const val PREFIX = ChatWire.PREFIX
        /** What the QR holds: a page that opens Muninn, or offers the download if it isn't installed.
         *  The code is after "#", which browsers never send to the server. */
        const val PAGE = "https://ravenor907.github.io/Huginn-Muninn/add/#"
        fun link(code: String) = PAGE + code

        /**
         * The code in a pasted link or a scanned page address, as well as a bare code. Some apps
         * write the ":" in a link as "%3A": read that the same.
         */
        fun unlink(text: String): String = text.replace(Regex("MUNINN1%3A", RegexOption.IGNORE_CASE), PREFIX)

        /** Only ever my own name and keys: a nickname is never in a code. */
        fun code(name: String, chatKey: String, mailbox: String, invite: String? = null, expires: Long = 0): String =
            ChatWire.code(name, chatKey, mailbox, invite, expires)

        /** When the code's invite runs out (ms), or 0 for older codes without one. */
        fun expires(link: String): Long {
            val text = unlink(link)
            val i = text.indexOf(PREFIX)
            if (i < 0) return 0
            val body = text.substring(i + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return try { JSONObject(String(java.util.Base64.getUrlDecoder().decode(body))).optLong("x") * 1000 } catch (e: Exception) { 0 }
        }

        /** Finds a contact code anywhere in pasted text (a whole message is fine). */
        fun parse(link: String): Contact? {
            val text = unlink(link)
            val i = text.indexOf(PREFIX)
            if (i < 0) return null
            val body = text.substring(i + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return try {
                val o = JSONObject(String(java.util.Base64.getUrlDecoder().decode(body)))
                val key = o.getString("k")
                ChatCrypto.publicFrom(key)   // must be a real key
                // Whoever gave you their code is who they say: the code itself carries their key.
                Contact(key, o.optString("n").take(40).ifBlank { "Someone" }, o.optString("p").takeIf { it.length == 64 }, verified = true,
                    invite = o.optString("i").ifEmpty { null })
            } catch (e: Exception) { null }
        }
    }
}

/**
 * Contact folders (protocol 0.6, section 4): local only, never on the wire. A folder is a name and the
 * chat keys (or "g:" + group id) in it. Family, Work and Kindred are there to start with; all of them can be
 * renamed or deleted, and new ones added. Stored through [write] (the vault), so it goes in the backup too.
 * The chat list shows each folder as a section that folds away; a folder's keys are in the order shown there,
 * and [top] is the order of the chats in no folder (the ones never moved go first, newest first).
 */
class Folders(private val read: () -> String?, private val write: (String) -> Unit) {
    data class Folder(val id: String, val name: String, val keys: List<String> = emptyList(), val collapsed: Boolean = false)

    private val text = try { read() } catch (e: Exception) { null }
    private val _top = MutableStateFlow(loadTop())
    /** The chats in no folder, in the order they were put in (only the ones moved by hand). */
    val top: StateFlow<List<String>> = _top.asStateFlow()
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Folder>> = _all.asStateFlow()

    private fun load(): List<Folder> {
        if (text == null) return DEFAULTS.map { Folder(newId(), it) }.also { save(it, emptyList()) }
        return try {
            val a = JSONObject(text).getJSONArray("folders")
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val k = o.optJSONArray("k")
                Folder(o.getString("id"), o.getString("n"), if (k == null) emptyList() else (0 until k.length()).map { k.getString(it) },
                    o.optBoolean("c"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun loadTop(): List<String> = try {
        val a = JSONObject(text ?: return emptyList()).optJSONArray("top")
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    private fun save(l: List<Folder>, top: List<String>) {
        val a = JSONArray()
        l.forEach { f -> a.put(JSONObject().put("id", f.id).put("n", f.name).put("k", JSONArray().apply { f.keys.forEach { put(it) } })
            .apply { if (f.collapsed) put("c", true) }) }
        try { write(JSONObject().put("folders", a).put("top", JSONArray().apply { top.forEach { put(it) } }).toString()) } catch (_: Exception) {}
    }

    private fun set(l: List<Folder>, top: List<String> = _top.value) { _all.value = l; _top.value = top; save(l, top) }

    /** A new folder; returns its id, or null for an empty name. */
    @Synchronized
    fun add(name: String): String? {
        val n = name.trim().take(MAX_NAME).ifEmpty { return null }
        val f = Folder(newId(), n)
        set(_all.value + f)
        return f.id
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val n = name.trim().take(MAX_NAME).ifEmpty { return }
        set(_all.value.map { if (it.id == id) it.copy(name = n) else it })
    }

    /** Deletes the folder only: the chats in it stay. */
    @Synchronized
    fun delete(id: String) = set(_all.value.filterNot { it.id == id })

    @Synchronized
    fun put(id: String, key: String, on: Boolean) = set(_all.value.map { f ->
        if (f.id != id) f else if (on) f.copy(keys = (f.keys + key).distinct()) else f.copy(keys = f.keys - key)
    })

    /** The folders [key] is in. */
    fun of(key: String) = _all.value.filter { key in it.keys }

    /** A contact or group that's gone leaves every folder. */
    @Synchronized
    fun forget(key: String) {
        if (_all.value.any { key in it.keys } || key in _top.value) set(_all.value.map { it.copy(keys = it.keys - key) }, _top.value - key)
    }

    /** Folds a folder's section away on the chat list, or opens it again. */
    @Synchronized
    fun collapse(id: String, on: Boolean) = set(_all.value.map { if (it.id == id) it.copy(collapsed = on) else it })

    /**
     * Moves [key] from the section [from] to [to] ("" is no folder): it leaves [from] and goes in [to].
     * A chat in other folders too stays in them.
     */
    @Synchronized
    fun move(key: String, from: String, to: String) {
        if (from == to) return
        val l = _all.value.map { f ->
            when (f.id) {
                from -> f.copy(keys = f.keys - key)
                to -> f.copy(keys = (f.keys - key) + key)
                else -> f
            }
        }
        set(l, if (to == NONE) (_top.value - key) + key else _top.value - key)
    }

    /** The section [id] ("" is no folder) now shows [keys] in this order; what isn't shown keeps its place after them. */
    @Synchronized
    fun order(id: String, keys: List<String>) {
        if (id == NONE) set(_all.value, keys.distinct() + _top.value.filter { it !in keys })
        else set(_all.value.map { f -> if (f.id != id) f else f.copy(keys = keys.filter { it in f.keys }.distinct() + f.keys.filter { it !in keys }) })
    }

    companion object {
        val DEFAULTS = listOf("Family", "Work", "Kindred")
        const val MAX_NAME = 24
        /** The section of chats in no folder. */
        const val NONE = ""
        fun groupKey(g: String) = "g:$g"
        private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

        /**
         * A section's chats as shown: [newest] (its chats, newest first) with the ones never moved by hand at the
         * top, then the rest in [order].
         */
        fun arrange(newest: List<String>, order: List<String>): List<String> {
            val placed = order.filter { it in newest }.distinct()
            return newest.filter { it !in placed } + placed
        }

        /** [list] with [key] moved [by] places (up is negative), stopping at either end. */
        fun shift(list: List<String>, key: String, by: Int): List<String> {
            val i = list.indexOf(key)
            if (i < 0) return list
            val to = (i + by).coerceIn(0, list.size - 1)
            if (to == i) return list
            return list.toMutableList().apply { removeAt(i); add(to, key) }
        }

        /** [list] with [key] put just before [before] (at the end when null or not there). */
        fun place(list: List<String>, key: String, before: String?): List<String> {
            val rest = list - key
            val i = if (before == null) -1 else rest.indexOf(before)
            return if (i < 0) rest + key else rest.toMutableList().apply { add(i, key) }
        }
    }
}

/**
 * Contacts in the trash (local only): their key and when they went in. Nothing from them reaches this phone
 * and their chat is hidden but kept, so putting them back brings everything back. After [DAYS] days they're
 * deleted for good. Stored through [write] (the vault), like [Folders].
 */
class Trash(private val read: () -> String?, private val write: (String) -> Unit) {
    private val _all = MutableStateFlow(load())
    /** Key to when it went in the trash (ms). */
    val all: StateFlow<Map<String, Long>> = _all.asStateFlow()

    private fun load(): Map<String, Long> = try {
        val o = JSONObject(read() ?: return emptyMap()).getJSONObject("trash")
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (e: Exception) { emptyMap() }

    private fun set(m: Map<String, Long>) {
        _all.value = m
        try { write(JSONObject().put("trash", JSONObject(m as Map<*, *>)).toString()) } catch (_: Exception) {}
    }

    fun has(key: String) = key in _all.value

    @Synchronized
    fun put(key: String, now: Long) { if (key !in _all.value) set(_all.value + (key to now)) }

    @Synchronized
    fun drop(key: String) { if (key in _all.value) set(_all.value - key) }

    /** Who has been in the trash long enough to go for good. */
    fun expired(now: Long): List<String> = expired(_all.value, now)

    companion object {
        const val DAYS = 30
        const val KEEP_MS = DAYS * 86_400_000L

        fun expired(all: Map<String, Long>, now: Long): List<String> = all.filter { (_, t) -> now - t >= KEEP_MS }.keys.toList()

        /** Whole days left before [since] (when it went in) is deleted for good: at least 1 until it goes. */
        fun daysLeft(since: Long, now: Long): Int = ((KEEP_MS - (now - since) + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(1)
    }
}
