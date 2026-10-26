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
import io.github.ravenor907.muninn.R

/** Huginn's stone and iron grays; Muninn's one accent is silver where Huginn's is gold. */
object Palette {
    val ground = Color(0xFF101112)
    val groundDeep = Color(0xFF0C0D0E)
    val panel = Color(0xFF17191B)
    val panelLow = Color(0xFF141617)
    val raised = Color(0xFF1D1E20)
    val line = Color(0xFF2C2F33)
    val lineMuted = Color(0xFF45484D)
    val text = Color(0xFFE4E1DA)
    val textSoft = Color(0xFFC4C0B7)
    val textMuted = Color(0xFF9C978D)
    val textFaint = Color(0xFF6E6A62)
    val accent = Color(0xFFC8CDD3)
    val accentFill = Color(0xFF2A2D31)
    val focusFill = Color(0xFF222427)
    val danger = Color(0xFFD9A79E)
    val dangerLine = Color(0xFF5A3A34)
    val dangerFill = Color(0xFF1E1716)
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

    val title = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 3.sp, color = Palette.text)
    val section = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = Palette.textMuted)
    val big = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Palette.text)
    val normal = TextStyle(fontFamily = body, fontSize = 16.sp, color = Palette.textSoft)
    val small = TextStyle(fontFamily = body, fontSize = 14.sp, color = Palette.textMuted)
}

@Composable
fun MuninnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, onPrimary = Palette.ground, background = Palette.ground, surface = Palette.panel,
            onSurface = Palette.text, onBackground = Palette.text, surfaceVariant = Palette.raised, outline = Palette.line,
            error = Palette.danger,
        ),
        content = content,
    )
}
