package io.github.ravenor907.muninn.tv

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The microphone on a Kids' TV. TV mics (the remote's, a camera's, a USB one) are tuned for a voice
 * search from across the room and come in very quiet, so everything recorded on the TV (voice messages,
 * speech to text, calls) goes through the same steps:
 *  - a source that isn't turned down: VOICE_RECOGNITION for clips and dictation, VOICE_COMMUNICATION
 *    for calls (it keeps the echo canceller, which a TV's loud speakers need), each falling back to MIC;
 *  - Android's AutomaticGainControl where the TV has one;
 *  - [Gain]: a software gain set by the parent's "Microphone level" (in the signed config, 7.5) with a
 *    soft limiter, so a loud voice close by bends instead of clipping.
 * Only used in kids' mode ([active]); a phone records exactly as before.
 */
object TvMic {
    /** True on a Kids' TV once its config is in. */
    @Volatile var active = false
    /** The parent's level, 0..100 (from the config's "mic"). */
    @Volatile var level = TvRules.DEFAULT_MIC

    /** 0 is the TV's own level, 100 is +30 dB; the default 50 is +15 dB (about 5.6 times louder). */
    fun gainDb(level: Int): Float = level.coerceIn(0, TvRules.MAX_MIC) * 0.3f

    /**
     * Gain with a soft limiter. Below [KNEE] the signal is only made louder; above it, it curves smoothly
     * toward full scale (tanh) and never wraps around. Pure Kotlin, so the unit tests can run it.
     */
    class Gain(level: Int) {
        private val g = 10f.pow(gainDb(level) / 20f)

        fun process(pcm: ShortArray, n: Int = pcm.size) {
            if (g == 1f) return
            for (i in 0 until n) pcm[i] = (shape(pcm[i] / 32768f * g) * 32767f).toInt().toShort()
        }

        companion object {
            const val KNEE = 0.6f
            fun shape(x: Float): Float {
                val a = abs(x)
                if (a <= KNEE) return x
                val y = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
                return if (x < 0) -y else y
            }
        }
    }

    // ------------------------------------------------------------------ for calls (CallMedia asks)

    @Volatile private var callAgc: AutomaticGainControl? = null

    /** The call's microphone source: unchanged on a phone. */
    fun callSource(): Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    /** The gain stage for a call's microphone, or null on a phone. Also turns on the TV's AGC for [sessionId]. */
    fun forCall(sessionId: Int): Gain? {
        if (!active) return null
        release()
        callAgc = agc(sessionId)
        return Gain(level)
    }

    fun release() { try { callAgc?.release() } catch (_: Exception) {}; callAgc = null }

    private fun agc(sessionId: Int): AutomaticGainControl? = try {
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.apply { enabled = true } else null
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ opening the microphone

    /** An AudioRecord for clips or dictation at [rate] Hz, mono 16-bit: the first source that opens. */
    @SuppressLint("MissingPermission")
    fun open(rate: Int, frame: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val r = try { AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 2 * 4)) } catch (e: Exception) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            r?.release()
        }
        return null
    }

    /** Turns on AGC for a clip's recording; release it when the recording ends. */
    fun clipAgc(sessionId: Int): AutomaticGainControl? = agc(sessionId)
}

/**
 * Voice messages on the TV, with the same result as [io.github.ravenor907.muninn.net.Voice] (plain AAC
 * frames in ADTS, about 4 KB a second, up to 5 minutes, kept only in memory) but recorded through
 * [TvMic]: an AudioRecord, the gain stage, then Android's AAC encoder.
 */
object TvRecorder {
    private const val RATE = 44_100
    private const val FRAME = 1024
    private const val MAX_BYTES = 2 * 1024 * 1024
    val recording = MutableStateFlow(false)
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    /** Called on the main thread when the 5-minute limit stops it; the screen then sends it. */
    var onLimit: (() -> Unit)? = null
    @Volatile private var stopFlag = false
    private var thread: Thread? = null
    private var out: ByteArrayOutputStream? = null
    private var started = 0L
    @Volatile private var failed = false

