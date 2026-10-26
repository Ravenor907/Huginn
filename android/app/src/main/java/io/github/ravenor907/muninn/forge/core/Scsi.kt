package io.github.ravenor907.muninn.forge.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Raw bulk pipe to a USB mass-storage device. Android implements this over UsbDeviceConnection. */
interface UsbTransport {
    /** Largest single bulk transfer known to work everywhere. */
    val maxTransfer: Int

    /**
     * Largest single bulk transfer worth trying. Bigger transfers keep the USB bus busy instead of
     * waiting on the phone between small pieces, which is most of the speed; the drive is probed
     * at this size first and falls back to [maxTransfer] if the phone or stick refuses.
     */
    val largestTransfer: Int get() = maxTransfer

    /** Returns bytes transferred, or a negative number on failure. */
    fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun bulkIn(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** Bulk-Only Mass Storage Reset plus clearing both endpoint halts. */
    fun resetRecovery()

    /** Whether [streamOut]/[streamIn] keep several transfers in flight at once. */
    val canPipeline: Boolean get() = false

    /**
     * Moves [length] bytes as [piece]-sized transfers, ideally several queued at once so the bus never
     * waits on the phone. Returns bytes moved (less than [length] on a short read), or -1 on failure.
     */
    fun streamOut(data: ByteArray, offset: Int, length: Int, piece: Int, timeoutMs: Int): Int {
        var done = 0
        while (done < length) {
            val r = bulkOut(data, offset + done, minOf(piece, length - done), timeoutMs)
            if (r < 0) return -1
            done += r
        }
        return done
    }

    fun streamIn(data: ByteArray, offset: Int, length: Int, piece: Int, timeoutMs: Int): Int {
        var done = 0
        while (done < length) {
            val n = minOf(piece, length - done)
            val r = bulkIn(data, offset + done, n, timeoutMs)
            if (r < 0) return -1
            done += r
            if (r < n) break
        }
        return done
    }
}

/** What the writer needs from a disk. */
interface BlockDevice {
    val blockSize: Int
    val blockCount: Long
    val name: String
    val sizeBytes: Long get() = blockSize.toLong() * blockCount

    fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int = 0)
    fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int = 0)
    fun sync()
}

class ScsiException(msg: String, val senseKey: Int = -1, val asc: Int = -1) : IOException(msg)

/** USB Mass Storage Class, Bulk-Only Transport: CBW, optional data phase, CSW. */
class BulkOnly(val t: UsbTransport, private val lun: Int = 0) {
    private var tag = 1

    /** Size of each bulk transfer in a data phase. */
    var transferSize = t.maxTransfer

    /** Queue several transfers at once (fast); switched off by the first sign of trouble. */
    var pipelined = t.canPipeline

    /**
     * Runs one SCSI command. Exactly one of [dataIn]/[dataOut] may be given.
     * Returns the CSW status (0 = passed, 1 = failed).
     */
    fun command(
        cdb: ByteArray,
        dataIn: ByteArray? = null,
        dataOut: ByteArray? = null,
        offset: Int = 0,
        length: Int = dataIn?.size ?: dataOut?.size ?: 0,
        timeoutMs: Int = 20_000,
    ): Int {
        require(cdb.size in 1..16)
        val myTag = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(CBW_SIGNATURE).putInt(myTag).putInt(length)
        cbw.put(if (dataIn != null) 0x80.toByte() else 0)
        cbw.put(lun.toByte()).put(cdb.size.toByte()).put(cdb)
        val cbwBytes = cbw.array()
        if (t.bulkOut(cbwBytes, 0, 31, timeoutMs) != 31) fail("Drive did not accept a command")

        if (length > 0 && pipelined) {
            val r = if (dataIn != null) {
                t.streamIn(dataIn, offset, length, transferSize, timeoutMs)
            } else {
                t.streamOut(dataOut!!, offset, length, transferSize, timeoutMs)
            }
            if (r < 0) fail("USB transfer failed")
        } else if (length > 0) {
            var done = 0
            while (done < length) {
                val n = minOf(transferSize, length - done)
                val r = if (dataIn != null) {
                    t.bulkIn(dataIn, offset + done, n, timeoutMs)
                } else {
                    t.bulkOut(dataOut!!, offset + done, n, timeoutMs)
                }
                if (r < 0) fail("USB transfer failed")
                done += r
                if (r < n) break // short packet ends the data phase
            }
        }

        val csw = ByteArray(13)
        var got = t.bulkIn(csw, 0, 13, timeoutMs)
        if (got < 0) got = t.bulkIn(csw, 0, 13, timeoutMs) // a stalled pipe often answers on retry
        if (got != 13) fail("Drive did not report command status")
        val b = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != CSW_SIGNATURE || b.getInt(4) != myTag) fail("Drive sent a garbled status")
        val status = csw[12].toInt() and 0xFF
        if (status == 2) fail("Drive reported a phase error")
        return status
    }

    private fun fail(msg: String): Nothing {
        try {
            t.resetRecovery()
        } catch (_: Exception) {
        }
        throw IOException(msg)
    }

    companion object {
        const val CBW_SIGNATURE = 0x43425355 // "USBC"
        const val CSW_SIGNATURE = 0x53425355 // "USBS"
    }
}

