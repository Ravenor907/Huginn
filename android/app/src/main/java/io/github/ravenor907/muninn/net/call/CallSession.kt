package io.github.ravenor907.muninn.net.call

import io.github.ravenor907.muninn.net.ChatCrypto
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.PrivateKey
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One 1:1 call from start to end (docs/protocol-0.6.md 5.2 timing, 5.6 "no path"). Every method runs on
 * [CallManager]'s single call thread ([exec]), so the state needs no locks; slow work (the relay login
 * through Tor, the TURN allocation) runs on [work] and hops back.
 */
class CallSession(
    val callId: String,
    val peerKey: String,
    val outgoing: Boolean,
    private val host: Host,
    private val exec: ScheduledExecutorService,
    private val work: java.util.concurrent.ExecutorService,
) {
    /** What a session needs from the app. */
    interface Host {
        val chatPrivate: PrivateKey
        fun send(peerKey: String, json: JSONObject)
        /** The peer's address on this Wi-Fi from Chat's discovery, or null. Never shown anywhere. */
        fun lanAddressOf(peerKey: String): java.net.InetAddress?
        /** A relay login (blocking, through Tor), or null. */
        fun relayLogin(): TurnCreds.Login?
        fun resolve(host: String): java.net.InetAddress?
        fun internetAllowed(): Boolean
        fun newMedia(s: CallSession): CallMedia
        fun changed(s: CallSession)
        fun ended(s: CallSession)
    }

    enum class Phase { CALLING, RINGING, INCOMING, CONNECTING, LIVE, RECONNECTING, ENDED }

    @Volatile var phase = if (outgoing) Phase.CALLING else Phase.INCOMING; private set
    @Volatile var connectedAt = 0L; private set
    val startedAt = System.currentTimeMillis()
    @Volatile var muted = false; private set
    @Volatile var peerMuted = false; private set
    @Volatile var peerSpeaking = false; private set
    @Volatile var relay = false; private set
    @Volatile var wifi = false; private set
    @Volatile var bluetooth = false; private set
    /** Why it ended, for the screen and the history: "", "declined", "busy", "noanswer", "nopath", "dropped", "hangup", "missed", "error". */
    @Volatile var endWhy = ""; private set
    /** Something to tell the person while the call goes on (relay trouble), never an address. */
    @Volatile var note = ""; private set
    @Volatile var answered = false; private set

    private var eph: KeyPair? = null
    private var myEph = ""
    private var theirEph = ""
    private var offerJson: JSONObject? = null
    private var answerJson: JSONObject? = null
    private var peerRelay: InetSocketAddress? = null
    private var peerLanPort: Int? = null
    private var myRelay: InetSocketAddress? = null
    private var media: CallMedia? = null
    private var gotMedia = false
    private var answeredAt = 0L
    private var deadSince = 0L
    private var lastCands = 0L
    private val timers = ArrayList<ScheduledFuture<*>>()

    private fun later(ms: Long, f: () -> Unit) { timers += exec.schedule(Runnable { if (phase != Phase.ENDED) f() }, ms, TimeUnit.MILLISECONDS) }
    private fun every(ms: Long, f: () -> Unit) { timers += exec.scheduleWithFixedDelay({ if (phase != Phase.ENDED) f() }, ms, ms, TimeUnit.MILLISECONDS) }
    private fun set(p: Phase) { if (phase != Phase.ENDED) { phase = p; host.changed(this) } }

    private fun lanPortIfClose(): Int? = if (host.lanAddressOf(peerKey) != null) media?.lanPort else null

    // ------------------------------------------------------------------ caller

    fun startOutgoing() {
        val kp = CallCrypto.newEphemeral(); eph = kp
        myEph = ChatCrypto.b64(kp.public.encoded)
        media = host.newMedia(this)
        val sig = CallCrypto.sign(host.chatPrivate, "offer", callId, myEph)
        offerJson = CallLogic.offer(callId, myEph, sig, System.currentTimeMillis(), null, lanPortIfClose(), host.bluetoothFor())
        host.send(peerKey, offerJson!!)
        // Resend the offer every 3 s until the other side says anything; give up after 45 s.
        val resend = exec.scheduleWithFixedDelay({ if (phase == Phase.CALLING) offerJson?.let { host.send(peerKey, it) } },
            CallLogic.OFFER_EVERY_MS, CallLogic.OFFER_EVERY_MS, TimeUnit.MILLISECONDS)
        timers += resend
        later(CallLogic.RING_MS) { if (phase == Phase.CALLING || phase == Phase.RINGING) end("noanswer", send = true) }
        startRelay()
        every(500) { watch() }
    }

    // ------------------------------------------------------------------ callee

    private var offerTs = 0L
    private var theirBle = false

    /** First sight of an offer that is already checked (signature, freshness). Starts ringing. */
    fun startIncoming(s: CallLogic.Signal) {
        theirEph = s.eph; offerTs = s.ts; theirBle = s.ble
        peerRelay = s.relay; peerLanPort = s.lanPort
        host.send(peerKey, CallLogic.simple("ring", callId))
        later(CallLogic.RING_MS) { if (phase == Phase.INCOMING) end("missed", send = false) }
        // Get the relay ready while it rings (login and allocation), so answering doesn't wait for it.
        // Nothing is sent on it until the person answers; declining or missing the call releases it.
        if (host.internetAllowed()) { media = host.newMedia(this); startRelay() }
    }

    /** The person tapped Answer. */
    fun answer() {
        if (phase != Phase.INCOMING) return
        answered = true
        val kp = CallCrypto.newEphemeral(); eph = kp
        myEph = ChatCrypto.b64(kp.public.encoded)
        val root = try { CallCrypto.root(kp.private, theirEph, callId, theirEph, myEph) } catch (e: Exception) { end("error", send = true); return }
        val m = media ?: host.newMedia(this).also { media = it }
        m.begin(CallCrypto.Keys(root)); root.fill(0)
        applyPeer()
        val sig = CallCrypto.sign(host.chatPrivate, "answer", callId, myEph)
        // The relay is usually up by now (it started while ringing): the answer carries it.
        answerJson = CallLogic.answer(callId, myEph, sig, myRelay, lanPortIfClose(), host.bluetoothFor())
        host.send(peerKey, answerJson!!)
        answeredAt = System.currentTimeMillis()
        // Resend the answer every 2 s until the first authentic media packet, for up to 15 s.
        val resend = exec.scheduleWithFixedDelay({ if (!gotMedia && System.currentTimeMillis() - answeredAt < CallLogic.ANSWER_FOR_MS) answerJson?.let { host.send(peerKey, it) } },
            CallLogic.ANSWER_EVERY_MS, CallLogic.ANSWER_EVERY_MS, TimeUnit.MILLISECONDS)
        timers += resend
        set(Phase.CONNECTING)
        if (!m.startAudio()) note = "The microphone isn't available"
        startRelay()
        every(500) { watch() }
    }

    fun decline() { if (phase == Phase.INCOMING) end("declined", send = true, t = "decline") }

    // ------------------------------------------------------------------ both

    /** A checked message for this call from the peer. */
    fun onSignal(s: CallLogic.Signal) {
        when (s.t) {
            "offer" -> if (!outgoing) {
                // A retry: ring again (or repeat the answer), and take any newer candidates.
                if (s.relay != null) peerRelay = s.relay
                if (s.lanPort != null) peerLanPort = s.lanPort
                if (answered) { applyPeer(); answerJson?.let { host.send(peerKey, it) } } else host.send(peerKey, CallLogic.simple("ring", callId))
            }
            "ring" -> if (outgoing && phase == Phase.CALLING) set(Phase.RINGING)
            "answer" -> if (outgoing && (phase == Phase.CALLING || phase == Phase.RINGING)) onAnswer(s)
                // A resent answer: the same keys, maybe with the relay that wasn't up the first time.
                else if (outgoing && answered && s.eph == theirEph) { if (s.relay != null) peerRelay = s.relay; if (s.lanPort != null) peerLanPort = s.lanPort; applyPeer() }
            "cands" -> { if (s.relay != null) peerRelay = s.relay; if (s.lanPort != null) peerLanPort = s.lanPort; applyPeer() }
            "mute" -> { peerMuted = s.on; host.changed(this) }
            "decline" -> if (outgoing) end("declined", send = false)
            "busy" -> if (outgoing && !answered) end("busy", send = false)
            "hangup" -> end(if (phase == Phase.INCOMING) "missed" else if (s.why == "hangup") "hangup" else s.why, send = false)
        }
    }

    private fun onAnswer(s: CallLogic.Signal) {
        val kp = eph ?: return
        theirEph = s.eph
        val root = try { CallCrypto.root(kp.private, theirEph, callId, myEph, theirEph) } catch (e: Exception) { end("error", send = true); return }
        answered = true
        s.relay?.let { peerRelay = it }; s.lanPort?.let { peerLanPort = it }; theirBle = s.ble
        val m = media ?: return
        m.begin(CallCrypto.Keys(root)); root.fill(0)
        applyPeer()
        answeredAt = System.currentTimeMillis()
        set(Phase.CONNECTING)
        if (!m.startAudio()) note = "The microphone isn't available"
    }

    /** Tells media where the peer is: its relayed address, and its Wi-Fi address when close by. */
    private fun applyPeer() {
        val m = media ?: return
        if (host.internetAllowed()) m.setPeerRelay(peerRelay)
        val lanIp = host.lanAddressOf(peerKey)
        val lp = peerLanPort
        if (lanIp != null && lp != null) m.setPeerLan(InetSocketAddress(lanIp, lp))
    }

    private var relayStarted = false

    private fun startRelay() {
        if (!host.internetAllowed() || relayStarted) return
        val m = media ?: return
        relayStarted = true
        work.execute {
            val login = try { host.relayLogin() } catch (e: Exception) { null }
            val r = login?.let { m.startRelay(it, host::resolve) }
            exec.execute {
                if (phase == Phase.ENDED) return@execute
                if (r == null) { note = if (login == null) TurnCreds.lastProblem.ifEmpty { "The internet relay isn't available" } else TurnClient.lastProblem.ifEmpty { "Couldn't reach the internet relay" }; host.changed(this); return@execute }
                myRelay = r
                // The offer resends now carry the relay too, and the peer hears it at once.
                val lp = lanPortIfClose()
                offerJson?.put("cands", CallLogic.cands(r, lp))
                answerJson?.put("cands", CallLogic.cands(r, lp))
                if (lp != null) { offerJson?.put("lp", lp); answerJson?.put("lp", lp) }
                // Still ringing here: the answer will carry it. Otherwise tell the peer now.
                if (outgoing || answered) sendCands()
            }
        }
    }

    private fun sendCands() {
        val now = System.currentTimeMillis()
        if (now - lastCands < 2_000) { later(2_000 - (now - lastCands)) { sendCands() }; return }
        lastCands = now
        host.send(peerKey, CallLogic.candsMsg(callId, myRelay, lanPortIfClose()))
    }

    // Events from media (any thread): hop onto the call thread.
    fun mediaFirst() = exec.execute { if (!gotMedia) { gotMedia = true; connectedAt = System.currentTimeMillis(); set(Phase.LIVE) } }
    fun mediaPaths(r: Boolean, w: Boolean, b: Boolean) = exec.execute { relay = r; wifi = w; bluetooth = b; host.changed(this) }
    fun mediaSpeaking(on: Boolean) = exec.execute { peerSpeaking = on; host.changed(this) }

    private fun watch() {
        val m = media ?: return
        val now = System.currentTimeMillis()
        when (phase) {
            Phase.CONNECTING -> if (!m.anyLive() && answeredAt > 0 && now - answeredAt > CallLogic.NO_PATH_MS) end("nopath", send = true)
            Phase.LIVE -> if (!m.anyLive()) { deadSince = now; set(Phase.RECONNECTING) }
            Phase.RECONNECTING -> if (m.anyLive()) { deadSince = 0; set(Phase.LIVE) } else if (now - deadSince > CallLogic.DROPPED_MS) end("dropped", send = true)
            else -> {}
        }
    }

    fun setMuted(on: Boolean) {
        muted = on; media?.muted = on
        if (answered) host.send(peerKey, CallLogic.mute(callId, on))
        host.changed(this)
    }

    fun setSpeaker(on: Boolean) { media?.setSpeaker(on); host.changed(this) }
    val speaker: Boolean get() = media?.speaker == true

    fun hangup() = end(if (phase == Phase.INCOMING) "declined" else "hangup", send = true, t = if (phase == Phase.INCOMING) "decline" else "hangup")

    /**
     * Ends the call. [send]: tell the peer, 3 times over 2 s (5.2). [t]: "hangup", or "decline"/"busy".
     */
    fun end(why: String, send: Boolean, t: String = "hangup") {
        if (phase == Phase.ENDED) return
        endWhy = why
        phase = Phase.ENDED
        timers.forEach { it.cancel(false) }; timers.clear()
        if (send) {
            val msg = if (t == "hangup") CallLogic.hangup(callId, if (why in CallLogic.WHY) why else "hangup") else CallLogic.simple(t, callId)
            host.send(peerKey, msg)
            exec.schedule(Runnable { host.send(peerKey, msg) }, 1, TimeUnit.SECONDS)
            exec.schedule(Runnable { host.send(peerKey, msg) }, 2, TimeUnit.SECONDS)
        }
        media?.stop(); media = null
        eph = null
        host.ended(this)
    }

    private fun Host.bluetoothFor(): Boolean = CallManager.bluetooth?.canCarry(peerKey) == true
}
