package io.github.ravenor907.muninn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.ravenor907.muninn.R

/**
 * Settings › Accessibility. Read by the theme, so every screen follows at once:
 * size scales everything, text scales only text, high contrast brightens text and outlines,
 * color-blind friendly swaps the warning color for one that doesn't depend on red/green, and
 * reduce motion turns animations off.
 */
object Access {
    var size by androidx.compose.runtime.mutableFloatStateOf(1f)
    var text by androidx.compose.runtime.mutableFloatStateOf(1f)
    var contrast by androidx.compose.runtime.mutableStateOf(false)
    var colorBlind by androidx.compose.runtime.mutableStateOf(false)
    var reduceMotion by androidx.compose.runtime.mutableStateOf(false)
}

/** Huginn's stone and iron grays; Muninn's one accent is silver where Huginn's is gold. */
object Palette {
    private val hc get() = Access.contrast
    val ground get() = if (hc) Color(0xFF000000) else Color(0xFF101112)
    val groundDeep get() = if (hc) Color(0xFF000000) else Color(0xFF0C0D0E)
    val panel get() = if (hc) Color(0xFF0A0A0A) else Color(0xFF17191B)
    val panelLow get() = if (hc) Color(0xFF050505) else Color(0xFF141617)
    val raised get() = if (hc) Color(0xFF1A1A1A) else Color(0xFF1D1E20)
    val line get() = if (hc) Color(0xFF9A9A9A) else Color(0xFF2C2F33)
    val lineMuted get() = if (hc) Color(0xFFBDBDBD) else Color(0xFF45484D)
    val text get() = if (hc) Color(0xFFFFFFFF) else Color(0xFFE4E1DA)
    val textSoft get() = if (hc) Color(0xFFFFFFFF) else Color(0xFFC4C0B7)
    val textMuted get() = if (hc) Color(0xFFE0E0E0) else Color(0xFF9C978D)
    val textFaint get() = if (hc) Color(0xFFC8C8C8) else Color(0xFF6E6A62)
    val accent get() = if (hc) Color(0xFFFFFFFF) else Color(0xFFC8CDD3)
    val accentFill get() = if (hc) Color(0xFF333333) else Color(0xFF2A2D31)
    val focusFill get() = if (hc) Color(0xFF2A2A2A) else Color(0xFF222427)
    // Color-blind friendly: amber instead of rose, so warnings never depend on telling red from green.
    val danger get() = if (Access.colorBlind) Color(0xFFF2B33D) else if (hc) Color(0xFFFFB4A8) else Color(0xFFD9A79E)
    val dangerLine get() = if (Access.colorBlind) Color(0xFF7A5A1E) else Color(0xFF5A3A34)
    val dangerFill get() = if (Access.colorBlind) Color(0xFF221B0E) else Color(0xFF1E1716)
}

object Type {
    val display = FontFamily(Font(R.font.cinzel, FontWeight.Bold))
    val body = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_bold, FontWeight.Bold),
    )
    val runic = FontFamily(Font(R.font.noto_sans_runic))

    val title get() = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 3.sp, color = Palette.text)
    val section get() = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = Palette.textMuted)
    val big get() = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Palette.text)
    val normal get() = TextStyle(fontFamily = body, fontSize = 16.sp, color = Palette.textSoft)
    val small get() = TextStyle(fontFamily = body, fontSize = 14.sp, color = Palette.textMuted)
}

@Composable
fun MuninnTheme(content: @Composable () -> Unit) {
    // Size scales everything (dp and sp); text scales only sp, on top of Android's own font size.
    val base = androidx.compose.ui.platform.LocalDensity.current
    val density = androidx.compose.ui.unit.Density(base.density * Access.size, base.fontScale * Access.text)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) { Themed(content) }
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, onPrimary = Palette.ground, background = Palette.ground, surface = Palette.panel,
            onSurface = Palette.text, onBackground = Palette.text, surfaceVariant = Palette.raised, outline = Palette.line,
            error = Palette.danger,
        ),
        content = content,
    )
}
