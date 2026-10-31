package io.github.ravenor907.muninn.net

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * "Close by" through Google Nearby, removed in 0.96: it relied on Google Play services and made TVs send
 * Bluetooth pairing requests to every device around them. Muninn's own Bluetooth (Ble) and Wi-Fi carry
 * close-by chat now. This keeps the same shape so the rest of the app needs no changes: it never finds,
 * advertises to or connects to anything.
 */
class Close(private val context: Context, private val identity: Identity) {
    /** [proven]: cap s1, it signed the number this phone picked with that chat key. Without (an older app), only an extra route. */
    data class Peer(val endpoint: String, val name: String, val connected: Boolean, val chatKey: String? = null, val mailbox: String? = null,
                    val proven: Boolean = false)

    private val _list = MutableStateFlow<List<Peer>>(emptyList())
    val list: StateFlow<List<Peer>> = _list.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _missing = MutableStateFlow<List<String>>(emptyList())
    val missing: StateFlow<List<String>> = _missing.asStateFlow()
    @Volatile var running = false
        private set

    var onMessage: ((String, JSONObject) -> Unit)? = null
    var prove: ((String) -> String?)? = null
    var hintKey: ((String) -> String?)? = null
    var onFile: ((String, Long, File?, Uri?) -> Unit)? = null
    var allowFile: ((endpoint: String, size: Long) -> Boolean)? = null
    var onSent: ((Long) -> Unit)? = null
    var onProgress: ((Long, Long, Long) -> Unit)? = null

    fun start(@Suppress("UNUSED_PARAMETER") hello: () -> JSONObject) { running = true; _error.value = null }
    fun stop() { running = false }
    fun readvertise() {}
    fun power(@Suppress("UNUSED_PARAMETER") r: PowerLogic.Radio) {}
    fun lookAgain() {}
    fun blocked() {}
    fun denied() {}
    fun offer() {}
    fun send(@Suppress("UNUSED_PARAMETER") endpoint: String, @Suppress("UNUSED_PARAMETER") o: JSONObject) {}
    fun sendFile(@Suppress("UNUSED_PARAMETER") endpoint: String, @Suppress("UNUSED_PARAMETER") file: File): Long = -1L
    fun cancel(@Suppress("UNUSED_PARAMETER") payloadId: Long) {}
    fun endpointFor(@Suppress("UNUSED_PARAMETER") chatKey: String, @Suppress("UNUSED_PARAMETER") strict: Boolean = false): String? = null
    fun proven(@Suppress("UNUSED_PARAMETER") chatKey: String) = false

    companion object {
        const val SERVICE = "io.github.ravenor907.muninn"
        fun newId() = Random.nextLong()
        const val OFFER_TEXT = "Tap to find phones close by over Bluetooth, with no internet needed. On this phone Android calls that Location: " +
            "Muninn never sees or shares where you are."
        const val NO_LOCATION_TEXT = "Google's close-by search wants Location on this phone, which Muninn doesn't use. Chat over Bluetooth still works."
        const val PERMISSION_TEXT = "Muninn needs the Nearby devices permission to find phones close by. Tap here to allow it."
    }
}
