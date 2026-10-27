package io.github.ravenor907.muninn.tv

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Muninn keeping itself up to date on a Kids' TV: it looks for a new release when it starts and once a
 * day (and when a parent presses Update TV on their phone), downloads it with the usual [Updater], and
 * installs it with a PackageInstaller session. Android asks once on the TV (Install, with the remote);
 * after Muninn has installed itself once, Android 12 and newer let later updates go in without asking.
 */
object TvUpdate {
    /** What the TV screen shows: a confirm waiting for OK on the remote, or a note for a parent. */
    data class State(val confirm: Intent? = null, val needsParent: Boolean = false, val status: String = "")
    val state = MutableStateFlow(State())
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-update").apply { isDaemon = true } }
    @Volatile private var started = false
    @Volatile private var waiting: File? = null
    private const val ACTION = "io.github.ravenor907.muninn.tv.INSTALLED"
    private const val DAY = 24 * 60 * 60_000L

    /** Kids' mode only, once: hooks the installer, checks a minute after start, then every day. */
    fun start(app: Muninn) {
        if (started) return
        started = true
        app.updater.installer = { apk -> install(app, apk); true }
        timer.scheduleWithFixedDelay({ try { checkNow(app) } catch (_: Exception) {} }, 60_000, DAY, TimeUnit.MILLISECONDS)
    }

    /** A parent asked (room tvconfig "update"), or the daily check. */
    fun checkNow(app: Muninn) { app.updater.check(installAfter = true) }

    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** Installs [apk] with a session; without the "install unknown apps" permission it waits for a parent. */
    fun install(c: Context, apk: File) {
        if (!canInstall(c)) { waiting = apk; state.value = State(needsParent = true, status = "A new Muninn is ready. A parent needs to allow updates once."); return }
        waiting = null
        Thread {
            try {
                val pi = c.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(c.packageName)
                    // Muninn stays the installer of record, so every later update can skip the prompt.
                    if (Build.VERSION.SDK_INT >= 30) setInstallerPackageName(c.packageName)
                    if (Build.VERSION.SDK_INT >= 31) {
                        // Needs UPDATE_PACKAGES_WITHOUT_USER_ACTION (manifest). Android still asks once
                        // (STATUS_PENDING_USER_ACTION below) while another app is the installer of record.
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("Muninn.apk", 0, apk.length()).use { os -> apk.inputStream().use { it.copyTo(os) }; s.fsync(os) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val done = PendingIntent.getBroadcast(c, id, Intent(c, TvInstallResult::class.java).setAction(ACTION).setPackage(c.packageName), flags)
                    s.commit(done.intentSender)
                }
                state.value = State(status = "Installing the new Muninn…")
            } catch (e: Exception) {
                state.value = State(status = "The update couldn't be installed. It tries again tomorrow.")
            }
        }.start()
    }

    /** Back from the "install unknown apps" setting with it allowed: install what was waiting. */
    fun resume(c: Context) {
        val apk = waiting ?: return
        if (canInstall(c) && apk.exists()) install(c, apk)
    }

    fun onResult(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else i.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                state.value = State(confirm = confirm, status = "A new Muninn is ready")
                // On screen now: Android's Install box comes straight up. Otherwise the TV screen offers it.
                try { if ((c.applicationContext as? Muninn)?.visible == true) c.startActivity(confirm) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> state.value = State(status = "Muninn is up to date")
            else -> state.value = State(status = "The update didn't go in. It tries again tomorrow.")
        }
    }

    /** The remote's OK on "Install the update": Android's own Install box. */
    fun confirm(c: Context) {
        val i = state.value.confirm ?: return
        state.value = state.value.copy(confirm = null)
        try { c.startActivity(i) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ the parent's one-time TV setup

    /** One thing a parent turns on once on the TV. [open] goes to it; true when done. */
    data class Need(val id: String, val title: String, val why: String)

    /** What's still off. Microphone and notifications are asked for in the app; the rest are Android settings. */
    fun needs(c: Context): List<Need> = buildList {
        if (TvAudio.hasMic(c) && ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            add(Need("mic", "Allow the microphone", "For calls, voice messages and talking to the TV."))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            add(Need("notify", "Allow notifications", "So new messages show while something else is on."))
        if (!Settings.canDrawOverlays(c))
            add(Need("overlay", "Show over other apps", "So a call from a parent comes up over a show or a game."))
        if (Build.VERSION.SDK_INT >= 34 && c.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == false)
            add(Need("fullscreen", "Full-screen calls", "So the call screen can open by itself when someone calls."))
        if (!canInstall(c))
            add(Need("install", "Install updates and apps", "So Muninn can keep itself up to date, and install apps a parent sends."))
    }

    /**
     * Opens the Android setting for [id]. False if this TV has no such screen (some TVs hide them). Never
     * Muninn's own app page: a child can reach this list, and that page has "Clear storage", which would
     * start the TV over.
     */
    fun open(c: Context, id: String): Boolean {
        val pkg = Uri.parse("package:${c.packageName}")
        val tries = when (id) {
            "overlay" -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) else emptyList()
            "install" -> listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg), Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            else -> emptyList()
        }
        for (i in tries) try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) {}
        return false
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("muninn-tv", Context.MODE_PRIVATE)
    /** The setup list comes up by itself once after pairing; after that only from "For a parent". */
    fun setupShown(c: Context) = prefs(c).getBoolean("setupShown", false)
    fun markSetupShown(c: Context) = prefs(c).edit().putBoolean("setupShown", true).apply()
}

/** The PackageInstaller session's answer. */
class TvInstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = TvUpdate.onResult(context, intent)
}
