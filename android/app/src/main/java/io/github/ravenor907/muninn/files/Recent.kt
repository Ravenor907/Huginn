package io.github.ravenor907.muninn.net

import android.content.Context
import java.io.File

/** Files opened, received, copied or moved lately, newest first. */
object Recent {
    private const val KEY = "recent"

    fun add(context: Context, f: File) {
        val p = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
        val list = listOf(f.absolutePath + "|" + System.currentTimeMillis()) +
            p.getString(KEY, "")!!.split('\n').filter { it.isNotEmpty() && !it.startsWith(f.absolutePath + "|") }
        p.edit().putString(KEY, list.take(40).joinToString("\n")).apply()
    }

    fun list(context: Context): List<Pair<File, Long>> =
        context.getSharedPreferences("muninn", Context.MODE_PRIVATE).getString(KEY, "")!!.split('\n').filter { it.contains('|') }
            .map { File(it.substringBeforeLast('|')) to (it.substringAfterLast('|').toLongOrNull() ?: 0L) }
            .filter { it.first.exists() }
}
