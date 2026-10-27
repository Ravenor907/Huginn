package io.github.ravenor907.muninn.net

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import io.github.ravenor907.muninn.Muninn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Watches the screen, charging, Android's Battery Saver and Doze, the network and calls, asks
 * [PowerLogic] what to do, and tells the relays, Bluetooth, Nearby and Wi-Fi discovery. Everything
 * runs on the main thread.
 */
class Power(private val app: Muninn) {
    private val main = Handler(Looper.getMainLooper())
    private val pm = app.getSystemService(PowerManager::class.java)
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val prefs = app.getSharedPreferences("muninn", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var started = 0
    @Volatile private var inCall = false
    @Volatile private var onWifi = false
    private var applied: PowerLogic.Plan? = null
    private var waiting: PowerLogic.Plan? = null
    private val later = Runnable { waiting?.let { use(it) } }

    private val _plan = MutableStateFlow(PowerLogic.plan(PowerLogic.Inputs(foreground = true, screenOn = true)))
    /** What Muninn does right now. */
    val plan: StateFlow<PowerLogic.Plan> = _plan.asStateFlow()
    private val _away = MutableStateFlow(_plan.value)
    /** What Muninn does once the screen goes off: the line Settings shows. */
    val away: StateFlow<PowerLogic.Plan> = _away.asStateFlow()

    /** Muninn's own "Battery saver" switch (on unless the person wants maximum speed). */
    var saver: Boolean
        get() = prefs.getBoolean("batterySaver", true)
        set(v) { prefs.edit().putBoolean("batterySaver", v).apply(); refresh() }

    fun install() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; refresh() }
            override fun onActivityStopped(a: Activity) { started = (started - 1).coerceAtLeast(0); refresh() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED); addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { refresh() }
            }, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}
        // Network changes: reconnect to the relays at once, and know whether this is Wi-Fi.
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { app.nostr.reconnectNow() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val w = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (w != onWifi) { onWifi = w; main.post { refresh() } }
                }
                override fun onLost(network: Network) { if (onWifi) { onWifi = false; main.post { refresh() } } }
            })
        } catch (_: Exception) {}
        refresh()
    }

    /** Called once the vault is open: calls are known from now on, and Bluetooth, Nearby and Wi-Fi exist. */
    fun ready() {
        scope.launch {
            combine(io.github.ravenor907.muninn.net.call.CallManager.call, io.github.ravenor907.muninn.net.call.RoomManager.room) { c, r -> c != null || r != null }
                .collect { inCall = it; refresh() }
        }
        applied?.let { radios(it) }
    }

    private fun inputs(): PowerLogic.Inputs {
        val battery = try { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 ||
            battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) == false ||
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val kids = try { io.github.ravenor907.muninn.tv.KidMode.on(app) } catch (_: Exception) { false }
        return PowerLogic.Inputs(
            foreground = started > 0, screenOn = pm.isInteractive, inCall = inCall, kidsTv = kids, plugged = plugged,
            alwaysFull = !saver, batterySaver = pm.isPowerSaveMode, doze = pm.isDeviceIdleMode,
            unrestricted = pm.isIgnoringBatteryOptimizations(app.packageName), onWifi = onWifi,
        )
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        val i = inputs()
        val next = PowerLogic.plan(i)
        _away.value = PowerLogic.whenAway(i)
        val now = applied
        if (now == null || !PowerLogic.saves(now, next)) { main.removeCallbacks(later); waiting = null; use(next); return }
        // Saving more: wait a moment, in case the screen comes straight back on.
        if (waiting != next) { waiting = next; main.removeCallbacks(later); main.postDelayed(later, PowerLogic.GRACE_MS) }
    }

    private fun use(p: PowerLogic.Plan) {
        waiting = null
        val before = applied
        applied = p
        _plan.value = p
        app.nostr.plan(p.relays, p.pingMs)
        radios(p)
        // No live connections now: check the inbox once at once, then the 15-minute check carries on.
        if (p.relays == 0 && before?.relays != 0) try {
            WorkManager.getInstance(app).enqueueUniqueWork("inbox-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<InboxWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        } catch (_: Exception) {}
    }

    private fun radios(p: PowerLogic.Plan) {
        if (!app.ready.value) return
        try { app.ble.power(p.radio) } catch (_: Exception) {}
        try { app.close.power(p.radio) } catch (_: Exception) {}
        try { app.net.discovery(p.wifi) } catch (_: Exception) {}
    }
}
