package io.github.ravenor907.muninn.forge.core

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

/**
 * An existing exFAT volume on a [BlockDevice]: list the root folder, see free space, add a file
 * and delete one. Written to Microsoft's exFAT specification.
 *
 * Added files are always one contiguous run of clusters (marked NoFatChain, as Windows does for
 * contiguous files). Ventoy needs that: it boots an ISO by reading it straight off the disk.
 *
 * Order of writes when adding: file data, then the allocation bitmap, then the directory entries.
 * If anything stops partway, the volume never points at half-written data.
 */
class ExFatVolume private constructor(private val dev: BlockDevice, val partitionStart: Long) {
    private val bs = dev.blockSize
    var sectorsPerCluster = 0; private set
    var clusterCount = 0L; private set
    private var fatOffset = 0L
    private var heapOffset = 0L
    private var rootCluster = 0L
    private var bitmapCluster = 0L
    private var bitmapBytes = 0L
    private lateinit var bitmap: ByteArray
    private lateinit var upcase: CharArray
    val clusterBytes get() = sectorsPerCluster.toLong() * bs
    var label = ""; private set

    data class FileInfo(
        val name: String,
        val size: Long,
        val firstCluster: Long,
        val contiguous: Boolean,
        internal val entryOffsets: List<Long>,
        val isDir: Boolean = false,
    )

    companion object {
        private const val FIRST = 2L

        fun open(dev: BlockDevice, partitionStart: Long): ExFatVolume = ExFatVolume(dev, partitionStart).also { it.load() }

        /** exFAT name rules: no control characters or " * / : < > ? \ |, at most 255 characters, not ending in a space or dot. */
        fun cleanName(name: String): String {
            val bad = "\"*/:<>?\\|"
            var n = name.map { if (it.code < 0x20 || it in bad) '_' else it }.joinToString("").trim().trimEnd('.')
            if (n.isEmpty()) n = "image"
            if (n.length > 255) {
                val ext = n.substringAfterLast('.', "").take(10)
                n = n.take(255 - ext.length - 1) + "." + ext
            }
            return n
        }
    }

    // ---------------------------------------------------------------- low level

    private fun readBytes(sectorInVolume: Long, length: Int): ByteArray {
        val sectors = (length + bs - 1) / bs
        val buf = ByteArray(sectors * bs)
        var at = 0
        while (at < sectors) {
            val n = minOf(sectors - at, 2048)
            dev.read(partitionStart + sectorInVolume + at, n, buf, at * bs)
            at += n
        }
        return if (buf.size == length) buf else buf.copyOf(length)
    }

    private fun writeSectors(sectorInVolume: Long, data: ByteArray) {
        require(data.size % bs == 0)
        var at = 0
        val sectors = data.size / bs
        while (at < sectors) {
            val n = minOf(sectors - at, 2048)
            dev.write(partitionStart + sectorInVolume + at, n, data, at * bs)
            at += n
        }
    }

    private fun sectorOf(cluster: Long) = heapOffset + (cluster - FIRST) * sectorsPerCluster

