package io.github.ravenor907.muninn.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.ravenor907.muninn.Muninn

/**
 * Reply straight from a message notification (Android's inline reply). The text is sent like one typed in
 * the chat; the notification goes once it's on its way. Only offered (and only sent) while
 * Muninn is open, not panic-locked, not a Kids' TV, and the app lock isn't due.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? Muninn ?: return
        val key = intent.getStringExtra(KEY) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TEXT)?.toString()?.trim().orEmpty()
        val nm = NotificationManagerCompat.from(context)
        // Checked again here: the lock may have come due (or Kids' TV mode come on) since the notification showed.
        if (text.isEmpty() || !app.canReply()) { nm.cancel(key.hashCode()); return }
        val pending = goAsync()
        Thread {
            try { app.chat.replyFromNotification(key, text) } catch (_: Exception) {}
            nm.cancel(key.hashCode())
            pending.finish()
        }.start()
    }

    companion object {
        const val KEY = "key"
        const val TEXT = "reply"
        const val ACTION = "io.github.ravenor907.muninn.REPLY"
    }
}
