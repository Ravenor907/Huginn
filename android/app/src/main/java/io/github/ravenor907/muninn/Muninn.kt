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
    lateinit var closeFiles: CloseFiles
    lateinit var contacts: Contacts
    lateinit var chat: Chat
    lateinit var direct: Direct
    lateinit var updater: Updater
    /** True once everything is running (the vault is open). */
    val ready = MutableStateFlow(false)
    @Volatile var visible = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        vault = Vault(this)
        identity = Identity(this, vault)
        nostr = Nostr(this) { if (vault.locked.value) null else identity.nostrSecret() }
        direct = Direct(this)
        updater = Updater(this, identity)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        // While locked, the internet inbox can still say something arrived, without opening it.
        nostr.onEnvelope = { notify(null, null, "inbox"); false }
        if (vault.openDefault()) start()
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
        contacts = Contacts(this, vault)
        chat = Chat(this, net, close, nostr, contacts, vault)
        closeFiles = CloseFiles(this, close, net)
        scope.launch { chat.arrivals.collect { (name, text, key) -> if (!visible) notify(name, text, key) } }
        ready.value = true
    }

    /** Privacy first: unless the person allows previews, a notification says only "New message". */
    fun notify(name: String?, text: String?, key: String) {
        val preview = identity.showPreview && name != null
        val open = PendingIntent.getActivity(this, key.hashCode(), Intent(this, MainActivity::class.java).putExtra("chat", key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, MESSAGES).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (preview) name else "Muninn").setContentText(if (preview) text else "New message")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build()
        try { NotificationManagerCompat.from(this).notify(if (preview) key.hashCode() else 1, n) } catch (_: SecurityException) {}
    }

    companion object { const val MESSAGES = "messages" }
}
