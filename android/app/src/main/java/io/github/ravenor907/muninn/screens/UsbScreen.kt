package io.github.ravenor907.muninn.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.ravenor907.muninn.files.Files
import io.github.ravenor907.muninn.forge.ForgeService
import io.github.ravenor907.muninn.forge.JobState
import io.github.ravenor907.muninn.forge.Jobs
import io.github.ravenor907.muninn.forge.Outcome
import io.github.ravenor907.muninn.forge.core.Distro
import io.github.ravenor907.muninn.forge.core.Stage
import io.github.ravenor907.muninn.forge.usb.Stick
import io.github.ravenor907.muninn.forge.usb.UsbSticks
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** The "Useful ISOs" folders, in order: catalog category, title, what's in it. */
private val FOLDERS = listOf(
    Triple("gaming", "Gaming", "Systems for handhelds and gaming PCs"),
    Triple("desktop", "Desktop", "Everyday Linux for a laptop or PC"),
    Triple("tools", "Tools", "Test, repair, back up and partition"),
)

/**
 * USB drives plugged into the phone (USB-C OTG). First "Useful ISOs": folders of systems and
 * tools from the catalog, each downloaded from its official source and written to the drive or
 * added to Ventoy. Below that, Handheld Forge's tested jobs: write an image, Ventoy, copy ISOs
 * onto Ventoy, format. No root: the phone talks to the drive directly over USB.
 */
