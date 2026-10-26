package io.github.ravenor907.muninn.forge.core

/** The shared catalog (handheld_forge/distros.json), the same file the desktop app reads. */
data class ImageSource(
    val label: String,
    val isoUrl: String,
    /** Null when the publisher offers no checksum (SteamOS); bzip2 CRCs and read-back still apply. */
    val checksumUrl: String?,
    val signatureUrl: String?,
    val requireSignature: Boolean,
    /** Publishers with no "latest" link (CachyOS names each build by date): a page to read and a
     * pattern whose first group is the build, newest sorting last. It must match isoUrl too, so the
     * newest build found on the page can replace the known one in the URLs. */
    val latestPage: String? = null,
    val latestPattern: String? = null,
) {
    val filename: String get() = isoUrl.trimEnd('/').substringAfterLast('/')

    val build: String? get() = latestPattern?.let { Regex(it).find(isoUrl)?.groupValues?.get(1) }

    /** The same source pointed at another build. */
    fun at(newBuild: String): ImageSource =
        copy(isoUrl = isoUrl.replace(build!!, newBuild), checksumUrl = checksumUrl?.replace(build!!, newBuild))
}

data class Distro(
    val id: String,
    val name: String,
    val edition: String,
    val enabled: Boolean,
    val minDriveGb: Int,
    /** Path of the public key inside the catalog, e.g. "keys/bazzite-cosign.pub". */
    val publicKey: String?,
    val images: List<ImageSource>,
    /**
     * "iso" (written as-is), "raw-bz2" or "raw-gz" (a compressed disk image, decompressed
     * while writing), or "zip" (one image inside a zip, unpacked while writing).
     */
    val format: String = "iso",
    /** What to do after booting the drive, shown on the "Ready to boot" screen. */
    val installSteps: List<String> = emptyList(),
    /** Shown before erasing, e.g. a known problem on some handhelds. */
    val warning: String? = null,
    /** Only listed once experimental options are unlocked (tap the version number). */
    val hidden: Boolean = false,
    /** Which "Useful ISOs" folder lists it: "gaming" (the default, for older catalogs), "desktop" or "tools". */
    val category: String = "gaming",
    /** One plain line about what it is, shown under its name. */
    val about: String = "",
    /** Download size as people read it, e.g. "3.1 GB"; null when not known. */
    val size: String? = null,
    /** Whether it can be added to a Ventoy drive as well as written to a whole drive. */
    val ventoy: Boolean = false,
) {
    val minDriveBytes: Long get() = (minDriveGb * 1_000_000_000L * 0.93).toLong()
}

class CatalogException(msg: String) : Exception(msg)

object Catalog {
    val FORMATS = setOf("iso", "raw-bz2", "raw-gz", "zip")
    val CATEGORIES = setOf("gaming", "desktop", "tools")

    @Suppress("UNCHECKED_CAST")
    private fun root(text: String): Map<String, Any?> {
        val root = Json.parse(text) as? Map<String, Any?> ?: throw CatalogException("catalog is not an object")
        if ((root["schema"] as? Long) != 1L) throw CatalogException("unsupported catalog schema")
        return root
    }

    /** How to open the boot menu on common handhelds; the same for every system. */
    @Suppress("UNCHECKED_CAST")
    fun bootMenu(text: String): List<String> = root(text)["boot_menu"] as? List<String> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): List<Distro> {
        val root = root(text)
        val seen = HashSet<String>()
        return (root["distros"] as List<Map<String, Any?>>).map { raw ->
            val id = raw["id"] as String
            if (!seen.add(id)) throw CatalogException("duplicate distro id $id")
            val images = (raw["images"] as? List<Map<String, Any?>> ?: emptyList()).map { img ->
                ImageSource(
                    label = img["label"] as String,
                    isoUrl = img["iso_url"] as String,
                    checksumUrl = img["checksum_url"] as String?,
                    signatureUrl = img["signature_url"] as String?,
                    requireSignature = img["require_signature"] == true,
                    latestPage = img["latest_page"] as String?,
                    latestPattern = img["latest_pattern"] as String?,
                )
            }
            for (img in images) {
                if ((img.latestPage == null) != (img.latestPattern == null)) throw CatalogException("$id: latest_page and latest_pattern go together")
                if (img.latestPattern != null && img.build == null) throw CatalogException("$id: latest_pattern doesn't match its own iso_url")
                for (url in listOfNotNull(img.isoUrl, img.checksumUrl, img.signatureUrl, img.latestPage)) {
                    if (!url.startsWith("https://")) throw CatalogException("$id: only https URLs allowed ($url)")
                }
                if (img.requireSignature && img.signatureUrl == null) {
                    throw CatalogException("$id: signature required but no signature_url")
                }
            }
            val enabled = raw["enabled"] == true
            val format = raw["format"] as? String ?: "iso"
            if (format !in FORMATS) throw CatalogException("$id: unknown format $format")
            if (enabled && images.isEmpty()) throw CatalogException("$id: enabled but has no images")
            val category = raw["category"] as? String ?: "gaming"
            if (category !in CATEGORIES) throw CatalogException("$id: unknown category $category")
            Distro(
                id = id,
                name = raw["name"] as String,
                edition = raw["edition"] as? String ?: "",
                enabled = enabled,
                minDriveGb = ((raw["min_drive_gb"] as? Long) ?: 16L).toInt(),
                publicKey = raw["public_key"] as String?,
                images = images,
                format = format,
                installSteps = raw["install_steps"] as? List<String> ?: emptyList(),
                warning = raw["warning"] as String?,
                hidden = raw["hidden"] == true,
                category = category,
                about = raw["about"] as? String ?: "",
                size = raw["size"] as? String,
                // ISOs boot from Ventoy; whole-disk images usually don't, so they opt in.
                ventoy = raw["ventoy"] as? Boolean ?: (format == "iso" || format == "zip"),
            )
        }
    }
}
