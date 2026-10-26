package io.github.ravenor907.muninn.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.util.Log
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.tor.ControlConnection
import io.github.ravenor907.muninn.net.tor.ControlProtocol
import io.github.ravenor907.muninn.net.tor.OnionAddress
import io.github.ravenor907.muninn.net.tor.OnionHttp
import io.github.ravenor907.muninn.net.tor.OnionKey
import io.github.ravenor907.muninn.net.tor.Reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Tor, built into Muninn (Guardian Project's tor-android). Every internet connection Muninn makes
 * goes through it, so no relay, server or contact ever sees this phone's IP address. If Tor isn't
 * ready, internet traffic waits or fails: it never falls back to a direct connection. Wi-Fi on
 * the same network, Bluetooth and Wi-Fi Direct stay direct (they never leave the room).
 *
 * Tor also gives this phone an onion address (a v3 onion service) so contacts can reach it
 * straight through Tor. Its key lives sealed in the Vault, so the address stays the same.
 *
 * Tor runs inside Muninn's process (TorService). Muninn talks to it over Tor's control socket,
 * a file in app-private storage, never a network port.
 */
object Tor {
    sealed class State {
        object Off : State() { override fun toString() = "Off" }
        data class Starting(val percent: Int) : State()
        object Ready : State() { override fun toString() = "Ready" }
        data class Failed(val reason: String) : State()
    }

    private const val TAG = "MuninnTor"
    private const val SERVICE = "org.torproject.jni.TorService"
    private const val MESSAGE_PATH = "/api/huginn/v1/message"
    /** How long Tor keeps running after nothing needs it (switching apps for a moment shouldn't restart it). */
    private const val GRACE_MS = 2 * 60_000L

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()
    val ready: Boolean get() = _state.value == State.Ready

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "muninn-tor").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var app: Context? = null
    @Volatile private var control: ControlConnection? = null
    @Volatile private var bound: ServiceConnection? = null
    @Volatile private var socksPort = -1
    @Volatile private var wanted = false
    @Volatile private var exited: CountDownLatch? = null
    @Volatile private var published = false
    @Volatile private var handler: ((ByteArray) -> Int)? = null
    private val holders = HashSet<String>()
    private var stopLater: ScheduledFuture<*>? = null
    private var retryLater: ScheduledFuture<*>? = null
    private var failures = 0
    private var watchingVault = false

    private val http = OnionHttp(MESSAGE_PATH) { handler }

    // ------------------------------------------------------------------ public API

    /** Starts Tor (does nothing if it's already starting or running). */
    fun start(context: Context) {
        app = context.applicationContext
        worker.execute { wanted = true; begin() }
    }

    /** Stops Tor now. Internet traffic then fails until it starts again. */
    fun stop() {
        worker.execute { wanted = false; shutdown(State.Off) }
    }

    /**
     * Keeps Tor running while someone needs it ("screen", "service", "inbox"). Once nobody does,
     * it stops after a short grace period.
     */
    fun hold(context: Context, who: String) {
        app = context.applicationContext
        worker.execute {
            holders += who
            stopLater?.cancel(false); stopLater = null
            wanted = true
            begin()
        }
    }

    fun release(who: String) {
        worker.execute {
            if (!holders.remove(who) || holders.isNotEmpty()) return@execute
            stopLater?.cancel(false)
            stopLater = worker.schedule({ if (holders.isEmpty()) { wanted = false; shutdown(State.Off) } }, GRACE_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * The SOCKS proxy into Tor, for OkHttp and anything else. Names are resolved by Tor at the exit
     * (OkHttp never looks up a host itself when given a SOCKS proxy). While Tor isn't ready this points
     * at a closed local port, so a connection fails instead of going out directly.
     */
    fun proxy(): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, socksPort.takeIf { it > 0 } ?: CLOSED_PORT))

    /** This phone's onion address without ".onion", or null until Tor has made one. Same across restarts. */
    fun onion(): String? = prefs()?.getString("onionId", null)?.takeIf { OnionAddress.valid(it) }

    /** Who gets each message that arrives at this phone's onion address. Returns the HTTP status to answer. */
    fun onMessage(handler: (ByteArray) -> Int) { this.handler = handler }

    /**
     * POSTs [body] to http://<onion>.onion<path> through Tor. Waits for Tor to be ready within the
     * same [timeoutMs]. Returns the HTTP status, or -1 if it couldn't be delivered.
     * Plain HTTP is fine inside Tor: the onion address itself proves who answers, and what's sent is
     * already end-to-end encrypted.
     */
    suspend fun post(onion: String, path: String, body: ByteArray, timeoutMs: Long = 30_000): Int {
        val host = OnionAddress.normalize(onion) ?: return -1
        if (!path.startsWith("/") || path.any { it.isWhitespace() }) return -1
        val deadline = System.currentTimeMillis() + timeoutMs
        withTimeoutOrNull(timeoutMs) { state.first { it == State.Ready } } ?: return -1
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) return -1
        val call = client().newBuilder().callTimeout(left, TimeUnit.MILLISECONDS).build().newCall(
            Request.Builder().url("http://$host.onion$path")
                .post(body.toRequestBody("application/json".toMediaType())).build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume(-1) }
                override fun onResponse(call: Call, response: Response) { val c = response.use { it.code }; if (cont.isActive) cont.resume(c) }
            })
        }
    }

    // ------------------------------------------------------------------ HTTP through Tor

    /** Refuses to resolve names: every lookup happens inside Tor. */
    private object NoDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("No lookups outside Tor")
    }

    /** Belt and braces: no request leaves while Tor isn't ready, even if a client was built earlier. */
    private val guard = Interceptor { chain ->
        if (!ready || socksPort <= 0) throw IOException("The private connection isn't ready")
        chain.proceed(chain.request())
    }

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder().dns(NoDns).addInterceptor(guard)
            .connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    }
    @Volatile private var cached: Pair<Int, OkHttpClient>? = null

    /** An OkHttp client that only ever connects through Tor. Build variants from it with newBuilder(). */
    fun client(): OkHttpClient {
        val p = socksPort
        cached?.let { (port, c) -> if (port == p) return c }
        return base.newBuilder().proxy(proxy()).build().also { cached = p to it }
    }

    /** Blocks (off the main thread) until Tor is ready, up to [timeoutMs]. */
    fun awaitReady(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ready && System.currentTimeMillis() < end) Thread.sleep(250)
        return ready
    }

    /** Words for the chats header: "Connecting privately (40%)" while Tor starts, else Online or Offline. */
    fun label(s: State, relaysUp: Int): String = when (s) {
        is State.Starting -> "Connecting privately (${s.percent}%)"
        is State.Failed -> "Private connection failed"
        State.Off -> "Offline"
        State.Ready -> if (relaysUp > 0) "Online" else "Offline"
    }

    /** Removes Tor's own files (relays it picked, cached directory). For erase everything. */
    fun forget(context: Context) {
        stop()
        worker.execute {
            try { context.getDir("TorService", Context.MODE_PRIVATE).deleteRecursively() } catch (_: Exception) {}
            try { File(context.cacheDir, "TorService").deleteRecursively() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ running Tor (on [worker] only)

    private fun begin() {
        val ctx = app ?: return
        if (bound != null) return
        retryLater?.cancel(false); retryLater = null
        // tor-android lets the next Tor start as soon as the old service is gone; wait for the old one to really exit.
        exited?.let { if (it.count > 0) { if (!it.await(15, TimeUnit.SECONDS)) Log.w(TAG, "Old Tor still exiting"); Thread.sleep(1000) } }
        _state.value = State.Starting(0)
        socksPort = -1
        published = false
        try { http.port() } catch (e: Exception) { Log.w(TAG, "No local listener: $e") }
        writeTorrc(ctx)
        val sock = controlSocket(ctx)
        sock.delete()   // a leftover from a killed process
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val ok = try { ctx.bindService(Intent().setClassName(ctx.packageName, SERVICE), conn, Context.BIND_AUTO_CREATE) } catch (e: Exception) { false }
        if (!ok) { fail("Tor couldn't start"); return }
        bound = conn
        watchVault()
        // Tor makes its control socket once it's running: connect to it (on its own thread, so stop() never waits).
        Thread({
            val end = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < end && bound === conn) {
                if (sock.exists() && connect(sock, conn)) return@Thread
                try { Thread.sleep(300) } catch (_: InterruptedException) { return@Thread }
            }
            worker.execute { if (bound === conn && control == null) fail("Tor didn't start") }
        }, "muninn-tor-connect").apply { isDaemon = true }.start()
    }

    private fun connect(sock: File, conn: ServiceConnection): Boolean {
        val ls = LocalSocket()
        var c: ControlConnection? = null
        return try {
            ls.connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            val latch = CountDownLatch(1)
            c = ControlConnection(ls.inputStream, ls.outputStream, ::event) {
                try { ls.close() } catch (_: Exception) {}
                latch.countDown()
                worker.execute { closed(latch) }
            }.start()
            if (bound !== conn) { c.close(); return true }   // stopped meanwhile
            exited = latch
            control = c
            if (!c.command("AUTHENTICATE").ok) throw IOException("Tor refused the controller")
            c.command("SETEVENTS STATUS_CLIENT")
            socksPort = c.command("GETINFO net/listeners/socks").values()["net/listeners/socks"]?.let { ControlProtocol.listenerPort(it) } ?: -1
            if (socksPort <= 0) throw IOException("Tor has no SOCKS port")
            cached = null
            val phase = c.command("GETINFO status/bootstrap-phase").values()["status/bootstrap-phase"] ?: ""
            progress(ControlProtocol.bootstrapProgress(phase) ?: 0)
            publishOnion()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Control: $e")
            if (control === c) control = null
            c?.close()
            try { ls.close() } catch (_: Exception) {}
            false
        }
    }

    private fun event(r: Reply) {
        val text = r.text
        when (ControlProtocol.clientStatus(text)) {
            "BOOTSTRAP" -> ControlProtocol.bootstrapProgress(text)?.let { progress(it) }
            "CIRCUIT_ESTABLISHED" -> if (_state.value is State.Starting && socksPort > 0) _state.value = State.Ready
            // Lost the network (or the clock jumped): Tor rebuilds by itself.
            "CIRCUIT_NOT_ESTABLISHED" -> if (_state.value == State.Ready) _state.value = State.Starting(99)
        }
    }

    private fun progress(p: Int) {
        if (control == null) return
        if (p >= 100 && socksPort > 0) { failures = 0; _state.value = State.Ready }
        else if (_state.value != State.Ready) _state.value = State.Starting(p)
    }

    /** The control connection ended. Unless asked to stop, Tor died: say so and try again soon. */
    private fun closed(latch: CountDownLatch) {
        if (exited !== latch || control == null) return
        control = null
        if (wanted) fail("Tor stopped")
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        shutdown(State.Failed(reason))
        if (!wanted) return
        failures++
        val delay = minOf(300L, 15L shl minOf(failures - 1, 4))   // 15 s, 30 s, 1 min ... up to 5 min
        retryLater = worker.schedule({ if (wanted) begin() }, delay, TimeUnit.SECONDS)
    }

    private fun shutdown(end: State) {
        socksPort = -1
        cached = null
        published = false
        _state.value = end
        val c = control
        control = null
        bound?.let { b -> try { app?.unbindService(b) } catch (_: Exception) {} }
        bound = null
        if (c != null) {
            // Ask Tor to exit and keep listening: the connection ends when Tor is really gone (see begin).
            try { c.command("SIGNAL SHUTDOWN", 3000) } catch (_: Exception) {}
            worker.schedule({ c.close() }, 15, TimeUnit.SECONDS)
        }
    }

    // ------------------------------------------------------------------ the onion service

    private fun prefs() = app?.getSharedPreferences("muninn", Context.MODE_PRIVATE)

    private fun vault(): Vault? = (app as? Muninn)?.let { a -> try { a.vault } catch (_: UninitializedPropertyAccessException) { null } }

    /** A password-locked vault can't give the key yet: publish once it opens. */
    private fun watchVault() {
        if (watchingVault) return
        val v = vault() ?: return
        watchingVault = true
        scope.launch { v.locked.collect { locked -> if (!locked) worker.execute { publishOnion() } } }
    }

    /** Asks Tor to run this phone's onion service, with the saved key (or a new one, saved at once). */
    @Synchronized
    private fun publishOnion() {
        val c = control ?: return
        if (published || c.closed) return
        val v = vault() ?: return
        if (v.locked.value) return
        val file = File(app!!.filesDir, KEY_FILE)
        val saved = OnionKey.decode(v.readBytes(file))
        val port = try { http.port() } catch (e: Exception) { return }
        val r = try { c.command(ControlProtocol.addOnion(saved?.key, 80, port)) } catch (e: Exception) { Log.w(TAG, "ADD_ONION: $e"); return }
        if (!r.ok) { Log.w(TAG, "ADD_ONION refused: ${r.status}"); return }
        val values = r.values()
        val id = values["ServiceID"]?.takeIf { OnionAddress.valid(it) } ?: return
        if (saved == null) {
            val key = values["PrivateKey"]?.takeIf { OnionKey.validKey(it) } ?: return
            try { v.writeBytes(file, OnionKey(key, id).encode()) } catch (e: Exception) { Log.w(TAG, "Couldn't save the onion key: $e") }
        } else if (saved.id != id) {
            Log.w(TAG, "Onion key and address disagree")
        }
        prefs()?.edit()?.putString("onionId", id)?.apply()
        published = true
    }

    private fun writeTorrc(ctx: Context) {
        // Overrides tor-android's defaults: a SOCKS port of its own (not 9050, which Orbot may hold)
        // that gives each destination its own circuit, so relays can't link each other's traffic.
        val lines = listOf(
            "SOCKSPort auto IsolateDestAddr",
            "AvoidDiskWrites 1",
            "DormantCanceledByStartup 1",
            "ReducedConnectionPadding 1",   // phones: fewer wake-ups, longer battery
        )
        try { File(ctx.getDir("TorService", Context.MODE_PRIVATE), "torrc").writeText(lines.joinToString("\n", postfix = "\n")) }
        catch (e: Exception) { Log.w(TAG, "torrc: $e") }
    }

    /** Where tor-android puts Tor's control socket (TorService's data folder). */
    private fun controlSocket(ctx: Context) = File(File(ctx.getDir("TorService", Context.MODE_PRIVATE), "data"), "ControlSocket")

    /** The sealed file that holds the onion key (in the vault's folder, so backups carry it). */
    const val KEY_FILE = "onion.key"
    // An address with no name attached, so nothing ever tries to look "localhost" up.
    private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    /** Nothing listens here: used while Tor isn't ready so connections fail closed. */
    private const val CLOSED_PORT = 9
}
