package io.github.ravenor907.muninn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Huginn's line icons (the same 24-unit paths), so both apps look like one family. */
object Icons {
    val paths = mapOf(
        "folder" to "M3 6h6l2 2h10v11H3z",
        "file" to "M6 3h8l4 4v14H6z M14 3v4h4",
        "image" to "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6",
        "archive" to "M4 7h16v13H4z M4 7l2-3h12l2 3 M12 7v5 M10 12h4v3h-4z",
        "disc" to "M12 3a9 9 0 1 0 0.01 0 M12 9.5a2.5 2.5 0 1 0 0.01 0",
        "text" to "M6 3h8l4 4v14H6z M14 3v4h4 M9 12h6 M9 15h6 M9 18h4",
        "video" to "M3 6h13v12H3z M16 10l5-3v10l-5-3",
        "audio" to "M9 18V5l11-2v13 M6 18a3 3 0 1 0 0.01 0 M17 16a3 3 0 1 0 0.01 0",
        "home" to "M3 11l9-7 9 7 M5 10v10h14V10 M10 20v-6h4v6",
        "sd" to "M7 3h8l4 4v14H7z M10 3v4 M13 3v4",
        "steam" to "M4 6h16v12H4z M8 10h8 M8 14h5",
        "game" to "M6 8h12a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.6H9A4 4 0 0 1 2 13v-1a4 4 0 0 1 4-4z M7 11v4 M5 13h4 M16 12h0.01 M18 14h0.01",
        "screenshot" to "M4 8V4h4 M16 4h4v4 M20 16v4h-4 M8 20H4v-4 M12 9a3 3 0 1 0 0.01 0",
        "download" to "M12 4v11 M7 10l5 5 5-5 M5 20h14",
        "network" to "M3 5h18v10H3z M8 19h8 M12 15v4",
        "server" to "M4 6h16v5H4z M4 13h16v5H4z M7 8.5h0.01 M7 15.5h0.01",
        "phone" to "M8 3h8v18H8z M11 18h2",
        "usb" to "M7 9h10v12H7z M9 3h6v6H9z",
        "gear" to "M12 9a3 3 0 1 0 0.01 0 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9l2.1 2.1 M17 17l2.1 2.1 M4.9 19.1L7 17 M17 7l2.1-2.1",
        "search" to "M11 4a7 7 0 1 0 0.01 0 M20 20l-3.5-3.5",
        "copy" to "M8 8h11v11H8z M5 16V5h11",
        "move" to "M4 12h14 M13 6l6 6-6 6",
        "rename" to "M4 20h4L19 9l-4-4L4 16z",
        "extract" to "M4 7h16v13H4z M12 10v7 M9 14l3 3 3-3",
        "compress" to "M4 7h16v13H4z M12 10v7 M9 13l3-3 3 3",
        "delete" to "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13",
        "info" to "M12 3l9 9-9 9-9-9z M12 10v6 M12 7v1",
        "newfolder" to "M3 6h6l2 2h10v11H3z M12 11v5 M9.5 13.5h5",
        "check" to "M5 12l5 5 9-10",
        "back" to "M15 5l-7 7 7 7",
        "forward" to "M9 5l7 7-7 7",
        "undo" to "M9 14L4 9l5-5 M4 9h11a5 5 0 0 1 0 10h-3",
        "link" to "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1 M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
        "lock" to "M6 11h12v9H6z M9 11V8a3 3 0 0 1 6 0v3",
        "open" to "M5 12h14 M12 5l7 7-7 7",
        "clock" to "M12 3a9 9 0 1 0 0.01 0 M12 7v5l3 3",
        "chat" to "M4 5h16v11H9l-5 4z",
        "send" to "M3 11l18-8-8 18-2-8z",
        "wifi" to "M2 9a15 15 0 0 1 20 0 M5 12.5a10 10 0 0 1 14 0 M8.5 16a5 5 0 0 1 7 0 M12 19.5h0.01",
        "user" to "M12 4a4 4 0 1 0 0.01 0 M4 21a8 8 0 0 1 16 0",
        "mute" to "M4 9h4l5-4v14l-5-4H4z M16 9l5 6 M21 9l-5 6",
        "close" to "M5 5l14 14 M19 5L5 19",
        "plus" to "M12 5v14 M5 12h14",
        "mic" to "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3",
        "play" to "M7 4l13 8-13 8z",
        "pause" to "M7 4v16 M17 4v16",
        "pausefill" to "M6 4h4v16H6z M14 4h4v16h-4z",
        "shield" to "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z",
    )
}

@Composable
fun MIcon(name: String, color: Color = Palette.textMuted, size: Dp = 24.dp, modifier: Modifier = Modifier, weight: Float = 1.6f, filled: Boolean = false) {
    val path = remember(name) { PathParser().parsePathString(Icons.paths[name] ?: Icons.paths.getValue("file")).toPath() }
    val boldness = weight * maxOf(1f, size.value / 28f)
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            if (filled) drawPath(path, color)
            else drawPath(path, color, style = Stroke(width = boldness, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        }
    }
}
