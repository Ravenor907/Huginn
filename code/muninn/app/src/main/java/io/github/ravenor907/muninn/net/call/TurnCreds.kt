package io.github.ravenor907.muninn.net.call

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * TURN logins from the relay Worker (shared/docs/relay.md), fetched over HTTPS. Cached in memory until 5 minutes before they expire; after a 429 nothing is asked until
 * the next UTC day, after a 5xx not for 60 s. The credential is never logged.
 */
object TurnCreds {
    data class Login(val urls: List<String>, val username: String, val credential: String, val expiresMs: Long) {
        fun usable(nowMs: Long) = nowMs < expiresMs - 300_000
        override fun toString() = "Login(${urls.size} urls)"
    }

    /** The request body: {"id", "t", "sig"} with sig = hex HMAC-SHA256(APP_SECRET, id + "." + t). */
    fun body(identityDer: ByteArray, tSec: Long, secret: String): String {
        val id = CallCrypto.hex(CallCrypto.sha256(identityDer))
        val sig = CallCrypto.hex(CallCrypto.hmac256(secret.toByteArray(Charsets.UTF_8), "$id.$tSec".toByteArray(Charsets.UTF_8)))
        return JSONObject().put("id", id).put("t", tSec).put("sig", sig).toString()
    }

    /** Reads a 200 answer; null if anything is missing. Only turn: and turns: URLs are kept. */
    fun parse(text: String, nowMs: Long): Login? = try {
        val o = JSONObject(text)
        val a = o.getJSONArray("urls")
        val urls = (0 until a.length()).mapNotNull { a.opt(it) as? String }.filter { it.startsWith("turn:") || it.startsWith("turns:") }
        val user = o.getString("username"); val pass = o.getString("credential")
        val ttl = o.optLong("ttl", 0)
        if (urls.isEmpty() || user.isEmpty() || pass.isEmpty() || ttl <= 0) null else Login(urls, user, pass, nowMs + ttl * 1000)
    } catch (e: Exception) { null }

    @Volatile private var cached: Login? = null
    @Volatile private var notBefore = 0L

    /** Why the last fetch failed, for the call screen ("" when it worked). Never a credential or an address. */
    @Volatile var lastProblem = ""; private set

    fun forget() { cached = null }

    /**
     * A usable login, from the cache or the Worker. Blocks (call it off the main thread). [post] does the
     * HTTPS POST and returns (status, body) or null if it couldn't connect.
     */
    @Synchronized
    fun get(identityDer: ByteArray, post: (url: String, body: String) -> Pair<Int, String>?, nowMs: Long = System.currentTimeMillis()): Login? {
        cached?.let { if (it.usable(nowMs)) return it }
        if (!RelayConfig.configured) { lastProblem = "The internet relay isn't set up yet"; return null }
        if (nowMs < notBefore) { lastProblem = "The internet relay is resting"; return null }
        val r = post(RelayConfig.WORKER_URL.trimEnd('/') + "/turn", body(identityDer, nowMs / 1000, RelayConfig.APP_SECRET))
        if (r == null) { lastProblem = "Couldn't reach the internet relay"; return null }
        val (status, text) = r
        when {
            status == 200 -> parse(text, System.currentTimeMillis())?.let { cached = it; lastProblem = ""; return it }
            status == 429 -> notBefore = nextUtcDayMs(nowMs)
            status >= 500 -> notBefore = nowMs + 60_000
        }
        lastProblem = when (status) {
            401 -> "The phone's clock looks wrong, so the internet relay said no"
            429 -> "The internet relay is used up for today"
            else -> "The internet relay didn't answer properly"
        }
        return null
    }

    fun nextUtcDayMs(nowMs: Long): Long {
        val day = java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
        return LocalDate.from(day).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
