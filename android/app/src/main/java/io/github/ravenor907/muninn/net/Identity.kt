package io.github.ravenor907.muninn.net

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.security.KeyPair
import java.util.UUID
import kotlin.random.Random

/** Who this phone is to other devices, and the person's choices. Kept in SharedPreferences. */
class Identity(context: Context) {
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

    var channel: String
        get() = prefs.getString("channel", "testing") ?: "testing"
        set(v) = prefs.edit().putString("channel", v).apply()

    /** Where received files go. */
    var receiveFolder: File
        get() = File(prefs.getString("receiveFolder", null) ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
        set(v) = prefs.edit().putString("receiveFolder", v.absolutePath).apply()

    /** This device's chat key pair, made once. */
    val keys: KeyPair by lazy {
        val priv = prefs.getString("chatPriv", null)
        val pub = prefs.getString("chatPub", null)
        if (priv != null && pub != null) {
            KeyPair(ChatCrypto.publicFrom(pub), ChatCrypto.privateFrom(priv))
        } else {
            ChatCrypto.newKeyPair().also {
                prefs.edit().putString("chatPriv", ChatCrypto.b64(it.private.encoded)).putString("chatPub", ChatCrypto.b64(it.public.encoded)).apply()
            }
        }
    }
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
