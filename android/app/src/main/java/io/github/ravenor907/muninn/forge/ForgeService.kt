package io.github.ravenor907.muninn.forge

import android.net.Uri
import android.provider.OpenableColumns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.MainActivity
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.forge.core.Catalog
import io.github.ravenor907.muninn.forge.core.CancelledException
import io.github.ravenor907.muninn.forge.core.CorruptImageException
import io.github.ravenor907.muninn.forge.core.Distro
import io.github.ravenor907.muninn.forge.core.DriveTest
import io.github.ravenor907.muninn.forge.core.WriteException
import io.github.ravenor907.muninn.forge.core.DownloadException
import io.github.ravenor907.muninn.forge.core.Preflight
import io.github.ravenor907.muninn.forge.core.Downloader
import io.github.ravenor907.muninn.forge.core.ImageKind
import io.github.ravenor907.muninn.forge.core.ImageWriter
import io.github.ravenor907.muninn.forge.core.Progress
import io.github.ravenor907.muninn.forge.core.Ventoy
import io.github.ravenor907.muninn.forge.core.VentoyImport
import io.github.ravenor907.muninn.forge.core.VentoyDrive
import io.github.ravenor907.muninn.forge.core.PlainDrive
import io.github.ravenor907.muninn.forge.core.Stage
import io.github.ravenor907.muninn.forge.usb.UsbSticks
import java.io.File
import java.io.IOException

/** Runs download, verify, write and read-back in the foreground so the phone can sleep the screen. */
class ForgeService : Service() {
    private var worker: Thread? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val distroId = intent?.getStringExtra(EXTRA_DISTRO)
        val stickKey = intent?.getStringExtra(EXTRA_STICK)
        if (distroId == null || stickKey == null || worker != null) return START_NOT_STICKY

