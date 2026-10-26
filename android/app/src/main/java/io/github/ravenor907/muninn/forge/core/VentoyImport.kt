package io.github.ravenor907.muninn.forge.core

import java.io.IOException
import java.io.InputStream

/**
 * Copies OS images from the phone onto a Ventoy drive's image partition, so they show up in
 * Ventoy's boot menu. Compressed images (.xz, .gz, .bz2, .zip) are unpacked on the way in,
 * because Ventoy boots files straight off the disk and can't unpack them itself.
 */
object VentoyImport {
    /** What Ventoy lists in its menu (by extension). */
    val BOOTABLE = setOf("iso", "img", "wim", "vhd", "vhdx", "efi", "vtoy")
    private val PACKED = setOf("xz", "gz", "bz2", "zip")

    /** A file picked on the phone. [open] gives a fresh stream from the start each time. */
    class Source(val name: String, val size: Long, val open: () -> InputStream)

    /** What will land on the drive. */
    data class Plan(
        val source: Source,
        val kind: ImageKind,
        /** For compressed files, what's inside once unpacked; otherwise the same as [kind]. */
        val content: ImageKind,
        val targetName: String,
        /** Bytes on the drive, or -1 for a compressed file (learned while it's unpacked onto the drive). */
        val targetSize: Long,
    ) {
        val unpacks get() = kind.compressed
        val bootable get() = targetName.substringAfterLast('.', "").lowercase() in BOOTABLE
    }

