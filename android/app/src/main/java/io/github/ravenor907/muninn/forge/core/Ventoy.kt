package io.github.ravenor907.muninn.forge.core

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Installs Ventoy (github.com/ventoy/Ventoy) the same way its own Linux installer,
 * Ventoy2Disk.sh, does in its default MBR mode:
 *
 *  - partition 1: exFAT labeled "Ventoy", from sector 2048 to just before partition 2 (you copy ISOs here)
 *  - partition 2: 32 MB (65536 sectors, 4 KB aligned) at the end, type 0xEF, holding ventoy.disk.img
 *  - sector 0: Ventoy's boot code (first 446 bytes of boot.img), the partition table, a random
 *    disk UUID at byte 384 and a random disk signature at byte 440
 *  - sectors 1 to 2047: core.img (GRUB for BIOS boot)
 *
 * Everything comes from the release's official Linux package, checked against the release's
 * sha256.txt.
 */
object Ventoy {
    const val API_LATEST = "https://api.github.com/repos/ventoy/Ventoy/releases/latest"
    const val PART2_SECTORS = 65536L
    const val PART1_START = 2048L
    const val LABEL = "Ventoy"
    const val MIN_BYTES = 1_000_000_000L

    data class Release(val version: String, val tarName: String, val tarUrl: String, val shaUrl: String?)

    class Files(val bootImg: ByteArray, val coreImgXz: ByteArray, val diskImgXz: ByteArray)

    data class Plan(val totalSectors: Long, val part1Sectors: Long, val part2Start: Long, val sectorsPerCluster: Int)

    /** The newest release from GitHub's API answer. */
    @Suppress("UNCHECKED_CAST")
    fun parseRelease(json: String): Release {
        val root = Json.parse(json) as? Map<String, Any?> ?: throw IOException("Unexpected answer from GitHub")
        val tag = root["tag_name"] as? String ?: throw IOException("GitHub didn't say which Ventoy release is newest")
        val version = tag.removePrefix("v")
        val assets = (root["assets"] as? List<Map<String, Any?>>).orEmpty()
        fun url(a: Map<String, Any?>) = a["browser_download_url"] as? String
        val tar = assets.firstOrNull { (it["name"] as? String)?.matches(Regex("ventoy-[0-9.]+-linux\\.tar\\.gz")) == true }
            ?: throw IOException("Ventoy $version has no Linux package to install from")
        val sha = assets.firstOrNull { it["name"] == "sha256.txt" }
        return Release(version, tar["name"] as String, url(tar)!!, sha?.let { url(it) })
    }

    /** Pulls the three files the installer needs out of the release package. */
    fun extract(tarGz: InputStream): Files {
        var boot: ByteArray? = null
        var core: ByteArray? = null
        var disk: ByteArray? = null
        TarArchiveInputStream(GzipCompressorInputStream(tarGz.buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                when {
                    e.name.endsWith("/boot/boot.img") -> boot = tar.readBytes()
                    e.name.endsWith("/boot/core.img.xz") -> core = tar.readBytes()
                    e.name.endsWith("/ventoy/ventoy.disk.img.xz") -> disk = tar.readBytes()
                }
            }
        }
        return Files(
            boot?.takeIf { it.size >= 446 } ?: throw IOException("The Ventoy package is missing boot.img"),
            core ?: throw IOException("The Ventoy package is missing core.img"),
            disk ?: throw IOException("The Ventoy package is missing ventoy.disk.img"),
        )
    }

    /** Partition sizes exactly as Ventoy2Disk.sh computes them (no reserved space). */
    fun plan(totalSectors: Long): Plan {
        var part1End = totalSectors - PART2_SECTORS - 1
        var part2Start = part1End + 1
        val mod = part2Start % 8
        if (mod > 0) {
            part1End -= mod
            part2Start = part1End + 1
        }
        val sizeGb = totalSectors / 2097152 // whole GiB, as the script does
        return Plan(totalSectors, part1End - PART1_START + 1, part2Start, if (sizeGb > 32) 256 else 64)
    }

