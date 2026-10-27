package io.github.ravenor907.muninn.net

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Files between close-by phones (no Wi-Fi network needed). The sender offers, the other person
 * accepts on screen, then the files go over Nearby Connections. Uses LocalSend's request and
 * progress state, so the screens show it the same way as Wi-Fi transfers.
 */
class CloseFiles(private val context: Context, private val close: Close, private val net: LocalSend) {
    private class Offer(val id: String, val endpoint: String, val from: String, val files: List<Pair<String, Long>>) {
        /** Set by Accept: until then nothing from this phone is saved. */
        @Volatile var accepted = false
    }
    private class Outgoing(val id: String, val endpoint: String, val name: String, val files: List<File>)

    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val expected = ConcurrentHashMap<Long, Pair<String, Long>>()    // payload id -> (file name, size)
    private val progress = ConcurrentHashMap<Long, Long>()
    @Volatile private var offer: Offer? = null
    @Volatile private var total = 0L
    @Volatile private var received = 0
    val pendingOffer = MutableStateFlow<LocalSend.Request?>(null)

    init {
        val prevMessage = close.onMessage
        close.onMessage = { endpoint, o ->
            prevMessage?.invoke(endpoint, o)
            when (o.optString("t")) {
                "offer" -> onOffer(endpoint, o)
                "accept" -> outgoing.remove(o.optString("id"))?.let { send(it, o.optJSONArray("ids")) }
                "decline" -> outgoing.remove(o.optString("id"))?.let { finish(false, "${it.name} turned the files down.") }
                "file" -> if (offer?.let { it.accepted && it.endpoint == endpoint } == true) expected[o.optLong("pid")] = o.optString("name") to o.optLong("size")
            }
        }
        close.onFile = { endpoint, pid, file, uri -> save(endpoint, pid, file, uri) }
        close.onSent = { if (--sending <= 0 && sentTo.isNotEmpty()) { net.logSent(sentNames, sentTo); finish(true, "Sent to $sentTo.") } }
        close.onProgress = { pid, done, all ->
            progress[pid] = done
            if (total > 0) net.closeProgress(progress.values.sum(), total)
        }
    }

    /** Sends files to a close-by phone: they see an Accept / Decline first. */
    fun send(peer: Close.Peer, files: List<File>) {
        val list = files.filter { it.isFile }
        if (list.isEmpty()) return
        val id = UUID.randomUUID().toString()
        outgoing[id] = Outgoing(id, peer.endpoint, peer.name, list)
        total = list.sumOf { it.length() }
        progress.clear()
        net.closeStatus(true, true, "Waiting for ${peer.name} to accept…", total)
        val arr = JSONArray(); list.forEach { arr.put(JSONObject().put("name", it.name).put("size", it.length())) }
        close.send(peer.endpoint, JSONObject().put("t", "offer").put("id", id).put("files", arr))
    }

    private fun send(o: Outgoing, @Suppress("UNUSED_PARAMETER") ignored: JSONArray?) {
        sentNames = o.files.map { it.name }
        net.closeStatus(true, true, "Sending to ${o.name}…", total)
        for (f in o.files) {
            val pid = close.sendFile(o.endpoint, f)
            close.send(o.endpoint, JSONObject().put("t", "file").put("pid", pid).put("name", f.name).put("size", f.length()))
        }
        sentTo = o.name
        sending = o.files.size
    }
    @Volatile private var sentTo = ""
    @Volatile private var sending = 0
    @Volatile private var sentNames = emptyList<String>()

    private fun onOffer(endpoint: String, o: JSONObject) {
        val from = close.list.value.firstOrNull { it.endpoint == endpoint }?.name ?: "A phone close by"
        val files = (0 until (o.optJSONArray("files")?.length() ?: 0)).map { o.getJSONArray("files").getJSONObject(it) }.map { it.optString("name") to it.optLong("size") }
        if (offer != null) { close.send(endpoint, JSONObject().put("t", "decline").put("id", o.optString("id"))); return }
        offer = Offer(o.optString("id"), endpoint, from, files)
        val names = files.map { it.first }.sorted()
        pendingOffer.value = LocalSend.Request(from, files.size, files.sumOf { it.second },
            names.take(3).joinToString(", ") + if (names.size > 3) " and ${names.size - 3} more" else "")
    }

    fun accept() {
        val o = offer ?: return
        o.accepted = true
        pendingOffer.value = null
        total = o.files.sumOf { it.second }
        received = 0
        progress.clear()
        net.closeStatus(true, false, "Receiving from ${o.from}…", total)
        close.send(o.endpoint, JSONObject().put("t", "accept").put("id", o.id))
    }

    fun decline() {
        val o = offer ?: return
        offer = null
        pendingOffer.value = null
        close.send(o.endpoint, JSONObject().put("t", "decline").put("id", o.id))
    }

    private fun save(endpoint: String, pid: Long, file: File?, uri: Uri?) {
        // Only files from the phone whose offer was accepted, and only after Accept; anything else is thrown away.
        val o = offer?.takeIf { it.accepted && it.endpoint == endpoint }
        if (o == null) { discard(file, uri); return }
        val (name, _) = expected.remove(pid) ?: ("file-$pid" to 0L)
        // Into Downloads (MediaStore on Android 10 and newer), out of Nearby's own temporary copy.
        var target: Received.Target? = null
        try {
            val t = Received.create(context, name.substringAfterLast('/')).also { target = it }
            val input = when {
                uri != null -> context.contentResolver.openInputStream(uri)
                file != null -> file.inputStream()
                else -> null
            } ?: throw java.io.IOException("Nothing arrived for $name.")
            input.use { ins -> t.out.use { ins.copyTo(it) } }
            t.finish()
            if (uri != null) try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            file?.delete()
            net.closeItem(t.name, true, o.from)
        } catch (e: Exception) {
            target?.discard()
            net.closeItem(name, false, o.from)
        }
        if (++received >= o.files.size) {
            offer = null
            finish(true, "Received ${if (received == 1) "1 file" else "$received files"} from ${o.from}. They're in ${Received.place(context)}.")
        }
    }

    /** Deletes Nearby's temporary copy of a file nobody accepted. */
    private fun discard(file: File?, uri: Uri?) {
        if (uri != null) try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        try { file?.delete() } catch (_: Exception) {}
    }

    private fun finish(ok: Boolean, message: String) {
        sentTo = ""
        net.closeStatus(false, false, "", 0)
        net.messages.tryEmit(ok to message)
    }
}
