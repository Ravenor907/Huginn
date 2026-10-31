package io.github.ravenor907.muninn.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ravenor907.muninn.MainActivity

/**
 * Kids' TV: a short banner for a new message over whatever is on (Google TV may keep notifications out of
 * sight while a show or a game plays). Only when a parent allowed "Show over other apps" and Muninn isn't
 * on screen. It goes away by itself after a few seconds; on the remote OK opens the chat and Back (or any
 * other key) closes it. The text is the notification's: just "New message" while previews are off.
 */
object TvBanner {
    private const val SHOW_MS = 8_000L
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hide = Runnable { dismiss() }

    /** [title] and [text] are what the notification shows (already private when previews are off). */
    fun show(c: Context, title: String, text: String, key: String) {
        val app = c.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        main.post { try { put(app, title, text, key) } catch (_: Exception) { view = null } }
    }

    private fun put(c: Context, title: String, text: String, key: String) {
        dismiss()
        val wm = c.getSystemService(WindowManager::class.java) ?: return
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
        fun line(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
            this.text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val box = object : LinearLayout(c) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.action != KeyEvent.ACTION_UP) return true
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { dismiss(); open(c, key) }
                    else -> dismiss()
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.argb(240, 20, 22, 30)); setStroke(dp(2), Color.rgb(212, 175, 55)) }
            isFocusable = true; isFocusableInTouchMode = true
            addView(line(title, 24f, Color.WHITE, bold = true))
            addView(line(text, 20f, Color.rgb(225, 225, 230)))
            addView(line("OK to open", 20f, Color.rgb(170, 170, 180)))
            setOnClickListener { dismiss(); open(c, key) }
        }
        val lp = WindowManager.LayoutParams(
            dp(480), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(32); y = dp(32) }
        wm.addView(box, lp)
        view = box
        box.requestFocus()
        main.postDelayed(hide, SHOW_MS)
    }

    fun dismiss() {
        main.removeCallbacks(hide)
        val v = view ?: return
        view = null
        try { v.context.getSystemService(WindowManager::class.java)?.removeView(v) } catch (_: Exception) {}
    }

    private fun open(c: Context, key: String) {
        try {
            c.startActivity(Intent(c, MainActivity::class.java).putExtra("chat", key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {}
    }
}
