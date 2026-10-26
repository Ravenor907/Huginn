package io.github.ravenor907.muninn.screens

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.files.Files
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.net.Recent
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FilesScreen(app: Muninn, onSend: (List<File>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember { BrowserState(Files.downloads) }
    var placing by remember { mutableStateOf<Boolean?>(null) }       // copy (false) or move (true) in progress: pick where
    val dest = remember { BrowserState(Files.home).also { it.all = true } }
    var renaming by remember { mutableStateOf<File?>(null) }
    var newName by remember { mutableStateOf("") }
    var volumes by remember { mutableStateOf(Files.volumes(context)) }
    var trash by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.refresh) { volumes = withContext(Dispatchers.IO) { Files.volumes(context) }; trash = withContext(Dispatchers.IO) { Files.trashSize(context) } }

    fun work(label: String, job: () -> String) {
        busy = label
        scope.launch {
            val msg = withContext(Dispatchers.IO) { try { job() } catch (e: Exception) { "!" + (e.message ?: e.javaClass.simpleName) } }
            busy = null
            s.picked.clear(); s.refresh++
            if (msg.startsWith("!")) Ui.say(msg.drop(1), true) else if (msg.isNotEmpty()) Ui.say(msg)
        }
    }

    fun open(f: File) {
        Recent.add(context, f)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Ui.say("No app on this phone opens ${f.name}. Use Send or Copy instead.", true)
        }
    }

    if (placing != null) {
        val move = placing == true
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Section(if (move) "Move ${s.picked.size} here?" else "Copy ${s.picked.size} here?") { MButton("Cancel") { placing = null } }
            FileBrowser(dest, Modifier.weight(1f), pickFolders = false)
            MButton(if (move) "Move here" else "Copy here", Modifier.fillMaxWidth().padding(vertical = 8.dp), Kind.Accent) {
                val items = s.picked.toList(); val to = dest.dir; placing = null
                work(if (move) "Moving…" else "Copying…") {
                    val done = Files.copy(context, items, to, move)
                    done.forEach { Recent.add(context, it.second) }
                    "${if (move) "Moved" else "Copied"} ${done.size} to ${to.name}."
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (s.dir.absolutePath == s.root().absolutePath && s.query.isBlank() && s.picked.isEmpty()) {
            Section("Storage")
            Panel {
                volumes.forEach { v ->
                    Row { Text(v.name, style = Type.normal, modifier = Modifier.weight(1f)); Text("${LocalSend.size(v.free)} free of ${LocalSend.size(v.total)}", style = Type.small) }
                    Bar(1f - v.free.toFloat() / v.total.coerceAtLeast(1), Modifier.padding(vertical = 6.dp))
                }
                if (trash > 0) Row {
                    Text("Trash: ${LocalSend.size(trash)}", style = Type.small, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    MButton("Empty") {
                        Ui.sure(Ask("Empty the trash?", "This frees ${LocalSend.size(trash)} for good. It can't be undone.", "Empty trash", danger = true, hold = true,
                            onConfirm = { work("Emptying…") { Files.emptyTrash(context); "Trash emptied." } }))
                    }
                }
            }
        }
        if (busy != null) Text(busy!!, style = Type.normal, modifier = Modifier.padding(vertical = 6.dp))
        FileBrowser(s, Modifier.weight(1f), onOpenFile = { open(it) })
        if (s.picked.isNotEmpty()) {
            val one = s.picked.singleOrNull()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Send", kind = Kind.Accent, icon = "send") { onSend(s.picked.toList()); s.picked.clear() }
                MButton("Copy to…", icon = "copy") { placing = false }
                MButton("Move to…", icon = "move") { placing = true }
                if (one != null) MButton("Rename", icon = "rename") { renaming = one; newName = one.name }
                MButton("Zip", icon = "compress") { val items = s.picked.toList(); val at = s.dir; work("Zipping…") { "Made ${Files.zip(items, at).name}." } }
                if (one != null && one.extension.equals("zip", true)) MButton("Extract", icon = "extract") {
                    work("Extracting…") { val into = Files.unzip(one, one.parentFile!!); if (into == one.parentFile) "Extracted ${one.name}." else "Some files were already there, so they went into a new folder, ${into.name}. Nothing was replaced." }
                }
                MButton("Delete", kind = Kind.Danger, icon = "delete") {
                    val items = s.picked.toList()
                    work("Moving to trash…") {
                        val moved = Files.trash(context, items)
                        lastTrash = moved
                        "Moved ${moved.size} to the trash. Undo is in the Files tab."
                    }
                }
            }
        }
        if (lastTrash.isNotEmpty() && s.picked.isEmpty()) MButton("Undo delete (${lastTrash.size})", Modifier.fillMaxWidth().padding(bottom = 8.dp), icon = "undo") {
            val back = lastTrash; lastTrash = emptyList()
            work("Restoring…") { back.forEach { (orig, inTrash) -> inTrash.renameTo(Files.free(orig.parentFile!!, orig.name)) }; "Restored ${back.size}." }
        }
    }

    renaming?.let { f ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = io.github.ravenor907.muninn.ui.Palette.panel,
            title = { Text("Rename", style = Type.big) },
            text = { Field(newName, { newName = it }, f.name) },
            confirmButton = {
                MButton("Rename", kind = Kind.Accent) {
                    val name = newName; renaming = null
                    val ending = { n: String -> if (n.contains('.') && !n.startsWith(".")) n.substringAfterLast('.').lowercase() else "" }
                    val go = { work("Renaming…") { "Renamed to ${Files.rename(f, name).name}." } }
                    // Games and emulators find files by their ending; changing it is usually a slip.
                    if (f.isFile && ending(f.name).isNotEmpty() && ending(f.name) != ending(name))
                        Ui.sure(Ask("Change the file type?", "The name no longer ends in .${ending(f.name)}. Apps may stop opening it.", "Rename anyway", onConfirm = go))
                    else go()
                }
            },
            dismissButton = { MButton("Cancel") { renaming = null } },
        )
    }
}

/** The last delete, so it can be undone. */
private var lastTrash by mutableStateOf<List<Pair<File, File>>>(emptyList())
