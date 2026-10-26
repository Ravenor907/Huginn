package io.github.ravenor907.muninn.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.StatFs
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * LocalSend protocol v2 (github.com/localsend/protocol), so Muninn works with Huginn and with the
 * LocalSend apps: multicast discovery on 224.0.0.167:53317, an HTTP server on port 53317, and
 * uploads to other devices. Every incoming transfer waits for the person to accept it.
 */
class LocalSend(private val context: Context, val identity: Identity) {
    data class Device(
        val id: String, val alias: String, val model: String, val type: String,
        val ip: String, val port: Int, val protocol: String,
        /** Huginn and Muninn add these; plain LocalSend apps don't chat. */
        val chatKey: String?, val chatName: String?, val app: String?,
        val seen: Long = System.currentTimeMillis(),
    ) {
        val canChat get() = chatKey != null
    }

    data class Request(val from: String, val count: Int, val size: Long, val names: String)

    data class Item(val name: String, val status: String, val fraction: Float, val failed: Boolean)

    data class Transfer(
        val active: Boolean = false, val sending: Boolean = false, val status: String = "",
        val done: Long = 0, val total: Long = 0,
    ) {
        val fraction get() = if (total > 0) done.toFloat() / total else 0f
    }

    /** Extra routes (chat) served on the same port. Returns (status, json body) or null. */
    var extraRoute: ((method: String, path: String, body: String, ip: String) -> Pair<Int, String>?)? = null

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()
    private val _transfer = MutableStateFlow(Transfer())
    val transfer: StateFlow<Transfer> = _transfer.asStateFlow()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    /** One-line results for a toast ("Received 3 files from…"). */
    val messages = MutableSharedFlow<Pair<Boolean, String>>(extraBufferCapacity = 16)
    /** A device asked for a PIN when sending to it. */
    val pinNeeded = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 4)

    private val found = ConcurrentHashMap<String, Device>()
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private var udp: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var running = false

    // Receiving: the one transfer at a time LocalSend allows.
    private class InFile(val name: String, val size: Long, val sha: String?) {
        val token: String = UUID.randomUUID().toString()
        @Volatile var received = 0L
        @Volatile var done = false
        @Volatile var failed = false
        @Volatile var savedAs: File? = null
    }
    private class Session(val id: String, val from: String, val ip: String, val files: Map<String, InFile>) {
        @Volatile var touched = System.currentTimeMillis()
    }
    @Volatile private var session: Session? = null
    private var pendingFiles: Map<String, InFile>? = null
    private var pendingFrom = ""
    private var pendingIp = ""
    private val decision = Object()
    @Volatile private var answer: Boolean? = null

    // Sending.
    private val stopSend = AtomicBoolean(false)
    @Volatile private var sendConn: HttpURLConnection? = null

    fun info(): JSONObject = JSONObject()
        .put("alias", identity.deviceName)
        .put("version", "2.1")
        .put("deviceModel", "Muninn")
        .put("deviceType", "mobile")
        .put("fingerprint", identity.fingerprint)
        .put("port", PORT)
        .put("protocol", "http")
        .put("download", false)
        .put("chatApp", "muninn")
        .apply {
            put("chatKey", identity.publicKey)
            if (identity.nearbyChat) put("chatName", identity.username)
        }

    // ------------------------------------------------------------------ start and stop

    fun start() {
        if (running) return
        running = true
        _error.value = null
        try {
            server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        } catch (e: Exception) {
            _error.value = "Nearby sharing couldn't start. Is the LocalSend app running on this phone too? Close it and try again."
            running = false
            return
        }
        pool.execute { acceptLoop() }
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("muninn").apply { setReferenceCounted(false); acquire() }
            udp = MulticastSocket(PORT).apply {
                reuseAddress = true
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (nif.isUp && !nif.isLoopback && nif.supportsMulticast() && nif.inetAddresses.toList().any { it is Inet4Address }) {
                        try { joinGroup(InetSocketAddress(GROUP, PORT), nif) } catch (_: Exception) {}
                    }
                }
            }
            pool.execute { udpLoop() }
        } catch (e: Exception) {
            // Discovery falls back to scanning the network.
        }
        announce()
        pool.execute {
            while (running) {
                Thread.sleep(60_000)
                if (!running) break
                announce()
                val cutoff = System.currentTimeMillis() - 10 * 60_000
                found.entries.removeIf { it.value.seen < cutoff }
                publish()
                val s = session
                if (s != null && System.currentTimeMillis() - s.touched > 120_000) endSession(false, "${s.from} stopped sending.")
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        lock?.let { if (it.isHeld) it.release() }
    }

    // ------------------------------------------------------------------ discovery

    fun announce() {
        val msg = info().put("announce", true).toString().toByteArray()
        pool.execute {
            try { udp?.send(DatagramPacket(msg, msg.size, InetAddress.getByName(GROUP), PORT)) } catch (_: Exception) {}
        }
    }

    /** Multicast plus asking every address on this network, for routers that drop multicast. */
    fun refresh() {
        announce()
        if (_scanning.value) return
        val me = localAddress() ?: return
        _scanning.value = true
        val prefix = me.substringBeforeLast('.') + "."
        val scan = Executors.newFixedThreadPool(48)
        val left = AtomicLong(253)
        for (i in 1..254) {
            val ip = prefix + i
            if (ip == me) continue
            scan.execute {
                register(ip, PORT, "https", scan = true) || register(ip, PORT, "http", scan = true)
                if (left.decrementAndGet() <= 0L) _scanning.value = false
            }
        }
        scan.shutdown()
        pool.execute { scan.awaitTermination(30, TimeUnit.SECONDS); _scanning.value = false }
    }

    private fun udpLoop() {
        val buf = ByteArray(8192)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                udp?.receive(p) ?: return
                val o = JSONObject(String(p.data, 0, p.length))
                if (o.optString("fingerprint") == identity.fingerprint) continue
                val ip = p.address.hostAddress ?: continue
                remember(o, ip, "https")
                if (o.optBoolean("announce", o.optBoolean("announcement", false))) {
                    pool.execute {
                        if (!register(ip, o.optInt("port", PORT), o.optString("protocol", "https"), scan = false)) {
                            val reply = info().put("announce", false).toString().toByteArray()
                            try { udp?.send(DatagramPacket(reply, reply.size, InetAddress.getByName(GROUP), PORT)) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    private fun register(ip: String, port: Int, protocol: String, scan: Boolean): Boolean = try {
        val c = open("$protocol://$ip:$port$API/register", if (scan) 1500 else 5000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(info().toString().toByteArray()) }
        val ok = c.responseCode == 200
        if (ok) {
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            if (!o.has("port")) o.put("port", port)
            remember(o, ip, protocol)
        }
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    private fun remember(o: JSONObject, ip: String, fallback: String) {
        val id = o.optString("fingerprint")
        if (id.isEmpty() || id == identity.fingerprint) return
        found[id] = Device(
            id, o.optString("alias", ip), o.optString("deviceModel", ""), o.optString("deviceType", "mobile"),
            ip, o.optInt("port", PORT), o.optString("protocol", fallback),
            o.optString("chatKey").ifEmpty { null }, o.optString("chatName").ifEmpty { null }, o.optString("chatApp").ifEmpty { null },
        )
        publish()
    }

    private fun publish() {
        _devices.value = found.values.sortedBy { it.alias.lowercase() }
    }

    fun device(id: String): Device? = found[id]

    // ------------------------------------------------------------------ server

    private fun acceptLoop() {
        while (running) {
            val s: Socket = (try { server?.accept() } catch (e: Exception) { null }) ?: if (running) continue else return
            pool.execute { try { handle(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} } }
        }
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

    private fun readRequest(input: InputStream): Req? {
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
            if (head.length > 64 * 1024) return null
        }
        val lines = head.toString().split("\r\n")
        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val target = first[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return Req(first[0], path, query, headers, input)
    }

    private fun reply(out: OutputStream, code: Int, body: String = "") {
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 409 -> "Conflict"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; else -> "Error" }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun handle(s: Socket) {
        s.soTimeout = 60_000
        val input = BufferedInputStream(s.getInputStream(), 1 shl 16)
        val out = s.getOutputStream()
        val r = readRequest(input) ?: return
        val ip = (s.inetAddress as? Inet4Address)?.hostAddress ?: s.inetAddress.hostAddress ?: ""
        val length = r.headers["content-length"]?.toLongOrNull() ?: 0L

        if (r.method == "POST" && r.path == "$API/upload") {
            upload(r, length, ip, out)
            return
        }
        if (length > 8 * 1024 * 1024) { reply(out, 400); return }
        val body = ByteArray(length.toInt()).also { var at = 0; while (at < it.size) { val n = input.read(it, at, it.size - at); if (n < 0) break; at += n } }
        val text = String(body)

        extraRoute?.invoke(r.method, r.path, text, ip)?.let { (code, json) -> reply(out, code, json); return }

        when (r.path) {
            "$API/register", "/api/localsend/v1/register" -> {
                try { remember(JSONObject(text), ip, "https") } catch (_: Exception) {}
                reply(out, 200, info().toString())
            }
            "$API/info", "/api/localsend/v1/info" -> reply(out, 200, info().toString())
            "$API/prepare-upload" -> prepare(text, ip, out)
            "$API/cancel" -> {
                val sess = session
                if (sess != null && r.query["sessionId"] == sess.id) endSession(false, "${sess.from} cancelled sending.")
                reply(out, 200)
            }
            else -> reply(out, 404)
        }
    }

    private fun prepare(text: String, ip: String, out: OutputStream) {
        if (session != null || pendingFiles != null) { reply(out, 409); return }
        val o = try { JSONObject(text) } catch (e: Exception) { reply(out, 400); return }
        val from = o.optJSONObject("info") ?: JSONObject()
        remember(from, ip, "https")
        val files = o.optJSONObject("files") ?: run { reply(out, 400); return }
        val map = LinkedHashMap<String, InFile>()
        var total = 0L
        for (k in files.keys()) {
            val f = files.getJSONObject(k)
            val inf = InFile(f.optString("fileName", "file"), f.optLong("size"), f.optString("sha256").ifEmpty { null }.takeUnless { it == "null" })
            total += inf.size
            map[k] = inf
        }
        val alias = from.optString("alias", ip)
        val dir = identity.receiveFolder.also { it.mkdirs() }
        val free = try { StatFs(dir.absolutePath).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
        if (total + 64L * 1024 * 1024 > free) {
            reply(out, 403)
            messages.tryEmit(false to "$alias tried to send ${size(total)}, but there's only ${size(free)} free. Free up space and try again.")
            return
        }
        synchronized(decision) {
            pendingFiles = map; pendingFrom = alias; pendingIp = ip; answer = null
            val names = map.values.map { it.name.substringAfterLast('/') }.sorted()
            _request.value = Request(alias, map.size, total, names.take(3).joinToString(", ") + if (names.size > 3) " and ${names.size - 3} more" else "")
            val deadline = System.currentTimeMillis() + 60_000
            while (answer == null && System.currentTimeMillis() < deadline) (decision as Object).wait(500)
            val yes = answer == true
            _request.value = null
            pendingFiles = null
            if (!yes) {
                if (answer == null) messages.tryEmit(false to "No answer in a minute, so the files from $alias were turned down.")
                reply(out, 403)
                return
            }
            val sess = Session(UUID.randomUUID().toString(), alias, ip, map)
            session = sess
            _transfer.value = Transfer(true, false, "Receiving from $alias…", 0, total)
            NearbyService.hold(context, true)
            val tokens = JSONObject().apply { map.forEach { (k, f) -> put(k, f.token) } }
            reply(out, 200, JSONObject().put("sessionId", sess.id).put("files", tokens).toString())
        }
    }

    fun accept() = synchronized(decision) { answer = true; (decision as Object).notifyAll() }
    fun decline() = synchronized(decision) { answer = false; (decision as Object).notifyAll() }

    private fun upload(r: Req, length: Long, ip: String, out: OutputStream) {
        val sess = session ?: run { reply(out, 409); return }
        val f = sess.files[r.query["fileId"]]
        if (r.query["sessionId"] != sess.id) { reply(out, 409); return }
        if (f == null || f.token != r.query["token"] || ip != sess.ip) { reply(out, 403); return }
        val target = savePath(f.name)
        target.parentFile?.mkdirs()
        f.savedAs = target
        f.received = 0
        f.failed = false
        val md = if (f.sha != null) MessageDigest.getInstance("SHA-256") else null
        var ok = false
        try {
            FileOutputStream(target).use { fo ->
                val buf = ByteArray(1 shl 18)
                var left = length
                while (left > 0) {
                    val n = r.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    fo.write(buf, 0, n)
                    md?.update(buf, 0, n)
                    left -= n
                    f.received += n
                    sess.touched = System.currentTimeMillis()
                    progressIn(sess)
                }
                ok = left == 0L
            }
        } catch (e: Exception) {
            ok = false
        }
        if (ok && md != null && md.digest().joinToString("") { "%02x".format(it) } != f.sha!!.lowercase()) {
            target.delete(); f.failed = true; reply(out, 422)
        } else if (!ok) {
            target.delete(); f.failed = true; f.received = 0
        } else {
            f.done = true; reply(out, 200)
        }
        progressIn(sess)
        if (sess.files.values.all { it.done || it.failed }) {
            val done = sess.files.values.count { it.done }
            val failed = sess.files.values.any { it.failed }
            endSession(!failed, if (failed) "$done of ${sess.files.size} files from ${sess.from} arrived. Send the rest again."
                else "Received ${if (done == 1) "1 file" else "$done files"} from ${sess.from}. They're in ${identity.receiveFolder.name}.")
        }
    }

    private fun progressIn(s: Session) {
        val total = s.files.values.sumOf { it.size }
        val got = s.files.values.sumOf { if (it.done) it.size else it.received }
        _transfer.update { it.copy(done = got, total = total) }
        _items.value = s.files.values.map { it.toItem() } + history
    }

    private fun InFile.toItem() = Item(
        savedAs?.name ?: name.substringAfterLast('/'),
        when { done -> "Done"; failed -> "Didn't arrive"; received == 0L -> "Waiting"; else -> "${size(received)} of ${size(size)}" },
        if (size > 0) received.toFloat() / size else if (done) 1f else 0f, failed,
    )

    private val history = mutableListOf<Item>()

    private fun endSession(ok: Boolean, message: String) {
        val s = session ?: return
        session = null
        s.files.values.forEach { f ->
            if (!f.done) f.savedAs?.delete()
            history.add(0, f.toItem().copy(fraction = if (f.done) 1f else 0f, status = if (f.done) "Done" else "Didn't arrive", failed = !f.done))
            f.savedAs?.takeIf { f.done }?.let { Recent.add(context, it) }
        }
        while (history.size > 30) history.removeAt(history.size - 1)
        _items.value = history.toList()
        _transfer.value = Transfer()
        NearbyService.hold(context, false)
        messages.tryEmit(ok to message)
    }

    /** Keeps folders a sender included ("Photos/a.jpg"), never lets a name climb out of the folder. */
    private fun savePath(name: String): File {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.ifEmpty { listOf("from-phone") }
        val dir = File(identity.receiveFolder, parts.dropLast(1).joinToString("/"))
        val file = parts.last()
        var target = File(dir, file)
        var n = 1
        while (target.exists()) {
            val base = file.substringBeforeLast('.', file)
            val ext = if (file.contains('.')) "." + file.substringAfterLast('.') else ""
            target = File(dir, "$base ($n)$ext"); n++
        }
        return target
    }

    // ------------------------------------------------------------------ sending

    fun send(deviceId: String, files: List<File>, pin: String? = null) {
        val dev = found[deviceId] ?: run { messages.tryEmit(false to "That device isn't nearby anymore. Look again and pick it."); return }
        val list = files.filter { it.isFile }
        if (list.isEmpty()) { messages.tryEmit(false to "Pick some files to send first."); return }
        if (_transfer.value.active) return
        stopSend.set(false)
        val total = list.sumOf { it.length() }
        _transfer.value = Transfer(true, true, "Waiting for ${dev.alias} to accept…", 0, total)
        NearbyService.hold(context, true)
        pool.execute {
            val base = "${dev.protocol}://${dev.ip}:${dev.port}$API"
            try {
                val meta = JSONObject()
                list.forEachIndexed { i, f ->
                    val ext = f.extension.lowercase()
                    meta.put("f$i", JSONObject().put("id", "f$i").put("fileName", f.name).put("size", f.length())
                        .put("fileType", MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
                        .put("sha256", JSONObject.NULL).put("preview", JSONObject.NULL))
                }
                val c = open("$base/prepare-upload" + (pin?.let { "?pin=" + URLEncoder.encode(it, "UTF-8") } ?: ""), 5 * 60_000)
                sendConn = c
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("info", info()).put("files", meta).toString().toByteArray()) }
                when (val code = c.responseCode) {
                    200 -> {
                        val o = JSONObject(c.inputStream.bufferedReader().readText())
                        val sid = o.getString("sessionId")
                        val tokens = o.getJSONObject("files")
                        val accepted = list.indices.filter { tokens.has("f$it") }
                        var sent = 0L
                        for ((n, i) in accepted.withIndex()) {
                            if (stopSend.get()) throw InterruptedException()
                            val f = list[i]
                            _transfer.update { it.copy(status = "Sending ${f.name} to ${dev.alias}…", total = accepted.sumOf { j -> list[j].length() }) }
                            val u = open("$base/upload?sessionId=${enc(sid)}&fileId=f$i&token=${enc(tokens.getString("f$i"))}", 60_000)
                            sendConn = u
                            u.requestMethod = "POST"; u.doOutput = true
                            u.setFixedLengthStreamingMode(f.length())
                            u.setRequestProperty("Content-Type", "application/octet-stream")
                            u.outputStream.use { os ->
                                f.inputStream().use { ins ->
                                    val buf = ByteArray(1 shl 18)
                                    while (true) {
                                        if (stopSend.get()) throw InterruptedException()
                                        val r = ins.read(buf); if (r < 0) break
                                        os.write(buf, 0, r); sent += r
                                        _transfer.update { it.copy(done = sent) }
                                    }
                                }
                            }
                            if (u.responseCode != 200) {
                                val left = accepted.drop(n).map { list[it].name }
                                throw java.io.IOException("${dev.alias} didn't take ${f.name}. Sent $n of ${accepted.size}. Not sent: ${left.take(4).joinToString(", ")}.")
                            }
                            u.disconnect()
                        }
                        done(true, "Sent ${if (accepted.size == 1) "1 file" else "${accepted.size} files"} to ${dev.alias}.")
                    }
                    204 -> done(true, "${dev.alias} already has these files.")
                    401 -> { done(null, ""); pinNeeded.tryEmit(deviceId to (pin != null)) }
                    403 -> done(false, "${dev.alias} turned the files down.")
                    409 -> done(false, "${dev.alias} is busy with another transfer. Try again in a moment.")
                    429 -> done(false, "${dev.alias} asked to wait a bit before sending again.")
                    else -> done(false, "${dev.alias} couldn't take the files (error $code).")
                }
            } catch (e: InterruptedException) {
                done(false, "Sending stopped.")
            } catch (e: java.io.IOException) {
                done(false, e.message?.takeIf { it.contains(dev.alias) } ?: "Couldn't reach ${dev.alias}. Is it on the same Wi-Fi with LocalSend, Huginn or Muninn open?")
            } catch (e: Exception) {
                done(false, "Sending failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancelSend() {
        stopSend.set(true)
        try { sendConn?.disconnect() } catch (_: Exception) {}
    }

    private fun done(ok: Boolean?, msg: String) {
        _transfer.value = Transfer()
        sendConn = null
        NearbyService.hold(context, false)
        if (ok != null) messages.tryEmit(ok to msg)
    }

    /** A JSON POST to a device's extra routes (chat). Returns the status code, or -1. */
    fun post(dev: Device, path: String, json: String): Int = try {
        val c = open("${dev.protocol}://${dev.ip}:${dev.port}$path", 6000)
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        code
    } catch (e: Exception) {
        -1
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val PORT = 53317
        const val GROUP = "224.0.0.167"
        const val API = "/api/localsend/v2"

        /** LocalSend apps use self-signed certificates, so certificate checks can't apply on a home network. */
        private val trustAll: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }), null)
            }
        }

        fun open(url: String, timeout: Int): HttpURLConnection {
            val c = URL(url).openConnection() as HttpURLConnection
            if (c is HttpsURLConnection) {
                c.sslSocketFactory = trustAll.socketFactory
                c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            c.connectTimeout = minOf(timeout, 4000)
            c.readTimeout = timeout
            return c
        }

        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress!! }
                .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(it) }
        } catch (e: Exception) { null }

        fun size(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
            b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
            b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
            else -> "$b bytes"
        }
    }
}
