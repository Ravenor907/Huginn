package io.github.ravenor907.muninn.ui

import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.ravenor907.muninn.Muninn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Panic lock: lock Muninn at once from the quick settings tile, the "Lock now" launcher shortcut,
 * or (if turned on) by shaking the phone hard 3 times while Muninn is open. Locking hides the
 * screen behind the lock, takes down message notifications, and keeps message text out of new
 * ones until Muninn is unlocked again.
 */
object Panic {
    private const val PREFS = "muninn"
    private val _locked = MutableStateFlow(false)
    /** True from a panic lock until the person unlocks. MainActivity shows the lock screen while it is. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("panicLock", true)
    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean("panicLock", on).apply(); updateShake(context) }
    fun shakeOn(context: Context): Boolean = prefs(context).getBoolean("shakeLock", false)
    fun setShake(context: Context, on: Boolean) { prefs(context).edit().putBoolean("shakeLock", on).apply(); updateShake(context) }

    /** Locks now. False if panic lock is off in Settings. */
    fun lock(context: Context): Boolean {
        if (!enabled(context)) return false
        _locked.value = true
        clearMessages(context)
        return true
    }

    /** The person unlocked: message text may show in notifications again (if they allow previews). */
    fun unlocked() { _locked.value = false }

    /** Takes down every message notification. Leaves the "Listening" and transfer ones alone. */
    fun clearMessages(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.activeNotifications.forEach { sb ->
                if (sb.notification.channelId == Muninn.MESSAGES) nm.cancel(sb.tag, sb.id)
            }
        } catch (_: Exception) {}
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ shake to lock

    private var shakeContext: Context? = null
    private var listening = false
    private val shakes = ArrayDeque<Long>()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
            val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            if (g < HARD) return
            val now = SystemClock.elapsedRealtime()
            // One hard shake makes several readings over the threshold: count it once.
            if (shakes.isNotEmpty() && now - shakes.last() < GAP) return
            shakes.addLast(now)
            while (shakes.isNotEmpty() && now - shakes.first() > WINDOW) shakes.removeFirst()
            if (shakes.size >= 3) {
                shakes.clear()
                shakeContext?.let { lock(it) }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Listens for shakes only while Muninn's screen is showing. Call once from the activity's onCreate. */
    fun watch(owner: LifecycleOwner, context: Context) {
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { shakeContext = context.applicationContext; updateShake(context) }
                Lifecycle.Event.ON_STOP -> { shakeContext = null; updateShake(context) }
                else -> {}
            }
        })
    }

    private fun updateShake(context: Context) {
        val want = shakeContext != null && enabled(context) && shakeOn(context)
        if (want == listening) return
        val sm = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
        if (want) {
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            shakes.clear()
            listening = sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.unregisterListener(listener)
            listening = false
        }
    }

    /** True if this phone can feel shakes at all. */
    fun canShake(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    private const val HARD = 2.7f      // about 2.7 g: a deliberate, hard shake, not walking or a bumpy ride
    private const val GAP = 350L       // ms between counted shakes
    private const val WINDOW = 2000L   // 3 shakes within 2 seconds
}
