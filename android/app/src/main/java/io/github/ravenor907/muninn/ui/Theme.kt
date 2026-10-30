package io.github.ravenor907.muninn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.draw.drawBehind
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
 * Settings › Look and text size, and its accessibility choices. Read by the theme, so every screen follows at once:
 * size scales everything, text scales only text, high contrast brightens text and outlines, color-blind friendly
 * swaps the warning color for one that doesn't depend on red/green, and reduce motion turns animations off.
 */
object Access {
    var size by androidx.compose.runtime.mutableFloatStateOf(1f)
    var text by androidx.compose.runtime.mutableFloatStateOf(1f)
    var contrast by androidx.compose.runtime.mutableStateOf(false)
    var colorBlind by androidx.compose.runtime.mutableStateOf(false)
    var reduceMotion by androidx.compose.runtime.mutableStateOf(false)
    /** The look: [Looks.DARK] (the usual), [Looks.BLACK], [Looks.LIGHT] (parchment) or [Looks.METAL] (brushed iron). */
    var look by androidx.compose.runtime.mutableStateOf(Looks.DARK)
}

/** The four looks, the same as Huginn's. Their keys are what Settings saves. */
object Looks {
    const val DARK = "dark"
    const val BLACK = "black"
    const val LIGHT = "light"
    const val METAL = "metal"
    val ALL = listOf(DARK to "Dark", BLACK to "True black", LIGHT to "Light", METAL to "Brushed metal")
    fun of(key: String?): String = ALL.firstOrNull { it.first == key }?.first ?: DARK
    fun name(key: String): String = ALL.firstOrNull { it.first == key }?.second ?: "Dark"
}

/**
 * Huginn's stone and iron grays; Muninn's one accent is silver where Huginn's is gold. Light is Huginn's parchment,
 * Brushed metal is iron and steel. High contrast wins over every look.
 */
object Palette {
    private val hc get() = Access.contrast
    /** The parchment look is on (and high contrast is off): dark text on light paper. */
    val light get() = Access.look == Looks.LIGHT && !hc
    private val black get() = Access.look == Looks.BLACK && !hc
    private val metal get() = Access.look == Looks.METAL && !hc
    /** Picks the shade for the look in use: dark, true black, parchment, brushed metal, high contrast. */
    private fun pick(dark: Long, deep: Long, parchment: Long, iron: Long, contrast: Long) =
        Color(if (hc) contrast else if (light) parchment else if (black) deep else if (metal) iron else dark)

    // True black: only the background goes black (panels and buttons keep their shade, so they still stand out).
    val ground get() = pick(0xFF101112, 0xFF000000, 0xFFF3EFE6, 0xFF1B1D20, 0xFF000000)
    val groundDeep get() = pick(0xFF0C0D0E, 0xFF000000, 0xFFE7E1D4, 0xFF15171A, 0xFF000000)
    val panel get() = pick(0xFF17191B, 0xFF0B0C0D, 0xFFFBF9F4, 0xFF25282C, 0xFF0A0A0A)
    val panelLow get() = pick(0xFF141617, 0xFF060607, 0xFFECE7DC, 0xFF202326, 0xFF050505)
    val raised get() = pick(0xFF1D1E20, 0xFF141516, 0xFFE2DCCF, 0xFF2E3237, 0xFF1A1A1A)
    val line get() = pick(0xFF2C2F33, 0xFF232528, 0xFFCEC6B6, 0xFF3B4046, 0xFF9A9A9A)
    val lineMuted get() = pick(0xFF45484D, 0xFF3E4146, 0xFFA69D8B, 0xFF575D64, 0xFFBDBDBD)
    val text get() = pick(0xFFE4E1DA, 0xFFE4E1DA, 0xFF1F1C17, 0xFFE7E9EB, 0xFFFFFFFF)
    val textSoft get() = pick(0xFFC4C0B7, 0xFFC4C0B7, 0xFF3B362E, 0xFFC9CDD2, 0xFFFFFFFF)
    val textMuted get() = pick(0xFF9C978D, 0xFF9C978D, 0xFF615A4E, 0xFF9BA1A8, 0xFFE0E0E0)
    val textFaint get() = pick(0xFF6E6A62, 0xFF6E6A62, 0xFF877F71, 0xFF6F757C, 0xFFC8C8C8)
    val accent get() = pick(0xFFC8CDD3, 0xFFC8CDD3, 0xFF4F5862, 0xFFD6DCE2, 0xFFFFFFFF)
    val accentFill get() = pick(0xFF2A2D31, 0xFF2A2D31, 0xFFDCD6C9, 0xFF353A40, 0xFF333333)
    val focusFill get() = pick(0xFF222427, 0xFF1A1B1D, 0xFFE7E1D4, 0xFF30343A, 0xFF2A2A2A)
    // Color-blind friendly: amber instead of rose, so warnings never depend on telling red from green.
    val danger get() = if (Access.colorBlind) (if (light) Color(0xFF8A5A00) else Color(0xFFF2B33D))
        else pick(0xFFD9A79E, 0xFFD9A79E, 0xFFA23B2C, 0xFFE0A89E, 0xFFFFB4A8)
    val dangerLine get() = if (Access.colorBlind) (if (light) Color(0xFFC79A45) else Color(0xFF7A5A1E))
        else if (light) Color(0xFFD9A79E) else Color(0xFF5A3A34)
    val dangerFill get() = if (Access.colorBlind) (if (light) Color(0xFFF6EBD4) else Color(0xFF221B0E))
        else if (light) Color(0xFFF5E2DD) else Color(0xFF1E1716)

