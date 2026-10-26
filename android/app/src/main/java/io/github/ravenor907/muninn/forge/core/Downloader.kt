package io.github.ravenor907.muninn.forge.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection

/** Steps of a job, in order. */
enum class Stage { LOOKUP, DOWNLOAD, VERIFY, WRITE, CHECK }

typealias StageFn = (Stage, Long, Long) -> Unit
typealias LogFn = (String) -> Unit

class DownloadException(msg: String) : Exception(msg)

data class PreparedImage(
    val file: File,
    val sha256: String,
    val source: ImageSource,
    val signatureVerified: Boolean,
    val fromCache: Boolean,
    /**
     * Set when the image is written while it downloads (Labs): read it through [LiveDownload.open]
     * instead of [file], then call [LiveDownload.finish] to check it.
     */
    val live: LiveDownload? = null,
)

/**
 * Finds the newest image for a distro, downloads it only if the cache is stale
 * (resuming partial downloads), and verifies checksum and signature.
 */
class Downloader(
    private val cacheDir: File,
    /** Returns the PEM text of a catalog key path such as "keys/bazzite-cosign.pub". */
    private val readKey: (String) -> String,
    private val userAgent: String = "HandheldForge-Android",
    /** Lets tests point the catalog's https URLs at a local http server. */
    private val rewriteUrl: (String) -> String = { it },
) {
    private fun open(url: String, method: String = "GET", range: Long = 0): HttpURLConnection {
        val c = java.net.URI(rewriteUrl(url)).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
        return c
    }

    fun fetchText(url: String, cap: Int = 64 * 1024): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw HttpStatus(c.responseCode)
            return c.inputStream.use { readCapped(it, cap) }.toString(Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    /** (size, version tag from ETag/Last-Modified) from HEAD, or null if not published. */
    fun remoteMeta(url: String): Pair<Long, String>? {
        val c = open(url, "HEAD")
        try {
            return when (c.responseCode) {
                in 200..299 -> c.contentLengthLong.coerceAtLeast(0) to
                    (c.getHeaderField("ETag") ?: c.getHeaderField("Last-Modified") ?: "").trim('"', ' ')
                403, 404, 410 -> null
                else -> throw HttpStatus(c.responseCode)
            }
        } finally {
            c.disconnect()
        }
    }

    /** The newest build named on the publisher's page, or the catalog's own when there's none. */
    fun newestBuild(img: ImageSource, log: LogFn): ImageSource {
        val page = img.latestPage ?: return img
        val newest = try {
            Regex(img.latestPattern!!).findAll(fetchText(page, 4 shl 20)).map { it.groupValues[1] }.maxOrNull()
        } catch (e: Exception) {
            log("Couldn't read $page (${e.message}); using the known build")
            return img
        }
        if (newest == null || newest <= img.build!!) return img
        log("Newest build on $page: $newest")
        return img.at(newest)
    }

    private fun resolveMeta(distro: Distro, log: LogFn): Pair<ImageSource, Pair<Long, String>> {
        if (!distro.enabled) throw DownloadException("${distro.name} is not available yet")
        for (img in distro.images.map { newestBuild(it, log) } + distro.images.filter { it.latestPage != null }) {
            try {
                val meta = remoteMeta(img.isoUrl)
                if (meta != null) {
                    log("Using ${img.label}: ${img.filename}")
                    return img to meta
                }
                log("${img.label} not found upstream, trying next")
            } catch (e: IOException) {
                log("Could not reach ${img.isoUrl}: ${e.message}")
            }
        }
        throw DownloadException("Couldn't reach the ${distro.name} download server. Check your connection.")
    }

    fun prepare(distro: Distro, stage: StageFn, log: LogFn, cancel: () -> Boolean, stream: Boolean = false): PreparedImage {
        stage(Stage.LOOKUP, 0, 0)
        val (img, meta) = resolveMeta(distro, log)
        val remote = meta.first
        // Without a published checksum, the cache is keyed on the server's size and version tag.
        val checksummed = img.checksumUrl != null
        val expected = if (checksummed) {
            Verify.parseChecksum(fetchText(img.checksumUrl!!), img.filename).also {
                log("Latest ${img.filename} sha256 ${it.take(16)}...")
            }
        } else {
            log("${distro.name} publishes no checksum; the image's own CRCs and a full read-back will be checked")
            "unchecked:$remote:${meta.second}"
        }

        val signature: String? = img.signatureUrl?.let { url ->
            try {
                fetchText(url)
            } catch (e: HttpStatus) {
                if (img.requireSignature) throw VerificationException("Signature required but missing (${e.code})")
                log("No signature published for this image")
                null
            }
        }
        val key = if (signature != null) {
            Verify.loadPublicKey(readKey(distro.publicKey ?: throw VerificationException("No key for ${distro.name}")))
        } else {
            null
        }

        cacheDir.mkdirs()
        val iso = File(cacheDir, img.filename)
        val stamp = File(cacheDir, "${img.filename}.verified")
        val part = File(cacheDir, "${img.filename}.part")
        val partStamp = File(cacheDir, "${img.filename}.part.expected")

        // The stamp records the hash (and whether the signature checked out) at download
        // time. Same hash means same bytes, so that verification still holds.
        val stampLines = if (stamp.exists()) stamp.readText().lines() else emptyList()
        if (iso.exists() && stampLines.firstOrNull()?.trim() == expected) {
            val wasSigned = stampLines.getOrNull(1)?.trim() == "signed"
            if (signature == null || wasSigned) {
                log("Already have the latest release, skipping download")
                return PreparedImage(iso, expected, img, wasSigned, fromCache = true)
            }
        }

        if (part.exists() && (!partStamp.exists() || partStamp.readText().trim() != expected)) part.delete()
        partStamp.writeText(expected)

        val need = remote - (if (part.exists()) part.length() else 0L)
        val free = cacheDir.usableSpace
        if (need > 0 && free < need + 200L * 1024 * 1024) {
            throw DownloadException(
                "Not enough space. Free up %.1f GB on this phone and try again."
                    .format((need + 200L * 1024 * 1024 - free) / 1e9),
            )
        }

        if (stream && remote > 0) {
            log("Downloading ${img.isoUrl} and writing it at the same time (Labs)")
            val live = LiveDownload({ range -> open(img.isoUrl, range = range) }, part, remote, expected, checksummed, signature, key, cancel) { done ->
                stamp.delete()
                iso.delete()
                if (!part.renameTo(iso)) throw IOException("Could not finalize the download")
                partStamp.delete()
                stamp.writeText(expected + if (done) "\nsigned" else "")
            }
            return PreparedImage(part, expected, img, false, fromCache = false, live = live)
        }

        log("Downloading ${img.isoUrl}")
        download(img.isoUrl, part, stage, cancel)

        stage(Stage.VERIFY, 0, 0)
        val (actual, signed) = if (checksummed) {
            Verify.hashAndVerify(part, signature, key, cancel) { d, t -> stage(Stage.VERIFY, d, t) }
        } else {
            (if (part.length() == remote) expected else "size-mismatch") to false
        }
        if (actual != expected) {
            part.delete()
            partStamp.delete()
            throw VerificationException("The download was corrupted, so it was deleted. Try again.")
        }
        log(
            when {
                signed -> "Checksum and signature verified"
                checksummed -> "Checksum verified"
                else -> "Download complete"
            },
        )
        stamp.delete()
        iso.delete()
        if (!part.renameTo(iso)) throw IOException("Could not finalize the download")
        partStamp.delete()
        stamp.writeText(expected + if (signed) "\nsigned" else "")
        return PreparedImage(iso, expected, img, signed, fromCache = false)
    }

    /** Downloads [url] into [part], resuming what's already there and retrying dropped connections. */
    fun download(url: String, part: File, stage: StageFn, cancel: () -> Boolean) {
        var attempt = 0
        while (true) {
            val before = if (part.exists()) part.length() else 0L
            try {
                downloadOnce(url, part, stage, cancel)
                return
            } catch (e: IOException) {
                // Phones drop Wi-Fi, especially when the screen goes off or another app takes
                // over. Resume as long as each try gets somewhere; give up after a run of
                // tries that get nothing (about a minute and a half).
                if (e is HttpStatus && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                if (part.exists() && part.length() > before) attempt = 0
                if (++attempt > MAX_STALLED_TRIES || cancel()) throw e
                val until = System.nanoTime() + minOf(2_000L * attempt, 15_000L) * 1_000_000
                while (System.nanoTime() < until) {
                    if (cancel()) throw CancelledException()
                    Thread.sleep(250)
                }
            }
        }
    }

    private fun downloadOnce(url: String, part: File, stage: StageFn, cancel: () -> Boolean) {
        var have = if (part.exists()) part.length() else 0L
        val c = open(url, range = have)
        try {
            val code = c.responseCode
            if (code == 416 && have > 0) return // already complete
            if (code !in 200..299) throw HttpStatus(code)
            if (have > 0 && code != 206) have = 0 // server ignored the range: start over
            val length = c.contentLengthLong
            val total = if (length > 0) have + length else 0L
            FileOutputStream(part, have > 0).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(1 shl 20)
                    var done = have
                    var lastReport = 0L
                    while (true) {
                        if (cancel()) throw CancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > (4 shl 20) || done == total) {
                            stage(Stage.DOWNLOAD, done, total)
                            lastReport = done
                        }
                    }
                    if (total > 0 && done < total) throw IOException("Connection closed early")
                }
            }
        } finally {
            c.disconnect()
        }
    }
}

