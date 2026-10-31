package io.github.ravenor907.muninn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
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
    /** The look: [Looks.DARK] (the usual), [Looks.BLACK], [Looks.LIGHT] (parchment) or [Looks.SEASONAL] (by the time of year). */
    var look by androidx.compose.runtime.mutableStateOf(Looks.DARK)
}

/**
 * The four looks. Their keys are what Settings saves; an unknown saved key means Dark, and a saved "autumn" (one
 * season's own key) means Seasonal.
 */
object Looks {
    const val DARK = "dark"
    const val BLACK = "black"
    const val LIGHT = "light"
    /** Seasonal: the look of the season it is now ([Seasons]). */
    const val SEASONAL = "seasonal"
    val ALL = listOf(DARK to "Dark", BLACK to "True black", LIGHT to "Light", SEASONAL to "Seasonal")
    fun of(key: String?): String = if (key == Seasons.AUTUMN) SEASONAL else ALL.firstOrNull { it.first == key }?.first ?: DARK
    fun name(key: String): String = ALL.firstOrNull { it.first == key }?.second ?: "Dark"
}

/**
 * The seasons the Seasonal look shows, each over a range of dates (month and day, both included; a range may run
 * past the new year). The first range that covers today wins. Autumn is the only season so far, so it covers the
 * whole year; a new season gets its own range here and its own shades in [Palette].
 */
object Seasons {
    const val AUTUMN = "autumn"

    /** [season] from [fromMonth]/[fromDay] to [toMonth]/[toDay] (months 1 to 12). */
    data class Span(val season: String, val fromMonth: Int, val fromDay: Int, val toMonth: Int, val toDay: Int) {
        fun covers(month: Int, day: Int): Boolean {
            val at = month * 100 + day
            val from = fromMonth * 100 + fromDay
            val to = toMonth * 100 + toDay
            return if (from <= to) at in from..to else at >= from || at <= to
        }
    }

    val SPANS = listOf(Span(AUTUMN, 1, 1, 12, 31))

    /** The season on [month]/[day]: the first span that covers it, else the first season. */
    fun at(month: Int, day: Int, spans: List<Span> = SPANS): String =
        spans.firstOrNull { it.covers(month, day) }?.season ?: spans.first().season

    @Volatile private var day = -1L
    @Volatile private var season = AUTUMN

    /** The season today (worked out once a day). */
    val now: String get() {
        val today = System.currentTimeMillis() / 86_400_000L
        if (today != day) {
            val c = java.util.Calendar.getInstance()
            season = at(c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
            day = today
        }
        return season
    }
}

/**
 * Huginn's stone and iron grays and its aged gold, so the two apps share one house style; Muninn's one accent is
 * silver. Light is Huginn's parchment. Autumn is warm bark and ember over falling leaves. High contrast wins over
 * every look.
 */
object Palette {
    private val hc get() = Access.contrast
    /** The parchment look is on (and high contrast is off): dark text on light paper. */
    val light get() = Access.look == Looks.LIGHT && !hc
    private val black get() = Access.look == Looks.BLACK && !hc
    /** Seasonal in autumn (and high contrast is off): rounded shapes, frosted panels, leaves behind everything. */
    val autumn get() = Access.look == Looks.SEASONAL && Seasons.now == Seasons.AUTUMN && !hc
    /** Picks the shade for the look in use: dark, true black, parchment, autumn, high contrast. */
    private fun pick(dark: Long, deep: Long, parchment: Long, autumn: Long, contrast: Long) =
        Color(if (hc) contrast else if (light) parchment else if (black) deep else if (this.autumn) autumn else dark)

