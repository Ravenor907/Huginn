package io.github.ravenor907.muninn.forge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Formats part of a block device as exFAT (Microsoft's published exFAT spec), with no help from
 * the phone. exFAT is readable and writable on Windows, macOS, Linux and Android and has no 4 GB
 * file limit, which matters for ISOs.
 *
 * Only the filesystem's own regions are written: boot region and its backup, the FAT, the
 * allocation bitmap, the up-case table and the root directory. Data in free clusters is left as
 * is, exactly like mkfs.exfat does.
 */
object ExFat {
    private const val SECTOR = 512
    private const val FIRST_CLUSTER = 2
    private const val ENTRY = 32

    data class Layout(
        val partitionOffset: Long,
        val volumeLength: Long,
        val sectorsPerCluster: Int,
        val fatOffset: Long,
        val fatLength: Long,
        val heapOffset: Long,
        val clusterCount: Long,
        val bitmapCluster: Long,
        val bitmapBytes: Long,
        val upcaseCluster: Long,
        val upcaseBytes: Long,
        val rootCluster: Long,
    ) {
        val clusterBytes get() = sectorsPerCluster * SECTOR
        fun clustersFor(bytes: Long) = (bytes + clusterBytes - 1) / clusterBytes
        fun sectorOf(cluster: Long) = heapOffset + (cluster - FIRST_CLUSTER) * sectorsPerCluster
        val usedClusters get() = rootCluster + 1 - FIRST_CLUSTER
    }

    /** Cluster size the way Ventoy and mkfs.exfat choose it: 32 KB up to 32 GB, 128 KB above. */
    fun defaultSectorsPerCluster(volumeSectors: Long): Int = if (volumeSectors * SECTOR > 32L * 1000 * 1000 * 1000) 256 else 64

    fun layout(partitionOffset: Long, volumeSectors: Long, sectorsPerCluster: Int = defaultSectorsPerCluster(volumeSectors)): Layout {
        require(Integer.bitCount(sectorsPerCluster) == 1 && sectorsPerCluster in 1..65536)
        val fatOffset = maxOf(128L, sectorsPerCluster.toLong()) // 64 KB in, aligned like mkfs.exfat
        var clusters = (volumeSectors - fatOffset) / sectorsPerCluster
        var fatLength: Long
        var heap: Long
        while (true) {
            fatLength = align((clusters + 2) * 4 / SECTOR + 1, sectorsPerCluster.toLong())
            heap = align(fatOffset + fatLength, sectorsPerCluster.toLong())
            val fits = (volumeSectors - heap) / sectorsPerCluster
            if (fits >= clusters) break
            clusters = fits
        }
        require(clusters > 16) { "Partition too small for exFAT" }
        val bitmapBytes = (clusters + 7) / 8
        val clusterBytes = sectorsPerCluster.toLong() * SECTOR
        val bitmapClusters = (bitmapBytes + clusterBytes - 1) / clusterBytes
        val upcaseBytes = upcaseTable().size.toLong()
        val upcaseClusters = (upcaseBytes + clusterBytes - 1) / clusterBytes
        return Layout(
            partitionOffset, volumeSectors, sectorsPerCluster, fatOffset, fatLength, heap, clusters,
            bitmapCluster = 2, bitmapBytes = bitmapBytes,
            upcaseCluster = 2 + bitmapClusters, upcaseBytes = upcaseBytes,
            rootCluster = 2 + bitmapClusters + upcaseClusters,
        )
    }

    /**
     * Formats [volumeSectors] sectors starting at [partitionOffset] (both in 512-byte sectors on
     * [dev]). [label] is at most 11 characters.
     */
    fun format(
        dev: BlockDevice,
        partitionOffset: Long,
        volumeSectors: Long,
        label: String,
        sectorsPerCluster: Int = defaultSectorsPerCluster(volumeSectors),
        serial: Int = SecureRandom().nextInt(),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Layout {
        require(dev.blockSize == SECTOR) { "exFAT here needs 512-byte sectors" }
        require(label.length in 1..11) { "exFAT labels are 1 to 11 characters" }
        val l = layout(partitionOffset, volumeSectors, sectorsPerCluster)
        val total = l.fatLength + l.usedClusters * l.sectorsPerCluster + 24
        var done = 0L
        fun put(sectorInVolume: Long, data: ByteArray) {
            val sectors = data.size / SECTOR
            var at = 0
            while (at < sectors) {
                val n = minOf(sectors - at, 2048)
                dev.write(partitionOffset + sectorInVolume + at, n, data, at * SECTOR)
                at += n
                done += n
                progress(done, total)
            }
        }

        // Clear what the structures below don't cover: the boot region, the gap before the FAT, the
        // gap before the cluster heap, and the volume's last 1 MB. Old filesystem markers there (an
        // ISO's at 32 KB, a UDF anchor at the end) make Linux see two filesystems and give up.
        val zero = ByteArray(2048 * SECTOR)
        for ((start, end) in listOf(
            0L to l.fatOffset,
            l.fatOffset + l.fatLength to l.heapOffset,
            maxOf(l.heapOffset, volumeSectors - 2048) to volumeSectors,
        )) {
            var s = start
            while (s < end) {
                val n = minOf(2048L, end - s).toInt()
                dev.write(partitionOffset + s, n, zero, 0)
                s += n
            }
        }

        // FAT: media entries, then a chain for each of bitmap, up-case table and root directory.
        val chunk = 2048 * SECTOR
        var fatWritten = 0L
        val fatBytes = l.fatLength * SECTOR
        while (fatWritten < fatBytes) {
            val buf = ByteArray(minOf(chunk.toLong(), fatBytes - fatWritten).toInt())
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val firstEntry = fatWritten / 4
            fun set(cluster: Long, value: Int) {
                val i = cluster - firstEntry
                if (i >= 0 && i * 4 < buf.size) bb.putInt((i * 4).toInt(), value)
            }
            set(0, 0xFFFFFFF8.toInt())
            set(1, 0xFFFFFFFF.toInt())
            fun chain(start: Long, count: Long) {
                for (c in start until start + count) set(c, if (c == start + count - 1) -1 else (c + 1).toInt())
            }
            chain(l.bitmapCluster, l.upcaseCluster - l.bitmapCluster)
            chain(l.upcaseCluster, l.rootCluster - l.upcaseCluster)
            chain(l.rootCluster, 1)
            put(l.fatOffset + fatWritten / SECTOR, buf)
            fatWritten += buf.size
        }

        // Allocation bitmap: the clusters used by the bitmap, up-case table and root directory.
        val bitmap = ByteArray((l.clustersFor(l.bitmapBytes) * l.clusterBytes).toInt())
        for (i in 0 until l.usedClusters) bitmap[(i / 8).toInt()] = (bitmap[(i / 8).toInt()].toInt() or (1 shl (i % 8).toInt())).toByte()
        put(l.sectorOf(l.bitmapCluster), bitmap)

        val upcase = upcaseTable()
        put(l.sectorOf(l.upcaseCluster), upcase.copyOf((l.clustersFor(l.upcaseBytes) * l.clusterBytes).toInt()))

        // Root directory: volume label, allocation bitmap, up-case table.
        val root = ByteArray(l.clusterBytes)
        val rb = ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN)
        root[0] = 0x83.toByte()
        root[1] = label.length.toByte()
        label.forEachIndexed { i, ch -> rb.putShort(2 + i * 2, ch.code.toShort()) }
        root[ENTRY] = 0x81.toByte()
        rb.putInt(ENTRY + 20, l.bitmapCluster.toInt())
        rb.putLong(ENTRY + 24, l.bitmapBytes)
        root[2 * ENTRY] = 0x82.toByte()
        rb.putInt(2 * ENTRY + 4, checksum(upcase))
        rb.putInt(2 * ENTRY + 20, l.upcaseCluster.toInt())
        rb.putLong(2 * ENTRY + 24, l.upcaseBytes)
        put(l.sectorOf(l.rootCluster), root)

        // Boot region last, so the volume only looks valid once everything else is in place.
        val boot = bootRegion(l, serial)
        put(12, boot) // backup
        put(0, boot)
        return l
    }

    /** The 12-sector boot region: boot sector, 8 extended boot sectors, OEM, reserved, checksum. */
    fun bootRegion(l: Layout, serial: Int): ByteArray {
        val r = ByteArray(12 * SECTOR)
        val b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
        r[0] = 0xEB.toByte(); r[1] = 0x76; r[2] = 0x90.toByte()
        "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(r, 3)
        b.putLong(64, l.partitionOffset)
        b.putLong(72, l.volumeLength)
        b.putInt(80, l.fatOffset.toInt())
        b.putInt(84, l.fatLength.toInt())
        b.putInt(88, l.heapOffset.toInt())
        b.putInt(92, l.clusterCount.toInt())
        b.putInt(96, l.rootCluster.toInt())
        b.putInt(100, serial)
        b.putShort(104, 0x0100) // revision 1.00
        b.putShort(106, 0) // volume flags
        r[108] = 9 // 512-byte sectors
        r[109] = Integer.numberOfTrailingZeros(l.sectorsPerCluster).toByte()
        r[110] = 1 // one FAT
        r[111] = 0x80.toByte()
        r[112] = ((l.usedClusters * 100) / l.clusterCount).toInt().toByte()
        for (i in 120 until 510) r[i] = 0xF4.toByte() // halt instructions, like mkfs.exfat
        r[510] = 0x55; r[511] = 0xAA.toByte()
        for (s in 1..8) { // extended boot sectors end with the signature too
            r[s * SECTOR + 510] = 0x55; r[s * SECTOR + 511] = 0xAA.toByte()
        }
        var sum = 0
        for (i in 0 until 11 * SECTOR) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = (if (sum and 1 != 0) 0x80000000.toInt() else 0) + (sum ushr 1) + (r[i].toInt() and 0xFF)
        }
        for (i in 0 until SECTOR / 4) b.putInt(11 * SECTOR + i * 4, sum)
        return r
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (x in data) sum = (if (sum and 1 != 0) 0x80000000.toInt() else 0) + (sum ushr 1) + (x.toInt() and 0xFF)
        return sum
    }

    private var upcaseCache: ByteArray? = null

    /**
     * Up-case table in the spec's compressed form (as Windows and mkfs.exfat write it): runs of
     * characters that map to themselves become 0xFFFF followed by the run length. This keeps the
     * table around 6 KB; some older tools can't read one over 64 KB.
     */
    fun upcaseTable(): ByteArray = upcaseCache ?: run {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }
        fun map(c: Int): Int {
            val up = Character.toUpperCase(c.toChar()).code
            // Surrogates and noncharacters stay as themselves.
            return if (c in 0xD800..0xDFFF) c else up
        }
        var c = 0
        while (c < 0x10000) {
            if (map(c) == c) {
                var run = 1
                while (c + run < 0x10000 && map(c + run) == c + run) run++
                // Short runs are cheaper written out, except one that reaches 0xFFFF:
                // a literal 0xFFFF would read as the start of a run.
                if (run > 2 || c + run == 0x10000) {
                    u16(0xFFFF)
                    u16(run)
                } else {
                    for (i in 0 until run) u16(c + i)
                }
                c += run
            } else {
                u16(map(c))
                c++
            }
        }
        out.toByteArray().also { upcaseCache = it }
    }

    private fun align(v: Long, to: Long) = (v + to - 1) / to * to
}

