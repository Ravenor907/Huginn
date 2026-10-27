package io.github.ravenor907.muninn.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.ravenor907.muninn.MainActivity
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.call.CallManager
import io.github.ravenor907.muninn.net.call.CallSession
import io.github.ravenor907.muninn.net.call.Opus
import io.github.ravenor907.muninn.ui.MuninnTheme
import io.github.ravenor907.muninn.ui.Panic
import kotlinx.coroutines.launch

/**
 * The call, full screen and over the lock screen: the incoming ring's full-screen intent opens it, and so
 * does the Call button in a chat ([call]). It asks for the microphone when needed, then shows [CallScreen].
 */
class CallActivity : ComponentActivity() {
    private var pending: (() -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending; pending = null
        if (ok) p?.invoke() else {
            Toast.makeText(this, "Calls need the microphone. Allow it in Android's settings for Muninn.", Toast.LENGTH_LONG).show()
            if (CallManager.call.value?.phase == CallSession.Phase.INCOMING) CallManager.decline()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val privateName = { !((application as? Muninn)?.identity?.showPreview ?: false) && isLocked() }
        setContent {
            MuninnTheme {
                // Kids' TV: the remote-friendly call screen.
                if (io.github.ravenor907.muninn.tv.TvMic.active) io.github.ravenor907.muninn.tv.TvCallScreen(onAnswer = { withMic { CallManager.answer() } }, onClose = { finish() })
                else CallScreen(onBack = { finish() }, onMessage = { key ->
                    startActivity(Intent(this, MainActivity::class.java).putExtra("chat", key).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }, onAnswer = { withMic { CallManager.answer() } }, hideName = Panic.locked.value || privateName())
            }
        }
        // Gone once the call is over (after the short "Call ended" moment).
        lifecycleScope.launch { var seen = false; CallManager.call.collect { if (it != null) seen = true else if (seen) finish() } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun isLocked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun handle(i: Intent?) {
        when (i?.action) {
            ANSWER -> withMic { CallManager.answer() }
            CALL -> {
                val key = i.getStringExtra(KEY) ?: return finish()
                i.action = SHOW   // a rotation or a return must not start a second call
                withMic {
                    if (Opus.loadError != null) { Toast.makeText(this, "Calling isn't working on this phone", Toast.LENGTH_LONG).show(); finish() }
                    else if (!CallManager.start(key) && CallManager.call.value?.peerKey != key) { Toast.makeText(this, "You're already on a call", Toast.LENGTH_SHORT).show() }
                }
            }
            else -> if (CallManager.call.value == null) finish()
        }
    }

    private fun withMic(f: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f()
        else { pending = f; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    companion object {
        const val SHOW = "io.github.ravenor907.muninn.call.SHOW"
        const val ANSWER = "io.github.ravenor907.muninn.call.ANSWER"
        const val CALL = "io.github.ravenor907.muninn.call.CALL"
        private const val KEY = "peerKey"

        /** Calls [peerKey]: opens the call screen, asks for the microphone if needed, and starts the call. */
        fun call(c: Context, peerKey: String) = c.startActivity(Intent(c, CallActivity::class.java).setAction(CALL).putExtra(KEY, peerKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))

        /** Back to the call going on (a "Return to call" bar). */
        fun show(c: Context) = c.startActivity(Intent(c, CallActivity::class.java).setAction(SHOW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
