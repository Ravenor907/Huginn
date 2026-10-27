package io.github.ravenor907.muninn.net

import android.content.Context
import android.net.Uri
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * "Close by": other phones running Muninn within Bluetooth range, with no Wi-Fi network needed.
 * Google's Nearby Connections finds them over Bluetooth and moves the data to a direct Wi-Fi link
 * when it can. Every phone both looks and is visible; they connect to each other automatically,
 * swap names and chat keys ("hello"), and then carry chat messages and files.
 */
class Close(private val context: Context, private val identity: Identity) {
    data class Peer(val endpoint: String, val name: String, val connected: Boolean, val chatKey: String? = null, val mailbox: String? = null)

    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val peers = ConcurrentHashMap<String, Peer>()
    private val _list = MutableStateFlow<List<Peer>>(emptyList())
    val list: StateFlow<List<Peer>> = _list.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    /** Permissions Nearby Connections said it was missing on the last try (asked for on top of the usual set). */
    private val _missing = MutableStateFlow<List<String>>(emptyList())
    val missing: StateFlow<List<String>> = _missing.asStateFlow()
    @Volatile var running = false
        private set

    /** Every message from a connected phone: (endpoint, json). */
    var onMessage: ((String, JSONObject) -> Unit)? = null
    /** A file finished arriving: (endpoint, payload id, file or content Uri). */
    var onFile: ((String, Long, File?, Uri?) -> Unit)? = null
    /** One of our outgoing files finished. */
    var onSent: ((Long) -> Unit)? = null
    /** Progress of incoming or outgoing files: (payload id, bytes done, total). */
    var onProgress: ((Long, Long, Long) -> Unit)? = null

    /** Breaks the tie when two phones find each other at once: the one with the lower id asks. */
    private val myId = identity.fingerprint.take(8)
    /** Hidden from people nearby: no name in what's advertised (contacts learn who it is from the chat key in the hello). */
    private fun advertisedName() = "$myId|${if (identity.visibleNearby) identity.username.take(24) else Identity.HIDDEN_NAME}"

    /** The name to advertise changed (or stopped being shown): start looking again with the new one. */
    fun readvertise() {
        main.post {
            if (!running || !looking) return@post
            quiet()
            look()
        }
    }

    fun start(hello: () -> JSONObject) {
        if (running) return
        running = true
        _error.value = null
        this.hello = hello
        looking = false
        look()
    }

    // ------------------------------------------------------------------ saving battery

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** See PowerLogic: FULL as before, LOW low-power and in turns, OFF not looking (connected phones stay). */
    @Volatile private var level = PowerLogic.Radio.FULL
    private var looking = false
    private var dutyOn = true
    private val duty = Runnable { dutyOn = !dutyOn; look() }

    fun power(r: PowerLogic.Radio) {
        main.post {
            if (r == level) return@post
            level = r
            dutyOn = true
            if (looking) quiet()   // the options change: start over with the new ones
            look()
        }
    }