/** MBR partition tables (what Ventoy uses, and the most widely readable layout for a plain drive). */
object Mbr {
    data class Part(val start: Long, val sectors: Long, val type: Int, val active: Boolean = false)

    /** Writes the four 16-byte entries and the 0x55AA signature into [sector0]. Bytes 0..445 are left alone. */
    fun writeTable(sector0: ByteArray, parts: List<Part>) {
        require(parts.size <= 4)
        val b = ByteBuffer.wrap(sector0).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 64) sector0[446 + i] = 0
        parts.forEachIndexed { i, p ->
            val o = 446 + i * 16
            sector0[o] = if (p.active) 0x80.toByte() else 0
            chs(p.start).copyInto(sector0, o + 1)
            sector0[o + 4] = p.type.toByte()
            chs(p.start + p.sectors - 1).copyInto(sector0, o + 5)
            b.putInt(o + 8, p.start.toInt())
            b.putInt(o + 12, p.sectors.toInt())
        }
        sector0[510] = 0x55; sector0[511] = 0xAA.toByte()
    }

    /** CHS address with the usual 255 heads / 63 sectors geometry; far sectors use the "too big" marker. */
    private fun chs(lba: Long): ByteArray {
        val spt = 63; val heads = 255
        val c = lba / (spt * heads)
        if (c > 1023) return byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val h = (lba / spt) % heads
        val s = (lba % spt) + 1
        return byteArrayOf(h.toByte(), ((s and 0x3F) or ((c shr 2) and 0xC0)).toByte(), (c and 0xFF).toByte())
    }
}

