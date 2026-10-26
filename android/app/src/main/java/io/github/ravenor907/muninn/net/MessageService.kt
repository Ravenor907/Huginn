package io.github.ravenor907.muninn.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.MainActivity
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R

/**
 * Keeps Muninn listening for messages while it's closed, so they arrive (and notify) right away
 * instead of when Android next lets the app run. No Google push servers: the phone stays
 * connected to the relays itself. Android requires a small ongoing notification for this; its
 * channel is silent and minimised, and can be hidden in the phone's settings.
 */
class MessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as Muninn
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening for messages", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps Muninn connected so messages arrive right away. You can hide this one; message notifications still come."
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle("Muninn")
            .setContentText("Listening for messages").setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            else if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (e: Exception) {
            // Android must see startForeground after startForegroundService, or it closes the app.
            try { startForeground(ID, n) } catch (_: Exception) {}
            try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf(); return START_NOT_STICKY
        }
        if (!app.identity.stayConnected) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        running = true
        app.listen()
        return START_STICKY   // if Android stops it for memory, it comes back
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    companion object {
        private const val CHANNEL = "listening"
        private const val ID = 9
        @Volatile var running = false

        fun start(context: Context) {
            val app = context.applicationContext as Muninn
            if (!app.identity.stayConnected) return
            try { ContextCompat.startForegroundService(context, Intent(context, MessageService::class.java)) } catch (_: Exception) {}
        }

        fun stop(context: Context) { context.stopService(Intent(context, MessageService::class.java)) }

        /** Without this, Android's battery saver cuts the connection while the phone sleeps. */
        fun unrestricted(context: Context) =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

        @android.annotation.SuppressLint("BatteryLife")
        fun askUnrestricted(context: Context) {
            try {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }
    }
}

/** After the phone restarts or Muninn updates, start listening again. */
class StartAtBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) MessageService.start(context)
    }
}
