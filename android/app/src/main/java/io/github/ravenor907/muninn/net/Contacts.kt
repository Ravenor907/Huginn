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
 * The invite is good for an hour: after that a leaked code can't be used to reach someone
 * who isn't already in touch (their phone drops messages without a valid invite).
 */
class Contacts(context: Context, private val vault: Vault) {
    private val file = File(context.filesDir, "contacts.json")
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Contact>> = _all.asStateFlow()

    fun get(chatKey: String) = _all.value.firstOrNull { it.chatKey == chatKey }

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

        /** Only ever my own name and keys: a nickname is never in a code. */
        fun code(name: String, chatKey: String, mailbox: String, invite: String? = null, expires: Long = 0): String =
            ChatWire.code(name, chatKey, mailbox, invite, expires)

        /** When the code's invite runs out (ms), or 0 for older codes without one. */
        fun expires(text: String): Long {
            val i = text.indexOf(PREFIX)
            if (i < 0) return 0
            val body = text.substring(i + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return try { JSONObject(String(java.util.Base64.getUrlDecoder().decode(body))).optLong("x") * 1000 } catch (e: Exception) { 0 }
        }

        /** Finds a contact code anywhere in pasted text (a whole message is fine). */
        fun parse(text: String): Contact? {
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
