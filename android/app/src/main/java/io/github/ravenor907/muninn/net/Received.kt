package io.github.ravenor907.muninn.net

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Where received files go: the phone's Downloads folder. Android 10 and newer save through MediaStore,
 * so Muninn needs no storage permission. Android 8 and 9 write into the folder with the storage
 * permission, or into Muninn's own Downloads folder if it was refused.
 */
object Received {
    /** One file being saved. Write to [out], then [finish] it, or [discard] it if it didn't arrive whole. */
    class Target internal constructor(
        /** The name it got (a clashing name gets " (2)" or similar). */
        val name: String,
        val out: OutputStream,
        private val context: Context,
        private val uri: Uri?,
        private val file: File?,
    ) {
        fun finish() {
            try { out.close() } catch (_: Exception) {}
            if (uri != null && Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                try { context.contentResolver.update(uri, v, null, null) } catch (_: Exception) {}
            }
        }

        fun discard() {
            try { out.close() } catch (_: Exception) {}
            if (uri != null) try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            file?.delete()
        }
    }

    private fun oldPhoneAllowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** True on Android 8 and 9 until the storage permission is allowed (ask before accepting a transfer). */
    fun needsPermission(context: Context) = Build.VERSION.SDK_INT < 29 && !oldPhoneAllowed(context)

    /** Where received files end up, in words for the screen. */
    fun place(context: Context): String =
        if (Build.VERSION.SDK_INT < 29 && !oldPhoneAllowed(context)) "Muninn's own Downloads folder" else "Downloads"

    /** Free space where received files are saved. */
    fun freeBytes(context: Context): Long = try {
        StatFs((context.getExternalFilesDir(null) ?: context.filesDir).absolutePath).availableBytes
    } catch (_: Exception) { Long.MAX_VALUE }

    /** [name] with " (2)", " (3)"... added until nothing in [dir] has it. */
    fun free(dir: File, name: String): File {
        var t = File(dir, name)
        var n = 2
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.') && !name.startsWith(".")) "." + name.substringAfterLast('.') else ""
        while (t.exists()) { t = File(dir, "$base ($n)$ext"); n++ }
        return t
    }

    /**
     * Starts saving a file. [path] is the name the sender gave; folders in it ("Photos/a.jpg") are kept
     * under Downloads, and nothing in it can climb out of Downloads.
     */
    fun create(context: Context, path: String): Target {
        val parts = path.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }.ifEmpty { listOf("from-phone") }
        val name = parts.last()
        val folders = parts.dropLast(1)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val ext = name.substringAfterLast('.', "").lowercase()
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, (listOf(Environment.DIRECTORY_DOWNLOADS) + folders).joinToString("/") + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("Couldn't save $name in Downloads.")
            val out = try { resolver.openOutputStream(uri) } catch (e: Exception) { null }
            if (out == null) { try { resolver.delete(uri, null, null) } catch (_: Exception) {}; throw IOException("Couldn't save $name in Downloads.") }
            // MediaStore renames a clash itself ("a (1).jpg"): show the name it really got.
            val given = try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) { null }
            return Target(given ?: name, out, context, uri, null)
        }
        val root = if (oldPhoneAllowed(context)) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "Download")
        val dir = if (folders.isEmpty()) root else File(root, folders.joinToString("/"))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't make a folder for $name.")
        val target = free(dir, name)
        return Target(target.name, FileOutputStream(target), context, null, target)
    }
}
