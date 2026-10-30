package io.github.ravenor907.muninn.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Link previews (cap m2, docs/protocol-0.6.md 12.4): made on the sending phone only, never on the receiving one,
 * so no website learns who got a link or when it was read. The page's title, description and picture come from its
 * Open Graph tags (or its <title>); the picture is shrunk to a small JPEG with nothing but pixels.
 */
object LinkPreview {
    private const val PAGE_MAX = 512 * 1024
    private const val IMAGE_MAX = 4 * 1024 * 1024
    private const val THUMB_SIDE = 240
    private const val THUMB_BYTES = 16 * 1024

    private val client by lazy {
        Net.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS).followRedirects(true).build()
    }

    /** Null when the page couldn't be read or has no title. Call off the main thread. */
    fun fetch(url: String): More.Preview? = try {
        val html = get(url, PAGE_MAX, "text/html")?.let { String(it, Charsets.UTF_8) } ?: null
        html?.let { page ->
            val title = (meta(page, "og:title") ?: meta(page, "twitter:title") ?: tag(page, "title"))?.let { clean(it, More.TITLE_MAX) }
            if (title.isNullOrEmpty()) null else {
                val desc = (meta(page, "og:description") ?: meta(page, "description"))?.let { clean(it, More.DESC_MAX) } ?: ""
                val img = meta(page, "og:image")?.let { resolve(url, it) }
                val thumb = img?.let { u -> get(u, IMAGE_MAX, "image/")?.let { thumbnail(it) } }
                More.Preview(url, title, desc, thumb).takeIf { More.ok("rich", org.json.JSONObject().put("text", url).put("lp", More.previewJson(it))) }
            }
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    private fun get(url: String, max: Int, type: String): ByteArray? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; link preview)").header("Accept", if (type.endsWith("/")) "image/*" else type).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            val ct = body.contentType()?.toString().orEmpty()
            if (!ct.startsWith(type)) return null
            val out = ByteArrayOutputStream()
            body.byteStream().use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() >= max) break   // the head of a page is enough; a huge picture isn't worth it
                }
            }
            return out.toByteArray()
        }
    }

    private fun attr(t: String, name: String): String? =
        Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(t)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    /** <meta property="og:title" content="..."> or name="...", in either order. */
    private fun meta(page: String, key: String): String? {
        for (m in Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(page)) {
            val t = m.value
            val k = attr(t, "property") ?: attr(t, "name") ?: continue
            if (k.equals(key, ignoreCase = true)) return attr(t, "content")?.let { unescape(it) }?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun tag(page: String, name: String): String? =
        Regex("""<$name\b[^>]*>(.*?)</$name>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(page)?.groupValues?.get(1)?.let { unescape(it) }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }

    /** One line, no control characters, at most [max] code points. */
    private fun clean(s: String, max: Int): String {
        val one = s.replace(Regex("\\s+"), " ").filter { it.code >= 0x20 && it.code != 0x7F }.trim()
        return if (one.codePointCount(0, one.length) <= max) one else one.substring(0, one.offsetByCodePoints(0, max - 1)).trimEnd() + "…"
    }

    private fun resolve(base: String, link: String): String? = try { java.net.URI(base).resolve(link.trim()).toString() } catch (e: Exception) { null }

    private fun thumbnail(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_SIDE) sample *= 2
        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val long = maxOf(b.width, b.height)
        if (long > THUMB_SIDE) { val f = THUMB_SIDE.toFloat() / long; b = Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true) }
        for (q in intArrayOf(70, 55, 40)) {
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= THUMB_BYTES) return out.toByteArray()
        }
        return null
    }
}
