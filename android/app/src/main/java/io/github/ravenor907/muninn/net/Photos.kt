package io.github.ravenor907.muninn.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Photos for chat. Re-encoding drops everything but the pixels, so location and camera details
 * never leave the phone.
 *  - Normal: 640 px on the long side, at most 60 KB. Arrives in a second or two on any route.
 *  - High quality: 2048 px, at most 2 MB. Quick close by or on Wi-Fi; slower over the internet.
 */
object Photos {
    const val PIECE = 16 * 1024
    const val MAX_PIECES = 200                   // 3.2 MB: more than the largest photo we make
    const val INTERNET_PIECES = 130              // 2 MB over the relays at most
    private const val NORMAL_SIDE = 640
    private const val NORMAL_BYTES = 60 * 1024
    private const val HD_SIDE = 2048
    private const val HD_BYTES = 2 * 1024 * 1024

    /** Null if it isn't a picture Android can open. */
    fun prepare(context: Context, uri: Uri, hd: Boolean): ByteArray? = try {
        val side = if (hd) HD_SIDE else NORMAL_SIDE
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
            val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            raw?.let { encode(upright(context, uri, scale(it, side)), if (hd) HD_BYTES else NORMAL_BYTES) }
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    private fun scale(b: Bitmap, side: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= side) return b
        val f = side.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    /** Phones store photos sideways and note the turn in the file; apply it, since that note is dropped. */
    private fun upright(context: Context, uri: Uri, b: Bitmap): Bitmap {
        val turn = try {
            context.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) { 0f }
        if (turn == 0f) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(turn) }, true)
    }

    /** Best quality that fits the size limit; shrinks further if even low quality doesn't fit. */
    private fun encode(b: Bitmap, limit: Int): ByteArray {
        var bmp = b
        while (true) {
            for (q in intArrayOf(85, 75, 65, 55, 45, 35)) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= limit) return out.toByteArray()
            }
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.75f).toInt().coerceAtLeast(1), (bmp.height * 0.75f).toInt().coerceAtLeast(1), true)
        }
    }

    fun split(data: ByteArray): List<ByteArray> = (data.indices step PIECE).map { data.copyOfRange(it, minOf(it + PIECE, data.size)) }

    /** Null unless it's a JPEG of a sensible size. */
    fun join(parts: List<ByteArray>): ByteArray? {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        val b = out.toByteArray()
        return if (b.size in 3..(MAX_PIECES * PIECE) && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) b else null
    }
}
