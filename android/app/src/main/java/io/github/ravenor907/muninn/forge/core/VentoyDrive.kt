package io.github.ravenor907.muninn.forge.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A partition found on a drive: first sector and length, in the drive's sectors. */
data class Partition(val index: Int, val start: Long, val sectors: Long, val mbrType: Int, val gptName: String = "")

object Partitions {
    /** Reads the MBR, or the GPT behind a protective MBR. Empty if the drive has neither. */
    fun read(dev: BlockDevice): List<Partition> {
        val s0 = ByteArray(dev.blockSize).also { dev.read(0, 1, it) }
        if ((s0[510].toInt() and 0xFF) != 0x55 || (s0[511].toInt() and 0xFF) != 0xAA) return emptyList()
        val b = ByteBuffer.wrap(s0).order(ByteOrder.LITTLE_ENDIAN)
        val mbr = (0 until 4).mapNotNull { i ->
            val o = 446 + i * 16
            val type = s0[o + 4].toInt() and 0xFF
            val start = b.getInt(o + 8).toLong() and 0xFFFFFFFFL
            val len = b.getInt(o + 12).toLong() and 0xFFFFFFFFL
            if (type == 0 || len == 0L) null else Partition(i + 1, start, len, type)
        }
        if (mbr.any { it.mbrType == 0xEE }) return gpt(dev)
        return mbr
    }

    private fun gpt(dev: BlockDevice): List<Partition> {
        val bs = dev.blockSize
        val h = ByteArray(bs).also { dev.read(1, 1, it) }
        if (String(h, 0, 8, Charsets.US_ASCII) != "EFI PART") return emptyList()
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val entriesLba = b.getLong(72)
        val count = b.getInt(80).coerceIn(0, 256)
        val size = b.getInt(84)
        if (size < 128 || size > 1024) return emptyList()
        val bytes = count * size
        val buf = ByteArray((bytes + bs - 1) / bs * bs)
        dev.read(entriesLba, buf.size / bs, buf)
        val e = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until count).mapNotNull { i ->
            val o = i * size
            if ((0 until 16).all { buf[o + it].toInt() == 0 }) return@mapNotNull null
            val first = e.getLong(o + 32)
            val last = e.getLong(o + 40)
            val name = String(CharArray(36) { e.getShort(o + 56 + it * 2).toInt().toChar() }).trimEnd('\u0000')
            Partition(i + 1, first, last - first + 1, 0, name)
        }
    }
}

/** A drive with Ventoy on it: partition 1 holds the images (exFAT here), partition 2 is VTOYEFI. */
data class VentoyDrive(val data: Partition, val efi: Partition) {
    companion object {
        /** Cheap check (two sectors read): is this a Ventoy drive? */
        fun detect(dev: BlockDevice): VentoyDrive? {
            val parts = try {
                Partitions.read(dev)
            } catch (e: IOException) {
                return null
            }
            if (parts.size < 2) return null
            val p1 = parts.first { it.index == parts.minOf { p -> p.index } }
            val p2 = parts.firstOrNull { it.index == p1.index + 1 } ?: return null
            if (p2.sectors * dev.blockSize != Ventoy.PART2_SECTORS * 512) return null
            val boot = ByteArray(dev.blockSize).also { dev.read(p2.start, 1, it) }
            val label = String(boot, 43, 11, Charsets.US_ASCII).trim()
            if (label != "VTOYEFI" && p2.gptName != "VTOYEFI") return null
            return VentoyDrive(p1, p2)
        }
    }

    /** The whole 32 MB EFI partition, for reading the version and Secure Boot setting. */
    fun readEfi(dev: BlockDevice): ByteArray {
        val out = ByteArray((efi.sectors * dev.blockSize).toInt())
        var at = 0L
        while (at < efi.sectors) {
            val n = minOf(efi.sectors - at, 2048L).toInt()
            dev.read(efi.start + at, n, out, (at * dev.blockSize).toInt())
            at += n
        }
        return out
    }

    /** Opens the image partition; Ventoy can also be NTFS or other filesystems, which this app can't write. */
    fun openData(dev: BlockDevice): ExFatVolume = try {
        ExFatVolume.open(dev, data.start)
    } catch (e: IOException) {
        throw IOException("This Ventoy drive's image partition isn't exFAT, so the app can't add files to it. Copy them on from a computer instead.")
    }
}
