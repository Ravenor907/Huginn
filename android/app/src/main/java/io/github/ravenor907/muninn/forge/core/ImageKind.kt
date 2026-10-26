package io.github.ravenor907.muninn.forge.core

import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * What a picked file is, judged from its first bytes rather than its name or the
 * type Android reports (which varies by phone and by the app that downloaded it).
 */
enum class ImageKind(val label: String, val compressed: Boolean = false) {
    /** An ISO that also has a disk partition table, so it boots when written to a USB drive. */
    ISO_HYBRID("ISO image"),

    /** An ISO made only for DVDs; it writes fine but most PCs won't boot it from USB. */
    ISO_DVD_ONLY("ISO image (DVD only)"),

    /** A whole disk image: MBR or GPT partition table. */
    DISK("Disk image"),
    BZIP2("Compressed disk image (.bz2)", true),
    GZIP("Compressed disk image (.gz)", true),
    XZ("Compressed disk image (.xz)", true),
    ZIP("Zipped image (.zip)", true),

    /** Compressed with something this app can't unpack. */
    ZSTD("Zstandard (.zst)", true),
    UNKNOWN("Unrecognized file");

    /** Whether this app can write it. */
    val writable: Boolean get() = this != ZSTD

    /** Shown before erasing, or null when the file looks fine. */
    val warning: String?
        get() = when (this) {
            ISO_DVD_ONLY ->
                "This ISO is made for DVDs, not USB drives. It will be written exactly, but most computers " +
                    "and handhelds won't start from it. Check the download page for a USB version."
            UNKNOWN ->
                "This doesn't look like an ISO or disk image. It will be written byte for byte as it is, " +
                    "which only works if it really is a drive image."
            else -> null
        }

    companion object {
        /** Enough of the file to see an ISO's volume descriptor at 32 KB. */
        const val SNIFF_BYTES = 34_816

        fun sniff(head: ByteArray): ImageKind {
            fun at(off: Int, vararg b: Int) = head.size >= off + b.size && b.indices.all { (head[off + it].toInt() and 0xFF) == b[it] }
            return when {
                at(0, 0x42, 0x5A, 0x68) -> BZIP2 // "BZh"
                at(0, 0x1F, 0x8B) -> GZIP
                at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> XZ
                at(0, 0x50, 0x4B, 0x03, 0x04) -> ZIP
                at(0, 0x28, 0xB5, 0x2F, 0xFD) -> ZSTD
                // "CD001" at sector 16 marks ISO 9660; a boot signature at 510 means it's hybrid.
                at(32_769, 0x43, 0x44, 0x30, 0x30, 0x31) -> if (at(510, 0x55, 0xAA)) ISO_HYBRID else ISO_DVD_ONLY
                at(512, 0x45, 0x46, 0x49, 0x20, 0x50, 0x41, 0x52, 0x54) -> DISK // "EFI PART"
                at(510, 0x55, 0xAA) -> DISK
                else -> UNKNOWN
            }
        }

        /** Unpacks [kind] while it's read; uncompressed kinds pass straight through. */
        fun decoder(kind: ImageKind): (InputStream) -> InputStream = when (kind) {
            // On every core at once: bzip2 is slow, and SteamOS's image is bzip2.
            BZIP2 -> { s -> ParallelBzip2InputStream(s) }
            GZIP -> { s -> GZIPInputStream(s, 1 shl 16) }
            XZ -> { s -> org.tukaani.xz.XZInputStream(s) }
            ZIP -> { s -> firstImageInZip(s) }
            ZSTD -> throw IllegalArgumentException("Zstandard files can't be written yet. Unpack the .zst first.")
            else -> { s -> s }
        }

        /** The zip's single file (skipping folders and macOS "__MACOSX" clutter). */
        private fun firstImageInZip(s: InputStream): InputStream {
            val zip = ZipInputStream(s)
            while (true) {
                val e = zip.nextEntry ?: throw java.io.IOException("The zip file is empty")
                if (!e.isDirectory && !e.name.startsWith("__MACOSX/") && !e.name.substringAfterLast('/').startsWith("._")) {
                    return zip
                }
            }
        }
    }
}
