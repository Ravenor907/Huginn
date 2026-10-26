package io.github.ravenor907.muninn.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.files.Files
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Where the browser is and what's picked; shared by Files and the send picker. */
class BrowserState(start: File) {
    var dir by mutableStateOf(start)
    var all by mutableStateOf(false)
    var query by mutableStateOf("")
    var refresh by mutableIntStateOf(0)
    val picked = SnapshotStateList<File>()

    fun toggle(f: File) { if (picked.contains(f)) picked.remove(f) else picked.add(f) }
    fun root(): File = if (all) Files.home else Files.downloads
}

/**
 * A folder listing that opens on Downloads, with Browse all for the whole phone and a search box
 * that looks through every folder under where you are. Tap opens folders and picks files;
 * long-press picks folders too.
 */
@Composable
fun FileBrowser(s: BrowserState, modifier: Modifier = Modifier, pickFolders: Boolean = true, onOpenFile: ((File) -> Unit)? = null) {
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(s.dir, s.query, s.refresh) {
        if (s.query.isBlank()) {
            entries = withContext(Dispatchers.IO) { Files.list(s.dir) }
        } else {
            searching = true
            val q = s.query
            val found = withContext(Dispatchers.IO) { Files.search(s.dir, q) { q != s.query } }
            if (q == s.query) entries = found
            searching = false
        }
    }
    BackHandler(enabled = s.query.isNotEmpty() || s.dir.absolutePath != s.root().absolutePath) {
        when {
            s.query.isNotEmpty() -> s.query = ""
            else -> s.dir.parentFile?.let { s.dir = it }
        }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton(if (s.all) "Downloads" else "Browse all", Modifier.weight(1f), icon = if (s.all) "download" else "folder") {
                s.all = !s.all; s.query = ""; s.dir = s.root()
            }
            if (s.dir.absolutePath != s.root().absolutePath) MButton("Up", icon = "back") { s.dir.parentFile?.let { s.dir = it } }
        }
        Field(s.query, { s.query = it }, "Search ${if (s.dir == Files.home) "the whole phone" else s.dir.name}", Modifier.padding(vertical = 8.dp))
        Text(
            when {
                searching -> "Searching…"
                s.query.isNotBlank() -> "${entries.size}${if (entries.size >= 300) "+" else ""} found under ${s.dir.name}"
                else -> s.dir.absolutePath.removePrefix(Files.home.absolutePath).ifEmpty { "/" }.let { "Phone$it" }
            },
            style = Type.small, modifier = Modifier.padding(bottom = 4.dp),
        )
        if (entries.isEmpty() && !searching) Text(if (s.query.isBlank()) "This folder is empty." else "Nothing matches.", style = Type.normal, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.absolutePath }) { f ->
                val meta = if (f.isDirectory) "Folder" else LocalSend.size(f.length()) + " · " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(f.lastModified()))
                Line(
                    f.name,
                    if (s.query.isNotBlank()) (f.parentFile?.absolutePath?.removePrefix(Files.home.absolutePath) ?: "") else meta,
                    icon = if (s.picked.contains(f)) "check" else Files.kind(f),
                    trailing = if (s.query.isNotBlank()) meta.substringBefore(" ·") else "",
                    selected = s.picked.contains(f),
                    onLong = { if (pickFolders || !f.isDirectory) s.toggle(f) },
                ) {
                    when {
                        f.isDirectory && s.picked.isEmpty() -> { s.query = ""; s.dir = f }
                        f.isDirectory -> if (pickFolders) s.toggle(f) else { s.query = ""; s.dir = f }
                        s.picked.isEmpty() && onOpenFile != null -> onOpenFile(f)
                        else -> s.toggle(f)
                    }
                }
            }
        }
        if (s.picked.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${s.picked.size} picked · ${LocalSend.size(s.picked.sumOf { if (it.isFile) it.length() else 0L })}", style = Type.small.copy(color = io.github.ravenor907.muninn.ui.Palette.accent), modifier = Modifier.weight(1f).padding(top = 14.dp))
                MButton("Clear", kind = Kind.Normal) { s.picked.clear() }
            }
        }
    }
}
