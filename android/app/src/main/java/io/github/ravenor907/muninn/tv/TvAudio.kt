package io.github.ravenor907.muninn.tv

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Microphones and speech to text on the TV (the remote's mic, a USB or Bluetooth mic, a camera's mic). */
object TvAudio {
    /** Inputs that aren't a microphone someone can talk into. */
    private val NOT_MICS = buildSet {
        add(AudioDeviceInfo.TYPE_REMOTE_SUBMIX); add(AudioDeviceInfo.TYPE_TELEPHONY); add(AudioDeviceInfo.TYPE_FM_TUNER)
        add(AudioDeviceInfo.TYPE_TV_TUNER); add(AudioDeviceInfo.TYPE_HDMI); add(AudioDeviceInfo.TYPE_HDMI_ARC)
        add(AudioDeviceInfo.TYPE_LINE_ANALOG); add(AudioDeviceInfo.TYPE_LINE_DIGITAL); add(AudioDeviceInfo.TYPE_AUX_LINE)
        if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_HDMI_EARC); add(AudioDeviceInfo.TYPE_ECHO_REFERENCE) }
    }

    /** True if some microphone is connected right now. */
    fun hasMic(context: Context): Boolean = try {
        context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.any { it.isSource && it.type !in NOT_MICS } == true
    } catch (e: Exception) { false }

    fun canDictate(context: Context): Boolean = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (e: Exception) { false }
}

/**
 * One "Say something": listens once, reports what it heard so far and the final words. Uses the on-device
 * recognizer when the TV has one (nothing leaves the TV), else the TV's usual speech service.
 * Create and use it on the main thread; [close] when the screen goes.
 */
class TvSpeech(private val context: Context) {
    private var rec: SpeechRecognizer? = null

    fun start(partial: (String) -> Unit, done: (String) -> Unit, fail: (String) -> Unit) {
        close()
        val r = try {
            if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) { fail("This TV can't turn speech into words."); return }
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(partial)
            }
            override fun onResults(results: Bundle?) {
                val t = first(results)
                if (t.isNullOrBlank()) fail("I didn't catch that. Try again?") else done(t)
            }
            override fun onError(error: Int) {
                fail(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Try again?"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Muninn isn't allowed to use the microphone. Ask a parent."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs the internet right now. Try a quick message instead."
                    else -> "The microphone isn't working right now."
                })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try { r.startListening(i) } catch (e: Exception) { fail("The microphone isn't working right now.") }
    }

    private fun first(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    fun stop() { try { rec?.stopListening() } catch (_: Exception) {} }

    fun close() {
        try { rec?.cancel(); rec?.destroy() } catch (_: Exception) {}
        rec = null
    }
}
