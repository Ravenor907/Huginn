package io.github.ravenor907.muninn.tv

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Canonical JSON (protocol 0.6, section 2.1) and signed objects (2.2), for the kids' TV.
 * Kept free of Android so the unit tests run it against tests/proto06-vectors.json.
 *
 * Objects: members sorted by key (ASCII), no spaces. Arrays keep their order. Strings: raw UTF-8 with
 * only `\` and `"` escaped; control characters are refused. Integers 0 <= n < 2^53. true, false. No null,
 * no fractions.
 */
object Canon {
    private const val LIMIT = 1L shl 53

    fun of(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> {
                val n = (v as Number).toLong()
                require(n in 0 until LIMIT) { "integer out of range" }
                sb.append(n)
            }
            is BigInteger -> {
                require(v.signum() >= 0 && v.bitLength() <= 53) { "integer out of range" }
                sb.append(v.toString())
            }
            is String -> {
                text(v)
                sb.append('"')
                for (ch in v) { if (ch == '\\' || ch == '"') sb.append('\\'); sb.append(ch) }
                sb.append('"')
            }
            is JSONArray -> {
                sb.append('[')
                for (i in 0 until v.length()) { if (i > 0) sb.append(','); write(sb, v.get(i)) }
                sb.append(']')
            }
            is JSONObject -> {
                val keys = keys(v).sorted()
                for (k in keys) require(k.all { it.code < 128 }) { "non-ASCII key" }
                sb.append('{')
                keys.forEachIndexed { i, k -> if (i > 0) sb.append(','); write(sb, k); sb.append(':'); write(sb, v.get(k)) }
                sb.append('}')
            }
            else -> throw IllegalArgumentException("type not allowed")
        }
    }

    /** A text field: no control characters (U+0000..U+001F, U+007F), at most [limit] characters (code points). */
    fun text(s: Any?, limit: Int = -1): String {
        require(s is String) { "not text" }
        require(s.none { it.code < 0x20 || it.code == 0x7F }) { "control character" }
        require(limit < 0 || s.codePointCount(0, s.length) <= limit) { "too long" }
        return s
    }

    fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    fun isInt(v: Any?): Boolean = v is Int || v is Long || v is Short || v is Byte || v is BigInteger

    fun unsigned(o: JSONObject): JSONObject = JSONObject().also { u -> keys(o).forEach { if (it != "sig") u.put(it, o.get(it)) } }

    fun signedBytes(domain: String, o: JSONObject): ByteArray = (domain + "\n" + of(unsigned(o))).toByteArray(Charsets.UTF_8)

    /** Lowercase hex SHA-256 of the canonical form with "sig" included. */
    fun hash(o: JSONObject): String =
        MessageDigest.getInstance("SHA-256").digest(of(o).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** A copy of [o] with "sig" (base64 DER ECDSA P-256 SHA-256) set. */
    fun sign(priv: PrivateKey, domain: String, o: JSONObject): JSONObject {
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(priv); update(signedBytes(domain, o)) }.sign()
        return unsigned(o).put("sig", Base64.getEncoder().encodeToString(s))
    }

    fun verify(pubB64: String, domain: String, o: JSONObject): Boolean = try {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
        val sig = Base64.getDecoder().decode(o.getString("sig"))
        Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(signedBytes(domain, o)) }.verify(sig)
    } catch (e: Exception) { false }
}

/** A parent-defined button on the TV. [id]: 8 random bytes, base64url (11 characters). */
data class Quick(val id: String, val text: String)

/** Someone the TV may talk to: chat key, name shown on the TV, Nostr mailbox (hex, or empty). */
data class TvPerson(val k: String, val n: String, val p: String)

/** A checked config, read into plain values. [raw] is the signed object exactly as it came. */
data class TvConfig(
    val tv: String, val v: Long, val by: String, val parents: List<String>, val quick: List<Quick>,
    val contacts: List<TvPerson>, val dictation: Boolean, val ts: Long, val raw: JSONObject,
    /** k2 (7.5): calls with the people on the list (absent: on), and the microphone level 0..100 (absent: 50). */
    val calls: Boolean = true, val mic: Int = TvRules.DEFAULT_MIC,
)

/** The k2 fields (7.5), only put in a config for a TV whose app said "k2". */
data class TvExtras(val calls: Boolean = true, val mic: Int = TvRules.DEFAULT_MIC)

/**
 * The kids' TV rules (protocol 0.6, section 7): what a well-formed config is, when a TV takes a new
 * config or a disconnect, and how a parent's app builds them. Matches check_tvconfig, accept_tvconfig and
 * accept_disconnect in tools/proto06_ref.py, reasons included.
 */
object TvRules {
    const val CONFIG = "huginn-muninn tvconfig v1"
    const val DISCONNECT = "huginn-muninn tvdisconnect v1"
    const val MAX_QUICK = 24
    const val MAX_QUICK_TEXT = 80
    const val MAX_CONTACTS = 50
    const val MAX_NAME = 40
    const val MAX_BYTES = 16 * 1024
    const val DEFAULT_MIC = 50
    const val MAX_MIC = 100
    val DEFAULT_QUICK = listOf("I need you", "Please come here", "I'm hungry", "I love you")

