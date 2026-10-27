package io.github.ravenor907.muninn.net.call

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The parts of calling that don't need Android, so unit tests cover them: the signaling messages of room
 * "call" (docs/protocol-0.6.md 5.2), the jitter buffer (5.5), the speaking indicator and path liveness (5.6).
 *
 * Owner's rule for internet calls: audio goes only through the TURN relay. So an offer or answer carries only
 * `relay` candidates (the relay's address, never this phone's), and candidates of any other type that a peer
 * sends are ignored: nothing is ever sent straight to a peer's internet address. Two devices on the same
 * Wi-Fi also send to each other directly: the call socket's port goes out twice, as a `lan` candidate and as
 * the top-level `lp` (both apps send both and accept either); the address comes from the Wi-Fi discovery
 * Chat already does, never from the message.
 */
object CallLogic {
    const val OFFER_EVERY_MS = 3_000L
    const val RING_MS = 45_000L
    const val ANSWER_EVERY_MS = 2_000L
    const val ANSWER_FOR_MS = 15_000L
    const val NO_PATH_MS = 15_000L
    const val DROPPED_MS = 20_000L
    const val PATH_DEAD_MS = 4_000L
    const val MAX_CANDS = 8
    val TYPES = setOf("offer", "ring", "answer", "decline", "busy", "cands", "mute", "hangup")
    val WHY = setOf("hangup", "noanswer", "nopath", "dropped", "error")

    // ------------------------------------------------------------------ messages

    fun cands(relay: InetSocketAddress?, lanPort: Int? = null): JSONArray = JSONArray().apply {
        if (relay != null) put(JSONObject().put("t", "relay").put("a", relay.address.hostAddress).put("p", relay.port))
        if (lanPort != null) put(JSONObject().put("t", "lan").put("p", lanPort))
    }

    fun offer(callId: String, eph: String, sig: String, tsMs: Long, relay: InetSocketAddress?, lanPort: Int?, ble: Boolean): JSONObject =
        JSONObject().put("t", "offer").put("call", callId).put("eph", eph).put("sig", sig).put("ts", tsMs)
            .put("cands", cands(relay, lanPort)).put("ble", ble).put("codec", CallCrypto.CODEC).apply { if (lanPort != null) put("lp", lanPort) }

    fun answer(callId: String, eph: String, sig: String, relay: InetSocketAddress?, lanPort: Int?, ble: Boolean): JSONObject =
        JSONObject().put("t", "answer").put("call", callId).put("eph", eph).put("sig", sig)
            .put("cands", cands(relay, lanPort)).put("ble", ble).apply { if (lanPort != null) put("lp", lanPort) }

    fun candsMsg(callId: String, relay: InetSocketAddress?, lanPort: Int?): JSONObject =
        JSONObject().put("t", "cands").put("call", callId).put("cands", cands(relay, lanPort)).apply { if (lanPort != null) put("lp", lanPort) }

    fun simple(t: String, callId: String): JSONObject = JSONObject().put("t", t).put("call", callId)
    fun mute(callId: String, on: Boolean): JSONObject = simple("mute", callId).put("on", on)
    fun hangup(callId: String, why: String): JSONObject = simple("hangup", callId).put("why", why)

    /** A checked incoming message. [relay]: the peer's relayed address, if it sent one. */
    data class Signal(
        val t: String, val call: String, val eph: String = "", val sig: String = "", val ts: Long = 0,
        val relay: InetSocketAddress? = null, val lanPort: Int? = null, val ble: Boolean = false, val codec: String = "",
        val on: Boolean = false, val why: String = "", val hasCands: Boolean = false, val voiceRoom: Boolean = false,
    )

    class Bad(why: String) : IllegalArgumentException(why)

