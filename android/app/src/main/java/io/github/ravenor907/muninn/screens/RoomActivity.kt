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
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.call.RoomManager
import io.github.ravenor907.muninn.ui.MuninnTheme
import io.github.ravenor907.muninn.ui.Panic
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A voice room or group call, full screen (also over the lock screen for a group call's ring). Joining,
 * calling a group and answering a ring come through here so the microphone can be asked for first.
 */
class RoomActivity : ComponentActivity() {
    private var pending: (() -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending; pending = null
        if (ok) p?.invoke() else {
            Toast.makeText(this, "Voice rooms need the microphone. Allow it in Android's settings for Muninn.", Toast.LENGTH_LONG).show()
            if (RoomManager.ring.value != null) RoomManager.declineRing()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val hide = { Panic.locked.value || (!((application as? Muninn)?.identity?.showPreview ?: false) &&
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) }
        setContent {
            MuninnTheme {
                RoomScreen(onBack = { finish() }, onJoinRing = { withMic { say(RoomManager.answerRing()) } }, hideNames = hide())
            }
        }
        // Gone once there's no room and no ring any more.
        lifecycleScope.launch {
            var seen = false
            RoomManager.room.combine(RoomManager.ring) { r, g -> r != null || g != null }.collect { if (it) seen = true else if (seen) finish() }
        }
        lifecycleScope.launch { RoomManager.notices.collect { Toast.makeText(this@RoomActivity, it, Toast.LENGTH_LONG).show() } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun say(why: String?) { if (why != null) Toast.makeText(this, why, Toast.LENGTH_LONG).show() }

    private fun handle(i: Intent?) {
        when (i?.action) {
            ANSWER -> withMic { say(RoomManager.answerRing()) }
            JOIN -> {
                val g = i.getStringExtra(GROUP) ?: return finish()
                val ch = i.getStringExtra(ROOM) ?: return finish()
                i.action = SHOW   // a rotation or a return must not join again
                withMic { RoomManager.join(g, ch)?.let { say(it); if (RoomManager.room.value == null) finish() } }
            }
            CALL -> {
                val g = i.getStringExtra(GROUP) ?: return finish()
                val keys = i.getStringArrayExtra(KEYS)?.toList() ?: emptyList()
                i.action = SHOW
                withMic { RoomManager.callGroup(g, keys)?.let { say(it); if (RoomManager.room.value == null) finish() } }
            }
            else -> if (RoomManager.room.value == null && RoomManager.ring.value == null) finish()
        }
    }

    private fun withMic(f: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f()
        else { pending = f; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    companion object {
        const val SHOW = "io.github.ravenor907.muninn.room.SHOW"
        const val ANSWER = "io.github.ravenor907.muninn.room.ANSWER"
        const val JOIN = "io.github.ravenor907.muninn.room.JOIN"
        const val CALL = "io.github.ravenor907.muninn.room.CALL"
        private const val GROUP = "group"
        private const val ROOM = "room"
        private const val KEYS = "keys"

        private fun open(c: Context, i: Intent) = c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        /** Joins a voice room (or a group call going on) of [g]. */
        fun join(c: Context, g: String, ch: String) = open(c, Intent(c, RoomActivity::class.java).setAction(JOIN).putExtra(GROUP, g).putExtra(ROOM, ch))
        /** Rings [keys] of [g] (everyone who can join, when empty) into a new group call. */
        fun call(c: Context, g: String, keys: List<String>) = open(c, Intent(c, RoomActivity::class.java).setAction(CALL).putExtra(GROUP, g).putExtra(KEYS, keys.toTypedArray()))
        /** Back to the room going on. */
        fun show(c: Context) = open(c, Intent(c, RoomActivity::class.java).setAction(SHOW))
    }
}