    private val FIELDS = setOf("tv", "v", "by", "parents", "quick", "contacts", "dictation", "ts", "sig")
    /** k2 (7.5): may be there or not; a TV without k2 never gets a config that has them. */
    private val OPTIONAL = setOf("calls", "mic")
    private val DISCONNECT_FIELDS = setOf("tv", "v", "by", "ts", "sig")
    private val random = SecureRandom()

    /** Throws IllegalArgumentException with the reference's reason if [c] isn't a well-formed config. */
    fun check(c: JSONObject) {
        val ks0 = Canon.keys(c)
        require(ks0.containsAll(FIELDS) && (ks0 - FIELDS).all { it in OPTIONAL }) { "fields" }
        if (c.has("calls")) require(c.get("calls") is Boolean) { "calls" }
        if (c.has("mic")) { val m = c.get("mic"); require(Canon.isInt(m) && (m as Number).toLong() in 0..MAX_MIC.toLong()) { "mic" } }
        val v = c.get("v")
        require(Canon.isInt(v) && (v as Number).toLong() >= 1) { "version" }
        val parents = strings(c.get("parents"), "parents")
        val by = c.get("by")
        require(parents.isNotEmpty() && parents == parents.distinct().sorted() && by is String && by in parents) { "parents" }
        require(c.get("dictation") is Boolean) { "dictation" }
        val quick = c.get("quick") as? JSONArray ?: throw IllegalArgumentException("quick fields")
        require(quick.length() <= MAX_QUICK) { "too many quick messages" }
        val qs = (0 until quick.length()).map { quick.get(it) as? JSONObject ?: throw IllegalArgumentException("quick fields") }
        val ids = qs.map { it.opt("id") }
        require(ids.distinct().size == ids.size) { "quick ids" }
        for (q in qs) {
            require(Canon.keys(q) == setOf("id", "text") && quickIdOk(q.get("id"))) { "quick fields" }
            Canon.text(q.get("text"), MAX_QUICK_TEXT)
            require(q.getString("text").isNotBlank()) { "empty quick message" }
        }
        val contacts = c.get("contacts") as? JSONArray ?: throw IllegalArgumentException("contacts")
        val cs = (0 until contacts.length()).map { contacts.get(it) as? JSONObject ?: throw IllegalArgumentException("contact fields") }
        val ks = cs.map { it.opt("k") as? String ?: throw IllegalArgumentException("contacts") }
        require(ks == ks.distinct().sorted() && ks.size <= MAX_CONTACTS) { "contacts" }
        for (x in cs) {
            require(Canon.keys(x) == setOf("k", "n", "p")) { "contact fields" }
            Canon.text(x.get("n"), MAX_NAME)
        }
        require(Canon.of(c).toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "too big" }
    }

    private fun strings(v: Any?, why: String): List<String> {
        val a = v as? JSONArray ?: throw IllegalArgumentException(why)
        return (0 until a.length()).map { a.get(it) as? String ?: throw IllegalArgumentException(why) }
    }

    private fun quickIdOk(id: Any?): Boolean = id is String && try { Base64.getUrlDecoder().decode(id).size == 8 } catch (e: Exception) { false }

    /**
     * Whether a TV with chat key [tvKey], current parent keys [parents] and current config [cur] takes [new].
     * Returns (accepted, reason), the reasons worded like the reference.
     */
    fun accept(tvKey: String, parents: Collection<String>, cur: JSONObject?, new: JSONObject): Pair<Boolean, String> {
        try { check(new) } catch (e: IllegalArgumentException) { return false to "malformed: ${e.message}" }
        if (new.opt("tv") != tvKey) return false to "for another TV"
        if (new.getString("by") !in parents) return false to "not from a parent"
        if (!Canon.verify(new.getString("by"), CONFIG, new)) return false to "bad signature"
        if (cur == null || new.getLong("v") > cur.getLong("v")) return true to "newer"
        if (new.getLong("v") == cur.getLong("v") && Canon.hash(new) < Canon.hash(cur)) return true to "same version, lower hash"
        return false to "not newer"
    }

    /** Whether a TV at config version [curV] with parents [parents] takes the disconnect [d]. */
    fun acceptDisconnect(tvKey: String, parents: Collection<String>, curV: Long, d: JSONObject): Pair<Boolean, String> {
        if (Canon.keys(d) != DISCONNECT_FIELDS) return false to "fields"
        if (d.opt("tv") != tvKey) return false to "for another TV"
        val by = d.opt("by") as? String ?: return false to "not from a parent"
        if (by !in parents) return false to "not from a parent"
        val v = d.opt("v")
        if (!Canon.isInt(v) || (v as Number).toLong() != curV) return false to "not for the current config"
        if (!Canon.verify(by, DISCONNECT, d)) return false to "bad signature"
        return true to "ok"
    }

