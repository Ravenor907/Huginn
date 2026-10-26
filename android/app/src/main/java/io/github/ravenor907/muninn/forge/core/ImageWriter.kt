package io.github.ravenor907.muninn.forge.core

import java.io.File
import java.io.IOException
import java.security.MessageDigest

class WriteException(msg: String) : IOException(msg)

/** The image file couldn't be read or decoded (a damaged download), as opposed to a drive failure. */
class CorruptImageException(cause: Throwable) : IOException("The image file is damaged: ${cause.message}", cause)

/**
 * Writes an ISO to a block device byte for byte (Bazzite's ISO is a hybrid
 * image, so this is a bootable drive), then reads the drive back and compares.
 */
object ImageWriter {
    /** Bytes per write command. Big commands let the stick write in long runs, like a PC does. */
    const val CHUNK = 4 shl 20

    /** Writes a plain image file (an ISO); [expectedSha256] must match what's written. */
    fun write(
        iso: File,
        dev: BlockDevice,
        expectedSha256: String,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
    ) {
        if (dev.sizeBytes < iso.length()) throw WriteException("This drive is too small for the image.")
        writeStream(iso, { it }, dev, expectedSha256, stage, log, cancel)
    }

    /**
     * Writes whatever [decode] produces from [file] (identity for an ISO, a
     * bzip2 decoder for SteamOS). Progress follows bytes read from [file], so it's
     * accurate even when the decoded size isn't known in advance. The drive is
     * read back and compared with the hash of what was actually written.
     */
    fun writeStream(
        file: File,
        decode: (java.io.InputStream) -> java.io.InputStream,
        dev: BlockDevice,
        expectedSha256: String?,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
        verify: Boolean = true,
    ) = writeStream({ file.inputStream() }, file.length(), decode, dev, expectedSha256, stage, log, cancel, verify)

    /** Same, reading from [open] (for example a file the person picked); [rawSize] drives progress. */
    fun writeStream(
        open: () -> java.io.InputStream,
        rawSize: Long,
        decode: (java.io.InputStream) -> java.io.InputStream,
        dev: BlockDevice,
        expectedSha256: String?,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
        /** Read the drive back and compare (off only by the Labs setting). */
        verify: Boolean = true,
    ): Long {
        val fileSize = rawSize
        val bs = dev.blockSize
        require(CHUNK % bs == 0)
        val buf = ByteArray(CHUNK)
        val md = MessageDigest.getInstance("SHA-256")

        // Clear the end of the drive: a stale backup partition table there from an
        // earlier use can confuse some firmware about which layout is real.
        val tailBlocks = minOf(CHUNK / bs.toLong(), dev.blockCount)
        java.util.Arrays.fill(buf, 0)
        dev.write(dev.blockCount - tailBlocks, tailBlocks.toInt(), buf)

        log("Writing ${dev.name}")
        if (dev is ScsiDisk) {
            log("USB: ${dev.commandBytes / 1024} KB per command, ${dev.transferSize / 1024} KB transfers" + if (dev.pipelined) ", several in flight" else "")
        }
        var done = 0L
        val raw = CountingStream(open().buffered(CHUNK))
        val timer = Timer()
        // Unpacking runs ahead on other threads while the USB writes go on (bzip2 has its own).
        decoding { ahead(decode(raw), raw) }.use { input ->
            while (true) {
                if (cancel()) throw CancelledException()
                var got = 0
                timer.reading {
                    while (got < CHUNK) {
                        val n = decoding { input.read(buf, got, CHUNK - got) }
                        if (n < 0) break
                        got += n
                    }
                }
                if (got == 0) break
                md.update(buf, 0, got)
                val padded = roundUp(got, bs)
                if (padded > got) java.util.Arrays.fill(buf, got, padded, 0)
                if (done / bs + padded / bs > dev.blockCount) {
                    throw WriteException("This drive is too small for the image.")
                }
                timer.writing { dev.write(done / bs, padded / bs, buf) }
                done += got
                stage(Stage.WRITE, raw.count, fileSize)
                if (got < CHUNK) break
            }
        }
        val size = done
        val written = md.digest().toHex()
        if (expectedSha256 != null && written != expectedSha256) {
            throw WriteException("The image changed while it was being written. Try again.")
        }
        stage(Stage.WRITE, fileSize, fileSize)
        timer.writing { dev.sync() }
        log(timer.summary(size))
        if (!verify) {
            log("Skipped reading the drive back (Labs setting)")
            stage(Stage.CHECK, size, size)
            return size
        }

        log("Reading the drive back to confirm")
        val readStart = System.nanoTime()
        val check = MessageDigest.getInstance("SHA-256")
        var read = 0L
        while (read < size) {
            if (cancel()) throw CancelledException()
            val want = minOf(CHUNK.toLong(), size - read).toInt()
            val padded = roundUp(want, bs)
            dev.read(read / bs, padded / bs, buf)
            check.update(buf, 0, want)
            read += want
            stage(Stage.CHECK, read, size)
        }
        if (check.digest().toHex() != written) {
            throw WriteException(
                "The drive didn't store the image correctly. It may be failing or counterfeit. Try another drive.",
            )
        }
        log("Every byte on the drive matches the image (read back at ${Timer.rate(size, System.nanoTime() - readStart)})")
        return size
    }