/** A SCSI direct-access block device (a USB stick) behind Bulk-Only Transport. */
class ScsiDisk(private val bot: BulkOnly) : BlockDevice {
    override var blockSize = 512
        private set
    override var blockCount = 0L
        private set
    override var name = "USB drive"
        private set

    fun init(): ScsiDisk {
        inquiry()
        // Freshly plugged sticks report "not ready" for a moment.
        var ready = false
        for (i in 0 until 20) {
            if (bot.command(byteArrayOf(0, 0, 0, 0, 0, 0)) == 0) {
                ready = true
                break
            }
            requestSense()
            Thread.sleep(250)
        }
        if (!ready) throw ScsiException("The drive isn't ready. Unplug it, plug it back in and try again.")
        readCapacity()
        bot.transferSize = bot.t.maxTransfer
        return this
    }

    /** Notes about the connection (slowing down after an error), for the job log. */
    var onNote: (String) -> Unit = {}

    /**
     * Bytes per SCSI read/write command. Sticks and phone USB ports differ in how much they take
     * at once, so this starts at a size most handle well and only ever steps down: when a command
     * fails, the stick is reset and the same blocks are sent again in smaller pieces. Linux uses
     * 120 KB per command for many sticks for the same reason.
     */
    var commandBytes = COMMAND_LADDER.first()
        private set

    private fun stepDown(): Boolean {
        if (bot.pipelined) {
            bot.pipelined = false
            onNote("USB hiccup: sending one transfer at a time from now on")
            return true
        }
        val smallerCmd = COMMAND_LADDER.firstOrNull { it < commandBytes && it >= blockSize }
        val smallerXfer = if (bot.transferSize > MIN_TRANSFER) MIN_TRANSFER else null
        if (smallerCmd == null && smallerXfer == null) return false
        smallerCmd?.let { commandBytes = it }
        smallerXfer?.let { bot.transferSize = it }
        onNote("USB hiccup: now sending ${commandBytes / 1024} KB per command, ${bot.transferSize / 1024} KB per transfer")
        return true
    }

    /** After a failed command: give the stick a moment, then wait until it answers again. */
    private fun recover(attempt: Int) {
        Thread.sleep(250L * attempt)
        for (i in 0 until 20) {
            try {
                if (bot.command(byteArrayOf(0, 0, 0, 0, 0, 0), timeoutMs = 5_000) == 0) return
                requestSense() // clears "unit attention" after a reset
            } catch (_: IOException) {
                // still settling
            }
            Thread.sleep(250)
        }
    }