    /** Downloads and checks the newest release; returns what was fetched and the files inside. */
    fun fetchLatest(dl: Downloader, cacheDir: File, stage: StageFn, log: LogFn, cancel: () -> Boolean): Pair<Release, Files> {
        stage(Stage.LOOKUP, 0, 0)
        val rel = parseRelease(dl.fetchText(API_LATEST, 2 shl 20))
        log("Latest Ventoy is ${rel.version}")
        cacheDir.mkdirs()
        val tar = File(cacheDir, rel.tarName)
        val part = File(cacheDir, "${rel.tarName}.part")
        val expected = rel.shaUrl?.let { Verify.parseChecksum(dl.fetchText(it), rel.tarName) }
        if (!(tar.exists() && expected != null && sha256(tar) == expected)) {
            tar.delete()
            dl.download(rel.tarUrl, part, stage, cancel)
            stage(Stage.VERIFY, 0, 0)
            if (expected != null) {
                if (sha256(part) != expected) {
                    part.delete()
                    throw VerificationException("The Ventoy download was corrupted, so it was deleted. Try again.")
                }
                log("Checksum matches Ventoy's sha256.txt")
            } else {
                log("Ventoy published no checksum for this release")
            }
            if (!part.renameTo(tar)) throw IOException("Couldn't save the Ventoy download")
        } else {
            log("Already have Ventoy ${rel.version}")
        }
        stage(Stage.VERIFY, 1, 1)
        return rel to tar.inputStream().use { extract(it) }
    }

    /**
     * Turns off Secure Boot support in the EFI partition image, exactly as Ventoy2Disk.sh -S does
     * (vtoycli partresize -s): the Microsoft-signed shim in BOOTX64.EFI is replaced by Ventoy's own
     * GRUB (grubx64_real.efi), and the shim's helpers and Ventoy's key file are removed. Same for
     * 32-bit UEFI. Such a drive starts only with Secure Boot turned off, with no key to enroll.
     */
    fun disableSecureBoot(part2: ByteArray) {
        val fs = Fat16(part2)
        fs.find("/EFI/BOOT/grubx64_real.efi")?.let {
            val real = fs.read("/EFI/BOOT/grubx64_real.efi")
            for (f in listOf("grubx64.efi", "grubx64_real.efi", "MokManager.efi", "mmx64.efi")) fs.delete("/EFI/BOOT/$f")
            fs.delete("/ENROLL_THIS_KEY_IN_MOKMANAGER.cer")
            fs.replace("/EFI/BOOT/BOOTX64.EFI", real)
        }
        fs.find("/EFI/BOOT/grubia32_real.efi")?.let {
            val real = fs.read("/EFI/BOOT/grubia32_real.efi")
            for (f in listOf("grubia32.efi", "grubia32_real.efi", "mmia32.efi")) fs.delete("/EFI/BOOT/$f")
            fs.replace("/EFI/BOOT/BOOTIA32.EFI", real)
        }
    }

    /** Whether an EFI partition image has Secure Boot support (how Ventoy's own tools tell). */
    fun secureBootEnabled(part2: ByteArray) = Fat16(part2).find("/EFI/BOOT/grubx64_real.efi") != null

    /** The Ventoy version written in the EFI partition's grub.cfg, if it can be read. */
    fun versionOf(part2: ByteArray): String? = try {
        val cfg = String(Fat16(part2).read("/grub/grub.cfg"), Charsets.UTF_8)
        Regex("VENTOY_VERSION=\"?([0-9][0-9.]*)").find(cfg)?.groupValues?.get(1)
    } catch (e: IOException) {
        null
    }