    private fun fatEntry(cluster: Long): Long {
        val byteOff = cluster * 4
        val sector = fatOffset + byteOff / bs
        val s = readBytes(sector, bs)
        return ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN).getInt((byteOff % bs).toInt()).toLong() and 0xFFFFFFFFL
    }

    private fun setFatEntries(values: Map<Long, Long>) {
        // Group by FAT sector so each sector is read and written once.
        for ((sector, entries) in values.entries.groupBy { fatOffset + it.key * 4 / bs }) {
            val s = readBytes(sector, bs)
            val bb = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN)
            for ((c, v) in entries) bb.putInt(((c * 4) % bs).toInt(), v.toInt())
            writeSectors(sector, s)
        }
    }

    private fun chain(first: Long, contiguous: Boolean, length: Long): List<Long> {
        if (first < FIRST) return emptyList()
        if (contiguous) {
            val n = maxOf(1L, (length + clusterBytes - 1) / clusterBytes)
            return (first until first + n).toList()
        }
        val out = ArrayList<Long>()
        var c = first
        while (c in FIRST until clusterCount + FIRST) {
            if (out.size > clusterCount) throw IOException("The drive's file table loops; run a disk check on a computer.")
            out.add(c)
            c = fatEntry(c)
        }
        return out
    }

    private fun readChain(first: Long, contiguous: Boolean, length: Long): ByteArray {
        val clusters = chain(first, contiguous, length)
        val out = ByteArray((clusters.size * clusterBytes).toInt())
        clusters.forEachIndexed { i, c ->
            readBytes(sectorOf(c), clusterBytes.toInt()).copyInto(out, (i * clusterBytes).toInt())
        }
        return if (length in 0 until out.size) out.copyOf(length.toInt()) else out
    }

    // ---------------------------------------------------------------- loading

    private fun load() {
        val boot = readBytes(0, 512)
        if (String(boot, 3, 8, Charsets.US_ASCII) != "EXFAT   ") throw IOException("This partition isn't exFAT.")
        val b = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        if ((boot[108].toInt() and 0xFF) != Integer.numberOfTrailingZeros(bs)) {
            throw IOException("This exFAT volume uses a sector size the drive doesn't report.")
        }
        fatOffset = b.getInt(80).toLong() and 0xFFFFFFFFL
        heapOffset = b.getInt(88).toLong() and 0xFFFFFFFFL
        clusterCount = b.getInt(92).toLong() and 0xFFFFFFFFL
        rootCluster = b.getInt(96).toLong() and 0xFFFFFFFFL
        sectorsPerCluster = 1 shl (boot[109].toInt() and 0xFF)
        val root = rootEntries()
        var upcaseCluster = 0L
        var upcaseLen = 0L
        forEachEntry(root) { _, e ->
            val bb = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN)
            when (e[0].toInt() and 0xFF) {
                0x81 -> if (bitmapCluster == 0L) {
                    bitmapCluster = bb.getInt(20).toLong() and 0xFFFFFFFFL
                    bitmapBytes = bb.getLong(24)
                }
                0x82 -> {
                    upcaseCluster = bb.getInt(20).toLong() and 0xFFFFFFFFL
                    upcaseLen = bb.getLong(24)
                }
                0x83 -> {
                    val n = e[1].toInt() and 0xFF
                    label = String(CharArray(n) { bb.getShort(2 + it * 2).toInt().toChar() })
                }
            }
        }
        if (bitmapCluster == 0L || upcaseCluster == 0L) throw IOException("This exFAT volume is missing its bitmap or up-case table.")
        bitmap = readChain(bitmapCluster, false, bitmapBytes)
        upcase = expandUpcase(readChain(upcaseCluster, false, upcaseLen))
    }

    private fun expandUpcase(t: ByteArray): CharArray {
        val out = CharArray(0x10000) { it.toChar() }
        val bb = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN)
        var c = 0
        while (bb.remaining() >= 2 && c < 0x10000) {
            val v = bb.short.toInt() and 0xFFFF
            if (v == 0xFFFF && bb.remaining() >= 2) {
                c += bb.short.toInt() and 0xFFFF
            } else {
                out[c++] = v.toChar()
            }
        }
        return out
    }

    /** A directory's clusters and its raw bytes; [folder] is null for the root. */
    private class Dir(val clusters: List<Long>, val bytes: ByteArray, val folder: FileInfo? = null)

    private fun rootEntries(): Dir = readDir(chain(rootCluster, false, -1), null)

    private fun readDir(clusters: List<Long>, folder: FileInfo?): Dir {
        val bytes = ByteArray((clusters.size * clusterBytes).toInt())
        clusters.forEachIndexed { i, c -> readBytes(sectorOf(c), clusterBytes.toInt()).copyInto(bytes, (i * clusterBytes).toInt()) }
        return Dir(clusters, bytes, folder)
    }

    private fun dirOf(folder: FileInfo?): Dir =
        if (folder == null) rootEntries() else readDir(chain(folder.firstCluster, folder.contiguous, folder.size), folder)

    private inline fun forEachEntry(d: Dir, f: (Int, ByteArray) -> Unit) {
        var o = 0
        while (o + 32 <= d.bytes.size) {
            if (d.bytes[o].toInt() == 0) return
            f(o, d.bytes.copyOfRange(o, o + 32))
            o += 32
        }
    }

    /** Byte offset on the volume (from its first sector) of directory byte [o]. */
    private fun dirOffsetOnVolume(d: Dir, o: Int): Long {
        val cluster = d.clusters[(o / clusterBytes).toInt()]
        return sectorOf(cluster) * bs + o % clusterBytes
    }

    // ---------------------------------------------------------------- reading

    /** Files in the root folder (what Ventoy lists by default). */
    fun files(): List<FileInfo> = entries(null).filter { !it.isDir }

    /** Files and folders directly in [folder] (null: the root). */
    fun entries(folder: FileInfo?): List<FileInfo> {
        val d = dirOf(folder)
        val out = ArrayList<FileInfo>()
        var o = 0
        while (o + 32 <= d.bytes.size) {
            val type = d.bytes[o].toInt() and 0xFF
            if (type == 0) break
            if (type == 0x85) {
                val count = d.bytes[o + 1].toInt() and 0xFF
                val attrs = ByteBuffer.wrap(d.bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(o + 4).toInt()
                val s = o + 32
                if (s + 32 <= d.bytes.size && (d.bytes[s].toInt() and 0xFF) == 0xC0 && count >= 2) {
                    val bb = ByteBuffer.wrap(d.bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val flags = d.bytes[s + 1].toInt()
                    val nameLen = d.bytes[s + 3].toInt() and 0xFF
                    val first = bb.getInt(s + 20).toLong() and 0xFFFFFFFFL
                    val size = bb.getLong(s + 24)
                    val sb = StringBuilder()
                    for (k in 2..count) {
                        val e = o + k * 32
                        if (e + 32 > d.bytes.size || (d.bytes[e].toInt() and 0xFF) != 0xC1) break
                        for (j in 0 until 15) if (sb.length < nameLen) sb.append(bb.getShort(e + 2 + j * 2).toInt().toChar())
                    }
                    out.add(
                        FileInfo(sb.toString(), size, first, flags and 0x02 != 0, (0..count).map { dirOffsetOnVolume(d, o + it * 32) }, attrs and 0x10 != 0),
                    )
                }
                o += 32 * (count + 1)
                continue
            }
            o += 32
        }
        return out
    }

    fun freeClusters(): Long {
        var used = 0L
        for (i in 0 until clusterCount) if (bit(i)) used++
        return clusterCount - used
    }

    val freeBytes: Long get() = freeClusters() * clusterBytes

    private fun bit(i: Long) = (bitmap[(i / 8).toInt()].toInt() shr (i % 8).toInt()) and 1 == 1

    private fun setBits(first: Long, count: Long, on: Boolean) {
        for (c in first until first + count) {
            val i = c - FIRST
            val idx = (i / 8).toInt()
            val m = 1 shl (i % 8).toInt()
            bitmap[idx] = (if (on) bitmap[idx].toInt() or m else bitmap[idx].toInt() and m.inv()).toByte()
        }
    }

    /** Writes back the bitmap sectors covering clusters [first, first+count). */
    private fun flushBitmap(first: Long, count: Long) {
        if (count <= 0) return
        val bitmapClusters = chain(bitmapCluster, false, bitmapBytes)
        val startByte = (first - FIRST) / 8
        val endByte = (first - FIRST + count - 1) / 8
        var sectorByte = startByte / bs * bs
        while (sectorByte <= endByte) {
            val cl = bitmapClusters[(sectorByte / clusterBytes).toInt()]
            val sector = sectorOf(cl) + (sectorByte % clusterBytes) / bs
            val chunk = ByteArray(bs)
            val n = minOf(bs.toLong(), bitmap.size - sectorByte).toInt()
            System.arraycopy(bitmap, sectorByte.toInt(), chunk, 0, n)
            // Keep whatever is past the bitmap's end in that sector.
            if (n < bs) readBytes(sector, bs).copyInto(chunk, n, n, bs)
            writeSectors(sector, chunk)
            sectorByte += bs
        }
    }

    /** First run of [n] free clusters, or null. */
    private fun findRun(n: Long): Long? {
        var run = 0L
        var start = 0L
        for (i in 0 until clusterCount) {
            if (bit(i)) {
                run = 0
            } else {
                if (run == 0L) start = i
                if (++run == n) return start + FIRST
            }
        }
        return null
    }

    /**
     * Whether files of these sizes, added in this order, each get one unbroken run (as [add]
     * places them: first fit), after [removing] files are deleted. Returns the first size that
     * wouldn't fit, or null if all fit. Changes nothing on the drive.
     */
    fun firstMisfit(sizes: List<Long>, removing: List<FileInfo> = emptyList()): Long? {
        val saved = bitmap.copyOf()
        try {
            for (f in removing) {
                if (f.firstCluster < FIRST) continue
                if (f.contiguous) setBits(f.firstCluster, maxOf(1L, (f.size + clusterBytes - 1) / clusterBytes), false)
                else chain(f.firstCluster, false, f.size).forEach { setBits(it, 1, false) }
            }
            for (size in sizes) {
                if (size == 0L) continue
                val n = (size + clusterBytes - 1) / clusterBytes
                val at = findRun(n) ?: return size
                setBits(at, n, true)
            }
            return null
        } finally {
            saved.copyInto(bitmap)
        }
    }

    /** Longest run of free space in bytes: the biggest file that can be added. */
    fun largestFreeRun(): Long = largestRun().second * clusterBytes

    /** First cluster and length (in clusters) of the longest free run. */
    private fun largestRun(): Pair<Long, Long> {
        var best = 0L
        var bestStart = 0L
        var run = 0L
        var start = 0L
        for (i in 0 until clusterCount) {
            if (bit(i)) {
                run = 0
            } else {
                if (run == 0L) start = i
                run++
                if (run > best) { best = run; bestStart = start }
            }
        }
        return (bestStart + FIRST) to best
    }

    // ---------------------------------------------------------------- names

    private fun nameHash(name: String): Int {
        var h = 0
        for (ch in name) {
            val u = upcase[ch.code].code
            for (byte in intArrayOf(u and 0xFF, u ushr 8)) h = ((if (h and 1 != 0) 0x8000 else 0) + (h ushr 1) + byte) and 0xFFFF
        }
        return h
    }

    private fun sameName(a: String, b: String) = a.length == b.length && a.indices.all { upcase[a[it].code] == upcase[b[it].code] }

    fun find(name: String): FileInfo? = files().firstOrNull { sameName(it.name, name) }

    /** A file or folder by path from the root ("ventoy/ventoy.json"), matched the way exFAT does (ignoring case). */
    fun lookup(path: String): FileInfo? {
        var folder: FileInfo? = null
        val parts = path.split('/').filter { it.isNotEmpty() }
        for ((i, p) in parts.withIndex()) {
            val e = entries(folder).firstOrNull { sameName(it.name, p) } ?: return null
            if (i == parts.lastIndex) return e
            if (!e.isDir) return null
            folder = e
        }
        return null
    }

    /** A small file's contents by path, or null if it isn't there. */
    fun readFile(path: String, limit: Int = 1 shl 20): ByteArray? {
        val f = lookup(path)?.takeIf { !it.isDir } ?: return null
        if (f.size > limit) throw IOException("$path is unexpectedly large.")
        return readChain(f.firstCluster, f.contiguous, f.size)
    }

    /**
     * Writes a small file at [path] (at most one folder deep), replacing any file already there
     * and creating the folder if needed.
     */
    fun writeFile(path: String, data: ByteArray) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        require(parts.size in 1..2) { "only one folder level is supported" }
        val folder = if (parts.size == 2) {
            lookup(parts[0])?.also { if (!it.isDir) throw IOException("${parts[0]} on the drive is a file, not a folder.") }
                ?: createFolder(parts[0])
        } else {
            null
        }
        entries(folder).firstOrNull { sameName(it.name, parts.last()) }?.let {
            if (it.isDir) throw IOException("$path on the drive is a folder.")
            delete(it)
        }
        add(parts.last(), data.size.toLong(), data.inputStream(), { _, _ -> }, { false }, folder)
    }

    /** Deletes the file at [path]; false if it wasn't there. */
    fun deleteFile(path: String): Boolean {
        val f = lookup(path)?.takeIf { !it.isDir } ?: return false
        delete(f)
        return true
    }

    /** Makes an empty folder in the root: one zeroed cluster, contiguous. */
    fun createFolder(name: String): FileInfo {
        val clean = cleanName(name)
        if (entries(null).any { sameName(it.name, clean) }) throw IOException("$clean is already on the drive.")
        val c = findRun(1) ?: throw IOException("The drive is full.")
        writeSectors(sectorOf(c), ByteArray(clusterBytes.toInt()))
        setBits(c, 1, true)
        flushBitmap(c, 1)
        writeEntrySet(clean, c, clusterBytes, null, folder = true)
        dev.sync()
        return lookup(clean) ?: throw IOException("Couldn't create the $clean folder.")
    }

    // ---------------------------------------------------------------- changes

    /** Deletes a file in the root folder: entries marked unused, clusters freed in the bitmap. */
    fun delete(f: FileInfo) {
        for (off in f.entryOffsets) {
            val sector = off / bs
            val s = readBytes(sector, bs)
            val i = (off % bs).toInt()
            s[i] = (s[i].toInt() and 0x7F).toByte() // InUse bit off
            writeSectors(sector, s)
        }
        if (f.firstCluster >= FIRST) {
            val clusters = if (f.contiguous) {
                val n = maxOf(1L, (f.size + clusterBytes - 1) / clusterBytes)
                listOf(f.firstCluster to n)
            } else {
                chain(f.firstCluster, false, f.size).map { it to 1L }
            }
            for ((c, n) in clusters) {
                setBits(c, n, false)
                flushBitmap(c, n)
            }
        }
    }

    /**
     * Adds [name] holding exactly [size] bytes read from [source], as one contiguous run.
     * Returns the SHA-256 of what was written; [verify] then re-reads it from the drive.
     */
    fun add(
        name: String,
        size: Long,
        source: InputStream,
        progress: (Long, Long) -> Unit,
        cancel: () -> Boolean,
        folder: FileInfo? = null,
    ): ByteArray {
        val clean = cleanName(name)
        if (entries(folder).any { sameName(it.name, clean) }) throw IOException("$clean is already on the drive.")
        if (size == 0L) {
            // An empty file owns no clusters (first cluster 0), per the spec.
            if (source.read() != -1) throw IOException("$clean is bigger than expected; it may still be changing.")
            writeEntrySet(clean, 0, 0, folder)
            dev.sync()
            return MessageDigest.getInstance("SHA-256").digest()
        }
        val clusters = maxOf(1L, (size + clusterBytes - 1) / clusterBytes)
        val first = findRun(clusters)
            ?: throw IOException(
                "Not enough free space in one piece for $clean (%.1f GB needed, %.1f GB available in one piece).".format(
                    size / 1e9, largestFreeRun() / 1e9,
                ),
            )

        // 1. Data.
        val md = MessageDigest.getInstance("SHA-256")
        val chunkSectors = 2048
        val buf = ByteArray(chunkSectors * bs)
        var written = 0L
        var sector = sectorOf(first)
        while (written < size) {
            if (cancel()) throw CancelledException()
            val want = minOf(buf.size.toLong(), size - written).toInt()
            var got = 0
            while (got < want) {
                val n = source.read(buf, got, want - got)
                if (n < 0) throw IOException("The file ended early (${written + got} of $size bytes). Is it still being downloaded?")
                got += n
            }
            md.update(buf, 0, got)
            val padded = (got + bs - 1) / bs * bs
            java.util.Arrays.fill(buf, got, padded, 0)
            var at = 0
            while (at < padded / bs) {
                val n = minOf(padded / bs - at, 2048)
                dev.write(partitionStart + sector + at, n, buf, at * bs)
                at += n
            }
            sector += padded / bs
            written += got
            progress(written, size)
        }
        if (source.read() != -1) throw IOException("$clean is bigger than expected; it may still be changing.")

        commit(clean, first, size, folder)
        return md.digest()
    }

    /** Steps 2 and 3 of adding a file whose data is already on the disk: bitmap, then entries. */
    private fun commit(name: String, first: Long, size: Long, folder: FileInfo?) {
        val clusters = maxOf(1L, (size + clusterBytes - 1) / clusterBytes)
        // 2. Allocation bitmap.
        setBits(first, clusters, true)
        flushBitmap(first, clusters)
        // 3. Directory entries, last. If they can't be written, give the clusters back.
        try {
            writeEntrySet(name, first, size, folder)
        } catch (e: IOException) {
            setBits(first, clusters, false)
            runCatching { flushBitmap(first, clusters) }
            throw e
        }
        dev.sync()
    }

    /**
     * Adds a file whose size is only known once [source] ends, such as an image unpacked on the
     * way in: it's written into the longest free run, and only the clusters it used are claimed.
     * [progress] gets the bytes written so far. Returns the size and SHA-256 of what was written.
     * Nothing is recorded on the drive unless the whole file fit and was written.
     */
    fun addStreaming(
        name: String,
        source: InputStream,
        progress: (Long) -> Unit,
        cancel: () -> Boolean,
        folder: FileInfo? = null,
    ): Pair<Long, ByteArray> {
        val clean = cleanName(name)
        if (entries(folder).any { sameName(it.name, clean) }) throw IOException("$clean is already on the drive.")
        val (first, runClusters) = largestRun()
        val limit = runClusters * clusterBytes
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(2048 * bs)
        var written = 0L
        var sector = sectorOf(first)
        while (true) {
            if (cancel()) throw CancelledException()
            var got = 0
            while (got < buf.size) {
                val n = source.read(buf, got, buf.size - got)
                if (n < 0) break
                got += n
            }
            if (got == 0) break
            if (written + got > limit) {
                throw IOException(
                    "$clean unpacks to more than the %.1f GB free in one piece on the drive. ".format(limit / 1e9) +
                        "Free up space on the drive and try again.",
                )
            }
            md.update(buf, 0, got)
            val padded = (got + bs - 1) / bs * bs
            java.util.Arrays.fill(buf, got, padded, 0)
            var at = 0
            while (at < padded / bs) {
                val n = minOf(padded / bs - at, 2048)
                dev.write(partitionStart + sector + at, n, buf, at * bs)
                at += n
            }
            sector += padded / bs
            written += got
            progress(written)
            if (got < buf.size) break
        }
        if (written == 0L) {
            writeEntrySet(clean, 0, 0, folder)
            dev.sync()
        } else {
            commit(clean, first, written, folder)
        }
        return written to md.digest()
    }

    /** Reads a file back from the drive and compares it with the hash [add] returned. */
    fun verify(f: FileInfo, expected: ByteArray, progress: (Long, Long) -> Unit, cancel: () -> Boolean) {
        val md = MessageDigest.getInstance("SHA-256")
        val clusters = chain(f.firstCluster, f.contiguous, f.size)
        var left = f.size
        var done = 0L
        // Contiguous files are read in big runs; chained ones a cluster at a time.
        val runs = ArrayList<Pair<Long, Long>>()
        for (c in clusters) {
            val last = runs.lastOrNull()
            if (last != null && last.first + last.second == c) runs[runs.lastIndex] = last.first to last.second + 1 else runs.add(c to 1L)
        }
        val buf = ByteArray(2048 * bs)
        for ((start, count) in runs) {
            var sector = sectorOf(start)
            var sectorsLeft = count * sectorsPerCluster
            while (sectorsLeft > 0 && left > 0) {
                if (cancel()) throw CancelledException()
                val n = minOf(sectorsLeft, 2048L).toInt()
                dev.read(partitionStart + sector, n, buf, 0)
                val use = minOf(left, n.toLong() * bs).toInt()
                md.update(buf, 0, use)
                left -= use
                done += use
                sector += n
                sectorsLeft -= n
                progress(done, f.size)
            }
        }
        if (!MessageDigest.isEqual(md.digest(), expected)) {
            throw WriteException("${f.name} didn't read back correctly from the drive. The drive may be failing or counterfeit.")
        }
    }

    private fun writeEntrySet(name: String, first: Long, size: Long, parent: FileInfo?, folder: Boolean = false) {
        val nameEntries = (name.length + 14) / 15
        val count = 2 + nameEntries
        val set = ByteArray(count * 32)
        val bb = ByteBuffer.wrap(set).order(ByteOrder.LITTLE_ENDIAN)
        val ts = timestamp()
        set[0] = 0x85.toByte()
        set[1] = (count - 1).toByte()
        bb.putShort(4, if (folder) 0x10 else 0x20) // directory, or archive
        bb.putInt(8, ts); bb.putInt(12, ts); bb.putInt(16, ts)
        set[22] = 0x80.toByte(); set[23] = 0x80.toByte(); set[24] = 0x80.toByte() // times are UTC
        set[32] = 0xC0.toByte()
        set[33] = if (size == 0L) 0x01 else 0x03 // AllocationPossible (+ NoFatChain when it has clusters)
        set[35] = name.length.toByte()
        bb.putShort(36, nameHash(name).toShort())
        bb.putLong(40, size) // ValidDataLength
        bb.putInt(52, first.toInt())
        bb.putLong(56, size) // DataLength
        for (k in 0 until nameEntries) {
            val o = 64 + k * 32
            set[o] = 0xC1.toByte()
            for (j in 0 until 15) {
                val idx = k * 15 + j
                if (idx < name.length) bb.putShort(o + 2 + j * 2, name[idx].code.toShort())
            }
        }
        bb.putShort(2, setChecksum(set).toShort())

        // A run of free slots: unused (InUse bit clear) or past the end marker.
        var d = dirOf(parent)
        var slot = findSlots(d, count)
        if (slot == null) {
            d = if (parent == null) {
                growRoot(d)
                rootEntries()
            } else {
                dirOf(growFolder(parent))
            }
            slot = findSlots(d, count) ?: throw IOException("Couldn't make room in the drive's folder.")
        }
        // Write the set sector by sector (it may cross a sector or cluster boundary).
        var i = 0
        while (i < set.size) {
            val off = dirOffsetOnVolume(d, slot + i)
            val sector = off / bs
            val s = readBytes(sector, bs)
            var j = (off % bs).toInt()
            while (j < bs && i < set.size) {
                s[j++] = set[i++]
            }
            writeSectors(sector, s)
        }
    }

    private fun findSlots(d: Dir, count: Int): Int? {
        var run = 0
        var start = 0
        var o = 0
        while (o + 32 <= d.bytes.size) {
            val t = d.bytes[o].toInt() and 0xFF
            val free = t == 0 || t and 0x80 == 0
            if (free) {
                if (run == 0) start = o
                if (++run == count) {
                    // If we use slots at the end marker, the slot after must stay an end marker (0x00), which
                    // holds as long as there's room; growing the folder zero-fills the new cluster.
                    return start
                }
            } else {
                run = 0
            }
            o += 32
        }
        return null
    }

    /**
     * Adds one zeroed cluster to a folder in the root and updates its entry set (size, and
     * NoFatChain cleared with a FAT chain written if the next cluster isn't free). Returns it fresh.
     */
    private fun growFolder(f: FileInfo): FileInfo {
        val clusters = chain(f.firstCluster, f.contiguous, f.size)
        val c = findRun(1) ?: throw IOException("The drive is full.")
        writeSectors(sectorOf(c), ByteArray(clusterBytes.toInt()))
        setBits(c, 1, true)
        flushBitmap(c, 1)
        val stillContiguous = f.contiguous && c == clusters.last() + 1
        if (!stillContiguous) {
            val links = HashMap<Long, Long>()
            if (f.contiguous) clusters.zipWithNext().forEach { (a, b) -> links[a] = b }
            links[clusters.last()] = c
            links[c] = 0xFFFFFFFFL
            setFatEntries(links)
        }
        // Rewrite the folder's entry set: new length, flags, checksum.
        val set = ByteArray(f.entryOffsets.size * 32)
        f.entryOffsets.forEachIndexed { i, off -> readBytes(off / bs, bs).copyInto(set, i * 32, (off % bs).toInt(), (off % bs).toInt() + 32) }
        val bb = ByteBuffer.wrap(set).order(ByteOrder.LITTLE_ENDIAN)
        val newSize = f.size + clusterBytes
        set[33] = if (stillContiguous) 0x03 else 0x01
        bb.putLong(40, newSize)
        bb.putLong(56, newSize)
        bb.putShort(2, setChecksum(set).toShort())
        f.entryOffsets.forEachIndexed { i, off ->
            val sector = off / bs
            val sec = readBytes(sector, bs)
            set.copyInto(sec, (off % bs).toInt(), i * 32, i * 32 + 32)
            writeSectors(sector, sec)
        }
        dev.sync()
        return entries(null).firstOrNull { it.entryOffsets.first() == f.entryOffsets.first() }
            ?: throw IOException("Lost track of the ${f.name} folder while growing it.")
    }

    private fun setChecksum(set: ByteArray): Int {
        var sum = 0
        for (i in set.indices) {
            if (i == 2 || i == 3) continue
            sum = ((if (sum and 1 != 0) 0x8000 else 0) + (sum ushr 1) + (set[i].toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }

    /** Adds one zeroed cluster to the root folder's chain. */
    private fun growRoot(d: Dir) {
        val c = findRun(1) ?: throw IOException("The drive is full.")
        writeSectors(sectorOf(c), ByteArray(clusterBytes.toInt()))
        setBits(c, 1, true)
        flushBitmap(c, 1)
        setFatEntries(mapOf(d.clusters.last() to c, c to 0xFFFFFFFFL))
    }

    /** exFAT timestamp: DOS date and time fields, in UTC. */
    private fun timestamp(): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val year = (c.get(Calendar.YEAR) - 1980).coerceIn(0, 127)
        return (year shl 25) or ((c.get(Calendar.MONTH) + 1) shl 21) or (c.get(Calendar.DAY_OF_MONTH) shl 16) or
            (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
    }
}