    /** Name for the drive: compression suffix dropped, and an extension Ventoy recognizes for what it is. */
    fun targetName(name: String, kind: ImageKind, content: ImageKind): String {
        var n = name
        if (kind.compressed) {
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext in PACKED) n = n.substringBeforeLast('.')
        }
        val ext = n.substringAfterLast('.', "").lowercase()
        n = when (content) {
            ImageKind.ISO_HYBRID, ImageKind.ISO_DVD_ONLY -> if (ext == "iso") n else "$n.iso"
            ImageKind.DISK -> if (ext in setOf("img", "vhd", "vhdx", "vtoy")) n else (if (ext == "raw") n.substringBeforeLast('.') else n) + ".img"
            else -> n
        }
        return ExFatVolume.cleanName(n)
    }

    /**
     * Works out what a file is and what it'll be called on the drive. For a compressed file only
     * its start is unpacked, to see what's inside (and catch a file that isn't really an archive);
     * the rest is unpacked once, straight onto the drive, by [run].
     */
    fun plan(src: Source): Plan {
        val head = src.open().use { readHead(it) }
        val kind = ImageKind.sniff(head)
        if (!kind.writable) throw IOException("${src.name} is a ${kind.label} file, which can't be unpacked here. Unpack it first.")
        if (!kind.compressed) return Plan(src, kind, kind, targetName(src.name, kind, kind), src.size)
        val inner = try {
            src.open().use { raw -> ImageKind.decoder(kind)(raw.buffered(1 shl 16)).use { readHead(it) } }
        } catch (e: IOException) {
            throw IOException("${src.name} is damaged or not really a ${kind.label} (${e.message}). Download it again.")
        } catch (e: RuntimeException) {
            throw IOException("${src.name} is damaged or not really a ${kind.label}. Download it again.")
        }
        if (inner.isEmpty()) throw IOException("${src.name} is empty once unpacked.")
        val content = ImageKind.sniff(inner)
        return Plan(src, kind, content, targetName(src.name, kind, content), -1)
    }

    /**
     * Copies every planned file (replacing files of the same name), then reads each back from the
     * drive and checks it. Compressed files are unpacked once, on their way onto the drive, with
     * unpacking and writing running at the same time. Progress: Stage.WRITE over the bytes read
     * from the phone, then Stage.CHECK over the bytes read back. Returns the names added, in order.
     */
    fun run(vol: ExFatVolume, picked: List<Plan>, stage: StageFn, log: LogFn, cancel: () -> Boolean, verify: Boolean = true): List<String> {
        val plans = unique(picked)
        val known = plans.filter { it.targetSize >= 0 }
        val knownBytes = known.sumOf { it.targetSize }
        val needClusters = known.sumOf { maxOf(1L, (it.targetSize + vol.clusterBytes - 1) / vol.clusterBytes) }
        val replacing = plans.mapNotNull { vol.find(it.targetName) }
        val freeAfterDeletes = vol.freeClusters() + replacing.sumOf { (it.size + vol.clusterBytes - 1) / vol.clusterBytes }
        if (needClusters > freeAfterDeletes) {
            throw IOException(
                "Not enough space: %.1f GB to add, %.1f GB free on the drive.".format(knownBytes / 1e9, freeAfterDeletes * vol.clusterBytes / 1e9),
            )
        }
        vol.firstMisfit(known.map { it.targetSize }, replacing)?.let { size ->
            val p = known.first { it.targetSize == size }
            throw IOException(
                "There's enough free space in total, but not in one piece for ${p.targetName} (%.1f GB). ".format(size / 1e9) +
                    "Ventoy needs each image in one piece. Remove an image from the drive, or copy everything off, reinstall Ventoy and add them again.",
            )
        }
        for (old in replacing) {
            log("Replacing ${old.name} already on the drive")
            vol.delete(old)
        }
        // Files of known size first; then compressed ones, each unpacked into the biggest free piece left.
        val order = known + plans.filter { it.targetSize < 0 }
        val results = HashMap<String, Pair<Long, ByteArray>>()
        val totalIn = order.sumOf { it.source.size }
        var doneIn = 0L
        stage(Stage.WRITE, 0, totalIn)
        for (p in order) {
            val before = doneIn
            val started = System.nanoTime()
            val raw = Counting(p.source.open())
            try {
                if (p.unpacks) {
                    log("Unpacking ${p.source.name} onto the drive as ${p.targetName}")
                    ImageWriter.ahead(ImageKind.decoder(p.kind)(raw.buffered(1 shl 16)), raw).use { input ->
                        var lastLog = 0L
                        results[p.targetName] = vol.addStreaming(p.targetName, input, { written ->
                            stage(Stage.WRITE, before + minOf(raw.count, p.source.size), totalIn)
                            if (written - lastLog >= 1_000_000_000L) {
                                lastLog = written
                                log("%.1f GB unpacked onto the drive so far".format(written / 1e9))
                            }
                        }, cancel)
                    }
                    log("${p.targetName}: %.2f GB once unpacked".format(results[p.targetName]!!.first / 1e9))
                } else {
                    log("Copying ${p.targetName}")
                    PrefetchInputStream(raw).use { input ->
                        val h = vol.add(p.targetName, p.targetSize, input, { d, _ -> stage(Stage.WRITE, before + d, totalIn) }, cancel)
                        results[p.targetName] = p.targetSize to h
                    }
                }
            } catch (e: CancelledException) {
                throw e
            } catch (e: WriteException) {
                throw e
            } catch (e: IOException) {
                if (e is ScsiException || e.message?.contains("USB") == true) throw e
                val why = if (p.unpacks && e.message?.contains("in one piece") != true) {
                    "${p.source.name} is damaged or didn't finish downloading (${e.message}). Download it again."
                } else {
                    e.message
                }
                throw IOException("Couldn't copy ${p.targetName}: $why", e)
            } catch (e: RuntimeException) {
                if (!p.unpacks) throw e
                throw IOException("Couldn't copy ${p.targetName}: ${p.source.name} is damaged. Download it again.", e)
            } finally {
                raw.close()
            }
            doneIn += p.source.size
            val took = System.nanoTime() - started
            val bytes = results[p.targetName]?.first ?: 0L
            log("${p.targetName}: %.2f GB in %s (%s)".format(bytes / 1e9, ImageWriter.Timer.clock(took), ImageWriter.Timer.rate(bytes, took)))
            stage(Stage.WRITE, doneIn, totalIn)
        }
        val totalOut = results.values.sumOf { it.first }
        if (!verify) {
            log("Skipped reading the images back (Labs setting)")
            stage(Stage.CHECK, totalOut, totalOut)
            return plans.map { it.targetName }
        }
        var checked = 0L
        stage(Stage.CHECK, 0, totalOut)
        for (p in plans) {
            val (size, hash) = results.getValue(p.targetName)
            log("Reading ${p.targetName} back from the drive to confirm")
            val f = vol.find(p.targetName) ?: throw WriteException("${p.targetName} is missing from the drive after copying.")
            if (f.size != size) throw WriteException("${p.targetName} has the wrong size on the drive after copying.")
            val before = checked
            vol.verify(f, hash, { d, _ -> stage(Stage.CHECK, before + d, totalOut) }, cancel)
            checked += size
        }
        stage(Stage.CHECK, totalOut, totalOut)
        return plans.map { it.targetName }
    }

    /** Two picked files that would get the same name: the later ones get " (2)", " (3)"... */
    fun unique(plans: List<Plan>): List<Plan> {
        val seen = HashSet<String>()
        return plans.map { p ->
            var name = p.targetName
            var k = 2
            while (!seen.add(name.lowercase())) {
                val ext = p.targetName.substringAfterLast('.', "")
                val base = if (ext.isEmpty()) p.targetName else p.targetName.substringBeforeLast('.')
                name = if (ext.isEmpty()) "$base ($k)" else "$base ($k).$ext"
                k++
            }
            if (name == p.targetName) p else p.copy(targetName = name)
        }
    }

    private fun readHead(input: InputStream): ByteArray {
        val head = ByteArray(ImageKind.SNIFF_BYTES)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        return head.copyOf(n)
    }

    private class Counting(inner: InputStream) : java.io.FilterInputStream(inner) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
