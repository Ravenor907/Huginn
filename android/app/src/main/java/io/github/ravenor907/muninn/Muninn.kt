package io.github.ravenor907.muninn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Close
import io.github.ravenor907.muninn.net.CloseFiles
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.Direct
import io.github.ravenor907.muninn.net.Identity
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.net.Nostr
import io.github.ravenor907.muninn.net.Vault
import io.github.ravenor907.muninn.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
    /** Saving battery while the screen is off (see net/PowerLogic). */
    lateinit var power: io.github.ravenor907.muninn.net.Power
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
            colorBlind = identity.colorBlind; reduceMotion = identity.reduceMotion; look = io.github.ravenor907.muninn.ui.Looks.of(identity.look)
        }
        nostr = Nostr(this) { if (vault.locked.value) null else identity.nostrSecret() }
        direct = Direct(this)
        updater = Updater(this, identity)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel("messages")   // unused: no vibration set
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
            // Quiet hours: messages from people not on the "Always right away" list, with no sound or pop-up.
            createNotificationChannel(NotificationChannel(QUIET, "Messages during quiet hours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Messages from people not on your Always right away list while quiet hours last. No sound or pop-up."
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
        }
        // While locked, the internet inbox can still say something arrived, without opening it.
        nostr.onEnvelope = { notify(null, null, "inbox"); false }
        // Screen, charging, Battery Saver, Doze, calls and the network decide how connected to stay.
        // (It also reconnects to the relays at once when the network comes back.)
        power = io.github.ravenor907.muninn.net.Power(this)
        power.install()
        // Moving to another device (cap x1): one under way goes on, and this device stays locked until it's done.
        io.github.ravenor907.muninn.net.Move.init(this)
        if (vault.openDefault()) start()
        io.github.ravenor907.muninn.net.MessageService.start(this)
        // Check the internet inbox even when Muninn is closed: every 15 minutes (Android's shortest) or every
        // hour, as Settings › Check for messages says; never for "Only when open".
        power.schedule()
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
        io.github.ravenor907.muninn.tv.Tv.install(this)
        io.github.ravenor907.muninn.net.call.CallManager.init(this, chat)
        io.github.ravenor907.muninn.net.call.RoomManager.init(this)
        closeFiles = CloseFiles(this, close, net)
        // A notification for every message, unless that very chat is on screen: then just a buzz.
        scope.launch { chat.arrivals.collect { (name, text, key) ->
            power.activity()   // Check less when it's quiet starts over
            // A 1:1 message opens the conversation window, so the next one comes at once too. Group messages don't.
            if (io.github.ravenor907.muninn.net.NetLogic.opensTalk(key)) power.talk()
            // Muted chats stay quiet; an archived chat comes back to the list (unless muted).
            if (!chat.flags.arrived(key)) return@collect
            if (visible && openChat == key && !io.github.ravenor907.muninn.ui.Panic.locked.value) { buzz(); return@collect }
            // Quiet hours kept open for the "Always right away" list: everyone else's messages wait quietly.
            when (power.alert(key)) {
                io.github.ravenor907.muninn.net.PowerLogic.Alert.LOUD -> notify(name, text, key)
                io.github.ravenor907.muninn.net.PowerLogic.Alert.QUIET -> notify(name, text, key, quiet = true)
                io.github.ravenor907.muninn.net.PowerLogic.Alert.NONE -> {}
            }
        } }
        // Unsent: take its notification down too, so the text doesn't linger there.
        scope.launch { chat.recalls.collect { key -> NotificationManagerCompat.from(this@Muninn).cancel(key.hashCode()) } }
        ready.value = true
        io.github.ravenor907.muninn.net.Move.ready(this)
        // A window already on screen (onStart ran before the vault opened): say so now, with Active status on.
        chat.foreground(visible)
        power.ready()
        // Messages that came while locked were only announced: ask the relays for them again now.
        nostr.resubscribe()
    }

    private var listening = false
    /** The one sweep of the Wi-Fi network for devices (done once Muninn is on screen). */
    private var swept = false

    /** Stay reachable: the relays always (even locked, to say "New message"), and the Wi-Fi side once open. */
    @Synchronized
    fun listen() {
        nostr.keepAlive = true
        nostr.connect()
        if (!ready.value) return
        if (!listening) { listening = true; net.start() }
        // Asking every address on the Wi-Fi only once Muninn is on screen, never from the background.
        if (visible && !swept) { swept = true; net.refresh() }
    }

    /** The refresh button on Chats: every relay checked again now, and anything not yet delivered sent again. */
    fun refreshChats() {
        if (!ready.value) return
        nostr.reconnectNow()
        nostr.resubscribe()
        nostr.checkInbox()
        chat.retryAllNow()
        net.announce()
    }

    /** The refresh button on Nearby: look again for phones close by, Huginn over Bluetooth, and devices on the Wi-Fi. */
    fun refreshNearby() {
        if (!ready.value) return
        close.lookAgain()
        ble.start()
        net.refresh()
    }

        /** Privacy first: unless the person allows previews, a notification says only "New message". */
    fun notify(name: String?, text: String?, key: String, quiet: Boolean = false) {
        // Panic-locked: never a name or text on the notification.
        val preview = identity.showPreview && name != null && !io.github.ravenor907.muninn.ui.Panic.locked.value
        val open = PendingIntent.getActivity(this, key.hashCode(), Intent(this, MainActivity::class.java).putExtra("chat", key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, if (quiet) QUIET else MESSAGES).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (preview) name else "Muninn").setContentText(if (preview) text else "New message")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply { if (quiet) setSilent(true) else setVibrate(BUZZ) }.setOnlyAlertOnce(key == "inbox")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open)
            .apply { replyAction(key)?.let { addAction(it) } }.build()
        try { NotificationManagerCompat.from(this).notify(key.hashCode(), n) } catch (_: SecurityException) {}
        // Kids' TV: also a short banner over the show or game, when a parent allowed "Show over other apps".
        if (!visible) try {
            if (io.github.ravenor907.muninn.tv.KidMode.on(this))
                io.github.ravenor907.muninn.tv.TvBanner.show(this, if (preview) name!! else "Muninn", if (preview) text ?: "New message" else "New message", key)
        } catch (_: Exception) {}
    }

    /**
     * Reply from the notification is allowed now: Muninn open, not panic-locked, not a Kids' TV, and the
     * app lock isn't due (the same check MainActivity makes before showing anything).
     */
    fun canReply(): Boolean {
        val kids = try { io.github.ravenor907.muninn.tv.KidMode.on(this) } catch (_: Exception) { true }
        val lockDue = try {
            io.github.ravenor907.muninn.ui.AppLock.due(vault.hasPassword, io.github.ravenor907.muninn.ui.AppLock.awaySince,
                android.os.SystemClock.elapsedRealtime(), identity.lockAfter)
        } catch (_: Exception) { true }
        return io.github.ravenor907.muninn.ui.AppLock.canReply(ready.value, io.github.ravenor907.muninn.ui.Panic.locked.value, kids, lockDue)
    }

    /** Reply from the notification (a contact's chat or a group), only when [canReply]. */
    private fun replyAction(key: String): NotificationCompat.Action? {
        if (!canReply()) return null
        if (!key.startsWith("g:") && !chat.isContact(key)) return null
        val input = androidx.core.app.RemoteInput.Builder(io.github.ravenor907.muninn.net.ReplyReceiver.TEXT).setLabel("Reply").build()
        val intent = Intent(this, io.github.ravenor907.muninn.net.ReplyReceiver::class.java)
            .setAction(io.github.ravenor907.muninn.net.ReplyReceiver.ACTION).putExtra(io.github.ravenor907.muninn.net.ReplyReceiver.KEY, key)
        // Android fills the typed text into the intent, so it has to be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(this, key.hashCode(), intent, flags)
        return NotificationCompat.Action.Builder(R.drawable.ic_notify, "Reply", pi).addRemoteInput(input).setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build()
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
        const val QUIET = "raven_quiet"
        val BUZZ = longArrayOf(0, 140, 90, 140)
    }
}
