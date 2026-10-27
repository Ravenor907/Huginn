package io.github.ravenor907.muninn.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp setup for everything Muninn does on the internet: the Nostr relays, the call
 * relay's Worker and updates from GitHub. Build variants from it with newBuilder() (they share its
 * connection pool and threads). Nearby Wi-Fi (LocalSend) has its own code for the local network.
 */
object Net {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    }
}