        ensureChannel()
        goForeground()
        val file = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        worker = Thread({
            when {
                distroId == FORMAT_ID || distroId == VENTOY_ID -> runTool(distroId, stickKey, intent.getBooleanExtra(EXTRA_SECURE_BOOT, true))
                distroId == IMPORT_ID -> runImport(intent.getStringArrayListExtra(EXTRA_URIS).orEmpty().map { Uri.parse(it) }, stickKey)
                distroId == TEST_ID -> runTest(stickKey)
                distroId == CHECK_ID -> runCheck(intent.getStringExtra(EXTRA_CHECK_DISTRO), file, stickKey)
                file != null -> runFile(file, stickKey)
                else -> run(distroId, stickKey, intent.getBooleanExtra(EXTRA_TO_VENTOY, false))
            }
        }, "forge").also { it.start() }
        return START_NOT_STICKY
    }

    /**
     * "Connected device" has no time limit and fits a job that drives a USB stick; the
     * "data sync" type Android 15 caps at 6 hours a day is only the fallback.
     */
    private fun goForeground() {
        val n = notification("Starting", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ServiceCompat.startForeground(this, NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                return
            } catch (e: Exception) {
                Jobs.log("Running as a data sync service (${e.javaClass.simpleName})")
            }
            ServiceCompat.startForeground(this, NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ServiceCompat.startForeground(this, NOTIFY_ID, n, 0)
        }
    }

    /** Android 15 calls this when a data sync service hits its daily limit; stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Jobs.cancel("Android stopped the job because it ran too long in the background. Keep the app open and try again.")
    }

    /** Writes a file the person picked (an ISO or disk image), byte for byte, then reads it back. */
    private fun runFile(uri: Uri, stickKey: String) {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muninn:write")
        wake.acquire(4 * 60 * 60 * 1000L)
        try {
            val (name, size) = describeFile(this, uri)
            if (size <= 0) throw IOException("Couldn't read the size of $name. Copy it to the phone's storage and try again.")
            // Judge the file by its contents; the name and Android's type can't be trusted.
            val kind = sniffFile(this, uri)
            if (!kind.writable) throw IOException("$name is a ${kind.label} file, which can't be written yet. Unpack it first.")
            Jobs.log("$name: ${kind.label}")
            val sticks = UsbSticks(this)
            val device = sticks.device(stickKey) ?: throw IOException("The USB drive was unplugged.")
            val stage = { st: Stage, d: Long, t: Long ->
                Jobs.progress(st, d, t)
                maybeNotify(st, d, t)
            }
            Jobs.log("Writing $name (%.1f GB)".format(size / 1e9))
            sticks.open(device).use { s ->
                s.disk.onNote = { Jobs.log(it) }
                logDrive(s)
                if (!kind.compressed && s.disk.sizeBytes < size) throw IOException("This drive is too small for $name.")
                try {
                    ImageWriter.writeStream(
                        { contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open $name") },
                        size, ImageKind.decoder(kind), s.disk, null, stage, Jobs::log, { Jobs.cancelRequested },
                        verify = !Labs.skipCheck(this),
                    )
                } catch (e: CorruptImageException) {
                    throw IOException("$name is damaged or isn't a compressed disk image. Download it again.")
                }
            }
            Jobs.finish(Outcome.OK, "")
            done("$name is on the drive")
        } catch (e: CancelledException) {
            Jobs.finish(Outcome.CANCELLED, listOfNotNull(Jobs.cancelReason, "The drive is unfinished, so write it again before booting from it.").joinToString(" "))
            done("Stopped")
        } catch (e: Exception) {
            Jobs.log("${e.javaClass.simpleName}: ${e.message}")
            Jobs.finish(Outcome.ERROR, e.message ?: e.javaClass.simpleName)
            done("The drive wasn't finished")
        } finally {
            if (wake.isHeld) wake.release()
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    /** Format drive (plain exFAT) or install Ventoy. */
    private fun runTool(tool: String, stickKey: String, secureBoot: Boolean) {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muninn:write")
        wake.acquire(60 * 60 * 1000L)
        try {
            val sticks = UsbSticks(this)
            val stage = { st: Stage, d: Long, t: Long ->
                Jobs.progress(st, d, t)
                maybeNotify(st, d, t)
            }
            val cancel = { Jobs.cancelRequested }
            val ventoy = if (tool == VENTOY_ID) {
                val dl = Downloader(isoDir(this), readKey = { "" }, userAgent = "Muninn/${BuildConfigCompat.versionName(this)}")
                Ventoy.fetchLatest(dl, File(isoDir(this), "ventoy"), stage, Jobs::log, cancel)
            } else {
                null
            }
            val device = sticks.device(stickKey) ?: throw IOException("The USB drive was unplugged.")
            sticks.open(device).use { s ->
                s.disk.onNote = { Jobs.log(it) }
                logDrive(s)
                if (ventoy != null) {
                    Ventoy.install(s.disk, ventoy.second, stage, Jobs::log, cancel, secureBoot = secureBoot)
                } else {
                    PlainDrive.format(s.disk, stage = stage, log = Jobs::log, cancel = cancel)
                }
            }
            Jobs.finish(
                Outcome.OK, ventoy?.first?.version ?: "",
                if (ventoy != null) setOf(if (secureBoot) FLAG_SB_ON else FLAG_SB_OFF) else emptySet(),
            )
            done(if (ventoy != null) "Ventoy ${ventoy.first.version} is installed" else "The drive is formatted")
        } catch (e: CancelledException) {
            Jobs.finish(Outcome.CANCELLED, "Stopped. The drive is unfinished; run it again before using the drive.")
            done("Stopped")
        } catch (e: Exception) {
            Jobs.log("${e.javaClass.simpleName}: ${e.message}")
            Jobs.finish(Outcome.ERROR, e.message ?: e.javaClass.simpleName)
            done("The drive wasn't finished")
        } finally {
            if (wake.isHeld) wake.release()
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    /** Copies picked images onto a Ventoy drive's image partition (nothing is erased). */
    private fun runImport(uris: List<Uri>, stickKey: String) {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muninn:write")
        wake.acquire(4 * 60 * 60 * 1000L)
        try {
            if (uris.isEmpty()) throw IOException("No files were picked.")
            val stage = { st: Stage, d: Long, t: Long ->
                Jobs.progress(st, d, t)
                maybeNotify(st, d, t)
            }
            val cancel = { Jobs.cancelRequested }
            val sources = uris.map { u ->
                val (name, size) = describeFile(this, u)
                if (size < 0) throw IOException("Couldn't read the size of $name. Copy it to the phone's storage and try again.")
                VentoyImport.Source(name, size) { contentResolver.openInputStream(u) ?: throw IOException("Couldn't open $name") }
            }
            // Learn what each file is first (a quick look at its start), so a file that can't be added stops it early.
            val plans = sources.map { src ->
                VentoyImport.plan(src).also {
                    Jobs.log(
                        "${src.name}: ${it.content.label}, " + if (it.unpacks) {
                            "unpacked once, straight onto the drive, as ${it.targetName}"
                        } else {
                            "%.2f GB, copied as ${it.targetName}".format(it.targetSize / 1e9)
                        },
                    )
                    if (!it.bootable) Jobs.log("Note: Ventoy lists only .iso, .img, .wim, .vhd, .vhdx, .efi and .vtoy files, so ${it.targetName} won't show in its menu")
                }
            }
            val device = UsbSticks(this).device(stickKey) ?: throw IOException("The USB drive was unplugged.")
            val added = UsbSticks(this).open(device).use { s ->
                s.disk.onNote = { Jobs.log(it) }
                val vd = VentoyDrive.detect(s.disk) ?: throw IOException("This drive doesn't have Ventoy on it any more.")
                val vol = vd.openData(s.disk)
                Jobs.log("Ventoy partition: %.1f GB free".format(vol.freeBytes / 1e9))
                VentoyImport.run(vol, plans, stage, Jobs::log, cancel, verify = !Labs.skipCheck(this))
            }
            Jobs.finish(Outcome.OK, added.joinToString("\n"), setOf(FLAG_IMPORT))
            done(if (added.size == 1) "${added[0]} is on the Ventoy drive" else "${added.size} images are on the Ventoy drive")
        } catch (e: CancelledException) {
            Jobs.finish(
                Outcome.CANCELLED,
                "Stopped. Files already copied are on the drive; the one being copied wasn't added.", setOf(FLAG_IMPORT),
            )
            done("Stopped")
        } catch (e: Exception) {
            Jobs.log("${e.javaClass.simpleName}: ${e.message}")
            Jobs.finish(Outcome.ERROR, e.message ?: e.javaClass.simpleName, setOf(FLAG_IMPORT))
            done("The images weren't added")
        } finally {
            if (wake.isHeld) wake.release()
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    /** Downloads a catalog system, then writes it over the whole drive, or adds it to Ventoy when [toVentoy]. */
    private fun run(distroId: String, stickKey: String, toVentoy: Boolean = false) {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muninn:write")
        wake.acquire(4 * 60 * 60 * 1000L)
        // Keeps Wi-Fi at full speed with the screen off; without it some phones throttle or drop it.
        @Suppress("DEPRECATION")
        val wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Muninn:download")
            .apply { setReferenceCounted(false) }
        try {
            wifi.acquire()
        } catch (e: Exception) {
            Jobs.log("No Wi-Fi lock: ${e.message}")
        }
        try {
            val distro = catalog(this).first { it.id == distroId }
            val sticks = UsbSticks(this)
            val cancel = { Jobs.cancelRequested }
            val stage = { s: Stage, d: Long, t: Long ->
                Jobs.progress(s, d, t)
                maybeNotify(s, d, t)
            }

            // Check the stick before spending time on a big download.
            val device = sticks.device(stickKey) ?: throw IOException("The USB drive was unplugged.")
            sticks.open(device).use { s ->
                s.disk.onNote = { Jobs.log(it) }
                logDrive(s)
                if (toVentoy) {
                    if (VentoyDrive.detect(s.disk) == null) throw IOException("This drive doesn't have Ventoy on it. Install Ventoy first.")
                } else if (s.disk.sizeBytes < distro.minDriveBytes) {
                    throw IOException("This drive is too small. ${distro.name} needs ${distro.minDriveGb} GB or more.")
                }
            }

            val downloader = Downloader(
                isoDir(this),
                readKey = { path -> assets.open(path).bufferedReader().use { it.readText() } },
                userAgent = "Muninn/${BuildConfigCompat.versionName(this)}",
            )
            // Ventoy gets a finished, checked file, so it's never written while downloading.
            val img = downloader.prepare(distro, stage, Jobs::log, cancel, stream = !toVentoy && Labs.streamWrite(this))
            if (cancel()) throw CancelledException()
            if (toVentoy) {
                addToVentoy(distro, img.file, stickKey, stage, cancel)
                return
            }
            val live = img.live

            val again = sticks.device(stickKey)
                ?: throw IOException("The USB drive was unplugged during the download. Plug it back in and try again.")
            sticks.open(again).use { s ->
                s.disk.onNote = { Jobs.log(it) }
                logDrive(s)
                try {
                    // Compressed disk images have no published checksum: bzip2's block CRCs and
                    // gzip's CRC catch a damaged download. bzip2 unpacks on every core.
                    val decode = decoderFor(distro)
                    val open = live?.let { l -> { l.open() } } ?: { img.file.inputStream() }
                    val rawSize = live?.total ?: img.file.length()
                    if (decode == null && s.disk.sizeBytes < rawSize) throw WriteException("This drive is too small for the image.")
                    ImageWriter.writeStream(
                        open, rawSize, decode ?: { it }, s.disk,
                        // A live download is checked once it's all in, below.
                        if (decode == null && live == null) img.sha256 else null,
                        stage, Jobs::log, cancel, verify = !Labs.skipCheck(this),
                    )
                    live?.finish(Jobs::log)
                } catch (e: CorruptImageException) {
                    Jobs.log(e.message ?: "")
                    img.file.delete() // fetch a fresh copy next time
                    throw IOException("The download was corrupted, so it was deleted. Try again.")
                }
            }
            Jobs.finish(Outcome.OK, "")
            done("${distro.name} is ready to boot")
        } catch (e: CancelledException) {
            val wasWriting = (Jobs.state.value as? JobState.Running)?.stage?.let { it >= Stage.WRITE } == true
            Jobs.finish(
                Outcome.CANCELLED,
                listOfNotNull(
                    Jobs.cancelReason,
                    if (wasWriting && toVentoy) {
                        "It wasn't added to the Ventoy drive. Everything else on the drive is as it was."
                    } else if (wasWriting) {
                        "The drive is unfinished, so create it again before booting from it."
                    } else {
                        "Download paused. It picks up where it left off next time."
                    },
                ).joinToString(" "),
            )
            done("Stopped")
        } catch (e: Exception) {
            Jobs.log("${e.javaClass.simpleName}: ${e.message}")
            val networkError = e is java.net.SocketException || e is java.net.SocketTimeoutException ||
                e is java.net.UnknownHostException || e is javax.net.ssl.SSLException ||
                e.message == "Connection closed early" ||
                (e is DownloadException && e.message?.startsWith("Couldn't reach") == true)
            Jobs.finish(Outcome.ERROR, Preflight.explain(e.message ?: e.javaClass.simpleName, Jobs.leftDuringJob, networkError))
            done("The drive wasn't finished")
        } finally {
            if (wifi.isHeld) wifi.release()
            if (wake.isHeld) wake.release()
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    /** Copies a downloaded catalog image onto a Ventoy drive (zips unpacked on the way); nothing else on it changes. */
    private fun addToVentoy(distro: Distro, file: File, stickKey: String, stage: (Stage, Long, Long) -> Unit, cancel: () -> Boolean) {
        val plan = VentoyImport.plan(VentoyImport.Source(file.name, file.length()) { file.inputStream() })
        if (!plan.bootable) Jobs.log("Note: Ventoy won't list ${plan.targetName} in its menu")
        val sticks = UsbSticks(this)
        val device = sticks.device(stickKey)
            ?: throw IOException("The USB drive was unplugged during the download. Plug it back in and try again.")
        val added = sticks.open(device).use { s ->
            s.disk.onNote = { Jobs.log(it) }
            logDrive(s)
            val vd = VentoyDrive.detect(s.disk) ?: throw IOException("This drive doesn't have Ventoy on it any more.")
            val vol = vd.openData(s.disk)
            Jobs.log("Ventoy partition: %.1f GB free".format(vol.freeBytes / 1e9))
            VentoyImport.run(vol, listOf(plan), stage, Jobs::log, cancel, verify = !Labs.skipCheck(this))
        }
        Jobs.finish(Outcome.OK, added.joinToString("\n"), setOf(FLAG_IMPORT))
        done("${distro.name} is on the Ventoy drive")
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(text: String, done: Long, total: Long): Notification {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setProgress(100, pct, total <= 0)
            .build()
    }

    private fun maybeNotify(s: Stage, d: Long, t: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 1000) return
        lastNotify = now
        // The same overall percentage and step as the app's screen.
        val job = Jobs.state.value as? JobState.Running ?: return
        val pct = (job.fraction * 100).toInt().coerceIn(0, 100)
        val text = "$pct%  " + Progress.describe(s, d, t, 0, job.distro, local = job.local).first
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification(text, pct.toLong(), 100))
    }

    private fun done(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n)
    }

    private fun logDrive(s: io.github.ravenor907.muninn.forge.usb.OpenStick) {
        Jobs.log("Drive: ${s.disk.name}, ${"%.1f".format(s.disk.sizeBytes / 1e9)} GB, connected at ${s.link}")
    }

    /** Labs: measures a drive's real speed and capacity (erases it), then formats it for normal use. */
    private fun runTest(stickKey: String) {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muninn:test")
        wake.acquire(4 * 60 * 60 * 1000L)
        try {
            val stage = { st: Stage, d: Long, t: Long ->
                Jobs.progress(st, d, t)
                maybeNotify(st, d, t)
            }
            val cancel = { Jobs.cancelRequested }
            val sticks = UsbSticks(this)
            val device = sticks.device(stickKey) ?: throw IOException("The USB drive was unplugged.")
            val report = sticks.open(device).use { s ->
                s.disk.onNote = { Jobs.log(it) }
                logDrive(s)
                val r = DriveTest.run(s.disk, stage, Jobs::log, cancel)
                r.lines().forEach { Jobs.log(it) }
                Jobs.log("Formatting the drive so it's usable again")
                PlainDrive.format(s.disk, stage = { _, _, _ -> }, log = Jobs::log, cancel = cancel)
                r to s.link
            }
            val (r, link) = report
            val flags = setOf(FLAG_TEST) + if (r.fake || r.failing) setOf(FLAG_TEST_BAD) else emptySet()
            Jobs.finish(Outcome.OK, (r.lines() + "Connected at $link").joinToString("\n"), flags)
            done(if (r.fake || r.failing) "This drive failed the test" else "Drive test done")
        } catch (e: CancelledException) {
            Jobs.finish(Outcome.CANCELLED, "Stopped. The drive holds test data now; use Format drive to make it usable.", setOf(FLAG_TEST))
            done("Stopped")
        } catch (e: Exception) {
            Jobs.log("${e.javaClass.simpleName}: ${e.message}")
            Jobs.finish(Outcome.ERROR, e.message ?: e.javaClass.simpleName, setOf(FLAG_TEST))
            done("The test didn't finish")
        } finally {
            if (wake.isHeld) wake.release()
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    /** Labs: compares a drive with the image it was made from, writing nothing. */
    private fun runCheck(distroId: String?, uri: Uri?, stickKey: String) {
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muninn:check")
        wake.acquire(4 * 60 * 60 * 1000L)
        var what = "the image"
        try {
            val stage = { st: Stage, d: Long, t: Long ->
                Jobs.progress(st, d, t)
                maybeNotify(st, d, t)
            }
            val cancel = { Jobs.cancelRequested }
            val (open, rawSize, decode) = if (uri != null) {
                val (name, size) = describeFile(this, uri)
                what = name
                val kind = sniffFile(this, uri)
                if (!kind.writable) throw IOException("$name is a ${kind.label} file, which can't be read here.")
                Triple({ contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open $name") }, size, ImageKind.decoder(kind))
            } else {
                val distro = catalog(this).first { it.id == distroId }
                what = distro.name
                val downloader = Downloader(
                    isoDir(this),
                    readKey = { path -> assets.open(path).bufferedReader().use { it.readText() } },
                    userAgent = "Muninn/${BuildConfigCompat.versionName(this)}",
                )
                val img = downloader.prepare(distro, stage, Jobs::log, cancel)
                Triple({ img.file.inputStream() }, img.file.length(), decoderFor(distro) ?: { it })
            }
            val sticks = UsbSticks(this)
            val device = sticks.device(stickKey) ?: throw IOException("The USB drive was unplugged.")
            val diff = sticks.open(device).use { s ->
                s.disk.onNote = { Jobs.log(it) }
                logDrive(s)
                Jobs.log("Comparing the drive with $what")
                ImageWriter.compare(open, rawSize, decode, s.disk, stage, Jobs::log, cancel)
            }
            if (diff == null) {
                Jobs.finish(Outcome.OK, "Every byte of $what matches what's on the drive.", setOf(FLAG_CHECK))
                done("The drive matches $what")
            } else {
                Jobs.finish(
                    Outcome.ERROR,
                    "The drive doesn't match $what: they differ from %.2f GB in. Create the drive again. ".format(diff / 1e9) +
                        "(If $what has been updated since the drive was made, a difference is expected.)",
                    setOf(FLAG_CHECK),
                )
                done("The drive doesn't match")
            }
        } catch (e: CancelledException) {
            Jobs.finish(Outcome.CANCELLED, "Stopped. Nothing on the drive was changed.", setOf(FLAG_CHECK))
            done("Stopped")
        } catch (e: Exception) {
            Jobs.log("${e.javaClass.simpleName}: ${e.message}")
            Jobs.finish(Outcome.ERROR, e.message ?: e.javaClass.simpleName, setOf(FLAG_CHECK))
            done("The check didn't finish")
        } finally {
            if (wake.isHeld) wake.release()
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    companion object {
        const val TEST_ID = "drive-test"
        const val CHECK_ID = "drive-check"
        const val EXTRA_CHECK_DISTRO = "checkDistro"
        const val FLAG_TEST = "drive-test"
        const val FLAG_TEST_BAD = "drive-test-bad"
        const val FLAG_CHECK = "drive-check"

        /** How a catalog system's image is unpacked while writing; null for an ISO written as it is. */
        fun decoderFor(distro: Distro): ((java.io.InputStream) -> java.io.InputStream)? = when (distro.format) {
            "raw-bz2" -> ImageKind.decoder(ImageKind.BZIP2)
            "raw-gz" -> ImageKind.decoder(ImageKind.GZIP)
            "zip" -> ImageKind.decoder(ImageKind.ZIP)
            else -> null
        }

        /** Labs: test a drive's speed and capacity (erases it). */
        fun startTest(context: Context, stickKey: String, stickLabel: String) {
            Jobs.begin("Drive test", stickLabel, local = true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, ForgeService::class.java).putExtra(EXTRA_DISTRO, TEST_ID).putExtra(EXTRA_STICK, stickKey),
            )
        }

        /** Labs: compare a drive with a catalog system's image or a picked file. */
        fun startCheck(context: Context, name: String, distroId: String?, uri: Uri?, stickKey: String, stickLabel: String) {
            Jobs.begin("Check against $name", stickLabel, local = uri != null)
            val i = Intent(context, ForgeService::class.java).putExtra(EXTRA_DISTRO, CHECK_ID).putExtra(EXTRA_STICK, stickKey)
            if (distroId != null) i.putExtra(EXTRA_CHECK_DISTRO, distroId)
            if (uri != null) i.putExtra(EXTRA_URI, uri.toString()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(context, i)
        }

        const val EXTRA_DISTRO = "distro"
        const val EXTRA_STICK = "stick"
        const val EXTRA_URI = "uri"
        const val CUSTOM_ID = "custom-file"
        const val FORMAT_ID = "format-drive"
        const val VENTOY_ID = "ventoy"
        const val IMPORT_ID = "ventoy-import"
        const val EXTRA_SECURE_BOOT = "secureBoot"
        const val EXTRA_URIS = "uris"
        const val EXTRA_TO_VENTOY = "toVentoy"
        const val FLAG_SB_ON = "secureboot-on"
        const val FLAG_SB_OFF = "secureboot-off"
        const val FLAG_IMPORT = "import"

        /** Add images to a Ventoy drive. Local job: no download steps. */
        fun startImport(context: Context, uris: List<Uri>, stickKey: String, stickLabel: String) {
            Jobs.begin(if (uris.size == 1) "1 image" else "${uris.size} images", stickLabel, local = true)
            val i = Intent(context, ForgeService::class.java)
                .putExtra(EXTRA_DISTRO, IMPORT_ID)
                .putExtra(EXTRA_STICK, stickKey)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(context, i)
        }

        /** Format drive or Ventoy. Ventoy downloads its latest release first; formatting is local. */
        fun startTool(context: Context, tool: String, name: String, stickKey: String, stickLabel: String, secureBoot: Boolean = true) {
            Jobs.begin(name, stickLabel, local = tool == FORMAT_ID)
            val i = Intent(context, ForgeService::class.java)
                .putExtra(EXTRA_DISTRO, tool)
                .putExtra(EXTRA_STICK, stickKey)
                .putExtra(EXTRA_SECURE_BOOT, secureBoot)
            ContextCompat.startForegroundService(context, i)
        }

        /**
         * Every file is pickable. Phones label ISOs and images inconsistently (and greyed-out
         * files can't be tapped), so the file's own bytes decide what it is, not its type.
         */
        val PICK_TYPES = arrayOf("*/*")

        /** Reads the start of a picked file to see what kind of image it is. */
        fun sniffFile(context: Context, uri: Uri): ImageKind {
            val head = ByteArray(ImageKind.SNIFF_BYTES)
            var n = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                while (n < head.size) {
                    val r = input.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
            } ?: throw IOException("Couldn't open the file")
            return ImageKind.sniff(head.copyOf(n))
        }

        /** (display name, size in bytes) of a picked file. */
        fun describeFile(context: Context, uri: Uri): Pair<String, Long> {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            return name to size
        }

        fun startFile(context: Context, uri: Uri, name: String, stickKey: String, stickLabel: String) {
            Jobs.begin(name, stickLabel, local = true)
            val i = Intent(context, ForgeService::class.java)
                .putExtra(EXTRA_DISTRO, CUSTOM_ID)
                .putExtra(EXTRA_STICK, stickKey)
                .putExtra(EXTRA_URI, uri.toString())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(context, i)
        }
        private const val CHANNEL = "forge"
        private const val NOTIFY_ID = 1

        /** Download a catalog system and write it over the drive, or with [toVentoy] add it to a Ventoy drive. */
        fun start(context: Context, distro: Distro, stickKey: String, stickLabel: String, toVentoy: Boolean = false) {
            Jobs.begin(distro.name, stickLabel)
            val i = Intent(context, ForgeService::class.java)
                .putExtra(EXTRA_DISTRO, distro.id)
                .putExtra(EXTRA_STICK, stickKey)
                .putExtra(EXTRA_TO_VENTOY, toVentoy)
            ContextCompat.startForegroundService(context, i)
        }

        private fun catalogText(context: Context) = context.assets.open("distros.json").bufferedReader().use { it.readText() }

        /**
         * The app's own "Useful ISOs" (desktop systems and tools), kept out of the shared distros.json.
         * Empty if the file is missing or can't be read, so the regular systems always load.
         */
        private fun usefulIsos(context: Context, taken: Set<String>): List<Distro> = try {
            Catalog.parse(context.assets.open("isos.json").bufferedReader().use { it.readText() }).filter { it.id !in taken }
        } catch (e: Exception) {
            emptyList()
        }

        /** The regular systems, the useful ISOs, then any experimental ones (hidden until unlocked; see [Labs]). */
        fun catalog(context: Context): List<Distro> {
            val main = Catalog.parse(catalogText(context))
            val more = main + usefulIsos(context, main.map { it.id }.toSet())
            return more + Labs.catalog(context, more.map { it.id }.toSet())
        }

        fun bootMenu(context: Context): List<String> = Catalog.bootMenu(catalogText(context))

        fun isoDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "iso")
    }
}

/** InputStream.readNBytes needs Android 13; this works everywhere. */
/** Version name without generating BuildConfig. */
object BuildConfigCompat {
    fun versionName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
