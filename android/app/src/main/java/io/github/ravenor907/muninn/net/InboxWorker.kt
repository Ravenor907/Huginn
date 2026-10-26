package io.github.ravenor907.muninn.net

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.ravenor907.muninn.Muninn

/** Checks the internet inbox while Muninn is closed: connect, wait for what's waiting, disconnect. */
class InboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as Muninn
        if (app.visible) return Result.success()
        // Locked with a password: only says "New message"; nothing is opened until it's unlocked.
        app.nostr.connect()
        Thread.sleep(20_000)
        if (!app.visible) app.nostr.disconnect()
        return Result.success()
    }
}
