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

/**
 * The media side of one call: sealed packets (5.4) on every live path at once, and the audio (5.5).
 *
 * Paths, all in parallel; the receiver's replay window drops the copies (5.4):
 *  - RELAY: the internet, only ever through this phone's TURN allocation to the peer's relayed address.
 *  - WIFI: straight to the peer's address on the same Wi-Fi (from Chat's Wi-Fi discovery), own UDP socket.
 *  - BLUETOOTH: through [CallBluetooth] when someone provides it.
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
    }

    private val tag = CallCrypto.tag(callId)
    // Set by begin() once both ephemeral keys are known; until then nothing is sent or opened.
    @Volatile private var keys: CallCrypto.Keys? = null
    @Volatile private var receiver: CallCrypto.Receiver? = null
    private val random = SecureRandom()
    private val jitter = CallLogic.Jitter()
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

    // ------------------------------------------------------------------ transports

    @Volatile private var turn: TurnClient? = null
    private val peerRelay = CallLogic.RelayPeer()
    /** The Wi-Fi socket: open for the whole call, bound to a random port on all interfaces. */
    val lan: DatagramSocket? = try { DatagramSocket(0) } catch (e: Exception) { null }
    val lanPort: Int? get() = lan?.localPort

    /** Allocates on the relay (blocking). The relayed address, or null if no relay could be had. */
    fun startRelay(login: TurnCreds.Login, resolve: (String) -> java.net.InetAddress?): InetSocketAddress? {
        if (stopped) return null
        // Only peers we gave a permission can reach our allocation, and every packet must still open
        // (tag, AEAD) before it counts, so the source isn't matched here: a relay between sites may
        // translate the peer's address (see CallLogic.RelayPeer).
        val t = TurnClient(login, resolve) { from, data -> incoming(data, CallLogic.Kind.RELAY, from) }
        if (!t.start()) { t.close(); return null }
        if (stopped) { t.close(); return null }
        turn = t
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

    private fun addPath(kind: CallLogic.Kind, a: InetSocketAddress?) { paths += CallLogic.Path(kind, a) }

    /** Both ephemeral keys are known: start sending and receiving. */
    fun begin(k: CallCrypto.Keys) {
        if (keys != null || stopped) return
        keys = k
        receiver = CallCrypto.Receiver(k, !offerer, tag)
        bluetooth?.let { bt ->
            bt.onMedia { from, p -> if (from == peerKey) incoming(p, CallLogic.Kind.BLUETOOTH, null) }
            addPath(CallLogic.Kind.BLUETOOTH, null)
        }
        ticker = timer.scheduleWithFixedDelay({ tick() }, 0, 50, TimeUnit.MILLISECONDS)
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
            if (p.length < 1 || (buf[0].toInt() and 0xFF) != CallCrypto.VERSION) continue
            incoming(buf.copyOf(p.length), CallLogic.Kind.WIFI, p.socketAddress as? InetSocketAddress)
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
        when (o.type) {
            CallCrypto.AUDIO -> jitter.put(o.ts, o.payload)
            CallCrypto.PING -> path(kind, from)?.let { send(CallCrypto.PONG, o.payload, only = it) }
            CallCrypto.PONG -> synchronized(pings) { pings.remove(CallCrypto.hex(o.payload)) }?.let { sentAt ->
                path(kind, from)?.rttMs = System.currentTimeMillis() - sentAt
            }
        }
        forgetOldKeys()
    }

    private fun path(kind: CallLogic.Kind, from: InetSocketAddress?) =
        paths.firstOrNull { it.kind == kind && (kind != CallLogic.Kind.WIFI || it.addr == from) }

    private fun markLive(kind: CallLogic.Kind, from: InetSocketAddress?) {
        val now = System.currentTimeMillis()
        var p = path(kind, from)
        // A Wi-Fi packet from an address we didn't know (a second interface): it's a path now.
        if (p == null && kind == CallLogic.Kind.WIFI && from != null && paths.count { it.kind == kind } < 3) { p = CallLogic.Path(kind, from); paths += p }
        // Relayed before their cands reached us: the relay is a path already.
        if (p == null && kind == CallLogic.Kind.RELAY && from != null) { p = CallLogic.Path(kind, from); paths += p }
        p?.lastIn = now
    }

    /** 5.3: drop keys and chain values the window has moved past in both directions. */
    private fun forgetOldKeys() {
        val k = keys ?: return; val rx = receiver ?: return
        k.forgetBefore(minOf(CallCrypto.epochOf(seq), rx.neededEpoch()))
    }

    private var lastReport = ""

    private fun tick() {
        if (stopped) return
        val now = System.currentTimeMillis()
        for (p in paths) if (CallLogic.pingDue(p, now)) {
            p.lastPing = now
            val nonce = ByteArray(8).also { random.nextBytes(it) }
            synchronized(pings) { if (pings.size > 64) pings.clear(); pings[CallCrypto.hex(nonce)] = now }
            send(CallCrypto.PING, nonce, only = p)
        }
        val r = paths.any { it.kind == CallLogic.Kind.RELAY && it.live(now) }
        val w = paths.any { it.kind == CallLogic.Kind.WIFI && it.live(now) }
        val b = paths.any { it.kind == CallLogic.Kind.BLUETOOTH && it.live(now) }
        val report = "$r$w$b"
        if (report != lastReport) { lastReport = report; events.onPaths(r, w, b) }
    }

    fun anyLive(): Boolean { val now = System.currentTimeMillis(); return paths.any { it.live(now) } }

    /** Seals with the next seq and sends on [only], or on every live path (every known one while none is live). */
    @Synchronized
    private fun send(type: Int, payload: ByteArray, only: CallLogic.Path? = null) {
        if (stopped) return
        val k = keys ?: return
        val s = seq++
        val p = try { CallCrypto.seal(k, offerer, tag, type, s, System.currentTimeMillis() - started, payload) } catch (e: Exception) { return }
        val now = System.currentTimeMillis()
        val targets = only?.let { listOf(it) } ?: paths.filter { it.live(now) }.ifEmpty { paths }
        // Up to 3 UDP paths (Wi-Fi first, then the lowest round trip) plus Bluetooth.
        val udp = targets.filter { it.kind != CallLogic.Kind.BLUETOOTH }
            .sortedWith(compareBy({ if (it.kind == CallLogic.Kind.WIFI) 0 else 1 }, { if (it.rttMs < 0) Long.MAX_VALUE else it.rttMs })).take(3)
        for (t in udp) when (t.kind) {
            CallLogic.Kind.RELAY -> turn?.let { tc -> for (a in peerRelay.targets()) tc.send(a, p) }
            CallLogic.Kind.WIFI -> t.addr?.let { a -> try { lan?.send(DatagramPacket(p, p.size, a)) } catch (_: Exception) {} }
            else -> {}
        }
        if (targets.any { it.kind == CallLogic.Kind.BLUETOOTH } && bluetooth?.canCarry(peerKey) == true) bluetooth.send(peerKey, p)
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
    fun nextPlay(): CallLogic.Play = jitter.next()

    /** Starts the microphone and the speaker. False if the microphone couldn't open (no permission?). */
    @SuppressLint("MissingPermission")
    fun startAudio(): Boolean {
        if (externalAudio) return true
        if (Opus.loadError != null) return false
        oldMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        routeDefault()
        val minIn = AudioRecord.getMinBufferSize(Opus.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(io.github.ravenor907.muninn.tv.TvMic.callSource(), Opus.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, Opus.FRAME * 2 * 4))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); return false }
        record = rec
        tvGain = io.github.ravenor907.muninn.tv.TvMic.forCall(rec.audioSessionId)   // Kids' TV: louder microphone
        if (AcousticEchoCanceler.isAvailable()) aec = try { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } } catch (e: Exception) { null }
        if (NoiseSuppressor.isAvailable()) ns = try { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } } catch (e: Exception) { null }
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

    private fun captureLoop(rec: AudioRecord) {
        val enc = try { Opus.Encoder() } catch (e: Throwable) { return }
        val pcm = ShortArray(Opus.FRAME)
        try {
            rec.startRecording()
            while (!stopped) {
                var got = 0
                while (got < Opus.FRAME && !stopped) { val n = rec.read(pcm, got, Opus.FRAME - got); if (n <= 0) break; got += n }
                if (got < Opus.FRAME) continue
                tvGain?.process(pcm, got)
                if (muted) continue   // pings go on; no audio while muted (5.5)
                enc.encode(pcm)?.let { send(CallCrypto.AUDIO, it) }
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
                    is CallLogic.Play.Packet -> dec.decode(f.data, pcm)
                    is CallLogic.Play.Fec -> dec.decode(f.next, pcm, fec = true)
                    CallLogic.Play.Conceal -> dec.decode(null, pcm)
                    CallLogic.Play.Silence -> { pcm.fill(0); Opus.FRAME }
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
