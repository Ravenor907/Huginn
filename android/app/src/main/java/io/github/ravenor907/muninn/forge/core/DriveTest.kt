package io.github.ravenor907.muninn.forge.core

import java.nio.ByteBuffer
import java.util.SplittableRandom

/**
 * Measures a drive's real write and read speed and checks it holds what it claims (Labs).
 * Erases the drive.
 *
 * 1. Writes a long sequential run from the start (1 to 4 GB, about 90 seconds' worth at the
 *    speed it shows), timing each 256 MB, so a stick that slows down once its fast cache fills
 *    shows that.
 * 2. Writes 1 MB markers spread over the rest of the drive, up to its last megabyte.
 * 3. Reads the run back (read speed, and every byte checked), then every marker. A fake drive
 *    that claims more space than it has loses or overwrites markers past its real size.
 *
 * Every block holds a pattern computed from its address, so nothing has to be kept in memory
 * and a block that ends up at the wrong address can't pass.
 */
object DriveTest {
    const val CHUNK = 4 shl 20
    private const val SEGMENT = 256L shl 20
    private const val SPOT = 1L shl 20

    data class Report(
        val driveBytes: Long,
        val testedBytes: Long,
        /** Write speed of each 256 MB of the sequential run, MB/s. */
        val segments: List<Double>,
        val writeMBs: Double,
        val readMBs: Double,
        /** Bytes of the sequential run that didn't read back as written. */
        val badBytes: Long,
        val spots: Int,
        val badSpots: Int,
        /** Start of the first marker that didn't survive, if any. */
        val firstBadSpot: Long?,
    ) {
        val fake get() = badSpots > 0
        val failing get() = badBytes > 0

        /** Speed after the drive's fast cache, if it slowed down a lot partway. */
        val slowdown: Pair<Double, Double>? get() {
            if (segments.size < 3) return null
            val first = segments.first()
            val tail = segments.takeLast(maxOf(1, segments.size / 3)).average()
            return if (tail < first * 0.5) first to tail else null
        }

        fun lines(): List<String> = buildList {
            add("Write: %.1f MB/s over %.1f GB".format(writeMBs, testedBytes / 1e9))
            slowdown?.let { (a, b) -> add("It starts at %.0f MB/s, then drops to %.0f MB/s once its fast cache is full".format(a, b)) }
            add("Read: %.1f MB/s".format(readMBs))
            when {
                fake -> add(
                    "FAKE CAPACITY: $badSpots of $spots markers spread over the drive were lost" +
                        (firstBadSpot?.let { ", starting at %.1f GB".format(it / 1e9) } ?: "") +
                        ". It doesn't really hold %.0f GB. Don't trust it with anything.".format(driveBytes / 1e9),
                )
                failing -> {}
                else -> add("Capacity: all $spots markers up to the last megabyte came back intact, so the full %.0f GB is real".format(driveBytes / 1e9))
            }
            if (failing) {
                add(
                    "LOST DATA: %.1f MB of the test data came back wrong. The drive is failing, or it's fake and smaller than it claims ".format(badBytes / 1e6) +
                        "(writing near its end overwrote the start). Don't trust it with anything.",
                )
            }
            val gb8 = 8.12e9
            add("A SteamOS image (8.1 GB) would take about %s to write".format(minutes(gb8 / (slowdown?.second ?: writeMBs) / 1e6)))
        }

        private fun minutes(seconds: Double) = if (seconds < 90) "%.0f seconds".format(seconds) else "%.0f minutes".format(seconds / 60)
    }

    fun pattern(buf: ByteArray, len: Int, byteOffset: Long, seed: Long) {
        val bb = ByteBuffer.wrap(buf)
        var o = 0
        while (o < len) {
            // A fresh generator per 64 KB, keyed by address: blocks are independent and verifiable alone.
            val r = SplittableRandom(seed xor ((byteOffset + o) * -0x61c8864680b583ebL))
            val end = minOf(len, o + 65536)
            while (o + 8 <= end) {
                bb.putLong(o, r.nextLong())
                o += 8
            }
            while (o < end) buf[o++] = r.nextInt().toByte()
        }
    }