    /**
     * Parses and checks one plaintext of room "call" from [fromKey]. Throws [Bad] when it is malformed or an
     * offer/answer signature fails (5.2: refuse it). Voice room messages (call "-") come back with voiceRoom set.
     */
    fun parse(fromKey: String, text: String): Signal {
        val o = try { JSONObject(text) } catch (e: Exception) { throw Bad("not JSON") }
        val t = o.opt("t") as? String ?: throw Bad("t")
        val call = o.opt("call") as? String ?: throw Bad("call")
        if (call == "-" || o.has("g")) return Signal(t, call, voiceRoom = true)
        if (t !in TYPES) throw Bad("type")
        if (!CallCrypto.isCallId(call)) throw Bad("call id")
        var s = Signal(t, call)
        if (t == "offer" || t == "answer") {
            val eph = o.opt("eph") as? String ?: throw Bad("eph")
            val sig = o.opt("sig") as? String ?: throw Bad("sig")
            if (!CallCrypto.verify(fromKey, t, call, eph, sig)) throw Bad("bad signature")
            s = s.copy(eph = eph, sig = sig, ble = o.optBoolean("ble", false))
            if (t == "offer") s = s.copy(ts = (o.opt("ts") as? Number)?.toLong() ?: throw Bad("ts"), codec = o.optString("codec"))
        }
        if (t == "offer" || t == "answer" || t == "cands") {
            val a = o.optJSONArray("cands")
            if (a != null && a.length() > MAX_CANDS) throw Bad("too many candidates")
            val lp = (o.opt("lp") as? Number)?.toInt()?.takeIf { it in 1..65535 }
            s = s.copy(relay = a?.let(::relayOf), hasCands = a != null, lanPort = lp ?: a?.let(::lanOf))
        }
        if (t == "mute") s = s.copy(on = o.optBoolean("on", false))
        if (t == "hangup") s = s.copy(why = o.optString("why").takeIf { it in WHY } ?: "hangup")
        return s
    }