/** "Format drive": one exFAT partition across the whole drive, readable everywhere. */
object PlainDrive {
    const val LABEL = "USB DRIVE"
    const val START = 2048L // 1 MB in, the standard alignment

    fun format(dev: BlockDevice, label: String = LABEL, stage: StageFn, log: LogFn, cancel: () -> Boolean) {
        require(dev.blockSize == 512) { "This drive uses ${dev.blockSize}-byte sectors, which formatting doesn't support yet." }
        val total = dev.blockCount
        require(total > START + 8192) { "This drive is too small to format." }
        stage(Stage.WRITE, 0, 0)
        log("Clearing the old layout from ${dev.name}")
        Wipe.ends(dev)
        if (cancel()) throw CancelledException()
        // Partition ends on a 1 MB boundary so the volume stays aligned.
        val sectors = (total - START) / 2048 * 2048
        log("Formatting exFAT, ${"%.1f".format(sectors * 512 / 1e9)} GB, label \"$label\"")
        ExFat.format(dev, START, sectors, label) { d, t -> stage(Stage.WRITE, d, t) }
        val mbr = ByteArray(512)
        Mbr.writeTable(mbr, listOf(Mbr.Part(START, sectors, 0x07)))
        // A disk signature, so Windows doesn't ask to "initialize" the drive.
        val sig = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        sig.copyInto(mbr, 440)
        dev.write(0, 1, mbr)
        dev.sync()
        stage(Stage.CHECK, 0, 1)
        val back = ByteArray(512)
        dev.read(0, 1, back)
        if (!back.contentEquals(mbr)) throw WriteException("The drive didn't keep what was written. It may be failing; try another drive.")
        stage(Stage.CHECK, 1, 1)
        log("Done: one exFAT partition")
    }
}

/** Clears partition tables and filesystem signatures at both ends of a drive (old GPTs live at the end too). */
object Wipe {
    fun ends(dev: BlockDevice, sectors: Int = 2048) {
        val n = minOf(sectors.toLong(), dev.blockCount).toInt()
        val zeros = ByteArray(n * dev.blockSize)
        dev.write(0, n, zeros)
        dev.write(dev.blockCount - n, n, zeros)
    }
}