@Composable
fun UsbScreen() {
    val context = LocalContext.current
    val sticks = remember { UsbSticks(context) }
    var list by remember { mutableStateOf<List<Stick>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }    // "write" or "copy": choosing files
    val browser = remember { BrowserState(Files.downloads) }
    val job by Jobs.state.collectAsState()
    val catalog = remember { try { ForgeService.catalog(context).filter { it.enabled } } catch (e: Exception) { emptyList() } }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }    // open "Useful ISOs" folder
    BackHandler(enabled = folder != null && picking == null) { folder = null }

    DisposableEffect(Unit) {
        val r = sticks.registerPermissionReceiver { tick++ }
        onDispose { try { context.unregisterReceiver(r) } catch (_: Exception) {} }
    }
    LaunchedEffect(tick, job) {
        while (true) {
            list = withContext(Dispatchers.IO) { sticks.list(probe = true) }
            if (chosen == null || list.none { it.key == chosen }) chosen = list.firstOrNull()?.key
            delay(2000)
        }
    }
    val stick = list.firstOrNull { it.key == chosen }

    fun uri(f: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    fun erase(title: String, what: String, note: String? = null, go: () -> Unit) {
        val s = stick ?: return
        Ui.sure(Ask(title, "Everything on ${s.label}${s.sizeBytes?.let { " (" + LocalSend.size(it) + ")" } ?: ""} will be erased and replaced by $what." +
            (note?.let { "\n\n$it" } ?: ""), "Erase", danger = true, hold = true, onConfirm = go))
    }

    if (picking != null) {
        val copy = picking == "copy"
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Section(if (copy) "Pick ISOs to copy onto Ventoy" else "Pick one image to write") { MButton("Cancel") { picking = null; browser.picked.clear() } }
            FileBrowser(browser, Modifier.weight(1f), pickFolders = false)
            val files = browser.picked.filter { it.isFile }
            MButton(if (copy) "Copy ${files.size} to the drive" else "Write to the drive", Modifier.fillMaxWidth().padding(vertical = 8.dp), Kind.Accent,
                enabled = if (copy) files.isNotEmpty() else files.size == 1) {
                val s = stick ?: return@MButton
                if (copy) {
                    ForgeService.startImport(context, files.map { uri(it) }, s.key, s.label)
                    picking = null; browser.picked.clear()
                } else {
                    val f = files[0]
                    erase("Erase this drive?", f.name) { ForgeService.startFile(context, uri(f), f.name, s.key, s.label); picking = null; browser.picked.clear() }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        when (val j = job) {
            is JobState.Running -> {
                Section("Working on ${j.drive}")
                Panel {
                    Text(j.distro, style = Type.big)
                    Text(when (j.stage) { Stage.LOOKUP -> "Checking for the newest version…"; Stage.DOWNLOAD -> "Downloading…"; Stage.VERIFY -> "Checking the download…"
                        Stage.WRITE -> "Writing to the drive…"; Stage.CHECK -> "Reading it back to confirm…" } + if (j.timeLeft.isNotEmpty()) "  ${j.timeLeft}" else "", style = Type.normal)
                    Spacer(Modifier.height(8.dp)); Bar(j.fraction)
                    MButton(if (j.stopping) "Stopping…" else "Stop", Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !j.stopping) {
                        Ui.sure(Ask("Stop now?", "The drive is only partly written. If you stop, it won't work until you write or format it again.", "Stop anyway", danger = true, hold = true, onConfirm = { Jobs.cancel() }))
                    }
                }
                return@Column
            }
            is JobState.Finished -> {
                Section(if (j.outcome == Outcome.OK) "Done" else "Didn't finish")
                Panel {
                    Text(if (j.outcome == Outcome.OK) "${j.distro} is on ${j.drive}." else j.message, style = Type.normal.copy(color = if (j.outcome == Outcome.OK) Palette.text else Palette.danger))
                    MButton("OK", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) { Jobs.reset(); sticks.forget(); tick++ }
                }
            }
            else -> {}
        }

        val ready = stick?.takeIf { it.hasPermission }
        val open = FOLDERS.firstOrNull { it.first == folder }
        if (open != null) {
            Section(open.second) { MButton("Back", icon = "back") { folder = null } }
            Text(when {
                ready == null && list.isEmpty() -> "Plug a USB drive into the phone to put these on it."
                ready == null -> "Tap the drive below to let Muninn use it."
                ready.ventoy -> "Using ${ready.label}, a Ventoy drive: add these next to the ISOs already on it, or write one over the whole drive."
                else -> "Using ${ready.label}."
            }, style = Type.small, modifier = Modifier.padding(bottom = 8.dp))
            if (ready == null && list.isNotEmpty()) DriveList(list, chosen, sticks) { chosen = it }
            catalog.filter { it.category == open.first }.forEach { d ->
                IsoEntry(d, ready,
                    onWrite = { s ->
                        erase("Erase this drive?", "${d.name}, downloaded now (${d.size ?: "several GB"}, kept for next time)", d.warning) {
                            ForgeService.start(context, d, s.key, s.label)
                        }
                    },
                    onVentoy = { s ->
                        Ui.sure(Ask("Add ${d.name} to Ventoy?", "${d.name} is downloaded now (${d.size ?: "it can take a while"}, kept for next time) " +
                            "and copied onto ${s.label}. Nothing on the drive is erased.", "Add", onConfirm = {
                            ForgeService.start(context, d, s.key, s.label, toVentoy = true)
                        }))
                    })
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        Section("Useful ISOs")
        FOLDERS.forEach { (id, title, about) ->
            val inside = catalog.filter { it.category == id }
            if (inside.isNotEmpty()) {
                Line(title, about, icon = "folder", trailing = inside.size.toString()) { folder = id }
            }
        }

        Section("USB drive")
        DriveList(list, chosen, sticks) { chosen = it }
        if (stick != null && stick.hasPermission) {
            Section("Put on the drive")
            MButton("Write an image from the phone", Modifier.fillMaxWidth(), Kind.Danger, icon = "disc") { picking = "write" }
            Section("Ventoy (many ISOs on one drive)")
            if (stick.ventoy) MButton("Copy ISOs onto Ventoy", Modifier.fillMaxWidth().padding(bottom = 8.dp), Kind.Accent, icon = "copy") { picking = "copy" }
            MButton(if (stick.ventoy) "Reinstall Ventoy (erases it)" else "Install Ventoy (erases it)", Modifier.fillMaxWidth(), Kind.Danger, icon = "usb") {
                erase("Erase this drive?", "Ventoy (latest release, Secure Boot support on)") { ForgeService.startTool(context, ForgeService.VENTOY_ID, "Ventoy", stick.key, stick.label, true) }
            }
            Section("Other")
            MButton("Format as a normal drive (erases it)", Modifier.fillMaxWidth(), icon = "delete") {
                erase("Format this drive?", "one empty exFAT drive that works everywhere") { ForgeService.startTool(context, ForgeService.FORMAT_ID, "Format", stick.key, stick.label) }
            }
            Text("Have your own ISO, Windows included? Use Write an image, or copy it onto Ventoy.", style = Type.small, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        }
    }
}

/** The plugged-in drives; tapping one picks it, and asks for permission the first time. */
@Composable
private fun DriveList(list: List<Stick>, chosen: String?, sticks: UsbSticks, onChoose: (String) -> Unit) {
    if (list.isEmpty()) Text("Plug a USB drive into the phone (a USB-C drive, or an adapter). The phone's own storage never shows up here, so it can't be erased by mistake.", style = Type.normal)
    list.forEach { s ->
        Line(s.label, when {
            !s.hasPermission -> "Tap to allow Muninn to use it"
            s.ventoy -> "Ventoy · ${s.sizeBytes?.let { LocalSend.size(it) } ?: ""}"
            else -> s.sizeBytes?.let { LocalSend.size(it) } ?: ""
        }, icon = "usb", selected = s.key == chosen) {
            onChoose(s.key)
            if (!s.hasPermission) sticks.device(s.key)?.let { sticks.requestPermission(it) }
        }
    }
}

/** One system or tool in a "Useful ISOs" folder: what it is, its size, and what can be done with it. */
@Composable
private fun IsoEntry(d: Distro, stick: Stick?, onWrite: (Stick) -> Unit, onVentoy: (Stick) -> Unit) {
    Panel(Modifier.padding(bottom = 10.dp)) {
        Text(d.name + if (d.edition.isNotEmpty()) " (" + d.edition + ")" else "", style = Type.big)
        if (d.about.isNotEmpty()) Text(d.about, style = Type.normal)
        if (d.size != null) Text("Download: ${d.size}", style = Type.small, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(10.dp))
        MButton("Write to the drive (erases it)", Modifier.fillMaxWidth(), Kind.Danger, enabled = stick != null, icon = "download") {
            stick?.let(onWrite)
        }
        if (d.ventoy) {
            MButton(if (stick != null && !stick.ventoy) "Add to Ventoy (install Ventoy first)" else "Add to Ventoy",
                Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, enabled = stick?.ventoy == true, icon = "copy") {
                stick?.let(onVentoy)
            }
        }
    }
}
