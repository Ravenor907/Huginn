package io.github.ravenor907.muninn.forge.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.forge.core.BulkOnly
import io.github.ravenor907.muninn.forge.core.ScsiDisk
import io.github.ravenor907.muninn.forge.core.UsbTransport
import java.io.Closeable
import java.io.IOException

/** A plugged-in USB stick as the UI sees it. */
data class Stick(
    val key: String, // UsbDevice.deviceName, e.g. /dev/bus/usb/001/004
    val label: String,
    val hasPermission: Boolean,
    val sizeBytes: Long?,
    /** The drive already has Ventoy on it (images can be added without erasing). */
    val ventoy: Boolean = false,
)

/** Android's USB host API wired to the protocol code in core/. */
class AndroidUsbTransport(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : UsbTransport, Closeable {
    // Before Android 9, a single bulk transfer was capped at 16 KiB.
    override val maxTransfer: Int = if (Build.VERSION.SDK_INT >= 28) 64 * 1024 else 16 * 1024


    override val canPipeline: Boolean get() = true

    /**
     * Keeps [DEPTH] transfers queued with UsbRequest, so the next piece is already waiting when the
     * stick finishes one. USB completes requests on an endpoint in order, so data lands in order.
     */
    private fun stream(ep: UsbEndpoint, data: ByteArray, offset: Int, length: Int, piece: Int, timeoutMs: Int, input: Boolean): Int {
        val reqs = ArrayList<UsbRequest>()
        val bufs = HashMap<UsbRequest, ByteBuffer>()
        val starts = HashMap<UsbRequest, Int>()
        val sizes = HashMap<UsbRequest, Int>()
        try {
            repeat(DEPTH) {
                val r = UsbRequest()
                if (!r.initialize(conn, ep)) return -1
                reqs.add(r)
                bufs[r] = ByteBuffer.allocateDirect(piece)
            }
            var queued = 0
            var moved = 0
            var inFlight = 0
            var ended = false
            fun queue(r: UsbRequest): Boolean {
                val n = minOf(piece, length - queued)
                if (n <= 0 || ended) return true
                val b = bufs.getValue(r)
                b.clear()
                if (!input) b.put(data, offset + queued, n)
                b.flip()
                if (input) b.limit(n)
                starts[r] = queued
                sizes[r] = n
                if (!r.queue(b)) return false
                queued += n
                inFlight++
                return true
            }
            for (r in reqs) if (!queue(r)) return -1
            while (inFlight > 0) {
                val done = try {
                    conn.requestWait(timeoutMs.toLong())
                } catch (e: java.util.concurrent.TimeoutException) {
                    null
                } ?: return -1
                inFlight--
                val b = bufs[done] ?: return -1
                val n = sizes.getValue(done)
                val got = if (input) b.position() else n
                if (input) {
                    b.flip()
                    b.get(data, offset + starts.getValue(done), got)
                    if (got < n) ended = true // short packet: the drive sent everything
                }
                moved += got
                if (!queue(done)) return -1
            }
            return moved
        } finally {
            for (r in reqs) {
                try {
                    r.cancel()
                } catch (_: Exception) {
                }
                r.close()
            }
        }
    }

    override fun streamOut(data: ByteArray, offset: Int, length: Int, piece: Int, timeoutMs: Int): Int =
        stream(outEp, data, offset, length, piece, timeoutMs, input = false)

    override fun streamIn(data: ByteArray, offset: Int, length: Int, piece: Int, timeoutMs: Int): Int =
        stream(inEp, data, offset, length, piece, timeoutMs, input = true)

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        conn.bulkTransfer(outEp, data, offset, length, timeoutMs)

    override fun bulkIn(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        conn.bulkTransfer(inEp, data, offset, length, timeoutMs)

    override fun resetRecovery() {
        // Bulk-Only Mass Storage Reset, then CLEAR_FEATURE(ENDPOINT_HALT) on both pipes.
        conn.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, 5000)
        conn.controlTransfer(0x02, 0x01, 0, inEp.address, null, 0, 5000)
        conn.controlTransfer(0x02, 0x01, 0, outEp.address, null, 0, 5000)
    }

    companion object {
        /** Transfers kept in flight; enough to hide the phone's turnaround without hogging memory. */
        const val DEPTH = 8
    }

    override fun close() {
        try {
            conn.releaseInterface(intf)
        } finally {
            conn.close()
        }
    }
}

class OpenStick(
    val disk: ScsiDisk,
    private val transport: AndroidUsbTransport,
    /** The USB speed the drive is connected at, from its bulk endpoint's packet size. */
    val link: String = "unknown speed",
) : Closeable {
    override fun close() = transport.close()
}

class UsbSticks(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sizes = HashMap<String, Long>()
    private val ventoys = HashMap<String, Boolean>()

    fun devices(): List<UsbDevice> = manager.deviceList.values.filter { massStorage(it) != null }

    fun device(key: String): UsbDevice? = manager.deviceList[key]

    fun hasPermission(d: UsbDevice) = manager.hasPermission(d)

    fun label(d: UsbDevice): String {
        val parts = listOfNotNull(d.manufacturerName?.trim(), d.productName?.trim()).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "USB drive" else parts.distinct().joinToString(" ")
    }

    /** Current sticks; sizes are probed once per stick (needs permission). */
    fun list(probe: Boolean): List<Stick> = devices().map { d ->
        val ok = hasPermission(d)
        var size = sizes[d.deviceName]
        if (ok && size == null && probe) {
            try {
                open(d).use {
                    size = it.disk.sizeBytes
                    ventoys[d.deviceName] = io.github.ravenor907.muninn.forge.core.VentoyDrive.detect(it.disk) != null
                }
            } catch (e: Exception) {
                size = null
            }
            size?.let { sizes[d.deviceName] = it }
        }
        Stick(d.deviceName, label(d), ok, size, ventoys[d.deviceName] == true)
    }

    /** Forget what was learned about the drives (after a job changed one), so the next list re-reads them. */
    fun forget() {
        sizes.clear()
        ventoys.clear()
    }

    fun requestPermission(d: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        manager.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    /** Calls [onResult] whenever the user answers a permission request. */
    fun registerPermissionReceiver(onResult: () -> Unit): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onResult()
        }
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        return r
    }

    fun open(d: UsbDevice): OpenStick {
        val intf = massStorage(d) ?: throw IOException("That device isn't a USB drive")
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        if (inEp == null || outEp == null) throw IOException("The drive has no bulk endpoints")
        val conn = manager.openDevice(d) ?: throw IOException("Android wouldn't open the drive. Unplug it and try again.")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("The drive is busy. Eject it in Android's storage settings, then try again.")
        }
        val t = AndroidUsbTransport(conn, intf, inEp, outEp)
        return try {
            OpenStick(ScsiDisk(BulkOnly(t)).init(), t, linkSpeed(outEp.maxPacketSize))
        } catch (e: Exception) {
            t.close()
            throw e
        }
    }

    companion object {
        /** Bulk packets are 1024 bytes at USB 3 speeds, 512 at USB 2 (480 Mbit/s), 64 at USB 1. */
        fun linkSpeed(maxPacket: Int): String = when {
            maxPacket >= 1024 -> "USB 3 speed"
            maxPacket >= 512 -> "USB 2 speed (480 Mbit/s, about 40 MB/s at best)"
            else -> "USB 1 speed (very slow; try another port or adapter)"
        }

        const val ACTION_PERMISSION = "io.github.ravenor907.muninn.forge.USB_PERMISSION"

        fun massStorage(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50
            }
    }
}
