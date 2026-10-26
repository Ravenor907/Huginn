package io.github.ravenor907.muninn.net

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.security.KeyPair
import java.util.UUID
import kotlin.random.Random

/** Who this phone is to other devices, and the person's choices. Kept in SharedPreferences. */
class Identity(context: Context, private val vault: Vault) {
    private val secrets = java.io.File(context.filesDir, "secrets.json")
    private val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)

    /** Random and stable: LocalSend uses it to tell devices apart and to skip itself. */
    val fingerprint: String = prefs.getString("fingerprint", null) ?: UUID.randomUUID().toString().replace("-", "").also {
        prefs.edit().putString("fingerprint", it).apply()
    }

    /** The name LocalSend apps and Huginn show for this phone. */
    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: "Muninn on ${Build.MODEL}"
        set(v) = prefs.edit().putString("deviceName", v.trim().take(40)).apply()

    /** Nearby chat name: random until the person picks one, so nobody is named by default. */
    var username: String
        get() = prefs.getString("username", null) ?: "Raven ${Random.nextInt(1000, 10000)}".also { username = it }
        set(v) = prefs.edit().putString("username", v.trim().take(24)).apply()

    var nearbyChat: Boolean
        get() = prefs.getBoolean("nearbyChat", true)
        set(v) = prefs.edit().putBoolean("nearbyChat", v).apply()

    var keepNearbyHistory: Boolean
        get() = prefs.getBoolean("keepNearby", false)
        set(v) = prefs.edit().putBoolean("keepNearby", v).apply()

    /** Where received files go. */
    var receiveFolder: File
        get() = File(prefs.getString("receiveFolder", null) ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
        set(v) = prefs.edit().putString("receiveFolder", v.absolutePath).apply()

    /** Secret keys, kept only in the encrypted vault (moved there from older versions' settings). */
    private fun secret(name: String, make: () -> String): String {
        val o = vault.read(secrets)?.let { org.json.JSONObject(it) } ?: org.json.JSONObject()
        o.optString(name).ifEmpty { null }?.let { return it }
        val v = prefs.getString(name, null) ?: make()
        o.put(name, v)
        vault.write(secrets, o.toString())
        prefs.edit().remove(name).apply()
        return v
    }

    /** This device's chat key pair, made once. */
    val keys: KeyPair by lazy {
        var pub = prefs.getString("chatPub", null)
        val priv = secret("chatPriv") {
            ChatCrypto.newKeyPair().let { kp -> pub = ChatCrypto.b64(kp.public.encoded); ChatCrypto.b64(kp.private.encoded) }
        }
        prefs.edit().putString("chatPub", pub).apply()
        KeyPair(ChatCrypto.publicFrom(pub!!), ChatCrypto.privateFrom(priv))
    }

    /** The Nostr secret (internet inbox). */
    fun nostrSecret(): String = secret("nostrSecret") { Schnorr.hex(Schnorr.newSecret()) }

    // ---- privacy
    /** Let people see when their messages were read. Delivered always shows. */
    var readReceipts: Boolean
        get() = prefs.getBoolean("readReceipts", true)
        set(v) = prefs.edit().putBoolean("readReceipts", v).apply()
    /** Delete messages older than this (milliseconds); 0 keeps them. */
    var deleteAfter: Long
        get() = prefs.getLong("deleteAfter", 0L)
        set(v) = prefs.edit().putLong("deleteAfter", v).apply()
    /** Notifications say only "New message" unless this is on. */
    var showPreview: Boolean
        get() = prefs.getBoolean("showPreview", false)
        set(v) = prefs.edit().putBoolean("showPreview", v).apply()
    /** Blocks screenshots and hides Muninn's screen in recent apps. */
    var secureScreen: Boolean
        get() = prefs.getBoolean("secureScreen", false)
        set(v) = prefs.edit().putBoolean("secureScreen", v).apply()
    /** With a password: ask again after Muninn has been in the background this long (ms). */
    var lockAfter: Long
        get() = prefs.getLong("lockAfter", 60_000L)
        set(v) = prefs.edit().putLong("lockAfter", v).apply()
    val publicKey: String get() = ChatCrypto.b64(keys.public.encoded)

    // Private chats: a device is trusted once both people saw the same code. Stored as key -> name.
    fun trusted(): Map<String, String> = prefs.getStringSet("trusted", emptySet())!!.mapNotNull {
        val i = it.indexOf('|'); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap()

    fun trust(key: String, name: String) {
        val set = prefs.getStringSet("trusted", emptySet())!!.filterNot { it.startsWith("$key|") }.toMutableSet()
        set.add("$key|$name")
        prefs.edit().putStringSet("trusted", set).apply()
    }

    fun forget(key: String) {
        prefs.edit().putStringSet("trusted", prefs.getStringSet("trusted", emptySet())!!.filterNot { it.startsWith("$key|") }.toSet()).apply()
    }

    fun muted(): Set<String> = prefs.getStringSet("muted", emptySet())!!
    fun setMuted(key: String, on: Boolean) {
        val s = muted().toMutableSet(); if (on) s.add(key) else s.remove(key)
        prefs.edit().putStringSet("muted", s).apply()
    }
}
