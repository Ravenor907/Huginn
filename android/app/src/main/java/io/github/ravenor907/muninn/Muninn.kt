package io.github.ravenor907.muninn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Close
import io.github.ravenor907.muninn.net.CloseFiles
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.Direct
import io.github.ravenor907.muninn.net.Identity
import io.github.ravenor907.muninn.net.InboxWorker
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.net.Nostr
import io.github.ravenor907.muninn.net.Vault
import io.github.ravenor907.muninn.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * One of each for the whole app. Everything that reads saved data starts only once the vault is
 * open: right away without a password, or after the person types it.
 */
class Muninn : Application() {
    lateinit var vault: Vault
    lateinit var identity: Identity
    lateinit var nostr: Nostr
    lateinit var net: LocalSend
    lateinit var close: Close
    lateinit var ble: io.github.ravenor907.muninn.net.Ble
    lateinit var closeFiles: CloseFiles
    lateinit var contacts: Contacts
    lateinit var chat: Chat
    lateinit var direct: Direct
    lateinit var updater: Updater
    /** True once everything is running (the vault is open). */
    val ready = MutableStateFlow(false)
    @Volatile var visible = false
    /** The conversation on screen right now ("" for none). */
    @Volatile var openChat = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        vault = Vault(this)
        identity = Identity(this, vault)
        io.github.ravenor907.muninn.ui.Access.apply {
            size = identity.uiSize; text = identity.textSize; contrast = identity.highContrast
            colorBlind = identity.colorBlind; reduceMotion = identity.reduceMotion
        }
        nostr = Nostr(this) { if (vault.locked.value) null else identity.nostrSecret() }
        direct = Direct(this)
        updater = Updater(this, identity)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel("messages")   // 0.32 and older: no vibration set
            deleteNotificationChannel("chat")       // test builds: sound not set
            createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages. Pops up on screen, vibrates and plays a sound."
                enableVibration(true); vibrationPattern = BUZZ
                // The raven call: Muninn's own message sound.
                setSound(android.net.Uri.parse("android.resource://$packageName/raw/raven_message"),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
        }
        // While locked, the internet inbox can still say something arrived, without opening it.
        nostr.onEnvelope = { notify(null, null, "inbox"); false }
        if (vault.openDefault()) start()
        // Back online (Wi-Fi to data, out of a dead zone): reconnect to the relays at once.
        try {
            getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { nostr.reconnectNow() }
            })
        } catch (_: Exception) {}
        io.github.ravenor907.muninn.net.MessageService.start(this)
        // Every 15 minutes (Android's shortest), check the internet inbox even when Muninn is closed.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("inbox", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES).build())
    }

    /** Called once the vault is open. */
    @Synchronized
    fun start() {
        if (ready.value) return
        net = LocalSend(this, identity)
        net.mailbox = nostr.publicKey
        close = Close(this, identity)
        ble = io.github.ravenor907.muninn.net.Ble(this) { chat.hello() }
        contacts = Contacts(this, vault)
        chat = Chat(this, net, close, ble, nostr, contacts, vault)
        closeFiles = CloseFiles(this, close, net)
        // A notification for every message, unless that very chat is on screen: then just a buzz.
        scope.launch { chat.arrivals.collect { (name, text, key) -> if (visible && openChat == key && !io.github.ravenor907.muninn.ui.Panic.locked.value) buzz() else notify(name, text, key) } }
        // Unsent: take its notification down too, so the text doesn't linger there.
        scope.launch { chat.recalls.collect { key -> NotificationManagerCompat.from(this@Muninn).cancel(key.hashCode()) } }
        ready.value = true
        // Messages that came while locked were only announced: ask the relays for them again now.
        nostr.resubscribe()
    }

    private var listening = false

    /** Stay reachable: the relays always (even locked, to say "New message"), and the Wi-Fi side once open. */
    @Synchronized
    fun listen() {
        nostr.keepAlive = true
        nostr.connect()
        if (!ready.value || listening) return
        listening = true
        net.start()
        net.refresh()
    }

    /** Privacy first: unless the person allows previews, a notification says only "New message". */
    fun notify(name: String?, text: String?, key: String) {
        // Panic-locked: never a name or text on the notification.
        val preview = identity.showPreview && name != null && !io.github.ravenor907.muninn.ui.Panic.locked.value
        val open = PendingIntent.getActivity(this, key.hashCode(), Intent(this, MainActivity::class.java).putExtra("chat", key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, MESSAGES).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (preview) name else "Muninn").setContentText(if (preview) text else "New message")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(BUZZ).setOnlyAlertOnce(key == "inbox")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build()
        try { NotificationManagerCompat.from(this).notify(key.hashCode(), n) } catch (_: SecurityException) {}
    }

    /** A short double buzz: a message arrived in the chat you're looking at. */
    fun buzz() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createWaveform(BUZZ, -1))
        } catch (_: Exception) {}
    }

    companion object {
        const val MESSAGES = "raven_messages"
        val BUZZ = longArrayOf(0, 140, 90, 140)
    }
}