    // True black: only the background goes black (panels and buttons keep their shade, so they still stand out).
    val ground get() = pick(0xFF101112, 0xFF000000, 0xFFF3EFE6, 0xFF221710, 0xFF000000)
    val groundDeep get() = pick(0xFF0C0D0E, 0xFF000000, 0xFFE7E1D4, 0xFF1A110B, 0xFF000000)
    val panel get() = pick(0xFF17191B, 0xFF0B0C0D, 0xFFFBF9F4, 0xFF2B1D13, 0xFF0A0A0A)
    val panelLow get() = pick(0xFF141617, 0xFF060607, 0xFFECE7DC, 0xFF241911, 0xFF050505)
    val raised get() = pick(0xFF1D1E20, 0xFF141516, 0xFFE2DCCF, 0xFF4A3421, 0xFF1A1A1A)
    val line get() = pick(0xFF2C2F33, 0xFF232528, 0xFFCEC6B6, 0xFF3F2C1C, 0xFF9A9A9A)
    val lineMuted get() = pick(0xFF45484D, 0xFF3E4146, 0xFFA69D8B, 0xFF5E4430, 0xFFBDBDBD)
    val text get() = pick(0xFFE4E1DA, 0xFFE4E1DA, 0xFF1F1C17, 0xFFECDCC4, 0xFFFFFFFF)
    val textSoft get() = pick(0xFFC4C0B7, 0xFFC4C0B7, 0xFF3B362E, 0xFFD9C6AD, 0xFFFFFFFF)
    val textMuted get() = pick(0xFF9C978D, 0xFF9C978D, 0xFF615A4E, 0xFFA88C6C, 0xFFE0E0E0)
    // Faint text (hints, times) still reads at 4.5:1 on parchment and on Autumn's bark.
    val textFaint get() = pick(0xFF6E6A62, 0xFF6E6A62, 0xFF746C5F, 0xFF9A7F5E, 0xFFC8C8C8)
    /** Text that is brighter than the usual text: names in a list. */
    val textBright get() = pick(0xFFF2F0EA, 0xFFF2F0EA, 0xFF14120E, 0xFFF3E2C7, 0xFFFFFFFF)
    val accent get() = pick(0xFFC8CDD3, 0xFFC8CDD3, 0xFF4F5862, 0xFFD9853B, 0xFFFFFFFF)
    val accentFill get() = pick(0xFF2A2D31, 0xFF2A2D31, 0xFFDCD6C9, 0xFF3D2615, 0xFF333333)
    /** Text and icons drawn on a solid accent. */
    val onAccent get() = pick(0xFF101112, 0xFF101112, 0xFFF3EFE6, 0xFF2A1608, 0xFF000000)
    val focusFill get() = pick(0xFF222427, 0xFF1A1B1D, 0xFFE7E1D4, 0xFF33221A, 0xFF2A2A2A)
    /** Section headings. */
    val section get() = pick(0xFFC8CDD3, 0xFFC8CDD3, 0xFF4F5862, 0xFFB08A5E, 0xFFFFFFFF)
    // Color-blind friendly: amber instead of rose, so warnings never depend on telling red from green.
    val danger get() = if (Access.colorBlind) (if (light) Color(0xFF8A5A00) else Color(0xFFF2B33D))
        else pick(0xFFD9A79E, 0xFFD9A79E, 0xFFA23B2C, 0xFFE0A89E, 0xFFFFB4A8)
    val dangerLine get() = if (Access.colorBlind) (if (light) Color(0xFFC79A45) else Color(0xFF7A5A1E))
        else if (light) Color(0xFFD9A79E) else Color(0xFF5A3A34)
    val dangerFill get() = if (Access.colorBlind) (if (light) Color(0xFFF6EBD4) else Color(0xFF221B0E))
        else if (light) Color(0xFFF5E2DD) else Color(0xFF1E1716)