private const val MAX_STALLED_TRIES = 8

/**
 * A download read while it arrives: whatever's already in [part] first, then the rest from the
 * server, saved to [part] as it goes (so it resumes and is kept for next time) and hashed on the
 * way through. Dropped connections are retried from where they stopped, like a normal download.
 */
class LiveDownload internal constructor(
    /** Opens the image's URL from a byte offset. */
    private val connector: (Long) -> HttpURLConnection,
    private val part: File,
    val total: Long,
    private val expected: String,
    private val checksummed: Boolean,
    signature: String?,
    key: java.security.PublicKey?,
    private val cancel: () -> Boolean,
    private val keep: (signed: Boolean) -> Unit,
) {
    private val verifier = Verify.Running(signature, key)
    private var stream: Stream? = null

    /** The image bytes, from the start. Only one stream per download. */
    fun open(): java.io.InputStream {
        check(stream == null) { "already open" }
        return Stream().also { stream = it }
    }

    /**
     * Reads whatever the writer didn't need, then checks size, checksum and signature. Keeps
     * the download as a verified copy, or deletes it and throws if it doesn't check out.
     */
    fun finish(log: LogFn) {
        val s = stream ?: throw IllegalStateException("never opened")
        val buf = ByteArray(1 shl 20)
        while (s.read(buf) >= 0) Unit
        s.close()
        val (hex, signed) = try {
            verifier.finish()
        } catch (e: VerificationException) {
            part.delete()
            throw e
        }
        val ok = s.position == total && (!checksummed || hex == expected)
        if (!ok) {
            part.delete()
            throw VerificationException(
                "The download didn't match the publisher's checksum, so the drive can't be trusted. It was deleted; create the drive again.",
            )
        }
        log(
            when {
                signed -> "Checksum and signature verified (after writing)"
                checksummed -> "Checksum verified (after writing)"
                else -> "Download complete"
            },
        )
        keep(signed)
    }

    private inner class Stream : java.io.InputStream() {
        var position = 0L
            private set
        private val local = if (part.exists()) part.length().coerceAtMost(total) else 0L
        private val localIn = if (local > 0) part.inputStream() else null
        private var out: FileOutputStream? = null
        private var conn: HttpURLConnection? = null
        private var net: java.io.InputStream? = null
        private var stalled = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position < local) {
                val n = localIn!!.read(b, off, minOf(len.toLong(), local - position).toInt())
                if (n > 0) {
                    verifier.update(b, off, n)
                    position += n
                    return n
                }
                throw IOException("The partial download changed while it was being read")
            }
            if (position >= total) return -1
            while (true) {
                if (cancel()) throw CancelledException()
                try {
                    val input = net ?: connect()
                    val n = input.read(b, off, minOf(len.toLong(), total - position).toInt())
                    if (n < 0) throw IOException("Connection closed early")
                    (out ?: FileOutputStream(part, true).also { out = it }).write(b, off, n)
                    verifier.update(b, off, n)
                    position += n
                    stalled = 0
                    return n
                } catch (e: IOException) {
                    if (e is HttpStatus && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                    disconnect()
                    if (++stalled > MAX_STALLED_TRIES) throw e
                    val until = System.nanoTime() + minOf(2_000L * stalled, 15_000L) * 1_000_000
                    while (System.nanoTime() < until) {
                        if (cancel()) throw CancelledException()
                        Thread.sleep(250)
                    }
                }
            }
        }

        private fun connect(): java.io.InputStream {
            val c = connector(position)
            val code = c.responseCode
            if (code !in 200..299) {
                c.disconnect()
                throw HttpStatus(code)
            }
            val input = c.inputStream.buffered(1 shl 20)
            if (position > 0 && code != 206) {
                // The server ignored the range: skip what we already have.
                var skip = position
                while (skip > 0) {
                    val k = input.skip(skip)
                    if (k <= 0) throw IOException("Connection closed early")
                    skip -= k
                }
            }
            conn = c
            net = input
            return input
        }

        private fun disconnect() {
            runCatching { net?.close() }
            runCatching { conn?.disconnect() }
            net = null
            conn = null
        }

        override fun close() {
            disconnect()
            runCatching { out?.close() }
            runCatching { localIn?.close() }
        }
    }
}

class HttpStatus(val code: Int) : IOException("HTTP $code")

private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < cap) {
        val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
