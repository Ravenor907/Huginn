package io.github.ravenor907.muninn.net.tor

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The tiny HTTP server behind this phone's onion service. Tor hands it the streams that arrive
 * for port 80; it listens on 127.0.0.1 only, never on a network. It takes one route, the same
 * one the Wi-Fi side serves (POST /api/huginn/v1/message), and gives the body to [handler].
 */
class OnionHttp(private val path: String, private val handler: () -> ((ByteArray) -> Int)?) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "muninn-onion-http").apply { isDaemon = true } }

    /** The local port Tor should forward to (started on first use, then kept). */
    @Synchronized
    fun port(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 32, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { if (s.isClosed) break else continue }
                pool.execute { try { serve(c) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} } }
            }
        }, "muninn-onion-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    private fun serve(c: Socket) {
        c.soTimeout = 60_000
        val input = BufferedInputStream(c.getInputStream(), 1 shl 16)
        val out = c.getOutputStream()
        val req = HttpRequest.read(input, MAX) ?: run { HttpRequest.reply(out, 400); return }
        val code = when {
            req.path != path -> 404
            req.method != "POST" -> 405
            req.body == null -> 413
            else -> handler()?.let { h -> try { h(req.body) } catch (e: Exception) { 500 } } ?: 503   // 503: not ready yet, try again
        }
        HttpRequest.reply(out, code)
    }

    companion object {
        /** Same cap as the Wi-Fi side. */
        const val MAX = 8 * 1024 * 1024
    }
}

/** One HTTP/1.1 request: [body] is null when it was bigger than allowed. */
class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray?) {
    companion object {
        /** Reads the head and body (Content-Length or chunked). Null if it isn't HTTP. */
        fun read(input: InputStream, max: Int): HttpRequest? {
            val head = StringBuilder()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                head.append(b.toChar())
                last4 = (last4 shl 8) or b
                if (last4 == 0x0d0a0d0a) break
                if (head.length > 16 * 1024) return null
            }
            val lines = head.toString().split("\r\n")
            val first = lines[0].split(" ")
            if (first.size < 3 || !first[2].startsWith("HTTP/")) return null
            val headers = lines.drop(1).filter { it.contains(':') }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val path = first[1].substringBefore('?')
            val body: ByteArray? = when {
                headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> chunked(input, max)
                else -> {
                    val n = headers["content-length"]?.toLongOrNull() ?: 0L
                    if (n < 0) return null
                    if (n > max) null else exactly(input, n.toInt()) ?: return null
                }
            }
            return HttpRequest(first[0].uppercase(), path, headers, body)
        }

        private fun exactly(input: InputStream, n: Int): ByteArray? {
            val b = ByteArray(n); var at = 0
            while (at < n) { val r = input.read(b, at, n - at); if (r < 0) return null; at += r }
            return b
        }

        private fun line(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 1024) return null
            }
        }

        private fun chunked(input: InputStream, max: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = line(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("bad chunk")
                if (size == 0) { while ((line(input) ?: "").isNotEmpty()) {}; return out.toByteArray() }
                if (size < 0 || out.size() + size > max) return null
                out.write(exactly(input, size) ?: throw IOException("short chunk"))
                line(input)   // the CRLF after each chunk
            }
        }

        fun reply(out: OutputStream, code: Int) {
            val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
                405 -> "Method Not Allowed"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"
                429 -> "Too Many Requests"; 503 -> "Service Unavailable"; else -> "Error" }
            out.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
    }
}
