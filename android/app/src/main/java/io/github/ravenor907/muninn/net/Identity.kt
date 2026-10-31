package io.github.ravenor907.muninn.net

import android.content.Context
import android.os.Build
import java.io.File
import java.security.KeyPair
import java.util.UUID
import kotlin.random.Random

/** Who this phone is to other devices, and the person's choices. Kept in SharedPreferences. */
class Identity(context: Context, private val vault: Vault) {
    private val secrets = java.io.File(context.filesDir, "secrets.json")
    private val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)

    init {
        // Link previews are off for new installs (0.95). A phone set up before that keeps them on unless it turned them off.
        if (prefs.contains("fingerprint") && !prefs.contains("linkPreviews")) prefs.edit().putBoolean("linkPreviews", true).apply()
    }

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

    /**
     * Off: hidden from strangers close by. No Nearby room, nobody new can message over Bluetooth or Wi-Fi, and
     * the name isn't sent out (Nearby, Bluetooth, LocalSend). Contacts still reach this phone, so its chat key
     * keeps being announced. File sharing has its own switch.
     */
    var visibleNearby: Boolean
        get() = prefs.getBoolean("visibleNearby", true)
        set(v) = prefs.edit().putBoolean("visibleNearby", v).apply()

    /** The Nearby room is on: its own switch, and only while visible to people nearby. */
    val nearbyRoom: Boolean get() = nearbyChat && visibleNearby

    var keepNearbyHistory: Boolean
        get() = prefs.getBoolean("keepNearby", false)
        set(v) = prefs.edit().putBoolean("keepNearby", v).apply()

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

    /**
     * The call relay's id for this phone: random, made once. Not the chat key, so nobody who knows that key
     * can use up this phone's relay logins, and the relay can't tell who is calling.
     */
    fun relayId(): ByteArray = secret("relayId") {
        Schnorr.hex(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
    }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Stay connected in the background so messages arrive right away (an ongoing, silent notification). */
    var stayConnected: Boolean
        get() = prefs.getBoolean("stayConnected", true)
        set(v) = prefs.edit().putBoolean("stayConnected", v).apply()

    // ---- accessibility (Settings › Accessibility)
    var uiSize: Float
        get() = prefs.getFloat("uiSize", 1f)
        set(v) = prefs.edit().putFloat("uiSize", v).apply()
    var textSize: Float
        get() = prefs.getFloat("textSize", 1f)
        set(v) = prefs.edit().putFloat("textSize", v).apply()
    var highContrast: Boolean
        get() = prefs.getBoolean("highContrast", false)
        set(v) = prefs.edit().putBoolean("highContrast", v).apply()
    var colorBlind: Boolean
        get() = prefs.getBoolean("colorBlind", false)
        set(v) = prefs.edit().putBoolean("colorBlind", v).apply()
    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduceMotion", false)
        set(v) = prefs.edit().putBoolean("reduceMotion", v).apply()

    /** The look: "dark", "black" (true black, kept from the old True black switch), "light" or "metal". */
    var look: String
        get() = prefs.getString("look", null) ?: if (prefs.getBoolean("trueBlack", false)) "black" else "dark"
        set(v) = prefs.edit().putString("look", v).apply()

    /** Where the lock pattern sits on the screen: "top", "middle" or "lower" (easiest one-handed). */
    var patternPos: String
        get() = prefs.getString("patternPos", null)?.takeIf { it in setOf("top", "middle", "lower") } ?: "lower"
        set(v) = prefs.edit().putString("patternPos", v).apply()

    // ---- privacy
    /** Let people see when their messages were read. Delivered always shows. */
    var readReceipts: Boolean
        get() = prefs.getBoolean("readReceipts", true)
        set(v) = prefs.edit().putBoolean("readReceipts", v).apply()
    /** Delete messages older than this (milliseconds); 0 keeps them. */
    var deleteAfter: Long
        get() = prefs.getLong("deleteAfter", 0L)
        set(v) = prefs.edit().putLong("deleteAfter", v).apply()
    /** Active status (cap m1): a green dot for contacts while their app is open. Off sends nothing and shows nobody's. */
    var activeStatus: Boolean
        get() = prefs.getBoolean("activeStatus", true)
        set(v) = prefs.edit().putBoolean("activeStatus", v).apply()
    /** Link previews (cap m2): this phone looks up a link I send and adds its title and picture. Never for links I get. */
    var linkPreviews: Boolean
        get() = prefs.getBoolean("linkPreviews", false)
        set(v) = prefs.edit().putBoolean("linkPreviews", v).apply()
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

    // From before contacts: devices trusted once both people saw the same code, stored as key -> name.
    // Chat moves them into contacts once, then they're dropped from here (they sat in plain settings).
    fun trusted(): Map<String, String> = prefs.getStringSet("trusted", emptySet())!!.mapNotNull {
        val i = it.indexOf('|'); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap()

    fun dropTrusted() { prefs.edit().remove("trusted").apply() }

    fun muted(): Set<String> = prefs.getStringSet("muted", emptySet())!!
    fun setMuted(key: String, on: Boolean) {
        val s = muted().toMutableSet(); if (on) s.add(key) else s.remove(key)
        prefs.edit().putStringSet("muted", s).apply()
    }

    companion object {
        /** What goes out in place of a name while hidden from people nearby. */
        const val HIDDEN_NAME = "Muninn"
    }
}