    /** Advertise and discover as [level] says, on the main thread. */
    private fun look() {
        main.post {
            main.removeCallbacks(duty)
            if (!running) return@post
            val want = level == PowerLogic.Radio.FULL || (level == PowerLogic.Radio.LOW && dutyOn)
            if (want && !looking) {
                looking = true
                val strategy = Strategy.P2P_CLUSTER
                val low = level == PowerLogic.Radio.LOW
                client.startAdvertising(advertisedName(), SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).setLowPower(low).build())
                    .addOnFailureListener { fail(it) }
                client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(strategy).setLowPower(low).build())
                    .addOnFailureListener { fail(it) }
            } else if (!want && looking) quiet()
            if (level == PowerLogic.Radio.LOW) main.postDelayed(duty, PowerLogic.dutyNext(dutyOn))
        }
    }

    /** Stops looking and being seen; phones already connected stay connected. */
    private fun quiet() {
        looking = false
        try { client.stopAdvertising(); client.stopDiscovery() } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        main.post { main.removeCallbacks(duty); looking = false }
        try {
            client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        } catch (_: Exception) {}
        peers.clear()
        publish()
    }

    private var hello: () -> JSONObject = { JSONObject() }

    private fun fail(e: Exception) {
        val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
        if (code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return
        _error.value = when (code) {
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH, ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN -> {
                _missing.value = if (android.os.Build.VERSION.SDK_INT >= 31) listOf(android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
                PERMISSION_TEXT
            }
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION, ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION -> {
                _missing.value = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                PERMISSION_TEXT
            }
            ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Turn Bluetooth on to find phones close by."
            else -> "Close-by search isn't available on this phone (Google Play services needed)."
        }
        running = false
    }

    /** Shown when Android won't ask again: the person has to allow it in Muninn's app settings. */
    fun blocked() { _error.value = "Close-by chat is off. Tap here, then allow Nearby devices (and Location if listed) in Muninn's permissions." }
    fun denied() { _error.value = PERMISSION_TEXT }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpoint: String, info: DiscoveredEndpointInfo) {
            val id = info.endpointName.substringBefore('|')
            val name = info.endpointName.substringAfter('|')
            peers[endpoint] = peers[endpoint]?.copy(name = name) ?: Peer(endpoint, name, false)
            publish()
            // Only one side asks, so two phones don't both request at once.
            if (myId < id) client.requestConnection(advertisedName(), endpoint, lifecycle)
        }

        override fun onEndpointLost(endpoint: String) {
            if (peers[endpoint]?.connected != true) { peers.remove(endpoint); publish() }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpoint: String, info: ConnectionInfo) {
            // Every connection is accepted for chat; private chats still verify with the 6-digit code,
            // and files always wait for the person to accept them.
            peers[endpoint] = peers[endpoint] ?: Peer(endpoint, info.endpointName.substringAfter('|'), false)
            client.acceptConnection(endpoint, payloads)
        }

        override fun onConnectionResult(endpoint: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                peers[endpoint] = (peers[endpoint] ?: Peer(endpoint, "Phone", false)).copy(connected = true)
                publish()
                send(endpoint, hello().put("t", "hello"))
            } else if (peers[endpoint]?.connected != true) {
                peers.remove(endpoint)
                publish()
            }
        }

        override fun onDisconnected(endpoint: String) {
            peers.remove(endpoint)
            publish()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpoint: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val o = try { JSONObject(String(payload.asBytes()!!)) } catch (e: Exception) { return }
                    if (o.optString("t") == "hello") {
                        peers[endpoint] = (peers[endpoint] ?: Peer(endpoint, "Phone", true)).copy(
                            name = o.optString("name").ifBlank { peers[endpoint]?.name ?: "Phone" }.take(40),
                            chatKey = o.optString("chatKey").ifEmpty { null }, mailbox = o.optString("mailbox").ifEmpty { null }, connected = true)
                        publish()
                    }
                    onMessage?.invoke(endpoint, o)
                }
                Payload.Type.FILE -> incoming[payload.id] = endpoint to payload
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpoint: String, update: PayloadTransferUpdate) {
            onProgress?.invoke(update.payloadId, update.bytesTransferred, update.totalBytes)
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val (from, p) = incoming.remove(update.payloadId) ?: run { if (update.totalBytes > 0 && outgoingFiles.remove(update.payloadId)) onSent?.invoke(update.payloadId); return }
                val f = p.asFile()
                @Suppress("DEPRECATION") onFile?.invoke(from, update.payloadId, f?.asJavaFile(), f?.asUri())
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED) {
                incoming.remove(update.payloadId)
            }
        }
    }
    private val incoming = ConcurrentHashMap<Long, Pair<String, Payload>>()
    private val outgoingFiles = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    fun send(endpoint: String, o: JSONObject) {
        client.sendPayload(endpoint, Payload.fromBytes(o.toString().toByteArray()))
    }

    /** Sends a file; returns its payload id so the other side can match it to the offer. */
    fun sendFile(endpoint: String, file: File): Long {
        val p = Payload.fromFile(file)
        outgoingFiles.add(p.id)
        client.sendPayload(endpoint, p)
        return p.id
    }

    fun cancel(payloadId: Long) { client.cancelPayload(payloadId) }

    fun endpointFor(chatKey: String): String? = peers.values.firstOrNull { it.connected && it.chatKey == chatKey }?.endpoint

    private fun publish() { _list.value = peers.values.sortedBy { it.name.lowercase() } }

    companion object {
        const val SERVICE = "io.github.ravenor907.muninn"
        fun newId() = Random.nextLong()
        const val PERMISSION_TEXT = "Muninn needs the Nearby devices permission to find phones close by. Tap here to allow it."
    }
}
