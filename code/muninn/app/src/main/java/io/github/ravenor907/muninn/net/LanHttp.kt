package io.github.ravenor907.muninn.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.URL

/**
 * Plain HTTP, only to a device on this phone's own network: LocalSend apps that don't use TLS. The app's network
 * security config allows no plain HTTP at all, so these few requests go over a socket of their own, and only ever
 * to a private (home network) address written as a number. Everything else is HTTPS.
 *
 * Just what Muninn's LAN requests use: a method, request headers, a body (fixed length or buffered), the status,
 * the response headers and body (by length, chunked, or until the device closes).
 */
class LanHttp private constructor(url: URL) : HttpURLConnection(url) {
    /** The address asked for (kept apart from URLConnection's own field of the same name). */
    private val u: URL = url
    private var message = ""
    private val headers = LinkedHashMap<String, String>()
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var body: OutputStream? = null
    private var buffered: ByteArrayOutputStream? = null
    private var sent = false
    private var code = -1
    private val answer = HashMap<String, String>()
    private var stream: InputStream? = null

    override fun setRequestProperty(key: String, value: String?) { if (value == null) headers.remove(key) else headers[key] = value }
    override fun addRequestProperty(key: String, value: String?) = setRequestProperty(key, value)
    override fun getRequestProperty(key: String): String? = headers[key]
    override fun usingProxy(): Boolean = false

    override fun connect() {
        if (connected) return
        val s = Socket()
        s.connect(InetSocketAddress(address(u), port(u)), connectTimeout)
        s.soTimeout = readTimeout
        socket = s
        input = BufferedInputStream(s.getInputStream())
        connected = true
    }

    private fun head(length: Long): ByteArray {
        val path = (u.path?.ifEmpty { null } ?: "/") + (u.query?.let { "?$it" } ?: "")
        val b = StringBuilder().append(requestMethod).append(' ').append(path).append(" HTTP/1.1\r\n")
            .append("Host: ").append(u.host).append(':').append(port(u)).append("\r\n")
            .append("Connection: close\r\n")
        for ((k, v) in headers) if (!k.equals("Content-Length", true) && !k.equals("Host", true) && !k.equals("Connection", true))
            b.append(k).append(": ").append(v).append("\r\n")
        if (length >= 0) b.append("Content-Length: ").append(length).append("\r\n")
        return b.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)
    }

    override fun getOutputStream(): OutputStream {
        body?.let { return it }
        if (!getDoOutput()) throw ProtocolException("Output isn't on for this request")
        if (sent) throw ProtocolException("The request has gone already")
        connect()
        val fixed = fixedContentLengthLong
        val made: OutputStream = if (fixed >= 0) {
            // A file: its length is known, so it streams straight out after the head.
            val raw = socket!!.getOutputStream()
            raw.write(head(fixed))
            sent = true
            object : FilterOutputStream(raw) {
                override fun write(b: ByteArray, off: Int, len: Int) { raw.write(b, off, len) }
                override fun close() { raw.flush() }   // the socket stays open for the answer
            }
        } else ByteArrayOutputStream().also { buffered = it }
        body = made
        return made
    }

    /** The request goes out (with its buffered body) if it hasn't yet. */
    private fun send() {
        if (sent) return
        connect()
        val raw = socket!!.getOutputStream()
        val b = buffered
        if (b != null) { raw.write(head(b.size().toLong())); b.writeTo(raw) }
        else raw.write(head(if (requestMethod == "POST" || requestMethod == "PUT") 0L else -1L))
        raw.flush()
        sent = true
    }

    private fun readAnswer() {
        if (code >= 0) return
        send()
        socket!!.getOutputStream().flush()
        val ins = input!!
        val status = line(ins) ?: throw IOException("No answer")
        val parts = status.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("Not an HTTP answer")
        code = parts[1].toIntOrNull() ?: throw IOException("Not an HTTP answer")
        message = parts.drop(2).joinToString(" ")
        while (true) {
            val l = line(ins) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) answer[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val length = answer["content-length"]?.toLongOrNull()
        stream = when {
            requestMethod == "HEAD" || code == 204 || code == 304 -> Limited(ins, 0)
            answer["transfer-encoding"]?.contains("chunked", true) == true -> Chunked(ins)
            length != null -> Limited(ins, length)
            else -> ins
        }
    }

    override fun getResponseCode(): Int { readAnswer(); return code }

    override fun getResponseMessage(): String { readAnswer(); return message }

    override fun getInputStream(): InputStream {
        readAnswer()
        if (code >= 400) throw IOException("HTTP $code")
        return stream!!
    }

    override fun getErrorStream(): InputStream? = if (code >= 400) stream else null

    override fun getHeaderField(name: String?): String? = name?.let { answer[it.lowercase()] }

    override fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    /** A body of a known length. */
    private class Limited(private val ins: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val r = ins.read(); if (r >= 0) left--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = ins.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    /** A chunked body. */
    private class Chunked(private val ins: InputStream) : InputStream() {
        private var left = 0L
        private var done = false
        private fun next(): Boolean {
            if (done) return false
            if (left > 0) return true
            val size = (line(ins) ?: return false.also { done = true }).substringBefore(';').trim().toLongOrNull(16) ?: return false.also { done = true }
            if (size == 0L) { done = true; return false }
            left = size
            return true
        }
        override fun read(): Int {
            if (!next()) return -1
            val r = ins.read()
            if (r >= 0 && --left == 0L) line(ins)
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val n = ins.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) { left -= n; if (left == 0L) line(ins) }
            return n
        }
    }

    companion object {
        /** A connection for [url]: HTTPS as usual, plain HTTP only to a home-network address (else an IOException). */
        fun open(url: URL): HttpURLConnection {
            if (url.protocol != "http") return url.openConnection() as HttpURLConnection
            if (!lanHost(url.host)) throw IOException("Plain HTTP only goes to devices on this network")
            return LanHttp(url)
        }

        /** A number address (never a name to look up) on a home network. */
        fun lanHost(host: String?): Boolean {
            val h = host?.removePrefix("[")?.removeSuffix("]") ?: return false
            if (h.isEmpty() || !(h.contains(':') || h.all { it.isDigit() || it == '.' })) return false
            return try { Lan.isPrivate(InetAddress.getByName(h)) } catch (_: Exception) { false }
        }

        private fun address(url: URL): InetAddress = InetAddress.getByName(url.host.removePrefix("[").removeSuffix("]"))
        private fun port(url: URL): Int = if (url.port > 0) url.port else 80

        /** One line of the head (CRLF or LF), null at the end. Heads are short: at most 16 KB a line. */
        private fun line(ins: InputStream): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = ins.read()
                if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
                if (c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
                if (b.size() > 16_384) throw IOException("Line too long")
            }
            return b.toString("ISO-8859-1")
        }
    }
}