    /** The first `relay` candidate with a literal IP address (never looked up by name). Others are ignored. */
    fun relayOf(a: JSONArray): InetSocketAddress? {
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            if (c.optString("t") != "relay") continue
            val addr = literal(c.optString("a")) ?: continue
            val p = (c.opt("p") as? Number)?.toInt() ?: continue
            if (p in 1..65535) return InetSocketAddress(addr, p)
        }
        return null
    }

    /** The port of the first `lan` candidate (the peer's call socket on the shared Wi-Fi), or null. */
    fun lanOf(a: JSONArray): Int? {
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            if (c.optString("t") != "lan") continue
            val p = (c.opt("p") as? Number)?.toInt() ?: continue
            if (p in 1..65535) return p
        }
        return null
    }

    /** An IPv4 or IPv6 literal, or null for anything else (a peer's candidate never makes this phone look a name up). */
    fun literal(s: String): InetAddress? {
        val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").find(s)
        if (v4 != null) {
            val b = v4.groupValues.drop(1).map { it.toInt() }
            if (b.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { b[it].toByte() })
        }
        if (s.contains(':') && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' })
            return try { InetAddress.getByName(s) } catch (e: Exception) { null }
        return null
    }

    /** 5.2: an offer is fresh if its ts is at most 60 s old and at most 120 s ahead. */
    fun fresh(tsMs: Long, nowMs: Long) = tsMs >= nowMs - 60_000 && tsMs <= nowMs + 120_000

    // ------------------------------------------------------------------ jitter buffer (5.5)

    /** What to play for one 20 ms slot. */
    sealed class Play {
        class Packet(val data: ByteArray) : Play()
        /** Rebuild this slot from the next packet's in-band FEC. */
        class Fec(val next: ByteArray) : Play()
        /** Nothing for this slot: Opus packet loss concealment (also covers DTX silence). */
        object Conceal : Play()
        /** Nothing has arrived yet (or for a long time): play silence. */
        object Silence : Play()
    }

    /**
     * Plays audio in order by slot (ts / 20). Target delay adapts between 40 and 200 ms (starts at 60) from
     * the jitter seen over the last few seconds. [put] runs on the network thread, [next] on the player.
     */
    class Jitter(private val clock: () -> Long = System::currentTimeMillis) {
        private val slots = java.util.TreeMap<Long, ByteArray>()
        private var play = -1L
        @Volatile var targetMs = 60L; private set
        private val lateness = ArrayDeque<Long>()
        private var lastArrival = 0L

        @Synchronized fun put(ts: Long, data: ByteArray) {
            val slot = (ts + 10) / 20
            val now = clock()
            lastArrival = now
            // Lateness relative to the sender's clock; jitter is its spread, so the offset doesn't matter.
            lateness.addLast(now - slot * 20)
            while (lateness.size > 150) lateness.removeFirst()   // about 3 s of audio
            if (lateness.size >= 10) {
                val sorted = lateness.sorted()
                val spread = sorted[(sorted.size * 95) / 100 - 1] - sorted[0]
                targetMs = (spread + 20).coerceIn(40, 200)
            }
            if (play >= 0 && slot < play) return          // too late to play
            if (slots.size > 50) slots.pollFirstEntry()
            slots[slot] = data
        }

        /** The next 20 ms to play. Call it once per 20 ms of playback. */
        @Synchronized fun next(): Play {
            if (play < 0) {
                val first = slots.firstEntry()?.key ?: return Play.Silence
                // Wait until the buffer holds the target delay.
                val last = slots.lastKey()
                if ((last - first) * 20 < targetMs - 20 && clock() - lastArrival < targetMs) return Play.Silence
                play = first
            }
            val ahead = slots.lastEntry()?.key?.let { (it - play) * 20 } ?: 0
            // Too far behind the newest packet: skip forward (drops a little audio, keeps the delay low).
            if (ahead > targetMs + 80) {
                val skipTo = slots.lastKey() - targetMs / 20
                while (slots.isNotEmpty() && slots.firstKey() < skipTo) slots.pollFirstEntry()
                play = skipTo
            }
            val slot = play++
            slots.remove(slot)?.let { return Play.Packet(it) }
            if (clock() - lastArrival > 1_000 && slots.isEmpty()) return Play.Silence
            slots[slot + 1]?.let { return Play.Fec(it) }
            return Play.Conceal
        }

    }

    // ------------------------------------------------------------------ speaking indicator (5.5)

    /** Speaking while the 20 ms level is above -45 dBFS for 2 frames in a row, held 300 ms after it drops. */
    class Speaking {
        private var loud = 0
        private var until = 0L
        fun feed(pcm: ShortArray, n: Int, nowMs: Long): Boolean {
            if (dbfs(pcm, n) > -45.0) { if (++loud >= 2) until = nowMs + 300 } else loud = 0
            return nowMs < until
        }
    }

    fun dbfs(pcm: ShortArray, n: Int): Double {
        if (n <= 0) return -120.0
        var sum = 0.0
        for (i in 0 until n) { val x = pcm[i] / 32768.0; sum += x * x }
        val rms = sqrt(sum / n)
        return if (rms <= 0) -120.0 else 20 * log10(rms)
    }

    // ------------------------------------------------------------------ paths (5.6)

    /**
     * Where the peer is on the relay (5.6). [advertised] is the relayed address from its offer, answer or
     * cands. Some relays translate addresses between their sites (Cloudflare anchors each allocation at
     * the nearest data center), so the peer's packets can arrive from a different port, or even a
     * different address, than the one it advertised. Media is authenticated end to end, so the first
     * authentic packet from such an address makes it [seen], and media goes to both from then on.
     */
    class RelayPeer {
        @Volatile var advertised: InetSocketAddress? = null; private set
        @Volatile var seen: InetSocketAddress? = null; private set

        /** A new advertised address (from signaling). True when it changed. */
        fun advertise(a: InetSocketAddress?): Boolean {
            if (a == null || a == advertised) return false
            advertised = a
            if (seen == a) seen = null
            return true
        }

        /** An authentic packet came relayed from [from]. True when it is a new address to permit and send to. */
        fun heard(from: InetSocketAddress?): Boolean {
            if (from == null || from == advertised || from == seen) return false
            seen = from
            return true
        }

        /** Every relayed address to send to: the advertised one, then the one its packets come from. */
        fun targets(): List<InetSocketAddress> = listOfNotNull(advertised, seen?.takeIf { it != advertised })
    }

    enum class Kind { RELAY, WIFI, BLUETOOTH }

    /** One way to reach the peer. Live while an authentic packet came on it within 4 s. */
    class Path(val kind: Kind, val addr: InetSocketAddress?) {
        @Volatile var lastIn = 0L
        @Volatile var lastPing = 0L
        @Volatile var rttMs = -1L
        val added = System.currentTimeMillis()
        fun live(now: Long) = lastIn > 0 && now - lastIn < PATH_DEAD_MS
    }

    /**
     * When to ping a path: while punching (the first 15 s, never live yet) every 50 ms for 2 s then every
     * 250 ms; once live every 1 s (keeps NAT and relay mappings open); dead ones every 2 s for revival.
     */
    fun pingDue(p: Path, now: Long): Boolean {
        val gap = when {
            p.live(now) -> 1_000L
            p.lastIn == 0L && now - p.added < 2_000 -> 50L
            p.lastIn == 0L && now - p.added < 15_000 -> 250L
            else -> 2_000L
        }
        return now - p.lastPing >= gap
    }
}
