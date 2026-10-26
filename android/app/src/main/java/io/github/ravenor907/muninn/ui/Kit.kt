package io.github.ravenor907.muninn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

val Corner = RoundedCornerShape(4.dp)

/** Raven, name and the name in Elder Futhark, like Huginn's header. */
@Composable
fun Brand(trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("MUNINN", style = Type.title)
            Text("ᛗᚢᚾᛁᚾᚾ", fontFamily = Type.runic, fontSize = 11.sp, letterSpacing = 5.sp, color = Color(0xFF8E897F))
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
    Divider()
}

@Composable
fun Divider() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Palette.line))
        Box(Modifier.padding(horizontal = 8.dp).size(7.dp).border(1.dp, Palette.accent).background(Color.Transparent))
        Box(Modifier.weight(1f).height(1.dp).background(Palette.line))
    }
}

@Composable
fun Section(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.section.copy(color = Palette.accent))
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, focused: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var m = modifier.fillMaxWidth().clip(Corner).background(if (focused) Palette.focusFill else Palette.panel)
        .border(if (focused) 2.dp else 1.dp, if (focused) Palette.accent else Palette.line, Corner)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(14.dp), content = content)
}

enum class Kind { Normal, Accent, Danger }

@Composable
fun MButton(text: String, modifier: Modifier = Modifier, kind: Kind = Kind.Normal, enabled: Boolean = true, icon: String? = null, onClick: () -> Unit) {
    val (bg, line, fg) = when (kind) {
        Kind.Accent -> Triple(Palette.accentFill, Palette.accent, Palette.accent)
        Kind.Danger -> Triple(Palette.dangerFill, Palette.dangerLine, Palette.danger)
        Kind.Normal -> Triple(Palette.raised, Palette.lineMuted, Palette.text)
    }
    Row(
        modifier.heightIn(min = 48.dp).clip(Corner).background(bg).border(1.dp, line, Corner)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { MIcon(icon, if (enabled) fg else Palette.textFaint, 20.dp); Spacer(Modifier.width(8.dp)) }
        FitText(text, TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            color = if (enabled) fg else Palette.textFaint, textAlign = TextAlign.Center), Modifier.weight(1f, fill = false))
    }
}

/**
 * Text that fits the space it's given on any phone and at any font size: it never splits a word
 * across lines. It shrinks (down to 70%) until every word fits on a line and the whole label takes
 * at most two lines; only if it still can't fit at the smallest size does it wrap normally.
 */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val size = remember(text, style, maxW) {
            if (!constraints.hasBoundedWidth) return@remember style.fontSize
            val words = text.split(' ').filter { it.isNotEmpty() }
            var s = style.fontSize.value
            val floor = s * 0.7f
            while (s > floor) {
                val st = style.copy(fontSize = s.sp)
                val widest = words.maxOfOrNull { measurer.measure(it, st, softWrap = false, maxLines = 1).size.width } ?: 0
                val lines = measurer.measure(text, st, constraints = Constraints(maxWidth = maxW)).lineCount
                if (widest <= maxW && lines <= 2) break
                s -= 0.5f
            }
            maxOf(s, floor).sp
        }
        Text(text, style = style.copy(fontSize = size, lineHeight = size * 1.2f))
    }
}

@Composable
fun ToggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.big.copy(fontSize = 16.sp))
            if (detail.isNotEmpty()) Text(detail, style = Type.small)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Palette.ground, checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.textMuted, uncheckedTrackColor = Palette.raised, uncheckedBorderColor = Palette.lineMuted))
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, single: Boolean = true, keyboard: KeyboardOptions = KeyboardOptions.Default,
          secret: Boolean = false) {
    Box(modifier.fillMaxWidth().clip(Corner).background(Palette.groundDeep).border(1.dp, Palette.lineMuted, Corner).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, style = Type.normal.copy(color = Palette.textFaint))
        BasicTextField(value, onChange, singleLine = single, keyboardOptions = keyboard, textStyle = Type.normal.copy(color = Palette.text),
            cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    }
}

@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.accent) {
    Box(modifier.fillMaxWidth().height(6.dp).background(Palette.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(color))
    }
}

@Composable
fun Line(title: String, detail: String = "", icon: String? = null, trailing: String = "", selected: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Corner).background(if (selected) Palette.accentFill else Color.Transparent)
            .border(if (selected) 1.dp else 0.dp, if (selected) Palette.accent else Color.Transparent, Corner)
            .then(if (onLong != null) Modifier.pointerInput(onLong, onClick) { detectTapsAndLong(onClick, onLong) } else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { MIcon(icon, if (selected) Palette.accent else Palette.textMuted, 24.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing.isNotEmpty()) Text(trailing, style = Type.small)
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapsAndLong(onTap: () -> Unit, onLong: () -> Unit) {
    detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() })
}

// ------------------------------------------------------------------ dialogs and toasts

/** One question at a time, app-wide. hold: the confirm button has to be held for 2 seconds. */
data class Ask(
    val title: String, val message: String, val confirm: String, val danger: Boolean = false, val hold: Boolean = false,
    val cancel: String = "Cancel", val onCancel: () -> Unit = {}, val onConfirm: () -> Unit,
)

object Ui {
    val ask = MutableStateFlow<Ask?>(null)
    val toast = MutableStateFlow<Pair<String, Boolean>?>(null)
    fun sure(a: Ask) { ask.value = a }
    fun say(text: String, bad: Boolean = false) { toast.value = text to bad }
}

@Composable
fun AskDialog(a: Ask) {
    AlertDialog(
        onDismissRequest = { Ui.ask.value = null; a.onCancel() },
        containerColor = Palette.panel,
        title = { Text(a.title, style = Type.big) },
        text = { Text(a.message, style = Type.normal) },
        confirmButton = {
            if (a.hold) HoldButton(a.confirm) { Ui.ask.value = null; a.onConfirm() }
            else MButton(a.confirm, kind = if (a.danger) Kind.Danger else Kind.Accent) { Ui.ask.value = null; a.onConfirm() }
        },
        dismissButton = { MButton(a.cancel) { Ui.ask.value = null; a.onCancel() } },
    )
}

/** Hold for 2 seconds: a stray tap can't erase a drive. Letting go early does nothing. */
@Composable
fun HoldButton(text: String, onDone: () -> Unit) {
    var held by remember { mutableFloatStateOf(0f) }
    var down by remember { mutableStateOf(false) }
    LaunchedEffect(down) {
        if (!down) { held = 0f; return@LaunchedEffect }
        val start = System.currentTimeMillis()
        while (down) {
            held = ((System.currentTimeMillis() - start) / 2000f).coerceAtMost(1f)
            if (held >= 1f) { down = false; onDone(); break }
            delay(30)
        }
    }
    Box(
        Modifier.heightIn(min = 48.dp).clip(Corner).background(Palette.dangerFill).border(1.dp, Palette.dangerLine, Corner)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); down = true; waitForUpOrCancellation(); down = false } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.matchParentSizeCompat(held).background(Palette.dangerLine))
        Text(if (held > 0f) "Keep holding…" else "Hold: $text", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.danger)
    }
}

private fun Modifier.matchParentSizeCompat(fraction: Float) = this.fillMaxWidth(fraction).height(48.dp)

/** A shared border for chips like "Ventoy 1.1 · Secure Boot on". */
val ChipBorder = BorderStroke(1.dp, Palette.lineMuted)
