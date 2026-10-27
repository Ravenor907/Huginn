package io.github.ravenor907.muninn.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.ravenor907.muninn.net.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import io.github.ravenor907.muninn.net.Tor
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Checks GitHub for a newer Muninn every time the app is opened (not just when its process starts:
 * the background service keeps the process alive for days). A newer release downloads by itself and
 * goes straight to Android's installer, which always asks the person to tap Install.
 * Both the check and the download go through Tor, so GitHub never sees this phone's IP address.
 */
class Updater(private val context: Context, private val identity: Identity) {
    data class State(val status: String = "", val latest: String = "", val busy: Boolean = false, val ready: Boolean = false, val progress: Float = 0f)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val pool = Executors.newSingleThreadExecutor()
    private var url: String? = null
    private var lastCheck = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Called whenever Muninn comes to the front. Checks at most every few minutes. */
    fun checkOnOpen() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < RECHECK_MS) return
        check(installAfter = true)
    }

    val current: String get() = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1"
    } catch (e: Exception) { "0.1" }

    fun check(installAfter: Boolean = false) {
        if (_state.value.busy) return
        lastCheck = android.os.SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(busy = true, status = "Checking for updates…")
        pool.execute {
            try {
                if (!Tor.ready) _state.value = _state.value.copy(status = "Connecting privately…")
                if (!Tor.awaitReady(TOR_WAIT_MS)) throw IOException("Tor isn't ready")
                val api = fromApi()
                // GitHub's API allows few checks per address, and Tor exits are shared: then ask the website instead.
                val (best, bestUrl) = if (api.first == null) fromWebsite() else api
                url = bestUrl
                val newer = best != null && rank(best) > rank(current)
                _state.value = State(if (newer) "Muninn $best is ready to install" else "Muninn is up to date", best ?: "", false, newer)
                if (newer && installAfter) download()
            } catch (e: Exception) {
                lastCheck = 0L   // try again next time Muninn opens
                _state.value = State("Couldn't reach GitHub privately. Check the connection.")
            }
        }
    }

    fun download() {
        val u = url ?: return
        _state.value = _state.value.copy(busy = true, status = "Downloading Muninn ${_state.value.latest}…")
        pool.execute {
            try {
                val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
                val out = File(dir, "Muninn.apk")
                if (!Tor.awaitReady(TOR_WAIT_MS)) throw IOException("Tor isn't ready")
                // GitHub's download links redirect to another host; OkHttp follows them, still through Tor.
                val c = Tor.client().newBuilder().readTimeout(2, java.util.concurrent.TimeUnit.MINUTES).build()
                c.newCall(Request.Builder().url(u).header("User-Agent", "Muninn").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("No body")
                    val total = body.contentLength()
                    body.byteStream().use { ins ->
                        out.outputStream().use { os ->
                            val buf = ByteArray(1 shl 16); var got = 0L
                            while (true) { val n = ins.read(buf); if (n < 0) break; os.write(buf, 0, n); got += n
                                if (total > 0) _state.value = _state.value.copy(progress = got.toFloat() / total) }
                        }
                    }
                }
                _state.value = _state.value.copy(busy = false, status = "Tap Install when Android asks")
                main.post { install(out) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, status = "The download stopped. Try again.")
            }
        }
    }

    /** The newest release with an APK, from GitHub's API: (version, download link), or nulls if the API said no. */
    private fun fromApi(): Pair<String?, String?> {
        val req = Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").header("User-Agent", "Muninn").build()
        val text = Tor.client().newCall(req).execute().use { r ->
            if (r.code == 403 || r.code == 429) return null to null   // rate limited
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("No body")
        }
        val a = JSONArray(text)
        var best: String? = null
        var bestUrl: String? = null
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            val name = versionOf(tag) ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            if (best == null || rank(name) > rank(best)) { best = name; bestUrl = apk.optString("browser_download_url") }
        }
        return best to bestUrl
    }

    /** The website's "latest release" link redirects to its tag; the APK is always named Muninn.apk. */
    private fun fromWebsite(): Pair<String?, String?> {
        val req = Request.Builder().url(LATEST).header("User-Agent", "Muninn").head().build()
        val finalUrl = Tor.client().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.request.url.toString()
        }
        val tag = tagFromLatest(finalUrl) ?: throw IOException("No release tag in $finalUrl")
        val name = versionOf(tag) ?: throw IOException("Unexpected tag $tag")
        return name to "$DOWNLOAD/$tag/Muninn.apk"
    }

    private fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _state.value = _state.value.copy(status = "Allow Muninn to install updates, then press Install again")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val RELEASES = "https://api.github.com/repos/Ravenor907/Huginn-Muninn/releases?per_page=100"
        private const val LATEST = "https://github.com/Ravenor907/Huginn-Muninn/releases/latest"
        private const val DOWNLOAD = "https://github.com/Ravenor907/Huginn-Muninn/releases/download"
        private const val RECHECK_MS = 5 * 60_000L
        /** Tor usually starts in seconds, but a first start on a slow network can take a minute or two. */
        private const val TOR_WAIT_MS = 120_000L

        /** ".../releases/tag/v0.6" -> "v0.6" (where the "latest" link lands), else null. */
        fun tagFromLatest(url: String): String? =
            Regex("""/releases/tag/([^/?#]+)$""").find(url.substringBefore('?').substringBefore('#'))?.groupValues?.get(1)

        /** "v0.31" -> "0.31". Old test tags (v0.3-test1) and anything else -> null. */
        fun versionOf(tag: String): String? =
            Regex("""^v(\d+\.\d+)$""").find(tag)?.groupValues?.get(1)

        /**
         * One dot, read as a decimal: 0.3 < 0.31 < 0.32 < 0.4 < 1.0. An installed old test build
         * ("0.3 Test 1") sits just below its version. Matches versionCode in build.gradle.kts.
         */
        fun rank(v: String): Long {
            val m = Regex("""^(\d+)\.(\d+)( Test \d+)?""").find(v) ?: return 0
            val (a, b, t) = m.destructured
            return (a.toLong() * 1_000_000 + (b + "000000").take(6).toLong()) * 10 + if (t.isEmpty()) 5 else 0
        }
    }
}
