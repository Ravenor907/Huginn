package io.github.ravenor907.muninn.forge.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Edits a small FAT12/FAT16 filesystem held in memory: Ventoy's 32 MB EFI partition image.
 * Supports what Ventoy's own vtoycli does to it: find, read, delete and replace files, with
 * long file names, in the root directory and subdirectories. Every FAT copy is kept in step.
 */
class Fat16(val image: ByteArray) {
    private val b = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
    val bytesPerSector = b.getShort(11).toInt() and 0xFFFF
    val sectorsPerCluster = image[13].toInt() and 0xFF
    private val reserved = b.getShort(14).toInt() and 0xFFFF
    private val fats = image[16].toInt() and 0xFF
    private val rootEntries = b.getShort(17).toInt() and 0xFFFF
    private val totalSectors = (b.getShort(19).toInt() and 0xFFFF).let { if (it != 0) it.toLong() else b.getInt(32).toLong() and 0xFFFFFFFFL }
    private val fatSectors = b.getShort(22).toInt() and 0xFFFF
    private val rootStart = (reserved + fats * fatSectors).toLong() * bytesPerSector
    private val rootBytes = rootEntries * 32
    private val dataStart = rootStart + ((rootBytes + bytesPerSector - 1) / bytesPerSector) * bytesPerSector
    private val clusterBytes = sectorsPerCluster * bytesPerSector
    val clusterCount = ((totalSectors * bytesPerSector - dataStart) / clusterBytes).toInt()
    private val fat12 = clusterCount < 4085
    private val eoc = if (fat12) 0xFF8 else 0xFFF8

    init {
        if (bytesPerSector !in setOf(512, 1024, 2048, 4096) || sectorsPerCluster == 0 || fats == 0 || fatSectors == 0 || rootEntries == 0) {
            throw IOException("Not a FAT12/16 filesystem")
        }
        if (clusterCount >= 65525) throw IOException("FAT32 isn't supported here")
    }

    /** Volume label from the boot sector (FAT12/16 extended BPB). */
    val label: String get() = String(image, 43, 11, Charsets.US_ASCII).trim()

    /** A directory entry: [offset] of its short entry in the image, and the offsets of its long-name entries. */
    data class Entry(val name: String, val offset: Int, val lfnOffsets: List<Int>, val isDir: Boolean, val firstCluster: Int, val size: Long)

    // ---------------------------------------------------------------- FAT

    fun fat(cluster: Int): Int {
        val base = reserved * bytesPerSector
        return if (fat12) {
            val o = base + cluster * 3 / 2
            val v = (image[o].toInt() and 0xFF) or ((image[o + 1].toInt() and 0xFF) shl 8)
            if (cluster and 1 == 0) v and 0xFFF else v ushr 4
        } else {
            b.getShort(base + cluster * 2).toInt() and 0xFFFF
        }
    }

    private fun setFat(cluster: Int, value: Int) {
        for (copy in 0 until fats) {
            val base = (reserved + copy * fatSectors) * bytesPerSector
            if (fat12) {
                val o = base + cluster * 3 / 2
                var v = (image[o].toInt() and 0xFF) or ((image[o + 1].toInt() and 0xFF) shl 8)
                v = if (cluster and 1 == 0) (v and 0xF000) or (value and 0xFFF) else (v and 0x000F) or ((value and 0xFFF) shl 4)
                image[o] = v.toByte()
                image[o + 1] = (v ushr 8).toByte()
            } else {
                b.putShort(base + cluster * 2, value.toShort())
            }
        }
    }

    private fun isEnd(v: Int) = v >= eoc

    fun chain(first: Int): List<Int> {
        val out = ArrayList<Int>()
        var c = first
        while (c in 2 until clusterCount + 2) {
            if (out.size > clusterCount) throw IOException("FAT chain loops")
            out.add(c)
            val n = fat(c)
            if (isEnd(n) || n < 2) break
            c = n
        }
        return out
    }

    private fun clusterOffset(c: Int) = (dataStart + (c - 2).toLong() * clusterBytes).toInt()

    // ---------------------------------------------------------------- directories

    /** Byte ranges holding a directory's entries (root is a fixed area; subdirectories are cluster chains). */
    private fun dirRegions(firstCluster: Int): List<IntRange> =
        if (firstCluster == 0) listOf(rootStart.toInt() until (rootStart + rootBytes).toInt())
        else chain(firstCluster).map { clusterOffset(it) until clusterOffset(it) + clusterBytes }