    // Chat ("Quiet" look): gold marks the few things you act on (new chat, play, record, unread).
    val gold get() = pick(0xFFC2A25E, 0xFFC2A25E, 0xFF7A6128, 0xFFE9B56A, 0xFFFFD27A)
    val goldBright get() = pick(0xFFE0C27F, 0xFFE0C27F, 0xFF6B5216, 0xFFF3C77E, 0xFFFFE2A0)
    /** Text and icons drawn on gold. */
    val onGold get() = pick(0xFF101112, 0xFF101112, 0xFFF3EFE6, 0xFF2A1608, 0xFF000000)
    /** My own avatar circle (my initial in goldBright on it), and the soft gold tint behind highlighted things. */
    val goldTint get() = pick(0xFF3A3220, 0xFF3A3220, 0xFFEADDBB, 0xFF4A3421, 0xFF3D3214)
    /** The Nearby room's avatar circle. */
    val coolTint get() = pick(0xFF23272C, 0xFF23272C, 0xFFD6DDEB, 0xFF3A2D24, 0xFF333333)
    /** Search box, message box and their bubbles. */
    val field get() = pick(0xFF1B1D20, 0xFF141516, 0xFFECE7DC, 0xFF1C130C, 0xFF141414)
    val bubbleMine get() = pick(0xFF2B2E33, 0xFF2B2E33, 0xFFE2DCCF, 0xFF4A3421, 0xFF2A2A2A)
    /** The empty part of a voice message's progress bar. */
    val track get() = pick(0xFF44484E, 0xFF44484E, 0xFFA69D8B, 0xFF5E4430, 0xFF9A9A9A)
    /** The thin line under a conversation's top bar. */
    val hairline get() = pick(0xFF1F2125, 0xFF16181A, 0xFFD9D2C4, 0xFF3A2718, 0xFF9A9A9A)
    /**
     * The status dots (online, away, do not disturb) and the encrypted shield. Color-blind friendly uses blue, yellow
     * and vermilion, which stay apart without telling red from green; high contrast makes each brighter on black. The
     * status is always said in words too, and do not disturb has a bar across its dot.
     */
    val online get() = if (Access.colorBlind) Color(if (hc) 0xFF9FD3FF else if (light) 0xFF2F5F9E else 0xFF6FB3F0)
        else pick(0xFF7FB08A, 0xFF7FB08A, 0xFF2F7D46, 0xFF3FB27F, 0xFF9BE0A8)
    /** The away status dot (amber; yellow when color-blind friendly). */
    val away get() = if (Access.colorBlind) Color(if (hc) 0xFFFFF176 else if (light) 0xFF7A6200 else 0xFFF0E442)
        else pick(0xFFD9A440, 0xFFD9A440, 0xFF9A6B00, 0xFFE0B44C, 0xFFFFD84D)
    /** The do-not-disturb status dot (red; vermilion when color-blind friendly). */
    val dnd get() = if (Access.colorBlind) Color(if (hc) 0xFFFF9E66 else if (light) 0xFFB34700 else 0xFFE8743B)
        else pick(0xFFD9534F, 0xFFD9534F, 0xFFA23B2C, 0xFFD9534F, 0xFFFF7A7A)
    /** The offline status dot. */
    val offline get() = pick(0xFF6E6A62, 0xFF6E6A62, 0xFF877F71, 0xFF7E6548, 0xFFC8C8C8)

