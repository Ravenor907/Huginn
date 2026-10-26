package io.github.ravenor907.muninn.files

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * File work for Muninn, with Huginn's safety rules: copies and moves never overwrite (a clashing
 * name gets " (2)"), deleting goes to a trash folder on the same storage so it can be undone,
 * extracting never replaces files, and the storage roots themselves can't be deleted or moved.
 */
object Files {
    val home: File get() = Environment.getExternalStorageDirectory()
    val downloads: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    data class Volume(val name: String, val root: File, val free: Long, val total: Long)

    fun volumes(context: Context): List<Volume> {
        val out = mutableListOf<Volume>()
        val sm = context.getSystemService(StorageManager::class.java)
        val roots = mutableListOf(home)
        context.getExternalFilesDirs(null).filterNotNull().forEach { f ->
            val root = f.absolutePath.substringBefore("/Android/").let(::File)
            if (roots.none { it.absolutePath == root.absolutePath }) roots.add(root)
        }
        for (r in roots) {
            try {
                val st = StatFs(r.absolutePath)
                val name = if (r == home) "Phone" else sm?.getStorageVolume(r)?.getDescription(context) ?: "SD card"
                out.add(Volume(name, r, st.availableBytes, st.totalBytes))
            } catch (_: Exception) {}
        }
        return out
    }

    fun list(dir: File, hidden: Boolean = false): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { hidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

    /** Files under root whose names contain the text, nearest first. Stops at limit. */
    fun search(root: File, text: String, limit: Int = 300, stop: () -> Boolean = { false }): List<File> {
        val q = text.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<File>()
        val queue = ArrayDeque<File>().apply { add(root) }
        while (queue.isNotEmpty() && out.size < limit && !stop()) {
            val d = queue.removeFirst()
            for (f in d.listFiles() ?: continue) {
                if (f.name.startsWith(".")) continue
                if (f.name.lowercase().contains(q)) out.add(f)
                if (f.isDirectory && f.absolutePath != File(home, "Android").absolutePath) queue.add(f)
                if (out.size >= limit) break
            }
        }
        return out
    }

    fun kind(f: File): String {
        if (f.isDirectory) return "folder"
        return when (f.extension.lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> "image"
            "mp4", "mkv", "webm", "mov", "avi" -> "video"
            "mp3", "flac", "ogg", "wav", "m4a", "opus" -> "audio"
            "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst" -> if (f.name.lowercase().endsWith(".img.bz2") || f.name.lowercase().endsWith(".img.xz") || f.name.lowercase().endsWith(".img.gz")) "disc" else "archive"
            "iso", "img", "raw" -> "disc"
            "txt", "ini", "cfg", "conf", "json", "xml", "md", "log", "yml", "yaml" -> "text"
            else -> "file"
        }
    }

    fun isImage(f: File) = kind(f) == "disc"

    private fun isRoot(f: File, context: Context) = volumes(context).any { it.root.absolutePath == f.absolutePath } || f.absolutePath == home.absolutePath

    private fun volumeOf(context: Context, f: File): String =
        volumes(context).map { it.root.absolutePath }.filter { f.absolutePath == it || f.absolutePath.startsWith("$it/") }.maxByOrNull { it.length } ?: ""

    fun free(dir: File, name: String): File {
        var t = File(dir, name)
        var n = 2
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.') && !name.startsWith(".")) "." + name.substringAfterLast('.') else ""
        while (t.exists()) { t = File(dir, "$base ($n)$ext"); n++ }
        return t
    }

    fun size(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { size(it) } else f.length()

    /** Copies (or moves) into dest. Never overwrites. Returns where each one went. */
    fun copy(context: Context, items: List<File>, dest: File, move: Boolean, progress: (Long, Long) -> Unit = { _, _ -> }): List<Pair<File, File>> {
        if (items.any { isRoot(it, context) }) throw IOException("Muninn won't move or copy a whole storage drive.")
        items.forEach { if (dest.absolutePath.startsWith(it.absolutePath + "/") || dest == it) throw IOException("${it.name} can't go inside itself.") }
        val total = items.sumOf { size(it) }
        val free = try { StatFs(dest.absolutePath).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
        // A move on the same storage is just a rename: it needs no room.
        val sameVolume = move && items.all { volumeOf(context, it) == volumeOf(context, dest) }
        if (!sameVolume && total + 32L * 1024 * 1024 > free)
            throw IOException("Not enough room there: this needs ${io.github.ravenor907.muninn.net.LocalSend.size(total)} and only ${io.github.ravenor907.muninn.net.LocalSend.size(free)} is free. Nothing was changed.")
        var done = 0L
        val out = mutableListOf<Pair<File, File>>()
        for (src in items) {
            val target = free(dest, src.name)
            if (move && src.renameTo(target)) { out.add(src to target); done += size(target); progress(done, total); continue }
            copyTree(src, target) { n -> done += n; progress(done, total) }
            if (move) deleteTree(src)
            out.add(src to target)
        }
        return out
    }

    private fun copyTree(src: File, dst: File, add: (Long) -> Unit) {
        if (src.isDirectory) {
            if (!dst.mkdirs() && !dst.isDirectory) throw IOException("Couldn't create ${dst.name}.")
            for (c in src.listFiles() ?: emptyArray()) copyTree(c, File(dst, c.name), add)
            return
        }
        FileInputStream(src).use { i -> FileOutputStream(dst).use { o ->
            val buf = ByteArray(1 shl 18)
            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); add(n.toLong()) }
        } }
        dst.setLastModified(src.lastModified())
    }

    fun deleteTree(f: File) { if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }; f.delete() }

