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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
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

    /** Muninn's own "Battery saver" switch (on unless the person wants maximum speed), for "Right away". */
    var saver: Boolean
        get() = prefs.getBoolean("batterySaver", true)
        set(v) { prefs.edit().putBoolean("batterySaver", v).apply(); refresh() }

    /**
     * Settings › Check for messages. Before it existed, "Get messages right away" off meant the 15-minute
     * check, so that is where such a phone starts. Only "Right away" keeps the Listening service.
     */
    var check: PowerLogic.Check
        get() = PowerLogic.Check.of(prefs.getString("checkFor", null))
            ?: if (prefs.getBoolean("stayConnected", true)) PowerLogic.Check.LIVE else PowerLogic.Check.QUARTER
        set(v) {
            prefs.edit().putString("checkFor", v.key).putBoolean("stayConnected", v == PowerLogic.Check.LIVE).apply()
            followed = null; followedEvery = -1L   // the schedule and the Listening service follow at once
            refresh()
        }

    /** Settings › Save more when the battery is low (on unless switched off). */
    var lowBatterySave: Boolean
        get() = prefs.getBoolean("lowBatterySave", true)
        set(v) { prefs.edit().putBoolean("lowBatterySave", v).apply(); refresh() }

    /** Settings › Quiet hours: on, from and to (hours of the day, 0 to 23), and the mode they use. */
    var quietOn: Boolean
        get() = prefs.getBoolean("quietOn", false)
        set(v) { prefs.edit().putBoolean("quietOn", v).apply(); refresh() }
    var quietFrom: Int
        get() = prefs.getInt("quietFrom", 23)
        set(v) { prefs.edit().putInt("quietFrom", Math.floorMod(v, 24)).apply(); refresh() }
    var quietTo: Int
        get() = prefs.getInt("quietTo", 7)
        set(v) { prefs.edit().putInt("quietTo", Math.floorMod(v, 24)).apply(); refresh() }
    var quietMode: PowerLogic.Check
        get() = PowerLogic.Check.of(prefs.getString("quietMode", null)) ?: PowerLogic.Check.HOUR
        set(v) { prefs.edit().putString("quietMode", v.key).apply(); refresh() }

    /** Settings › Quiet hours › Also during Do Not Disturb (on unless switched off). */
    var followDnd: Boolean
        get() = prefs.getBoolean("followDnd", true)
        set(v) { prefs.edit().putBoolean("followDnd", v).apply(); refresh() }

    /** Settings › Check less when it's quiet (on unless switched off). */
    var adaptive: Boolean
        get() = prefs.getBoolean("adaptiveCheck", true)
        set(v) { prefs.edit().putBoolean("adaptiveCheck", v).apply(); refresh() }

    /** Settings › Data saver: on mobile data, big files wait until you tap them, and updates wait for Wi-Fi (on unless switched off). */
    var dataSaver: Boolean
        get() = prefs.getBoolean("dataSaver", true)
        set(v) { prefs.edit().putBoolean("dataSaver", v).apply() }

    /** Something arrived or Muninn was opened: wide awake again (Check less when it's quiet starts over). */
    fun activity() {
        prefs.edit().putLong("lastActivity", System.currentTimeMillis()).apply()
        refresh()
    }

    /** Charging (or a TV, which always is). */
    fun charging(): Boolean {
        val battery = try { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 ||
            battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) == false ||
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** On a network that costs by the byte (mobile data, a hotspot), or Android's Data Saver is on. */
    fun metered(): Boolean = try {
        cm.isActiveNetworkMetered || cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (_: Exception) { false }

    /** Data saver is on and this network costs: big downloads wait for a tap, or for Wi-Fi. */
    fun savingData(): Boolean = dataSaver && metered()

    /** Do Not Disturb, Bedtime mode or another quiet mode is on. */
    private fun dndOn(): Boolean = try {
        app.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter.let {
            it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL && it != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
    } catch (_: Exception) { false }

    /** Settings › Find phones close by in the background (off unless switched on). */
    var closeBy: Boolean
        get() = prefs.getBoolean("closeByBackground", false)
        set(v) { prefs.edit().putBoolean("closeByBackground", v).apply(); refresh() }

    /** The Files tab is the one showing (MainActivity says): Wi-Fi discovery at full while Muninn is open on it. */
    val filesOpen = MutableStateFlow(false)
    @Volatile private var sharing = false

    /** What Muninn checks by right now: the person's choice, or less for quiet hours or a low battery. */
    fun effective(): PowerLogic.Check = PowerLogic.effective(inputs())

    /**
     * The background inbox check, as often as the mode in use says. "Only when open" chosen by the person
     * checks nothing; quiet hours that use it still wake Muninn hourly, only to see whether they've ended.
     */
    fun schedule(now: PowerLogic.Check = effective(), minutes: Long = PowerLogic.everyMinutes(inputs())) {
        val every = if (now == PowerLogic.Check.OPEN && check != PowerLogic.Check.OPEN) 60L else minutes
        try {
            val wm = WorkManager.getInstance(app)
            if (every == 0L) wm.cancelUniqueWork("inbox")
            else wm.enqueueUniquePeriodicWork("inbox", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<InboxWorker>(every, TimeUnit.MINUTES).build())
        } catch (_: Exception) {}
    }

    fun install() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; activity() }
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
            // The battery level, for saving more when it's low (a cheap check each time it changes).
            addAction(Intent.ACTION_BATTERY_CHANGED)
            // Do Not Disturb turned on or off: quiet hours follow it.
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
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
        // Sharing files: a transfer or Direct connect keeps Wi-Fi discovery at full (and the Files tab, while open).
        scope.launch {
            combine(app.net.transfer, app.direct.state) { t, d -> t.active || d.on }
                .collect { sharing = it; refresh() }
        }
        scope.launch { filesOpen.collect { refresh() } }
        applied?.let { radios(it) }
    }

    private fun inputs(): PowerLogic.Inputs {
        val battery = try { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 ||
            battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) == false ||
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val kids = try { io.github.ravenor907.muninn.tv.KidMode.on(app) } catch (_: Exception) { false }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val quiet = if ((quietOn && PowerLogic.inQuiet(quietFrom, quietTo, hour)) || (followDnd && dndOn())) quietMode else null
        val idle = (System.currentTimeMillis() - prefs.getLong("lastActivity", System.currentTimeMillis())).coerceAtLeast(0)
        return PowerLogic.Inputs(
            foreground = started > 0, screenOn = pm.isInteractive, inCall = inCall, kidsTv = kids, plugged = plugged,
            alwaysFull = !saver, batterySaver = pm.isPowerSaveMode, doze = pm.isDeviceIdleMode,
            unrestricted = pm.isIgnoringBatteryOptimizations(app.packageName), onWifi = onWifi, check = check,
            battery = percent, lowBatterySave = lowBatterySave, quiet = quiet, closeBy = closeBy, sharing = sharing || (filesOpen.value && started > 0),
            idleMs = if (started > 0) 0 else idle, adaptive = adaptive,
        )
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        val i = inputs()
        val next = PowerLogic.plan(i)
        _away.value = PowerLogic.whenAway(i)
        follow(PowerLogic.effective(i), i.kidsTv, PowerLogic.everyMinutes(i))
        // Quiet hours start and end on the hour, and Check less when it's quiet steps down hourly: look again then.
        main.removeCallbacks(onTheHour)
        main.postDelayed(onTheHour, 3_600_000L - System.currentTimeMillis() % 3_600_000L + 1_000L)
        val now = applied
        if (now == null || !PowerLogic.saves(now, next)) { main.removeCallbacks(later); waiting = null; use(next); return }
        // Saving more: wait a moment, in case the screen comes straight back on.
        if (waiting != next) { waiting = next; main.removeCallbacks(later); main.postDelayed(later, PowerLogic.GRACE_MS) }
    }

    // ------------------------------------------------------------------ battery report (Settings)

    /**
     * Today so far: time fully connected, time saving (two relays), and resting (nothing live, so nothing
     * running: the rest of the day); background checks made; data Muninn has used since the phone started.
     */
    data class Report(val connectedMs: Long, val savingMs: Long, val restingMs: Long, val checks: Int, val dataBytes: Long?)

    private var countedSince = System.currentTimeMillis()
    private var countedKind = -1

    private fun kindOf(p: PowerLogic.Plan): Int = when {
        p.relays == 0 -> 2
        p.why == PowerLogic.Why.SCREEN_OFF || p.why == PowerLogic.Why.BACKGROUND || p.why == PowerLogic.Why.BATTERY_SAVER -> 1
        else -> 0
    }

    /** Adds the time since the last count to its kind, starting over each day. */
    @Synchronized private fun count(now: Long, next: Int) {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now))
        val e = prefs.edit()
        val newDay = prefs.getString("reportDay", "") != today
        if (newDay) {
            e.putString("reportDay", today).putInt("reportChecks", 0)
            countedSince = maxOf(countedSince, midnight(now))
        }
        var connected = if (newDay) 0L else prefs.getLong("reportConnected", 0)
        var saving = if (newDay) 0L else prefs.getLong("reportSaving", 0)
        val spent = (now - countedSince).coerceAtLeast(0)
        when (countedKind) { 0 -> connected += spent; 1 -> saving += spent }
        e.putLong("reportConnected", connected).putLong("reportSaving", saving).apply()
        countedSince = now; countedKind = next
    }

    private fun midnight(now: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** A background inbox check ran (InboxWorker). */
    fun countCheck() {
        count(System.currentTimeMillis(), countedKind)
        prefs.edit().putInt("reportChecks", prefs.getInt("reportChecks", 0) + 1).apply()
    }

    fun report(): Report {
        val now = System.currentTimeMillis()
        count(now, countedKind)
        val connected = prefs.getLong("reportConnected", 0)
        val saving = prefs.getLong("reportSaving", 0)
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        val data = if (rx < 0 || tx < 0) null else rx + tx
        return Report(connected, saving, (now - midnight(now) - connected - saving).coerceAtLeast(0), prefs.getInt("reportChecks", 0), data)
    }

    private val onTheHour = Runnable { refresh() }
    private var followed: PowerLogic.Check? = null
    private var followedEvery = -1L

    /**
     * The mode in use changed (the person's choice, quiet hours or the battery): the background check runs as
     * often as it says, and the Listening service runs only while it's "Right away".
     */
    private fun follow(now: PowerLogic.Check, kids: Boolean, every: Long) {
        if (now == followed && every == followedEvery) return
        val modeChanged = now != followed
        followed = now
        followedEvery = every
        schedule(now, every)
        if (!modeChanged) return
        if (kids) return   // a Kids' TV always listens (KidMode keeps the service)
        if (now == PowerLogic.Check.LIVE) MessageService.start(app) else MessageService.stop(app)
    }

    private fun use(p: PowerLogic.Plan) {
        waiting = null
        val before = applied
        applied = p
        _plan.value = p
        count(System.currentTimeMillis(), kindOf(p))
        app.nostr.plan(p.relays, p.pingMs)
        radios(p)
        // No live connections now: check the inbox once at once, then the scheduled check carries on
        // ("Only when open" checks nothing until Muninn is opened again).
        if (p.relays == 0 && before?.relays != 0 && p.why != PowerLogic.Why.ONLY_OPEN) try {
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