    fun list(dirCluster: Int = 0): List<Entry> {
        val out = ArrayList<Entry>()
        val lfn = ArrayList<Pair<Int, String>>()
        for (r in dirRegions(dirCluster)) {
            var o = r.first
            while (o < r.last) {
                val first = image[o].toInt() and 0xFF
                if (first == 0) return out
                val attr = image[o + 11].toInt() and 0xFF
                if (first == 0xE5) {
                    lfn.clear()
                } else if (attr == 0x0F) {
                    lfn.add(o to lfnPart(o))
                } else if (attr and 0x08 == 0) { // skip volume labels
                    val short = shortName(o)
                    val long = if (lfn.isNotEmpty()) lfn.reversed().joinToString("") { it.second } else null
                    val cluster = b.getShort(o + 26).toInt() and 0xFFFF
                    val size = b.getInt(o + 28).toLong() and 0xFFFFFFFFL
                    if (short != "." && short != "..") {
                        out.add(Entry(long ?: short, o, lfn.map { it.first }, attr and 0x10 != 0, cluster, size))
                    }
                    lfn.clear()
                } else {
                    lfn.clear()
                }
                o += 32
            }
        }
        return out
    }

    private fun lfnPart(o: Int): String {
        val sb = StringBuilder()
        for (pos in intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)) {
            val ch = b.getShort(o + pos).toInt() and 0xFFFF
            if (ch == 0 || ch == 0xFFFF) break
            sb.append(ch.toChar())
        }
        return sb.toString()
    }

    private fun shortName(o: Int): String {
        val base = String(image, o, 8, Charsets.ISO_8859_1).trimEnd()
        val ext = String(image, o + 8, 3, Charsets.ISO_8859_1).trimEnd()
        val lcBase = image[o + 12].toInt() and 0x08 != 0
        val lcExt = image[o + 12].toInt() and 0x10 != 0
        val n = if (lcBase) base.lowercase() else base
        val e = if (lcExt) ext.lowercase() else ext
        return if (e.isEmpty()) n else "$n.$e"
    }

    /** The entry at [path] ("/EFI/BOOT/BOOTX64.EFI"), matched without regard to case, or null. */
    fun find(path: String): Entry? {
        var dir = 0
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        for ((i, p) in parts.withIndex()) {
            val e = list(dir).firstOrNull { it.name.equals(p, ignoreCase = true) } ?: return null
            if (i == parts.lastIndex) return e
            if (!e.isDir) return null
            dir = e.firstCluster
        }
        return null
    }

    fun read(path: String): ByteArray {
        val e = find(path) ?: throw IOException("$path not found")
        val out = ByteArray(e.size.toInt())
        var at = 0
        for (c in chain(e.firstCluster)) {
            val n = minOf(clusterBytes, out.size - at)
            if (n <= 0) break
            System.arraycopy(image, clusterOffset(c), out, at, n)
            at += n
        }
        if (at < out.size) throw IOException("$path is shorter on disk than its size says")
        return out
    }

    private fun freeChain(first: Int) {
        for (c in chain(first)) setFat(c, 0)
    }

    /** Deletes a file the way DOS does: entries marked 0xE5, clusters freed in every FAT. */
    fun delete(path: String): Boolean {
        val e = find(path) ?: return false
        if (e.isDir) throw IOException("$path is a folder")
        if (e.firstCluster >= 2) freeChain(e.firstCluster)
        for (o in e.lfnOffsets + e.offset) image[o] = 0xE5.toByte()
        return true
    }

    /** Replaces a file's contents in place, keeping its name and directory entry. */
    fun replace(path: String, data: ByteArray) {
        val e = find(path) ?: throw IOException("$path not found")
        if (e.firstCluster >= 2) freeChain(e.firstCluster)
        val need = (data.size + clusterBytes - 1) / clusterBytes
        val free = (2 until clusterCount + 2).filter { fat(it) == 0 }.take(need)
        if (free.size < need) throw IOException("Not enough room in the FAT partition for $path")
        free.forEachIndexed { i, c ->
            setFat(c, if (i == free.lastIndex) 0xFFFF else free[i + 1])
            val off = clusterOffset(c)
            val n = minOf(clusterBytes, data.size - i * clusterBytes)
            System.arraycopy(data, i * clusterBytes, image, off, n)
            java.util.Arrays.fill(image, off + n, off + clusterBytes, 0)
        }
        b.putShort(e.offset + 26, (free.firstOrNull() ?: 0).toShort())
        b.putShort(e.offset + 20, 0) // high cluster word, always 0 on FAT16
        b.putInt(e.offset + 28, data.size)
    }
}