    // Chat ("Quiet" look): gold marks the few things you act on (new chat, play, record, unread).
    val gold get() = pick(0xFFC9A964, 0xFFC9A964, 0xFF7A6128, 0xFFC9A964, 0xFFFFD27A)
    val goldBright get() = pick(0xFFE0C27F, 0xFFE0C27F, 0xFF6B5216, 0xFFE0C27F, 0xFFFFE2A0)
    /** Text and icons drawn on gold. */
    val onGold get() = pick(0xFF101112, 0xFF101112, 0xFFF3EFE6, 0xFF101112, 0xFF000000)
    /** A person's avatar circle (their initial in goldBright on it). */
    val goldTint get() = pick(0xFF3A3220, 0xFF3A3220, 0xFFEADDBB, 0xFF3A3220, 0xFF3D3214)
    /** The Nearby room's avatar circle. */
    val coolTint get() = pick(0xFF23272C, 0xFF23272C, 0xFFD6DDEB, 0xFF33393F, 0xFF333333)
    /** Search box, message box and their bubbles. */
    val field get() = pick(0xFF1B1D20, 0xFF141516, 0xFFECE7DC, 0xFF2A2E33, 0xFF141414)
    val bubbleMine get() = pick(0xFF2B2E33, 0xFF2B2E33, 0xFFE2DCCF, 0xFF3A3F45, 0xFF2A2A2A)
    /** The empty part of a voice message's progress bar. */
    val track get() = pick(0xFF44484E, 0xFF44484E, 0xFFA69D8B, 0xFF575D64, 0xFF9A9A9A)
    /** The thin line under a conversation's top bar. */
    val hairline get() = pick(0xFF1F2125, 0xFF16181A, 0xFFD9D2C4, 0xFF33383E, 0xFF9A9A9A)
    /** Online dot and the encrypted shield. Color-blind friendly: blue, so it never depends on green. */
    val online get() = if (Access.colorBlind) (if (light) Color(0xFF2F5F9E) else Color(0xFF6FB3F0))
        else pick(0xFF7FB08A, 0xFF7FB08A, 0xFF2F7D46, 0xFF7FB08A, 0xFF9BE0A8)
}

/**
 * Brushed metal: fine horizontal strokes over headers and rails, so they read as worked steel. Every other look
 * (and high contrast) draws nothing. The strokes are fixed (seeded), so they never shimmer between frames.
 */
fun androidx.compose.ui.Modifier.brushed(): androidx.compose.ui.Modifier =
    if (Access.look != Looks.METAL || Access.contrast) this
    else this.drawBehind {
        val rnd = java.util.Random(907)
        var y = 0f
        val step = 1.5f * density
        while (y < size.height) {
            val a = 0.025f + rnd.nextFloat() * 0.06f
            val light = rnd.nextBoolean()
            val x0 = rnd.nextFloat() * size.width * 0.3f
            val x1 = size.width - rnd.nextFloat() * size.width * 0.3f
            drawLine(if (light) Color.White.copy(alpha = a) else Color.Black.copy(alpha = a * 1.4f),
                androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x1, y), strokeWidth = density * 0.7f)
            y += step
        }
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
        colorScheme = if (Palette.light) lightColorScheme(
            primary = Palette.accent, onPrimary = Palette.ground, background = Palette.ground, surface = Palette.panel,
            onSurface = Palette.text, onBackground = Palette.text, surfaceVariant = Palette.raised, outline = Palette.line,
            error = Palette.danger, onSurfaceVariant = Palette.textMuted, surfaceContainer = Palette.panel,
        ) else darkColorScheme(
            primary = Palette.accent, onPrimary = Palette.ground, background = Palette.ground, surface = Palette.panel,
            onSurface = Palette.text, onBackground = Palette.text, surfaceVariant = Palette.raised, outline = Palette.line,
            error = Palette.danger, onSurfaceVariant = Palette.textMuted, surfaceContainer = Palette.panel,
        ),
        content = content,
    )
}
