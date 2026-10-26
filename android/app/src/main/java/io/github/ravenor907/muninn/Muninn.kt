package io.github.ravenor907.muninn

import android.app.Application
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Direct
import io.github.ravenor907.muninn.net.Identity
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.update.Updater

/** One of each for the whole app. */
class Muninn : Application() {
    lateinit var identity: Identity
    lateinit var net: LocalSend
    lateinit var chat: Chat
    lateinit var direct: Direct
    lateinit var updater: Updater

    override fun onCreate() {
        super.onCreate()
        identity = Identity(this)
        net = LocalSend(this, identity)
        chat = Chat(this, net)
        direct = Direct(this)
        updater = Updater(this, identity)
    }
}
