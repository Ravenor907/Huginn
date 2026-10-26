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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks GitHub for a newer Muninn every time the app is opened (not just when its process starts:
 * the background service keeps the process alive for days). A newer release downloads by itself and
 * goes straight to Android's installer, which always asks the person to tap Install.
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
                val c = URL(RELEASES).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "Muninn/$current")
                c.connectTimeout = 8000; c.readTimeout = 15000
                val a = JSONArray(c.inputStream.bufferedReader().readText())
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
                url = bestUrl
                val newer = best != null && rank(best) > rank(current)
                _state.value = State(if (newer) "Muninn $best is ready to install" else "Muninn is up to date", best ?: "", false, newer)
                if (newer && installAfter) download()
            } catch (e: Exception) {
                lastCheck = 0L   // try again next time Muninn opens
                _state.value = State("Couldn't reach GitHub. Check the connection.")
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
                var c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                // GitHub's download links redirect to another host.
                var hops = 0
                while (c.responseCode in 300..399 && hops++ < 5) {
                    c = URL(c.getHeaderField("Location")).openConnection() as HttpURLConnection
                }
                val total = c.contentLengthLong
                c.inputStream.use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(1 shl 16); var got = 0L
                        while (true) { val n = ins.read(buf); if (n < 0) break; os.write(buf, 0, n); got += n
                            if (total > 0) _state.value = _state.value.copy(progress = got.toFloat() / total) }
                    }
                }
                _state.value = _state.value.copy(busy = false, status = "Tap Install when Android asks")
                main.post { install(out) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, status = "The download stopped. Try again.")
            }
        }
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
        private const val RELEASES = "https://api.github.com/repos/Ravenor907/Huginn/releases?per_page=100"
        private const val RECHECK_MS = 5 * 60_000L

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
