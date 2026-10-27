package io.github.ravenor907.muninn.net.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.PowerManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val STUN_HOST = "stun.cloudflare.com"
private const val STUN_PORT = 3478
private const val KEEP_FRAMES = 64
private const val MAX_RESEND = 120

/**
 * The media side of one call: sealed packets (5.4) on every live path at once, the audio (5.5), and between two c2
 * apps the receiver reports, video fragments and their repairs (5.8).
 *
 * Paths, all in parallel; the receiver's replay window drops the copies (5.4):
 *  - RELAY: the internet through this phone's TURN allocation to the peer's relayed address.
 *  - WIFI: straight to the peer's address on the same Wi-Fi (from Chat's Wi-Fi discovery), own UDP socket.
 *  - DIRECT: only when both people trust each other (5.8.2): straight to the peer's internet address from the same
 *    socket, after both pinged each other's candidates (hole punching).
 *  - BLUETOOTH: through [CallBluetooth] when someone provides it.
 * The relay rests (pings only) while Wi-Fi or Direct carried something within 750 ms (5.8.3); video never goes
 * over Bluetooth.
 */
class CallMedia(
    private val context: Context,
    /** True when this phone made the call: it sends offerer-to-answerer. */
    private val offerer: Boolean,
    callId: String,
    private val peerKey: String,
    private val bluetooth: CallBluetooth?,
    private val events: Events,
) {
    interface Events {
        /** The first authentic packet arrived (the callee stops resending its answer). */
        fun onFirstMedia()
        fun onPaths(relay: Boolean, wifi: Boolean, bluetooth: Boolean)
        fun onSpeaking(peer: Boolean)
        fun onDirect(live: Boolean) {}
        /** A whole VP8 frame from the peer, in order (any thread). */
        fun onVideoFrame(stream: Int, data: ByteArray, key: Boolean, rotation: Int) {}
        /** The peer lost something: the next frame of [stream] should be a keyframe. */
        fun onKeyframeWanted(stream: Int) {}
        /** The peer's camera or screen share went on or off (from its reports). */
        fun onPeerVideo(camera: Boolean, screen: Boolean) {}
        /** A free path came or went: the video may use another size (5.8.6). */
        fun onProfile() {}
    }

    private val tag = CallCrypto.tag(callId)
    // Set by begin() once both ephemeral keys are known; until then nothing is sent or opened.
    @Volatile private var keys: CallCrypto.Keys? = null
    @Volatile private var receiver: CallCrypto.Receiver? = null
    private val random = SecureRandom()
    private val jitter = Jitter()
    private val speaking = CallLogic.Speaking()
    private val paths = CopyOnWriteArrayList<CallLogic.Path>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "muninn-call-net").apply { isDaemon = true } }
    private var ticker: ScheduledFuture<*>? = null
    @Volatile private var seq = 0L
    private val started = System.currentTimeMillis()
    @Volatile private var stopped = false
    @Volatile private var gotFirst = false
    @Volatile var muted = false
    @Volatile var speaker = false; private set
    private val pings = HashMap<String, Long>()
    @Volatile var rttMs = 0; private set

    // ------------------------------------------------------------------ c2 (5.8)

    /** Both apps speak c2: receiver reports, Opus at 24 or 32 kbps (16 in a [room]), video. */
    @Volatile var c2 = false; private set
    @Volatile var room = false; private set
    fun setC2(on: Boolean, inRoom: Boolean) { c2 = on; room = inRoom }
    @Volatile var peerLoss = 0; private set
    private var lastReport = 0L
    private var lastSlot = -1L
    private var audioDue = 0; private var audioLost = 0; private var videoDue = 0; private var videoLost = 0
    private var bytesIn = 0L
    private val counters = Any()

    // ------------------------------------------------------------------ transports

    @Volatile private var turn: TurnClient? = null
    private val peerRelay = CallLogic.RelayPeer()
    /** The Wi-Fi (and Direct) socket: open for the whole call, bound to a random port on all interfaces. */
    val lan: DatagramSocket? = try { DatagramSocket(0) } catch (e: Exception) { null }
    val lanPort: Int? get() = lan?.localPort

    /** Allocates on the relay (blocking). The relayed address, or null if no relay could be had. A second call replaces the first. */
    fun startRelay(login: TurnCreds.Login, resolve: (String) -> java.net.InetAddress?): InetSocketAddress? {
        if (stopped) return null
        // Only peers we gave a permission can reach our allocation, and every packet must still open
        // (tag, AEAD) before it counts, so the source isn't matched here: a relay between sites may
        // translate the peer's address (see CallLogic.RelayPeer).
        val self = arrayOfNulls<TurnClient>(1)
        val t = TurnClient(login, resolve) { from, data -> if (turn === self[0]) incoming(data, CallLogic.Kind.RELAY, from) }
        self[0] = t
        if (!t.start()) { t.close(); return null }
        if (stopped) { t.close(); return null }
        val old = turn
        turn = t
        old?.let { try { it.close() } catch (_: Exception) {} }
        peerRelay.forgetSeen()
        peerRelay.advertised?.let { t.permit(it); addPath(CallLogic.Kind.RELAY, it) }
        return t.relayed
    }

    /** The peer's relayed address (from their offer, answer or cands). Never anything but a relay. */
    fun setPeerRelay(a: InetSocketAddress?) {
        if (!peerRelay.advertise(a) || a == null) return
        paths.removeAll { it.kind == CallLogic.Kind.RELAY }
        turn?.let { it.permit(a); addPath(CallLogic.Kind.RELAY, a) }
    }

    /** The peer on the same Wi-Fi: its discovered address and the port it put in "lp". */
    fun setPeerLan(a: InetSocketAddress?) {
        if (a == null || paths.any { it.kind == CallLogic.Kind.WIFI && it.addr == a }) return
        addPath(CallLogic.Kind.WIFI, a)
    }

    // ---- Direct (5.8.2)

    @Volatile var directOn = false; private set
    @Volatile private var directSince = 0L

    /** Both trust each other: ping [cands] (their srflx and host candidates) from the call socket. More may follow. */
    fun enableDirect(cands: List<InetSocketAddress>) {
        if (!directOn) directSince = System.currentTimeMillis()
        directOn = true
        for (a in cands) if (paths.count { it.kind == CallLogic.Kind.DIRECT } < 4 && paths.none { it.kind == CallLogic.Kind.DIRECT && it.addr == a })
            paths += CallLogic.Path(CallLogic.Kind.DIRECT, a)
    }

    @Volatile private var stunServer: InetSocketAddress? = null
    @Volatile private var stunTid: ByteArray? = null
    @Volatile private var stunGot: InetSocketAddress? = null
    private val stunLock = Object()

    /**
     * Asks the STUN server what address the call socket has on the internet (blocking, at most about 3 s): one Binding
     * request, resent after 300, 900 and 1800 ms. Null when there was no usable answer. Only in a call with someone trusted.
     */
    fun probeStun(host: String = STUN_HOST, port: Int = STUN_PORT, resolve: (String) -> java.net.InetAddress?): InetSocketAddress? {
        val s = lan ?: return null
        val ip = try { resolve(host) } catch (e: Exception) { null } ?: return null
        val server = InetSocketAddress(ip, port)
        val tid = Stun.newTid()
        synchronized(stunLock) { stunGot = null; stunServer = server; stunTid = tid }
        val req = Stun.encode(Stun.type(Stun.BINDING, Stun.REQUEST), tid, emptyList(), fingerprint = true)
        val deadline = System.currentTimeMillis() + 3_000
        for (wait in longArrayOf(300, 600, 900, 1200)) {
            if (stopped) break
            try { s.send(DatagramPacket(req, req.size, server)) } catch (_: Exception) {}
            synchronized(stunLock) {
                val until = minOf(deadline, System.currentTimeMillis() + wait)
                while (stunGot == null && System.currentTimeMillis() < until) stunLock.wait(maxOf(1, until - System.currentTimeMillis()))
                stunGot?.let { stunTid = null; return it }
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        synchronized(stunLock) { stunTid = null }
        return null
    }

    private fun onStun(b: ByteArray, len: Int, from: InetSocketAddress?) {
        val server = stunServer ?: return
        if (from == null || from.port != server.port || !sameIp(from.address, server.address)) return
        val m = Stun.decode(b, len) ?: return
        synchronized(stunLock) {
            val tid = stunTid ?: return
            if (!m.tid.contentEquals(tid) || Stun.classOf(m.type) != Stun.SUCCESS || Stun.methodOf(m.type) != Stun.BINDING) return
            val a = m.xorAddress(Stun.A_XOR_MAPPED_ADDRESS) ?: return
            if (!CallLogic.usable(a.address)) return
            stunGot = a
            stunLock.notifyAll()
        }
    }

    private fun sameIp(a: java.net.InetAddress, b: java.net.InetAddress): Boolean = v4(a).contentEquals(v4(b))
    private fun v4(a: java.net.InetAddress): ByteArray {
        val b = a.address
        // ::ffff:a.b.c.d is the IPv4 address a.b.c.d (a dual-stack socket reports it that way)
        if (b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1) return b.copyOfRange(12, 16)
        return b
    }

    private fun addPath(kind: CallLogic.Kind, a: InetSocketAddress?) {
        // One relay path: its sends go to every relayed address in peerRelay.targets().
        if (kind == CallLogic.Kind.RELAY && paths.any { it.kind == kind }) return
        paths += CallLogic.Path(kind, a)
    }

    /** Both ephemeral keys are known: start sending and receiving. */
    fun begin(k: CallCrypto.Keys) {
        if (keys != null || stopped) return
        keys = k
        receiver = CallCrypto.Receiver(k, !offerer, tag)
        bluetooth?.let { bt ->
            bt.onMedia { from, p -> if (from == peerKey) incoming(p, CallLogic.Kind.BLUETOOTH, null) }
            addPath(CallLogic.Kind.BLUETOOTH, null)
        }
        lastReport = System.currentTimeMillis()
        ticker = timer.scheduleWithFixedDelay({ try { tick() } catch (_: Exception) {} }, 0, 25, TimeUnit.MILLISECONDS)
    }

    init { start() }

    private fun start() {
        lan?.let { s -> Thread({ lanLoop(s) }, "muninn-call-lan").apply { isDaemon = true; start() } }
    }

    private fun lanLoop(s: DatagramSocket) {
        val buf = ByteArray(1500)
        while (!stopped) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: Exception) { if (stopped) return else continue }
            if (p.length < 1) continue
            val from = p.socketAddress as? InetSocketAddress
            if ((buf[0].toInt() and 0xFF) != CallCrypto.VERSION) { if (Stun.isStun(buf, p.length)) onStun(buf, p.length, from); continue }
            // The peer's Wi-Fi address (or a second interface on the same network) is Wi-Fi; any other source is the
            // Direct path, only when both trust each other.
            val kind = if (directOn && from != null && paths.none { it.kind == CallLogic.Kind.WIFI && it.addr?.address?.let { a -> sameIp(a, from.address) } == true } &&
                CallLogic.usable(from.address)) CallLogic.Kind.DIRECT else CallLogic.Kind.WIFI
            incoming(buf.copyOf(p.length), kind, from)
        }
    }

    /** Every packet from every path comes through here; the receiver drops copies and forgeries. */
    private fun incoming(data: ByteArray, kind: CallLogic.Kind, from: InetSocketAddress?) {
        if (stopped) return
        val rx = receiver ?: return
        val o = try { rx.open(data) } catch (e: CallCrypto.Dropped) {
            // A copy that already came on another path still proves this path works.
            if (e.why == "duplicate") markLive(kind, from)
            return
        }
        markLive(kind, from)
        // Authentic, relayed from an address the peer didn't advertise: answer there too.
        if (kind == CallLogic.Kind.RELAY && peerRelay.heard(from)) from?.let { turn?.permit(it) }
        if (!gotFirst) { gotFirst = true; events.onFirstMedia() }
        synchronized(counters) { bytesIn += data.size }
        when (o.type) {
            CallCrypto.AUDIO -> { countAudio(o.ts); jitter.put(o.ts, o.payload) }
            CallCrypto.PING -> path(kind, from)?.let { send(CallCrypto.PONG, o.payload, only = it) }
            CallCrypto.PONG -> synchronized(pings) { pings.remove(CallCrypto.hex(o.payload)) }?.let { sentAt ->
                val r = System.currentTimeMillis() - sentAt
                path(kind, from)?.rttMs = r
                rttMs = r.toInt()
            }
            CallCrypto.VIDEO -> if (c2) onVideo(o.payload)
            CallCrypto.REPORT -> if (c2) VideoWire.decodeReport(o.payload)?.let { onReport(it) }
            CallCrypto.KEY_REQUEST -> if (c2 && o.payload.isNotEmpty() && (o.payload[0].toInt() and 0xFF) <= 1) events.onKeyframeWanted(o.payload[0].toInt())
            CallCrypto.NACK -> if (c2) VideoWire.decodeNack(o.payload)?.let { onNack(it) }
        }
        forgetOldKeys()
    }

    /** Loss for the report (5.8.4): gaps of up to 10 frames are lost packets, longer ones a DTX pause. */
    private fun countAudio(ts: Long) = synchronized(counters) {
        val slot = (ts + 10) / 20
        if (lastSlot >= 0) {
            val d = slot - lastSlot
            when {
                d in 1..10 -> { audioDue += d.toInt(); audioLost += (d - 1).toInt() }
                d > 10 -> audioDue += 1
                audioLost > 0 -> audioLost--   // one counted lost came late after all
            }
        }
        if (slot > lastSlot) lastSlot = slot
    }

    private fun path(kind: CallLogic.Kind, from: InetSocketAddress?) =
        paths.firstOrNull { it.kind == kind && ((kind != CallLogic.Kind.WIFI && kind != CallLogic.Kind.DIRECT) || it.addr == from) }

    private fun markLive(kind: CallLogic.Kind, from: InetSocketAddress?) {
        val now = System.currentTimeMillis()
        var p = path(kind, from)
        // A Wi-Fi packet from an address we didn't know (a second interface): it's a path now.
        if (p == null && kind == CallLogic.Kind.WIFI && from != null && paths.count { it.kind == kind } < 3) { p = CallLogic.Path(kind, from); paths += p }
        // Direct from an address that wasn't a candidate: their NAT picked another port. It's the Direct path now.
        if (p == null && kind == CallLogic.Kind.DIRECT && from != null && paths.count { it.kind == kind } < 6) { p = CallLogic.Path(kind, from); paths += p }
        // Relayed before their cands reached us: the relay is a path already.
        if (p == null && kind == CallLogic.Kind.RELAY && from != null) { addPath(kind, from); p = path(kind, from) }
        p?.lastIn = now
    }

    /** 5.3: drop keys and chain values the window has moved past in both directions. */
    private fun forgetOldKeys() {
        val k = keys ?: return; val rx = receiver ?: return
        k.forgetBefore(minOf(CallCrypto.epochOf(seq), rx.neededEpoch()))
    }

    private var lastReportText = ""
    private var wasFree = false
    private var wasDirect = false

    private fun tick() {
        if (stopped) return
        val now = System.currentTimeMillis()
        for (p in paths) {
            // Direct candidates are pinged from when the direct path was turned on, not from the start of the call.
            val due = if (p.kind == CallLogic.Kind.DIRECT && p.lastIn == 0L) {
                val age = now - maxOf(p.added, directSince)
                now - p.lastPing >= when { age < 2_000 -> 50L; age < 15_000 -> 250L; else -> 2_000L }
            } else CallLogic.pingDue(p, now)
            if (!due) continue
            p.lastPing = now
            val nonce = ByteArray(8).also { random.nextBytes(it) }
            synchronized(pings) { if (pings.size > 64) pings.clear(); pings[CallCrypto.hex(nonce)] = now }
            send(CallCrypto.PING, nonce, only = p)
        }
        val r = paths.any { it.kind == CallLogic.Kind.RELAY && it.live(now) }
        val w = paths.any { it.kind == CallLogic.Kind.WIFI && it.live(now) }
        val b = paths.any { it.kind == CallLogic.Kind.BLUETOOTH && it.live(now) }
        val d = paths.any { it.kind == CallLogic.Kind.DIRECT && it.live(now) }
        val report = "$r$w$b"
        if (report != lastReportText) { lastReportText = report; events.onPaths(r, w, b) }
        if (d != wasDirect) { wasDirect = d; events.onDirect(d) }
        val free = w || d
        if (free != wasFree) {
            wasFree = free
            rate[0].setCap(profile(VideoWire.CAMERA).capKbps); rate[1].setCap(profile(VideoWire.SCREEN).capKbps)
            events.onProfile()
        }
        if (c2 && gotFirst && now - lastReport >= 1_000) sendReport()
        if (c2) pollVideo(now)
    }

    fun anyLive(): Boolean { val now = System.currentTimeMillis(); return paths.any { it.live(now) } }

    /** A free path (Wi-Fi or Direct) carried something within 750 ms: the relay rests (5.8.3). */
    fun freeFresh(now: Long = System.currentTimeMillis()) =
        paths.any { (it.kind == CallLogic.Kind.WIFI || it.kind == CallLogic.Kind.DIRECT) && it.lastIn > 0 && now - it.lastIn < CallLogic.FRESH_MS }

    /** Live paths the UI shows: true while the direct path carries the call. */
    fun directLive(): Boolean { val now = System.currentTimeMillis(); return paths.any { it.kind == CallLogic.Kind.DIRECT && it.live(now) } }

    /**
     * Seals with the next seq and sends on [only], or on every live path (every known one while none is live). The relay
     * is left out while a free path is fresh, Bluetooth for [video].
     */
    @Synchronized
    private fun send(type: Int, payload: ByteArray, only: CallLogic.Path? = null, video: Boolean = false) {
        if (stopped) return
        val k = keys ?: return
        val s = seq++
        val p = try { CallCrypto.seal(k, offerer, tag, type, s, System.currentTimeMillis() - started, payload) } catch (e: Exception) { return }
        val now = System.currentTimeMillis()
        val targets = only?.let { listOf(it) } ?: paths.filter { it.live(now) }.ifEmpty { if (video) emptyList() else paths.filter { it.kind != CallLogic.Kind.DIRECT || directOn } }
        // Up to 3 UDP paths (Wi-Fi first, then Direct, then the lowest round trip) plus Bluetooth; each distinct
        // destination gets the packet once (the relay once per relayed address, not once per relay path).
        val rest = only == null && freeFresh(now)
        for (dst in CallLogic.udpSends(targets, peerRelay.targets(), restRelay = rest)) when (dst.kind) {
            CallLogic.Kind.RELAY -> turn?.send(dst.addr, p)
            CallLogic.Kind.WIFI, CallLogic.Kind.DIRECT -> try { lan?.send(DatagramPacket(p, p.size, dst.addr)) } catch (_: Exception) {}
            else -> {}
        }
        if (!video && targets.any { it.kind == CallLogic.Kind.BLUETOOTH } && bluetooth?.canCarry(peerKey) == true) bluetooth.send(peerKey, p)
    }

    // ------------------------------------------------------------------ reports (5.8.4)

    private fun sendReport() {
        val now = System.currentTimeMillis()
        val r = synchronized(counters) {
            val span = maxOf(1L, now - lastReport)
            val due = audioDue + videoDue; val lost = audioLost + videoLost
            val rep = VideoWire.Report(if (due > 0) (lost * 100 / due).coerceIn(0, 100) else 0, sending[0], sending[1], jitter.targetMs.toInt(),
                (bytesIn * 8 / span).toInt(), rttMs)
            audioDue = 0; audioLost = 0; videoDue = 0; videoLost = 0; bytesIn = 0
            rep
        }
        lastReport = now
        send(CallCrypto.REPORT, VideoWire.encodeReport(r))
    }

    private fun onReport(r: VideoWire.Report) {
        peerLoss = r.lossPct
        if (r.camera != peerSending[0] || r.screen != peerSending[1]) {
            if (!r.camera) synchronized(asm[0]) { asm[0].reset() }
            if (!r.screen) synchronized(asm[1]) { asm[1].reset() }
            peerSending[0] = r.camera; peerSending[1] = r.screen
            events.onPeerVideo(r.camera, r.screen)
        }
        val now = System.currentTimeMillis()
        for (s in 0..1) if (sending[s]) rate[s].onReport(r.lossPct, now)
    }

    /** The Opus bitrate and expected loss now (5.8.4): 32 kbps on a free path, 24 over the relay, 16 in a room or with 0.7. */
    fun opusTuning(): Pair<Int, Int> {
        if (!c2 || room) return Opus.BITRATE to 10
        val l = peerLoss
        return (if (freeFresh()) 32_000 else 24_000) to (l + l / 2 + 3).coerceIn(5, 30)
    }

    // ------------------------------------------------------------------ video (5.8.5 to 5.8.7)

    private val sending = booleanArrayOf(false, false)
    private val peerSending = booleanArrayOf(false, false)
    private val frameNo = intArrayOf(0, 0)
    private val sent = arrayOf(LinkedHashMap<Int, List<ByteArray>>(), LinkedHashMap<Int, List<ByteArray>>())
    private val asm = arrayOf(VideoWire.Assembler(VideoWire.CAMERA), VideoWire.Assembler(VideoWire.SCREEN))
    val rate = arrayOf(VideoWire.RateControl().apply { setCap(VideoWire.cameraProfile(false).capKbps) },
        VideoWire.RateControl().apply { setCap(VideoWire.screenProfile(false).capKbps) })
    /** Unit-test style knob for the field: leave out every Nth video packet (resends get through). */
    @Volatile var dropVideoEvery = 0
    private var videoCount = 0

    fun peerSending(stream: Int) = peerSending[stream and 1]
    fun videoSending(stream: Int) = sending[stream and 1]

    /** The size, frame rate and bitrate cap for [stream] right now: 480p over the relay, up to 720p on a free path. */
    fun profile(stream: Int): VideoWire.Profile {
        val now = System.currentTimeMillis()
        val free = paths.any { (it.kind == CallLogic.Kind.WIFI || it.kind == CallLogic.Kind.DIRECT) && it.live(now) }
        return if ((stream and 1) == VideoWire.SCREEN) VideoWire.screenProfile(free) else VideoWire.cameraProfile(free)
    }

    /** What this side says it sends (in every report, so the other side shows or hides the picture). */
    fun setVideoSending(stream: Int, on: Boolean) {
        val s = stream and 1
        if (sending[s] == on) return
        sending[s] = on
        if (!on) synchronized(sent[s]) { sent[s].clear() }
        if (c2 && gotFirst && !stopped) timer.execute { sendReport() }
    }

    /** Asks the peer for a keyframe now (the screen came back, or the decoder lost its place). */
    fun requestKeyframe(stream: Int) { if (c2 && !stopped) timer.execute { send(CallCrypto.KEY_REQUEST, byteArrayOf((stream and 1).toByte())) } }

    /** One encoded VP8 frame: cut into packets and sent. False when video can't go now. */
    fun sendVideo(stream: Int, key: Boolean, rotation: Int, vp8: ByteArray): Boolean {
        val s = stream and 1
        if (!c2 || stopped || keys == null || !sending[s]) return false
        val n = frameNo[s] and 0xFFFF
        frameNo[s]++
        val parts = VideoWire.split(s, n, key, rotation, vp8)
        if (parts.isEmpty()) return false
        synchronized(sent[s]) {
            sent[s][n] = parts
            while (sent[s].size > KEEP_FRAMES) sent[s].remove(sent[s].keys.first())
        }
        for (p in parts) {
            videoCount++
            if (dropVideoEvery > 0 && videoCount % dropVideoEvery == 0) continue
            send(CallCrypto.VIDEO, p, video = true)
        }
        return true
    }

    private fun onVideo(payload: ByteArray) {
        val f = VideoWire.decode(payload) ?: return
        synchronized(counters) { videoDue++ }
        if (!peerSending[f.stream]) { peerSending[f.stream] = true; events.onPeerVideo(peerSending[0], peerSending[1]) }
        val a = asm[f.stream]
        val out = ArrayList<VideoWire.Assembler.Frame>()
        synchronized(a) {
            a.add(f, System.currentTimeMillis())
            while (true) out += a.next() ?: break
        }
        for (fr in out) events.onVideoFrame(f.stream, fr.data, fr.key, fr.rotation)
    }

    private fun pollVideo(now: Long) {
        for (s in 0..1) {
            if (!peerSending[s]) continue
            val a = asm[s]
            val out = ArrayList<VideoWire.Assembler.Frame>()
            val ask = synchronized(a) { a.poll(now, rttMs).also { while (true) out += a.next() ?: break } }
            for (n in ask.nacks) {
                if (n.first) synchronized(counters) { videoLost += maxOf(1, n.indexes.size) }
                send(CallCrypto.NACK, VideoWire.encodeNack(n))
            }
            if (ask.keyframe) send(CallCrypto.KEY_REQUEST, byteArrayOf(s.toByte()))
            for (fr in out) events.onVideoFrame(s, fr.data, fr.key, fr.rotation)
        }
    }

    private fun onNack(n: VideoWire.Nack) {
        val parts = synchronized(sent[n.stream]) { sent[n.stream][n.frame] } ?: return   // too old: their keyframe request covers it
        val which = n.indexes.ifEmpty { parts.indices.toList() }
        for (i in which.take(MAX_RESEND)) if (i in parts.indices) send(CallCrypto.VIDEO, parts[i], video = true)
    }

    // ------------------------------------------------------------------ audio

    private val audio = context.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var proximity: PowerManager.WakeLock? = null
    private var oldMode = AudioManager.MODE_NORMAL
    private var mic: Thread? = null
    private var player: Thread? = null
    private var tvGain: io.github.ravenor907.muninn.tv.TvMic.Gain? = null

    // ------------------------------------------------------------------ voice rooms (section 6)

    /** In a voice room the room owns the microphone and the speaker for every peer: this media only seals and opens. */
    @Volatile var externalAudio = false
    /** One encoded 20 ms frame from the room's microphone (nothing while muted). */
    fun sendOpus(opus: ByteArray) { if (!muted) send(CallCrypto.AUDIO, opus) }
    /** What the room's player plays next for this peer. */
    fun nextPlay(): Play = jitter.next()

    /** Starts the microphone and the speaker. False if the microphone couldn't open (no permission?). */
    @SuppressLint("MissingPermission")
    fun startAudio(): Boolean {
        if (externalAudio) return true
        if (Opus.loadError != null) return false
        oldMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        routeDefault()
        val minIn = AudioRecord.getMinBufferSize(Opus.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // A phone has one source; a Kids' TV falls back to MIC when VOICE_COMMUNICATION won't open or start.
        val rec = io.github.ravenor907.muninn.tv.TvMic.callSources().asSequence().mapNotNull { openMic(it, minIn) }.firstOrNull() ?: return false
        record = rec
        val minOut = AudioTrack.getMinBufferSize(Opus.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(Opus.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minOut, Opus.FRAME * 2 * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        mic = Thread({ captureLoop(rec) }, "muninn-call-mic").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
        player = Thread({ playLoop(track!!) }, "muninn-call-play").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
        proximityOn(true)
        return true
    }

    /**
     * Opens and starts the microphone on [src] with the echo canceller, noise suppressor and (Kids' TV)
     * gain and AGC where the device has them. Null, with everything released, if it doesn't record.
     */
    @SuppressLint("MissingPermission")
    private fun openMic(src: Int, minIn: Int): AudioRecord? {
        val rec = try {
            AudioRecord(src, Opus.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, Opus.FRAME * 2 * 4))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); return null }
        tvGain = io.github.ravenor907.muninn.tv.TvMic.forCall(rec.audioSessionId)   // Kids' TV: louder microphone
        if (AcousticEchoCanceler.isAvailable()) aec = try { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } } catch (e: Exception) { null }
        if (NoiseSuppressor.isAvailable()) ns = try { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } } catch (e: Exception) { null }
        val ok = try { rec.startRecording(); rec.recordingState == AudioRecord.RECORDSTATE_RECORDING } catch (e: Exception) { false }
        if (ok) return rec
        try { aec?.release(); ns?.release() } catch (_: Exception) {}
        aec = null; ns = null
        if (tvGain != null) { io.github.ravenor907.muninn.tv.TvMic.release(); tvGain = null }
        rec.release()
        return null
    }

    private fun captureLoop(rec: AudioRecord) {
        val enc = try { Opus.Encoder() } catch (e: Throwable) { return }
        val pcm = ShortArray(Opus.FRAME)
        var lastSent = 0L
        var frames = 0
        try {
            // Already recording: openMic started it to know the source works.
            while (!stopped) {
                var got = 0
                while (got < Opus.FRAME && !stopped) { val n = rec.read(pcm, got, Opus.FRAME - got); if (n <= 0) break; got += n }
                if (got < Opus.FRAME) continue
                tvGain?.process(pcm, got)
                if (muted) continue   // pings go on; no audio while muted (5.5)
                if (frames++ % 25 == 0) opusTuning().let { (b, l) -> enc.tune(b, l) }   // twice a second
                val p = enc.encode(pcm, keepSilence = true) ?: continue
                // DTX: one silence packet every 400 ms, so the other side's decoder makes comfort noise (5.5).
                val now = System.currentTimeMillis()
                if (Opus.isSilence(p) && now - lastSent < 400) continue
                lastSent = now
                send(CallCrypto.AUDIO, p)
            }
        } catch (_: Exception) {
        } finally { try { rec.stop() } catch (_: Exception) {}; enc.close() }
    }

    private fun playLoop(t: AudioTrack) {
        val dec = try { Opus.Decoder() } catch (e: Throwable) { return }
        val pcm = ShortArray(Opus.FRAME)
        var wasSpeaking = false
        try {
            t.play()
            while (!stopped) {
                val n = when (val f = jitter.next()) {
                    is Play.Packet -> dec.decode(f.data, pcm)
                    is Play.Fec -> dec.decode(f.next, pcm, fec = true)
                    Play.Conceal -> dec.decode(null, pcm)
                    Play.Silence -> { pcm.fill(0); Opus.FRAME }
                }
                if (n <= 0) pcm.fill(0)
                val s = speaking.feed(pcm, Opus.FRAME, System.currentTimeMillis())
                if (s != wasSpeaking) { wasSpeaking = s; events.onSpeaking(s) }
                t.write(pcm, 0, Opus.FRAME)   // blocking: the audio clock paces this loop
            }
        } catch (_: Exception) {
        } finally { try { t.stop() } catch (_: Exception) {}; dec.close() }
    }

    // ------------------------------------------------------------------ routing

    /** Speaker on or off. Off goes back to a Bluetooth headset when one is connected, else the earpiece. */
    fun setSpeaker(on: Boolean) {
        speaker = on
        if (on) {
            if (Build.VERSION.SDK_INT >= 31) audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audio.setCommunicationDevice(it) }
            else { @Suppress("DEPRECATION") run { audio.stopBluetoothSco(); audio.isBluetoothScoOn = false; audio.isSpeakerphoneOn = true } }
        } else routeDefault()
        proximityOn(!on)
    }

    private fun routeDefault() {
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = audio.availableCommunicationDevices
            val bt = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            val wired = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            val ear = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            (bt ?: wired ?: ear)?.let { audio.setCommunicationDevice(it) } ?: audio.clearCommunicationDevice()
        } else @Suppress("DEPRECATION") {
            audio.isSpeakerphoneOn = false
            val btHeadset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (btHeadset) try { audio.startBluetoothSco(); audio.isBluetoothScoOn = true } catch (_: Exception) {}
        }
    }

    /** Proximity: the screen goes dark against the ear (not on speaker). */
    private fun proximityOn(on: Boolean) {
        try {
            if (on) {
                if (proximity == null) {
                    val pm = context.getSystemService(PowerManager::class.java)
                    if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                        proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "muninn:call")
                }
                proximity?.takeIf { !it.isHeld }?.acquire(4 * 60 * 60_000L)
            } else proximity?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ end

    fun stop() {
        if (stopped) return
        stopped = true
        synchronized(stunLock) { stunLock.notifyAll() }
        synchronized(sent[0]) { sent[0].clear() }; synchronized(sent[1]) { sent[1].clear() }
        ticker?.cancel(false)
        timer.shutdownNow()
        try { lan?.close() } catch (_: Exception) {}
        try { turn?.close() } catch (_: Exception) {}
        bluetooth?.onMedia(null)
        // Let the audio threads leave read() and write() before their objects go.
        try { mic?.join(500); player?.join(500) } catch (_: InterruptedException) {}
        try { record?.release() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        try { aec?.release(); ns?.release() } catch (_: Exception) {}
        if (tvGain != null) io.github.ravenor907.muninn.tv.TvMic.release()
        proximityOn(false)
        if (!externalAudio) try {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            else @Suppress("DEPRECATION") { audio.stopBluetoothSco(); audio.isBluetoothScoOn = false; audio.isSpeakerphoneOn = false }
            audio.mode = oldMode
        } catch (_: Exception) {}
        keys?.wipe()
    }
}
