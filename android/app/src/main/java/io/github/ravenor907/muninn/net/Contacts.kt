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
 * Nostr mailbox:  MUNINN1:<base64url of {"n":name,"k":chatKey,"p":mailbox}>
 */
class Contacts(context: Context, private val vault: Vault) {
    data class Contact(val chatKey: String, val name: String, val mailbox: String?, val verified: Boolean)

    private val file = File(context.filesDir, "contacts.json")
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Contact>> = _all.asStateFlow()

    fun get(chatKey: String) = _all.value.firstOrNull { it.chatKey == chatKey }

    @Synchronized
    fun put(c: Contact) {
        val old = get(c.chatKey)
        // Never downgrade: a verified contact stays verified, a known mailbox is kept.
        val merged = if (old == null) c else c.copy(verified = c.verified || old.verified, mailbox = c.mailbox ?: old.mailbox,
            name = c.name.ifBlank { old.name })
        _all.value = _all.value.filterNot { it.chatKey == c.chatKey } + merged
        save()
    }

    @Synchronized
    fun remove(chatKey: String) {
        _all.value = _all.value.filterNot { it.chatKey == chatKey }
        save()
    }

    private fun save() {
        val a = JSONArray()
        _all.value.forEach { a.put(JSONObject().put("k", it.chatKey).put("n", it.name).put("p", it.mailbox ?: "").put("v", it.verified)) }
        vault.write(file, a.toString())
    }

    private fun load(): List<Contact> = try {
        val a = JSONArray(vault.read(file) ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Contact(it.getString("k"), it.getString("n"), it.optString("p").ifEmpty { null }, it.optBoolean("v")) }
    } catch (e: Exception) { emptyList() }

    companion object {
        const val PREFIX = "MUNINN1:"
        /** What the QR holds: a page that opens Muninn, or offers the download if it isn't installed.
         *  The code is after "#", which browsers never send to the server. */
        const val PAGE = "https://ravenor907.github.io/Huginn/add/#"
        fun link(code: String) = PAGE + code

        fun code(name: String, chatKey: String, mailbox: String): String {
            val json = JSONObject().put("n", name).put("k", chatKey).put("p", mailbox).toString()
            return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
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
                Contact(key, o.optString("n").take(40).ifBlank { "Someone" }, o.optString("p").takeIf { it.length == 64 }, verified = true)
            } catch (e: Exception) { null }
        }
    }
}
