package io.github.ravenor907.muninn.net

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.ravenor907.muninn.Muninn

/** Checks the internet inbox while Muninn is closed: connect, wait for what's waiting, disconnect. */
class InboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as Muninn
        if (app.visible || MessageService.running) return Result.success()   // already listening
        MessageService.start(app)   // allowed again once Muninn may run in the background
        // The relays are only reached through Tor: start it for this check (it stops again by itself).
        Tor.hold(app, "inbox")
        try {
            if (!Tor.awaitReady(90_000)) return Result.success()   // no private connection now: try next time
            // Locked with a password: only says "New message"; nothing is opened until it's unlocked.
            app.nostr.connect()   // (no keepAlive: this short check closes again)
            Thread.sleep(20_000)
            if (!app.visible && !MessageService.running && !app.nostr.keepAlive) app.nostr.disconnect()
        } finally { Tor.release("inbox") }
        return Result.success()
    }
}
