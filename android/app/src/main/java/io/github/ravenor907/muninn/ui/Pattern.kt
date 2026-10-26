package io.github.ravenor907.muninn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Turns a drawn pattern into the secret the vault stretches, like a password. */
fun patternSecret(dots: List<Int>) = "pattern:" + dots.joinToString(",")

/**
 * Android-style 3×3 pattern. Drag through the dots; passing straight over a dot in between picks
 * it up too, as on Android. [error] colours it red after a wrong try. Calls [onDone] on release.
 */
@Composable
fun PatternPad(modifier: Modifier = Modifier, error: Boolean = false, enabled: Boolean = true, onDone: (List<Int>) -> Unit) {
    val dots = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val done by rememberUpdatedState(onDone)
    val color = if (error) Palette.danger else Palette.accent
    Canvas(modifier.fillMaxWidth().widthIn(max = 320.dp).aspectRatio(1f).pointerInput(enabled) {
        if (!enabled) return@pointerInput
        fun center(i: Int) = Offset(size.width * ((i % 3) * 2 + 1) / 6f, size.height * ((i / 3) * 2 + 1) / 6f)
        fun hit(p: Offset): Int? = (0..8).firstOrNull { (center(it) - p).getDistance() < size.width / 9f }
        fun add(i: Int) {
            if (i in dots) return
            dots.lastOrNull()?.let { last ->
                // Skipping over the middle dot of a row, column or diagonal picks it up.
                val (r1, c1, r2, c2) = listOf(last / 3, last % 3, i / 3, i % 3)
                if ((r1 + r2) % 2 == 0 && (c1 + c2) % 2 == 0) { val mid = (r1 + r2) / 2 * 3 + (c1 + c2) / 2; if (mid !in dots) dots.add(mid) }
            }
            dots.add(i)
        }
        detectDragGestures(
            onDragStart = { p -> dots.clear(); finger = p; hit(p)?.let { add(it) } },
            onDrag = { change, _ -> finger = change.position; hit(change.position)?.let { add(it) } },
            onDragEnd = { finger = null; val d = dots.toList(); if (d.isNotEmpty()) done(d); dots.clear() },
            onDragCancel = { finger = null; dots.clear() },
        )
    }) {
        fun center(i: Int) = Offset(size.width * ((i % 3) * 2 + 1) / 6f, size.height * ((i / 3) * 2 + 1) / 6f)
        val stroke = size.width / 40f
        for (k in 1 until dots.size) drawLine(color, center(dots[k - 1]), center(dots[k]), stroke, StrokeCap.Round)
        if (dots.isNotEmpty()) finger?.let { drawLine(color.copy(alpha = 0.5f), center(dots.last()), it, stroke, StrokeCap.Round) }
        for (i in 0..8) {
            val on = i in dots
            drawCircle(if (on) color else Palette.textFaint, radius = size.width / (if (on) 22f else 34f), center = center(i))
        }
    }
}