    fun start(context: Context): Boolean {
        cancel()
        val rec = TvMic.open(RATE, FRAME) ?: return false
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2 * 4)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) { rec.release(); return false }
        val buf = ByteArrayOutputStream()
        stopFlag = false; failed = false; out = buf
        started = System.currentTimeMillis()
        recording.value = true
        thread = Thread({ loop(rec, codec, buf) }, "muninn-tv-voice").apply { isDaemon = true; start() }
        return true
    }

    private fun loop(rec: AudioRecord, codec: MediaCodec, buf: ByteArrayOutputStream) {
        val agc = TvMic.clipAgc(rec.audioSessionId)
        val gain = TvMic.Gain(TvMic.level)
        val pcm = ShortArray(FRAME)
        val bytes = ByteArray(FRAME * 2)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        try {
            rec.startRecording()
            while (true) {
                val atLimit = System.currentTimeMillis() - started >= io.github.ravenor907.muninn.net.Voice.MAX_MS
                val end = stopFlag || atLimit
                var n = 0
                if (!end) { n = rec.read(pcm, 0, FRAME); if (n < 0) { failed = true; break } }
                gain.process(pcm, n)
                for (i in 0 until n) { bytes[2 * i] = pcm[i].toInt().toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                val inIdx = codec.dequeueInputBuffer(20_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    ib.clear(); ib.put(bytes, 0, n * 2)
                    codec.queueInputBuffer(inIdx, 0, n * 2, samples * 1_000_000 / RATE, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    samples += n
                }
                if (drain(codec, info, buf, end)) break
                if (atLimit && !stopFlag) { stopFlag = true; main.post { onLimit?.invoke() } }
            }
        } catch (e: Exception) { failed = true
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { agc?.release() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    /** Moves encoded frames out, each with its ADTS header. True once the end of stream came out. */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, buf: ByteArrayOutputStream, ending: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + if (ending) 2000 else 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (ending) 10_000 else 0)
            if (idx < 0) { if (!ending || System.currentTimeMillis() > deadline) return false else continue }
            val ob = codec.getOutputBuffer(idx)
            if (ob != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val frame = ByteArray(info.size)
                ob.position(info.offset); ob.get(frame)
                synchronized(buf) { if (buf.size() < MAX_BYTES - frame.size - 7) { buf.write(adts(frame.size)); buf.write(frame) } }
            }
            codec.releaseOutputBuffer(idx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
        }
    }

    fun elapsed(): Long = if (recording.value) System.currentTimeMillis() - started else 0

    /** Stops and returns (sound, length in ms), or null if it was too short or failed. */
    fun finish(): Pair<ByteArray, Int>? {
        val t = thread ?: return null
        val ms = (System.currentTimeMillis() - started).toInt().coerceAtMost(io.github.ravenor907.muninn.net.Voice.MAX_MS)
        stopFlag = true
        t.join(4000)
        thread = null; recording.value = false
        val buf = out; out = null
        if (failed || ms < 700 || buf == null) return null
        val bytes = synchronized(buf) { buf.toByteArray() }
        return io.github.ravenor907.muninn.net.Voice.check(listOf(bytes))?.let { it to ms }
    }

    fun cancel() {
        stopFlag = true
        thread?.join(2000)
        thread = null; out = null; recording.value = false
    }

    /** The 7-byte ADTS header for one AAC LC frame of [size] bytes, 44.1 kHz, mono. */
    fun adts(size: Int): ByteArray {
        val len = size + 7
        val profile = 2   // AAC LC
        val freq = 4      // 44100 Hz
        val chan = 1
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((profile - 1) shl 6) or (freq shl 2) or (chan shr 2)).toByte(),
            (((chan and 3) shl 6) or (len shr 11)).toByte(),
            ((len and 0x7FF) shr 3).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