    /** Reads a config that passed [check]. */
    fun parse(c: JSONObject): TvConfig {
        val q = c.getJSONArray("quick")
        val p = c.getJSONArray("contacts")
        val ps = c.getJSONArray("parents")
        return TvConfig(
            c.getString("tv"), c.getLong("v"), c.getString("by"), (0 until ps.length()).map { ps.getString(it) },
            (0 until q.length()).map { q.getJSONObject(it).let { o -> Quick(o.getString("id"), o.getString("text")) } },
            (0 until p.length()).map { p.getJSONObject(it).let { o -> TvPerson(o.getString("k"), o.getString("n"), o.getString("p")) } },
            c.getBoolean("dictation"), c.getLong("ts"), c,
            c.optBoolean("calls", true), if (c.has("mic")) c.getInt("mic") else DEFAULT_MIC,
        )
    }

    /** A signed config from a parent's app. Sorts [parents] and [contacts] the way [check] wants them. */
    fun config(priv: PrivateKey, tv: String, v: Long, by: String, parents: Collection<String>, quick: List<Quick>,
               contacts: List<TvPerson>, dictation: Boolean, ts: Long, extras: TvExtras? = null): JSONObject {
        val o = JSONObject().put("tv", tv).put("v", v).put("by", by)
            .put("parents", JSONArray().apply { (parents + by).distinct().sorted().forEach { put(it) } })
            .put("quick", JSONArray().apply { quick.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) } })
            .put("contacts", JSONArray().apply { contacts.distinctBy { it.k }.sortedBy { it.k }.forEach { put(JSONObject().put("k", it.k).put("n", it.n).put("p", it.p)) } })
            .put("dictation", dictation).put("ts", ts)
        extras?.let { o.put("calls", it.calls).put("mic", it.mic.coerceIn(0, MAX_MIC)) }
        return Canon.sign(priv, CONFIG, o)
    }

    fun disconnect(priv: PrivateKey, tv: String, v: Long, by: String, ts: Long): JSONObject =
        Canon.sign(priv, DISCONNECT, JSONObject().put("tv", tv).put("v", v).put("by", by).put("ts", ts))

    fun quickId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8).also { random.nextBytes(it) })

    /** Text a person typed, made safe for a config: no control characters, trimmed, at most [max] characters. */
    fun clean(s: String, max: Int): String {
        val t = s.filter { it.code >= 0x20 && it.code != 0x7F }.trim()
        return if (t.codePointCount(0, t.length) <= max) t else t.substring(0, t.offsetByCodePoints(0, max)).trim()
    }

    // ---- the plaintexts in room "tvconfig"
    fun configMessage(cfg: JSONObject): String = JSONObject().put("t", "config").put("cfg", cfg).toString()
    /** [app]: the TV's Muninn version (k2, so a parent's phone can show it and offer Update TV). */
    fun appliedMessage(v: Long, app: String? = null): String =
        JSONObject().put("t", "applied").put("v", v).apply { if (!app.isNullOrEmpty()) put("app", app) }.toString()
    /** k2: a parent asks the TV to look for a new Muninn now and install it. */
    fun updateMessage(): String = JSONObject().put("t", "update").toString()
    /** t1 (7.6): a parent hands someone they invited the TV's contact code, so their app adds the TV. */
    fun cardMessage(code: String): String = JSONObject().put("t", "card").put("code", code).toString()
    fun disconnectMessage(d: JSONObject): String = JSONObject().put("t", "disconnect").put("d", d).toString()

    // ---- k3 (7.7): the TV's microphone level set on the TV, its storage, and Clear history
    /** TV to parents: the level in use and the bytes Muninn takes on the TV; [ts] when the level last changed (TV clock). */
    fun statusMessage(mic: Int, used: Long, ts: Long): String =
        JSONObject().put("t", "status").put("mic", mic.coerceIn(0, MAX_MIC)).put("used", used.coerceIn(0L, MAX_INT)).put("ts", ts.coerceIn(0L, MAX_INT)).toString()
    /** Parent to TV: "send me your status". */
    fun usageMessage(): String = JSONObject().put("t", "usage").toString()
    /** Parent to TV: erase the messages, photos, voice messages and caches (pairing and contacts stay). */
    fun clearMessage(): String = JSONObject().put("t", "clear").toString()

    /** A TV's status as (mic, used, ts), or null if it isn't well-formed (7.7). */
    fun status(o: JSONObject): Triple<Int, Long, Long>? {
        if (o.optString("t") != "status") return null
        val mic = o.opt("mic"); val used = o.opt("used"); val ts = o.opt("ts")
        if (!Canon.isInt(mic) || !Canon.isInt(used) || !Canon.isInt(ts)) return null
        val m = (mic as Number).toLong(); val u = (used as Number).toLong(); val t = (ts as Number).toLong()
        if (m !in 0..MAX_MIC.toLong() || u !in 0..MAX_INT || t !in 0..MAX_INT) return null
        return Triple(m.toInt(), u, t)
    }

    private const val MAX_INT = (1L shl 53) - 1
}
