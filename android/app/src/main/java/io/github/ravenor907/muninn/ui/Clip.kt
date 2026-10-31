package io.github.ravenor907.muninn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** Copies text marked as sensitive, so keyboards and the clipboard preview don't show or keep it. */
object Clip {
    fun copy(context: Context, label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        // ClipDescription.EXTRA_IS_SENSITIVE (Android 13); older keyboards that know the name honour it too.
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        try { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip) } catch (_: Exception) {}
    }
}