    /**
     * A person's avatar disc and the initial on it, picked from their name so it never changes (the same five tones
     * as Huginn). High contrast draws every initial white on near-black.
     */
    fun avatarTone(name: String): Pair<Color, Color> {
        if (hc) return Color(0xFF1F1F1F) to Color(0xFFFFFFFF)
        val tones = if (light) LIGHT_TONES else if (autumn) AUTUMN_TONES else DARK_TONES
        var h = 0
        for (ch in name) h = (h * 31 + ch.code) % 9973
        val t = tones[h % tones.size]
        return Color(t.first) to Color(t.second)
    }
    private val DARK_TONES = listOf(0xFF2C3A2CL to 0xFFB6D6B6L, 0xFF2D3340L to 0xFFB9C6E0L, 0xFF3A2724L to 0xFFE6A596L, 0xFF33293AL to 0xFFD2BCE0L, 0xFF24363AL to 0xFFAED4D8L)
    private val LIGHT_TONES = listOf(0xFFD5E4D3L to 0xFF2E5A33L, 0xFFD6DDEBL to 0xFF2F4A78L, 0xFFEED5CFL to 0xFF86392AL, 0xFFE2D6EAL to 0xFF5D3D78L, 0xFFD0E5E7L to 0xFF235A60L)
    private val AUTUMN_TONES = listOf(0xFF3D5A80L to 0xFFF3E2C7L, 0xFF7A4E3AL to 0xFFF3E2C7L, 0xFF6F5A2AL to 0xFFF3E2C7L, 0xFF4E6B5AL to 0xFFF3E2C7L, 0xFF5A4A72L to 0xFFF3E2C7L)
    /** The six colours a person can pick for their own initial disc (sent with their profile). */
    val MY_COLOURS = listOf("#C9A964", "#3D5A80", "#7A4E3A", "#4E7A4E", "#6B4E8A", "#8A8A8A")
    /** A colour someone picked ("#RRGGBB"), or null when it isn't one. */
    fun parse(hex: String?): Color? {
        val h = hex?.trim() ?: return null
        if (!Regex("^#[0-9A-Fa-f]{6}$").matches(h)) return null
        return Color(0xFF000000L or h.substring(1).toLong(16))
    }
    /** Text that reads on [bg]: near-black on a light disc, cream on a dark one. */
    fun onDisc(bg: Color): Color = if (bg.luminance() > 0.45f) Color(0xFF1A1208) else Color(0xFFF3E2C7)
}

/**
 * The shapes that change with the look. Every look but Autumn is sharp (the house style); Autumn rounds
 * buttons, panels, fields and the bars, and the composer becomes a pill.
 */
object LookShapes {
    private val sharp = RoundedCornerShape(4.dp)
    private val round = RoundedCornerShape(14.dp)
    private val pill = RoundedCornerShape(22.dp)
    private val barAll = RoundedCornerShape(22.dp)
    private val barTop = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    /** Panels, buttons and fields. */
    val corner: Shape get() = if (Palette.autumn) round else sharp
    /** The message box: a pill in Autumn, sharp elsewhere. */
    val composer: Shape get() = if (Palette.autumn) pill else sharp
    /** A bar with all corners rounded (the header). */
    val bar: Shape get() = if (Palette.autumn) barAll else RectangleShape
    /** A bar rounded along its top edge (the bottom tabs, the main list). */
    val barTopShape: Shape get() = if (Palette.autumn) barTop else RectangleShape
}

/**
 * A header, rail or tab bar. Most looks fill it with [fill] (unspecified: the ground shows through, nothing is
 * drawn). Autumn rounds it ([top]: only the top corners) and makes the fill a little see-through for the leaves.
 */
fun androidx.compose.ui.Modifier.bar(fill: Color = Color.Unspecified, top: Boolean = false): androidx.compose.ui.Modifier =
    if (!Palette.autumn) { if (fill.isSpecified) this.background(fill) else this }
    else {
        val shape = if (top) LookShapes.barTopShape else LookShapes.bar
        val tint = (if (fill.isSpecified) fill else Palette.panelLow).copy(alpha = if (top) 0.92f else 0.78f)
        (if (top) this else this.padding(horizontal = 10.dp, vertical = 4.dp)).clip(shape).background(tint)
    }

/**
 * The main list: a frosted panel in Autumn (leaves show softly through it, dim enough that muted text on top of a
 * bright leaf still reads); nothing elsewhere.
 */
fun androidx.compose.ui.Modifier.frosted(): androidx.compose.ui.Modifier =
    if (!Palette.autumn) this
    else this.padding(start = 10.dp, end = 10.dp, top = 8.dp).clip(LookShapes.barTopShape).background(Palette.panel.copy(alpha = 0.8f))

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
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        if (PrivateKeyboard.on) Private { Themed(content) } else Themed(content)
    }
}

/** Settings › Lock and privacy › Private keyboard. Set from the saved choice when Muninn starts, and by the switch. */
object PrivateKeyboard {
    var on by androidx.compose.runtime.mutableStateOf(false)
}

/**
 * Every text field under it asks the keyboard for private mode: nothing typed is learned or synced by the keyboard.
 * Most keyboards turn voice typing off in that mode too, so it's only on when Private keyboard is.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Private(content: @Composable () -> Unit) = androidx.compose.ui.platform.InterceptPlatformTextInput(Incognito, content)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private object Incognito : androidx.compose.ui.platform.PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: androidx.compose.ui.platform.PlatformTextInputMethodRequest,
        nextHandler: androidx.compose.ui.platform.PlatformTextInputSession,
    ): Nothing {
        val private = object : androidx.compose.ui.platform.PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection =
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
        }
        nextHandler.startInputMethod(private)
    }
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
