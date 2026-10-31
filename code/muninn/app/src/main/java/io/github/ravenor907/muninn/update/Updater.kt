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
import io.github.ravenor907.muninn.net.Net
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Checks GitHub for a newer Muninn every time the app is opened (not just when its process starts:
 * the background service keeps the process alive for days). A newer release downloads by itself and
 * goes straight to Android's installer, which always asks the person to tap Install. The download must
 * match the release's SHA-256 (GitHub's digest for the file, or the Muninn.apk.sha256 next to it), carry
 * the project's signature (Muninn.apk.sig, Ed25519 over the file, see shared/docs/releases.md) and its signature of
 * the release's version (Muninn.apk.vsig, [versionMessage]), and be newer than this copy, so a broken, swapped or
 * older file is never installed.
 */
class Updater(private val context: Context, private val identity: Identity) {
    data class State(val status: String = "", val latest: String = "", val busy: Boolean = false, val ready: Boolean = false, val progress: Float = 0f)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val pool = Executors.newSingleThreadExecutor()
    private var url: String? = null
    /** The SHA-256 (hex) the download must have, from the same release. */
    private var sha: String? = null
    /** The version the release's tag names ("0.97" for v0.97): the one its .vsig must sign. */
    private var version: String? = null
    private var lastCheck = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** A Kids' TV installs through its own PackageInstaller session (tv/TvUpdate); true when it took the file. */
    @Volatile var installer: ((File) -> Boolean)? = null

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
                val api = fromApi()
                // GitHub's API allows few checks per address an hour: then ask the website instead.
                var (best, bestUrl, bestSha) = if (api.first == null) fromWebsite() else api
                // The Pre-release channel (Labs): the "beta" pre-release too, when it's newer than the latest release.
                if (identity.preRelease) fromBeta()?.let { (bv, bu, bs) ->
                    if (best == null || Versions.newer(bv, best!!)) { best = bv; bestUrl = bu; bestSha = bs }
                }
                url = bestUrl
                sha = bestSha
                version = best
                val newer = best != null && mayInstall(best!!, current)
                // Data saver on mobile data: the download waits for Wi-Fi, unless the person taps to get it now.
                val wait = newer && installAfter && (context.applicationContext as? io.github.ravenor907.muninn.Muninn)?.power?.savingData() == true
                _state.value = State(if (!newer) "Muninn is up to date" else if (wait) "Muninn $best is ready. It downloads on Wi-Fi, or tap to get it now"
                    else "Muninn $best is ready to install", best ?: "", false, newer)
                if (newer && installAfter && !wait) download()
            } catch (e: Exception) {
                lastCheck = 0L   // try again next time Muninn opens
                _state.value = State("Couldn't reach GitHub. Check the connection.")
            }
        }
    }

    /**
     * Labs, "Go back to stable": the latest release, installed even though it's older than this pre-release. Its
     * file, SHA-256, .sig and .vsig are checked as always. The channel goes back to Stable and Labs mode turns off.
     */
    fun backToStable() {
        if (_state.value.busy) return
        identity.preRelease = false
        identity.labs = false
        _state.value = _state.value.copy(busy = true, status = "Checking for updates…")
        pool.execute {
            try {
                val api = fromApi()
                val (best, bestUrl, bestSha) = if (api.first == null) fromWebsite() else api
                if (best == null || bestUrl == null) { _state.value = State("Couldn't find the latest release."); return@execute }
                url = bestUrl; sha = bestSha; version = best
                _state.value = State("Muninn $best is ready to install", best, false, true)
                download(back = true)
            } catch (e: Exception) {
                _state.value = State("Couldn't reach GitHub. Check the connection.")
            }
        }
    }

    /** [back]: going back to stable ([backToStable]), the one time an older release may be installed. */
    fun download(back: Boolean = false) {
        val u = url ?: return
        val want = sha
        val ver = version
        _state.value = _state.value.copy(busy = true, status = "Downloading Muninn ${_state.value.latest}…")
        pool.execute {
            try {
                // The project's signatures, published beside the APK by the release job: of the file (.sig) and of the
                // release's version (.vsig). Both are always required.
                val sig = signature("$u.sig")
                val vsig = signature("$u.vsig")
                if (sig == null || vsig == null || ver == null) {
                    _state.value = _state.value.copy(busy = false, status = NOT_SIGNED)
                    return@execute
                }
                // Never an older (or the same) version, whatever a release's tag says. Going back to stable takes the
                // latest release (never a pre-release) even when it's older.
                if (!(back && Versions.isStable(ver) && !u.contains("/$BETA_TAG/")) && !mayInstall(ver, current)) {
                    _state.value = _state.value.copy(busy = false, status = NOT_NEWER)
                    return@execute
                }
                val signed = Ed25519.Check(hex(RELEASE_KEY), sig)
                val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
                val out = File(dir, "Muninn.apk")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                // GitHub's download links redirect to another host; OkHttp follows them.
                val c = Net.client.newBuilder().readTimeout(2, java.util.concurrent.TimeUnit.MINUTES).build()
                c.newCall(Request.Builder().url(u).header("User-Agent", AGENT).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("No body")
                    val total = body.contentLength()
                    body.byteStream().use { ins ->
                        out.outputStream().use { os ->
                            val buf = ByteArray(1 shl 16); var got = 0L
                            while (true) { val n = ins.read(buf); if (n < 0) break; os.write(buf, 0, n); digest.update(buf, 0, n); signed.update(buf, 0, n); got += n
                                if (total > 0) _state.value = _state.value.copy(progress = got.toFloat() / total) }
                        }
                    }
                }
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (want == null || got != want) {
                    out.delete()
                    _state.value = _state.value.copy(busy = false, status = if (want == null) "This update can't be checked, so it wasn't installed." else "The download didn't match the release, so it wasn't installed.")
                    return@execute
                }
                if (!signed.valid()) {
                    out.delete()
                    _state.value = _state.value.copy(busy = false, status = NOT_SIGNED)
                    return@execute
                }
                // The version signed in .vsig must be the one this release's tag names: an older signed file posted
                // again under a newer tag doesn't check out.
                if (!versionSigned(ver, u.substringAfterLast('/'), got, vsig)) {
                    out.delete()
                    _state.value = _state.value.copy(busy = false, status = VERSION_NOT_SIGNED)
                    return@execute
                }
                _state.value = _state.value.copy(busy = false, status = "Tap Install when Android asks")
                main.post { if (installer?.invoke(out) != true) install(out) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, status = "The download stopped. Try again.")
            }
        }
    }

    /** The newest release with an APK, from GitHub's API: (version, download link, SHA-256), or nulls if the API said no. */
    private fun fromApi(): Triple<String?, String?, String?> {
        val req = Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").header("User-Agent", AGENT).build()
        val text = Net.client.newCall(req).execute().use { r ->
            if (r.code == 403 || r.code == 429) return Triple(null, null, null)   // rate limited
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("No body")
        }
        val a = JSONArray(text)
        var best: String? = null
        var bestUrl: String? = null
        var bestSha: String? = null
        betaSha = null
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            // The "beta" pre-release: only its APK's SHA-256 is kept, for the Pre-release channel ([fromBeta]).
            if (r.optString("tag_name") == BETA_TAG && r.optBoolean("prerelease")) {
                val assets = r.optJSONArray("assets")
                if (assets != null) betaSha = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == "Muninn.apk" }?.let { shaOf(it.optString("digest")) }
            }
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            val name = versionOf(tag) ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            if (best == null || rank(name) > rank(best)) {
                best = name; bestUrl = apk.optString("browser_download_url")
                bestSha = shaOf(apk.optString("digest")) ?: sidecar("$DOWNLOAD/$tag/${apk.optString("name")}.sha256")
            }
        }
        return Triple(best, bestUrl, bestSha)
    }

    /** The "beta" pre-release's APK SHA-256, from the last [fromApi]. */
    @Volatile private var betaSha: String? = null

    /**
     * The "beta" pre-release: (label, download link, SHA-256), or null when there is none. Its version.txt holds the
     * label ("0.99 beta 12"), which its .vsig signs.
     */
    private fun fromBeta(): Triple<String, String, String?>? {
        val label = try {
            Net.client.newCall(Request.Builder().url("$DOWNLOAD/$BETA_TAG/version.txt").header("User-Agent", AGENT).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.trim() else null
            }
        } catch (e: Exception) { null } ?: return null
        if (!Versions.isBeta(label)) return null
        return Triple(label, "$DOWNLOAD/$BETA_TAG/Muninn.apk", betaSha ?: sidecar("$DOWNLOAD/$BETA_TAG/Muninn.apk.sha256"))
    }

    /** The hex SHA-256 in a release's ".sha256" file (as sha256sum writes it), or null. */
    private fun sidecar(u: String): String? = try {
        Net.client.newCall(Request.Builder().url(u).header("User-Agent", AGENT).build()).execute().use { r ->
            if (r.isSuccessful) shaOf(r.body?.string().orEmpty()) else null
        }
    } catch (e: Exception) { null }

    /** The 64 bytes of a release's ".sig" file, or null when there is none. */
    private fun signature(u: String): ByteArray? = try {
        Net.client.newCall(Request.Builder().url(u).header("User-Agent", AGENT).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size == 64 } else null
        }
    } catch (e: Exception) { null }

    /** The website's "latest release" link redirects to its tag; the APK is always named Muninn.apk. */
    private fun fromWebsite(): Triple<String?, String?, String?> {
        val req = Request.Builder().url(LATEST).header("User-Agent", AGENT).head().build()
        val finalUrl = Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.request.url.toString()
        }
        val tag = tagFromLatest(finalUrl) ?: throw IOException("No release tag in $finalUrl")
        val name = versionOf(tag) ?: throw IOException("Unexpected tag $tag")
        return Triple(name, "$DOWNLOAD/$tag/Muninn.apk", sidecar("$DOWNLOAD/$tag/Muninn.apk.sha256"))
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
        /** The pre-release's fixed tag, replaced on every push to the beta branch. */
        const val BETA_TAG = "beta"
        /** A plain agent with no app name or version, so GitHub can't tell which app or version is asking. */
        private const val AGENT = "Mozilla/5.0"
        private const val NOT_SIGNED = "This update isn't signed with the project's key, so it wasn't installed."
        private const val VERSION_NOT_SIGNED = "This update's version isn't signed with the project's key, so it wasn't installed."
        private const val NOT_NEWER = "This update isn't newer than the Muninn you have, so it wasn't installed."
        /** The first line of the text a .vsig signs (shared/docs/releases.md). */
        const val VERSION_DOMAIN = "huginn-muninn release v1"
        /** The project's release key (Ed25519, shared/docs/release-key.pem). CI signs every release file with its private half. */
        const val RELEASE_KEY = "705d7c9eb0626439b6ab749bbeefc9271876249c701914d60902a6b2b7341d06"

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        /** True when [sig] is the project's signature of [data]. */
        fun signedByProject(data: ByteArray, sig: ByteArray): Boolean = Ed25519.verify(hex(RELEASE_KEY), data, sig)

        /**
         * What a release file's .vsig signs: [VERSION_DOMAIN], the version, the file's name and the lowercase hex SHA-256
         * of the file, one per line (no newline at the end). Built from the tag being installed.
         */
        fun versionMessage(version: String, file: String, sha: String): ByteArray =
            "$VERSION_DOMAIN\n$version\n$file\n${sha.lowercase()}".toByteArray(Charsets.UTF_8)

        /** True when [vsig] is [key]'s signature of [version] for [file] with SHA-256 [sha] (the project's key by default). */
        fun versionSigned(version: String, file: String, sha: String, vsig: ByteArray, key: ByteArray = hex(RELEASE_KEY)): Boolean =
            Ed25519.verify(key, versionMessage(version, file, sha), vsig)

        /** Only a version newer than the one installed ([current]) is ever installed, by [Versions] order. */
        fun mayInstall(version: String, current: String) = Versions.newer(version, current)

        /** ".../releases/tag/v0.6" -> "v0.6" (where the "latest" link lands), else null. */
        fun tagFromLatest(url: String): String? =
            Regex("""/releases/tag/([^/?#]+)$""").find(url.substringBefore('?').substringBefore('#'))?.groupValues?.get(1)

        /** The hex SHA-256 in GitHub's "sha256:<hex>" digest or a sha256sum line ("<hex>  Muninn.apk"), lowercase; else null. */
        fun shaOf(text: String): String? =
            Regex("""^(?:sha256:)?([0-9a-fA-F]{64})(?:\s|$)""").find(text.trim())?.groupValues?.get(1)?.lowercase()

        /** "v0.31" -> "0.31". Test tags (v0.3-test1) and anything else -> null. */
        fun versionOf(tag: String): String? =
            Regex("""^v(\d+\.\d+)$""").find(tag)?.groupValues?.get(1)

        /**
         * One dot, read as a decimal: 0.3 < 0.31 < 0.32 < 0.4 < 1.0. An installed test build
         * ("0.3 Test 1") sits just below its version. Matches versionCode in build.gradle.kts. Which version is newer is
         * [Versions] (it also orders pre-releases).
         */
        fun rank(v: String): Long {
            val m = Regex("""^(\d+)\.(\d+)( Test \d+)?""").find(v) ?: return 0
            val (a, b, t) = m.destructured
            return (a.toLong() * 1_000_000 + (b + "000000").take(6).toLong()) * 10 + if (t.isEmpty()) 5 else 0
        }
    }
}