    fun run(
        dev: BlockDevice,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
        minBytes: Long = 1L shl 30,
        maxBytes: Long = 4L shl 30,
        budgetSeconds: Double = 90.0,
        spots: Int = 128,
        seed: Long = System.nanoTime(),
    ): Report {
        val bs = dev.blockSize
        require(CHUNK % bs == 0)
        val size = dev.sizeBytes
        val buf = ByteArray(CHUNK)
        val check = ByteArray(CHUNK)
        val spotArea = SPOT * spots
        if (size < minBytes / 4 + spotArea) throw java.io.IOException("This drive is too small to test")

        // 1. Sequential run.
        var target = minOf(minBytes, size - spotArea) / CHUNK * CHUNK
        val segments = ArrayList<Double>()
        var written = 0L
        var segStart = System.nanoTime()
        var segBytes = 0L
        val t0 = System.nanoTime()
        log("Writing test data from the start of the drive")
        while (written < target) {
            if (cancel()) throw CancelledException()
            val n = minOf(CHUNK.toLong(), target - written).toInt() / bs * bs
            pattern(buf, n, written, seed)
            dev.write(written / bs, n / bs, buf)
            written += n
            segBytes += n
            if (segBytes >= SEGMENT || written >= target) {
                val ns = System.nanoTime() - segStart
                val mbs = segBytes / 1e6 / (ns / 1e9)
                segments.add(mbs)
                log("%.2f GB written, %.1f MB/s".format(written / 1e9, mbs))
                if (segments.size == 1) {
                    // Aim for about the time budget at this speed, within the limits.
                    target = (mbs * 1e6 * budgetSeconds).toLong().coerceIn(minBytes, maxBytes).coerceAtMost(size - spotArea) / CHUNK * CHUNK
                    target = maxOf(target, written)
                }
                segStart = System.nanoTime()
                segBytes = 0
            }
            stage(Stage.WRITE, written, target + spotArea)
        }

        // 2. Markers across the rest of the drive, the last one at its very end.
        val from = written
        val positions = (0 until spots).map { i ->
            val span = size - from - SPOT
            (from + span * i / maxOf(1, spots - 1)) / bs * bs
        }.distinct()
        for ((i, p) in positions.withIndex()) {
            if (cancel()) throw CancelledException()
            pattern(buf, SPOT.toInt(), p, seed.inv())
            dev.write(p / bs, (SPOT / bs).toInt(), buf)
            stage(Stage.WRITE, written + (i + 1) * SPOT, target + spotArea)
        }
        dev.sync()
        val writeNs = System.nanoTime() - t0
        val writeMBs = (written + positions.size * SPOT) / 1e6 / (writeNs / 1e9)

        // 3. Read everything back.
        log("Reading it back")
        var badBytes = 0L
        var read = 0L
        val r0 = System.nanoTime()
        val total = written + positions.size * SPOT
        while (read < written) {
            if (cancel()) throw CancelledException()
            val n = minOf(CHUNK.toLong(), written - read).toInt()
            dev.read(read / bs, n / bs, check)
            pattern(buf, n, read, seed)
            if (!buf.contentEquals(check) || n < CHUNK) {
                for (k in 0 until n) if (buf[k] != check[k]) badBytes++
            }
            read += n
            stage(Stage.CHECK, read, total)
        }
        val readMBs = written / 1e6 / ((System.nanoTime() - r0) / 1e9)
        var badSpots = 0
        var firstBad: Long? = null
        val spotBuf = ByteArray(SPOT.toInt())
        val spotCheck = ByteArray(SPOT.toInt())
        for ((i, p) in positions.withIndex()) {
            if (cancel()) throw CancelledException()
            dev.read(p / bs, (SPOT / bs).toInt(), spotCheck)
            pattern(spotBuf, SPOT.toInt(), p, seed.inv())
            if (!spotBuf.contentEquals(spotCheck)) {
                badSpots++
                if (firstBad == null) firstBad = p
            }
            stage(Stage.CHECK, written + (i + 1) * SPOT, total)
        }
        return Report(size, written, segments, writeMBs, readMBs, badBytes, positions.size, badSpots, firstBad)
    }
}