    /** Lays Ventoy out on [dev], then reads back everything except the empty exFAT space and compares. */
    fun install(
        dev: BlockDevice,
        files: Files,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
        secureBoot: Boolean = true,
        random: SecureRandom = SecureRandom(),
    ) {
        if (dev.blockSize != 512) throw WriteException("This drive uses ${dev.blockSize}-byte sectors; Ventoy needs 512-byte sectors.")
        if (dev.sizeBytes < MIN_BYTES) throw WriteException("Ventoy needs a drive of at least 1 GB.")
        val p = plan(dev.blockCount)
        val core = ImageKind.decoder(ImageKind.XZ)(ByteArrayInputStream(files.coreImgXz)).readBytes()
        if (core.size > 2047 * 512) throw WriteException("This Ventoy release's boot code is larger than expected; it can't be installed here.")
        val part2 = ImageKind.decoder(ImageKind.XZ)(ByteArrayInputStream(files.diskImgXz)).readBytes()
        if (part2.size.toLong() != PART2_SECTORS * 512) {
            throw WriteException("This Ventoy release's EFI partition isn't the expected 32 MB; it can't be installed here.")
        }
        if (!secureBoot) {
            if (!secureBootEnabled(part2)) throw WriteException("This Ventoy release has no Secure Boot files to remove.")
            disableSecureBoot(part2)
            log("Secure Boot support off: BOOTX64.EFI is Ventoy's GRUB, the shim and key are removed")
        } else {
            log("Secure Boot support on: first start asks to enroll Ventoy's key once")
        }

        val written = ArrayList<Pair<Long, ByteArray>>() // regions to read back
        val total = 2048L + PART2_SECTORS + 4096
        var done = 0L
        fun put(lba: Long, data: ByteArray, check: Boolean = true) {
            var at = 0
            val sectors = data.size / 512
            while (at < sectors) {
                if (cancel()) throw CancelledException()
                val n = minOf(sectors - at, 2048)
                dev.write(lba + at, n, data, at * 512)
                at += n
                done += n
                stage(Stage.WRITE, minOf(done, total), total)
            }
            if (check) written.add(lba to data)
        }

        stage(Stage.WRITE, 0, total)
        log("Clearing the old layout from ${dev.name}")
        Wipe.ends(dev)
        log("Formatting the Ventoy partition as exFAT (%.1f GB)".format(p.part1Sectors * 512 / 1e9))
        ExFat.format(dev, PART1_START, p.part1Sectors, LABEL, p.sectorsPerCluster) { _, _ -> }
        log("Writing Ventoy's EFI partition")
        put(p.part2Start, part2)
        log("Writing Ventoy's boot code")
        put(1, core.copyOf(((core.size + 511) / 512) * 512))

        // Sector 0 last: the drive only looks like a Ventoy drive once everything else is there.
        val mbr = ByteArray(512)
        files.bootImg.copyInto(mbr, 0, 0, 446)
        Mbr.writeTable(
            mbr,
            listOf(Mbr.Part(PART1_START, p.part1Sectors, 0x07, active = true), Mbr.Part(p.part2Start, PART2_SECTORS, 0xEF)),
        )
        ByteArray(16).also { random.nextBytes(it) }.copyInto(mbr, 384) // disk UUID
        ByteArray(4).also { random.nextBytes(it) }.copyInto(mbr, 440) // disk signature
        put(0, mbr)
        dev.sync()

        log("Reading Ventoy back from the drive to confirm")
        val checkTotal = written.sumOf { it.second.size.toLong() }
        var checked = 0L
        for ((lba, data) in written) {
            val back = ByteArray(data.size)
            var at = 0
            while (at < data.size / 512) {
                if (cancel()) throw CancelledException()
                val n = minOf(data.size / 512 - at, 2048)
                dev.read(lba + at, n, back, at * 512)
                at += n
                stage(Stage.CHECK, checked + at * 512L, checkTotal)
            }
            if (!MessageDigest.isEqual(digest(back), digest(data))) {
                throw WriteException("The drive didn't keep what was written. It may be failing or counterfeit; try another drive.")
            }
            checked += data.size
        }
        stage(Stage.CHECK, checkTotal, checkTotal)
        log("Ventoy is installed")
    }

    private fun digest(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }
}