    /**
     * Compares a drive with an image without writing anything: the image as [decode] produces it
     * against the drive's first bytes. Returns null if they match, or the byte offset where they
     * first differ.
     */
    fun compare(
        open: () -> java.io.InputStream,
        rawSize: Long,
        decode: (java.io.InputStream) -> java.io.InputStream,
        dev: BlockDevice,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
    ): Long? {
        val bs = dev.blockSize
        val img = ByteArray(CHUNK)
        val disk = ByteArray(CHUNK)
        var at = 0L
        val raw = CountingStream(open().buffered(CHUNK))
        val start = System.nanoTime()
        decoding { ahead(decode(raw), raw) }.use { input ->
            while (true) {
                if (cancel()) throw CancelledException()
                var got = 0
                while (got < CHUNK) {
                    val n = decoding { input.read(img, got, CHUNK - got) }
                    if (n < 0) break
                    got += n
                }
                if (got == 0) break
                if (at + got > dev.sizeBytes) return dev.sizeBytes.also { log("The image is bigger than the drive") }
                val padded = roundUp(got, bs)
                dev.read(at / bs, padded / bs, disk)
                for (i in 0 until got) {
                    if (img[i] != disk[i]) return (at + i).also { log("First difference at byte ${at + i}") }
                }
                at += got
                stage(Stage.CHECK, raw.count, rawSize)
                if (got < CHUNK) break
            }
        }
        log("All %.2f GB match the image (read at %s)".format(at / 1e9, Timer.rate(at, System.nanoTime() - start)))
        return null
    }

    /** Where the writing time went: waiting for the image (download or unpacking) or for the drive. */
    class Timer {
        private var readNs = 0L
        private var writeNs = 0L
        private val start = System.nanoTime()

        fun <T> reading(block: () -> T): T = timed(true, block)
        fun <T> writing(block: () -> T): T = timed(false, block)

        private fun <T> timed(read: Boolean, block: () -> T): T {
            val t = System.nanoTime()
            try {
                return block()
            } finally {
                val d = System.nanoTime() - t
                if (read) readNs += d else writeNs += d
            }
        }

        fun summary(bytes: Long): String {
            val total = System.nanoTime() - start
            val busy = (readNs + writeNs).coerceAtLeast(1)
            val drive = (writeNs * 100 / busy).toInt()
            val who = when {
                drive >= 70 -> "the drive is the limit"
                drive <= 30 -> "unpacking or downloading is the limit, not the drive"
                else -> "the drive and unpacking are about even"
            }
            return "Wrote %.2f GB in %s (%s). %d%% of the time waiting on the drive, %d%% on the image: %s".format(
                bytes / 1e9, clock(total), rate(bytes, total), drive, 100 - drive, who,
            )
        }

        companion object {
            fun rate(bytes: Long, ns: Long) = "%.1f MB/s".format(bytes / 1e6 / (ns.coerceAtLeast(1) / 1e9))
            fun clock(ns: Long): String {
                val s = ns / 1_000_000_000
                return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
            }
        }
    }

    fun ahead(decoded: java.io.InputStream, raw: java.io.InputStream): java.io.InputStream =
        if (decoded === raw || decoded is ParallelBzip2InputStream) decoded else PrefetchInputStream(decoded)

    /** Decoders report bad input as IOException or, for some malformed data, a RuntimeException. */
    private inline fun <T> decoding(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw CorruptImageException(e)
    } catch (e: RuntimeException) {
        throw CorruptImageException(e)
    }

    private fun roundUp(n: Int, to: Int) = (n + to - 1) / to * to

    private class CountingStream(inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}