    /** Moves to a hidden trash folder on the same storage: instant, and undoable. */
    fun trash(context: Context, items: List<File>): List<Pair<File, File>> {
        if (items.any { isRoot(it, context) }) throw IOException("Muninn won't delete a whole storage drive.")
        val out = mutableListOf<Pair<File, File>>()
        for (f in items) {
            val vol = volumes(context).filter { f.absolutePath.startsWith(it.root.absolutePath + "/") }.maxByOrNull { it.root.absolutePath.length }?.root ?: home
            val bin = File(vol, ".Trash-muninn/${System.currentTimeMillis()}").also { it.mkdirs() }
            val target = File(bin, f.name)
            if (!f.renameTo(target)) throw IOException("Couldn't move ${f.name} to the trash.")
            out.add(f to target)
        }
        return out
    }

    fun emptyTrash(context: Context) = volumes(context).forEach { deleteTree(File(it.root, ".Trash-muninn")) }

    fun trashSize(context: Context): Long = volumes(context).sumOf { File(it.root, ".Trash-muninn").let { t -> if (t.exists()) size(t) else 0L } }

    fun rename(f: File, name: String): File {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean == "." || clean == "..") throw IOException("That name can't be used.")
        val t = File(f.parentFile, clean)
        if (t.exists()) throw IOException("Something named $clean is already there.")
        if (!f.renameTo(t)) throw IOException("Couldn't rename ${f.name}.")
        return t
    }

    fun zip(items: List<File>, dest: File): File {
        val target = free(dest, (if (items.size == 1) items[0].nameWithoutExtension else "archive") + ".zip")
        ZipOutputStream(FileOutputStream(target)).use { z ->
            fun add(f: File, path: String) {
                if (f.isDirectory) { z.putNextEntry(ZipEntry("$path/")); z.closeEntry(); f.listFiles()?.forEach { add(it, "$path/${it.name}") }; return }
                z.putNextEntry(ZipEntry(path)); FileInputStream(f).use { it.copyTo(z) }; z.closeEntry()
            }
            items.forEach { add(it, it.name) }
        }
        return target
    }

    /** Extracts a zip. If anything at its top level is already there, it goes into a new folder. */
    fun unzip(zip: File, dest: File): File {
        val tops = mutableSetOf<String>()
        ZipInputStream(FileInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: break; tops.add(e.name.trimStart('/').substringBefore('/')) } }
        val into = if (tops.any { File(dest, it).exists() }) free(dest, zip.nameWithoutExtension).also { it.mkdirs() } else dest
        ZipInputStream(FileInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(into, e.name)
                if (!out.canonicalPath.startsWith(into.canonicalPath + File.separator) && out.canonicalPath != into.canonicalPath) continue
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { z.copyTo(it) }
            }
        }
        return into
    }
}