    /**
     * Runs one read or write command, retrying through USB hiccups. A stick that refuses
     * (write-protected, media error) fails at once; a connection that keeps dropping fails with
     * advice after [MAX_ATTEMPTS] tries.
     */
    private fun <T> retrying(what: String, op: () -> T): T {
        var last: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return op()
            } catch (e: ScsiException) {
                if (e.senseKey == 0x7 || e.senseKey == 0x3 || e.senseKey == 0x5) throw e // real answers, not hiccups
                last = e
            } catch (e: IOException) {
                last = e
                stepDown()
            }
            onNote("Retrying $what (attempt ${attempt + 1} of $MAX_ATTEMPTS): ${last.message}")
            recover(attempt)
        }
        throw IOException(
            "The USB connection to the drive kept failing while trying to $what. Try a different USB-C adapter " +
                "or port, plug the phone into a charger (some phones can't power some drives), or use another drive.",
            last,
        )
    }

    /** Bytes per bulk transfer (for the log). */
    val transferSize: Int get() = bot.transferSize

    /** Whether transfers are queued several at a time (for the log). */
    val pipelined: Boolean get() = bot.pipelined

    private fun inquiry() {
        val buf = ByteArray(36)
        val cdb = byteArrayOf(0x12, 0, 0, 0, 36, 0)
        if (bot.command(cdb, dataIn = buf) == 0) {
            val vendor = String(buf, 8, 8, Charsets.US_ASCII).trim()
            val product = String(buf, 16, 16, Charsets.US_ASCII).trim()
            val joined = listOf(vendor, product).filter { it.isNotEmpty() }.joinToString(" ")
            if (joined.isNotEmpty()) name = joined
        } else {
            requestSense()
        }
    }

    private fun readCapacity() {
        val buf = ByteArray(8)
        check(bot.command(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), dataIn = buf), "read the drive size")
        val bb = ByteBuffer.wrap(buf) // SCSI is big-endian
        val lastLba = bb.getInt(0).toLong() and 0xFFFFFFFFL
        blockSize = bb.getInt(4)
        if (lastLba == 0xFFFFFFFFL) { // over 2 TiB: READ CAPACITY(16)
            val b16 = ByteArray(32)
            val cdb = ByteArray(16).also {
                it[0] = 0x9E.toByte()
                it[1] = 0x10
                it[13] = 32
            }
            check(bot.command(cdb, dataIn = b16), "read the drive size")
            val bb16 = ByteBuffer.wrap(b16)
            blockCount = bb16.getLong(0) + 1
            blockSize = bb16.getInt(8)
        } else {
            blockCount = lastLba + 1
        }
        if (blockSize !in 512..4096 || blockSize and (blockSize - 1) != 0) {
            throw ScsiException("The drive reports an unusual block size ($blockSize)")
        }
        if (blockCount <= 0) throw ScsiException("No media in the drive")
    }

    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) =
        pieces("read from the drive", lba, blocks) { at, n, skip ->
            check(bot.command(rwCdb(READ10, READ16, at, n), dataIn = buf, offset = offset + skip,
                length = n * blockSize), "read from the drive")
        }

    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) =
        pieces("write to the drive", lba, blocks) { at, n, skip ->
            check(bot.command(rwCdb(WRITE10, WRITE16, at, n), dataOut = buf, offset = offset + skip,
                length = n * blockSize, timeoutMs = 60_000), "write to the drive")
        }

    /**
     * Sends a request as commands of at most [commandBytes]. Each attempt re-reads the size, so
     * after a step down the same blocks are retried in smaller pieces.
     */
    private fun pieces(what: String, lba: Long, blocks: Int, each: (Long, Int, Int) -> Unit) {
        var done = 0
        while (done < blocks) {
            retrying(what) {
                val n = minOf(blocks - done, maxOf(1, commandBytes / blockSize))
                each(lba + done, n, done * blockSize)
                done += n
            }
        }
    }

    override fun sync() {
        // SYNCHRONIZE CACHE(10). Some sticks don't implement it; that's fine.
        if (bot.command(byteArrayOf(0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0), timeoutMs = 120_000) != 0) requestSense()
    }

    private fun rwCdb(op10: Int, op16: Int, lba: Long, blocks: Int): ByteArray {
        require(blocks in 1..0xFFFF)
        return if (lba + blocks <= 0xFFFFFFFFL) {
            ByteBuffer.allocate(10).put(op10.toByte()).put(0).putInt(lba.toInt()).put(0)
                .putShort(blocks.toShort()).put(0).array()
        } else {
            ByteBuffer.allocate(16).put(op16.toByte()).put(0).putLong(lba).putInt(blocks).put(0).put(0).array()
        }
    }

    private fun check(status: Int, what: String) {
        if (status == 0) return
        val (key, asc) = requestSense()
        val msg = when (key) {
            0x7 -> "This drive is write-protected. Check for a lock switch, or use another drive."
            0x2 -> "The drive isn't ready. Unplug it, plug it back in and try again."
            0x3 -> "The drive reported a media error. It may be failing; try another drive."
            else -> "Couldn't $what (sense key 0x%x, code 0x%02x).".format(key, asc)
        }
        throw ScsiException(msg, key, asc)
    }

    private fun requestSense(): Pair<Int, Int> {
        val buf = ByteArray(18)
        return try {
            bot.command(byteArrayOf(0x03, 0, 0, 0, 18, 0), dataIn = buf)
            (buf[2].toInt() and 0x0F) to (buf[12].toInt() and 0xFF)
        } catch (e: IOException) {
            -1 to -1
        }
    }

    companion object {
        /** Bytes per command, largest first. 120 KB is what Linux uses for sticks that need care. */
        val COMMAND_LADDER = listOf(1 shl 20, 256 shl 10, 120 shl 10, 64 shl 10, 16 shl 10, 4 shl 10)
        const val MIN_TRANSFER = 16 * 1024
        const val MAX_ATTEMPTS = 8
        const val READ10 = 0x28
        const val WRITE10 = 0x2A
        const val READ16 = 0x88
        const val WRITE16 = 0x8A
    }
}
