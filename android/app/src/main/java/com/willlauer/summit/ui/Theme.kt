package com.willlauer.summit.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willlauer.summit.R

/**
 * A look: everything that changes when you switch Summit's style. Colours, the cluster palette, type, shapes, icons
 * and how the map draws (pixel grid or smooth glow, wind or still sky). Screens read the current look through [C],
 * the shapes, [Type] and [PI], so switching restyles everything. The desktop has the same two (web/shared.css).
 */
data class Look(
    val key: String, val label: String, val pixel: Boolean,
    val bg: Color, val panel: Color, val raised: Color, val raised2: Color,
    val text: Color, val muted: Color, val faint: Color, val line: Color,
    val accent: Color, val accentLight: Color, val accentDeep: Color, val like: Color, val select: Color, val added: Color, val similar: Color,
    val ink: Color, val shade: Color,
    val palette: IntArray,                 // cluster colours on the map
    val skyTop: Color, val skyBottom: Color, val wind: Boolean,
    /** The desktop's neon family (web/shared.css): Michroma / Exo 2, cut-corner tube buttons, glowing titles, haze and rain. */
    val neon: Boolean = false, val tube: Color = accent, val sign: Color = accentDeep,
)

private fun ints(vararg c: Long) = c.map { it.toInt() }.toIntArray()

object Looks {
    /** The desktop's look: dark glass, Inter, neon cluster colours, round glowing stars, a still sky. */
    val Classic = Look(
        "classic", "Classic", pixel = false,
        bg = Color(0xFF08080D), panel = Color(0xFF0D0D15), raised = Color(0xFF14141F), raised2 = Color(0xFF1B1B29),
        text = Color(0xFFECEEF5), muted = Color(0xFF8B8FA4), faint = Color(0xFF5A5E72), line = Color(0x1FFFFFFF),
        accent = Color(0xFF3EE6FF), accentLight = Color(0xFF9FF3FF), accentDeep = Color(0xFF1E9FBF), like = Color(0xFFFF4D8D),
        select = Color(0xFFFFD84D), added = Color(0xFF3DFFB0), similar = Color(0xFFA874FF), ink = Color(0xFF031317), shade = Color(0xFF000000),
        palette = ints(0xFF00F0FF, 0xFFFF2A6D, 0xFF05FFA1, 0xFFB026FF, 0xFFFF9A3D, 0xFF5AA9FF, 0xFFFCEE0A, 0xFFF45BFF,
            0xFF3DFFC5, 0xFFFF5C5C, 0xFFA8FF3D, 0xFFFFB3D9, 0xFF7D7DFF, 0xFFE2B45A, 0xFF40C4AA, 0xFFC9A6FF),
        skyTop = Color(0xFF110C1F), skyBottom = Color(0xFF08080D), wind = false,
    )

    /** Pixel-art ice: pixel type, stepped corners, pixel icons, square stars on the grid, wind and blown snow. */
    val Pixel = Look(
        "pixel", "Pixel", pixel = true,
        bg = Color(0xFF050B14), panel = Color(0xFF0A1422), raised = Color(0xFF0F1D30), raised2 = Color(0xFF16283F),
        text = Color(0xFFEAF6FF), muted = Color(0xFF8DA6BF), faint = Color(0xFF51697F), line = Color(0xFF1C3350),
        accent = Color(0xFF7FDBFF), accentLight = Color(0xFFBFEFFF), accentDeep = Color(0xFF2F8FD8), like = Color(0xFFFF7BA8),
        select = Color(0xFFD7F5FF), added = Color(0xFF7AF2DC), similar = Color(0xFFA9B8FF), ink = Color(0xFF031220), shade = Color(0xFF02060C),
        palette = ints(0xFF7FDBFF, 0xFF4FA8FF, 0xFFA9B8FF, 0xFF7AF2DC, 0xFFC5A8FF, 0xFF3FD0E0, 0xFFE3F6FF, 0xFF6F8CFF,
            0xFF9FFFF0, 0xFF5CC8FF, 0xFFB4E2FF, 0xFF8F7DFF, 0xFF49E3B8, 0xFFD6C8FF, 0xFF66B2D9, 0xFF2FB8FF),
        skyTop = Color(0xFF071019), skyBottom = Color(0xFF03070D), wind = true,
    )

    /** The look (as on the desktop, web/shared.css): electric blue and ice white on near-black, smooth glowing stars. */
    val IceWire = Look(
        "icewire", "Ice Wire", pixel = false,
        bg = Color(0xFF03060F), panel = Color(0xFF060C1C), raised = Color(0xFF0C1A3A), raised2 = Color(0xFF12244A),
        text = Color(0xFFE2EFFF), muted = Color(0xFF8EA8D6), faint = Color(0xFF4C6290), line = Color(0x422F7BFF),
        accent = Color(0xFFD8F6FF), accentLight = Color(0xFFFFFFFF), accentDeep = Color(0xFF2F7BFF), like = Color(0xFF2F7BFF),
        select = Color(0xFFD8F6FF), added = Color(0xFF6FFFE0), similar = Color(0xFF6E8BFF), ink = Color(0xFF020814), shade = Color(0xFF000000),
        palette = ints(0xFF2F7BFF, 0xFF7CC8FF, 0xFF6FFFE0, 0xFFA48BFF, 0xFFD8F6FF, 0xFF38B6FF, 0xFF5CE1E6, 0xFF6E8BFF,
            0xFF9FE8FF, 0xFF4F6DFF, 0xFF3DFFC0, 0xFFC6D8FF, 0xFF00D2FF, 0xFF8EF0FF, 0xFF7A7DFF, 0xFFB8F2FF),
        skyTop = Color(0xFF07122A), skyBottom = Color(0xFF02040A), wind = false,
        neon = true, tube = Color(0xFFD8F6FF), sign = Color(0xFF2F7BFF),
    )

    // Ice Wire is locked in; Classic and Pixel stay defined for reference but aren't offered.
    val all = listOf(IceWire)
    fun byKey(@Suppress("UNUSED_PARAMETER") k: String?) = IceWire
}

/** The look in use (persisted by Repo). Reading it in a composable re-renders that composable when it changes. */
object LookState {
    var current by mutableStateOf(Looks.IceWire); private set
    fun set(l: Look) { com.willlauer.summit.data.PALETTE = l.palette; current = l }
    init { com.willlauer.summit.data.PALETTE = Looks.IceWire.palette }
}
private val L get() = LookState.current

// One pixel unit: corners, borders, icons, cover art, map stars and snow all use it in the pixel look.
val P: Dp = 2.dp

/** Colours by role, from the current look. */
object C {
    val Bg get() = L.bg
    val Panel get() = L.panel
    val Raised get() = L.raised
    val Raised2 get() = L.raised2
    val Text get() = L.text
    val Muted get() = L.muted
    val Faint get() = L.faint
    val Line get() = L.line
    val Cyan get() = L.accent          // primary actions, selection
    val Glacier get() = L.accentLight
    val Deep get() = L.accentDeep      // the lip under primary buttons
    val Magenta get() = L.like         // likes
    val Yellow get() = L.select        // the selected track on the map
    val Green get() = L.added          // in the playlist being built
    val Violet get() = L.similar       // similar / suggestions
    val Ink get() = L.ink              // text on the accent colour
    val Shade get() = L.shade          // hard shadows and outlines
    val Tube get() = L.tube            // neon: button tubes, h2
    val Sign get() = L.sign            // neon: primary fill, glows, h3
    val Hi = Color.White               // brightest text: titles, the playing track
}
/** The neon (desktop) styling is on. */
val Neon: Boolean get() = L.neon

private fun pixel(w: Int) = Font(R.font.pixelify, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private fun exo(w: Int) = Font(R.font.exo2, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private val PixelFont = FontFamily(pixel(400), pixel(500), pixel(600), pixel(700))
private val InterFont = FontFamily(inter(400), inter(500), inter(600), inter(700))
/** Neon: Michroma for titles (wide, one weight), Exo 2 for everything else, as on the desktop. */
val Display = FontFamily(Font(R.font.michroma, FontWeight.Normal))
private val ExoFont = FontFamily(exo(400), exo(500), exo(600), exo(700))
/** The look's typeface (pixel type in the pixel look, Exo 2 in neon, Inter in Classic). */
val Pixel: FontFamily get() = if (L.pixel) PixelFont else if (L.neon) ExoFont else InterFont

private fun neonTypography(l: Look): Typography {
    val glow = Shadow(l.sign.copy(alpha = .55f), Offset.Zero, 22f)
    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, color = C.Hi, shadow = glow, letterSpacing = 1.2.sp)
    val text = TextStyle(fontFamily = ExoFont, color = l.text)
    return Typography(
        displaySmall = title.copy(fontSize = 26.sp),
        headlineMedium = title.copy(fontSize = 22.sp),
        headlineSmall = title.copy(fontSize = 19.sp),
        titleLarge = title.copy(fontSize = 17.sp),
        titleMedium = title.copy(fontSize = 13.sp, color = l.tube, letterSpacing = 1.6.sp, shadow = Shadow(l.sign.copy(alpha = .4f), Offset.Zero, 14f)),
        titleSmall = text.copy(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = text.copy(fontSize = 16.sp),
        bodyMedium = text.copy(fontSize = 14.5.sp),
        bodySmall = text.copy(fontSize = 13.sp, color = l.muted),
        labelLarge = text.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
        labelMedium = text.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp),
        labelSmall = text.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp),
    )
}

private fun typography(l: Look): Typography {
    if (l.neon) return neonTypography(l)
    val f = if (l.pixel) PixelFont else InterFont
    val title = TextStyle(fontFamily = f, color = l.text, shadow = if (l.pixel) Shadow(l.shade, Offset(0f, 5f), 0f) else null,
        letterSpacing = if (l.pixel) 0.sp else (-0.4).sp)
    val text = TextStyle(fontFamily = f, color = l.text)
    return Typography(
        displaySmall = title.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold),
        headlineMedium = title.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold),
        headlineSmall = title.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleLarge = title.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = title.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = text.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = text.copy(fontSize = 16.sp),
        bodyMedium = text.copy(fontSize = if (l.pixel) 15.sp else 14.sp),
        bodySmall = text.copy(fontSize = if (l.pixel) 13.sp else 12.5.sp, color = l.muted),
        labelLarge = text.copy(fontSize = if (l.pixel) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = text.copy(fontSize = if (l.pixel) 13.sp else 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = text.copy(fontSize = if (l.pixel) 12.sp else 11.sp, fontWeight = FontWeight.Medium),
    )
}
private val types = HashMap<String, Typography>()
val Type: Typography get() = types.getOrPut(L.key) { typography(L) }

/** Pixel corners: each corner is a staircase of [steps] pixel units instead of a curve. */
class PixelShape(private val steps: Int = 2, private val unit: Dp = P) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(pixelPath(size, with(density) { unit.toPx() }, steps = steps))
}

/** The outline of a pixel-cornered box: a staircase of [steps] stairs of [u] px at each corner, clockwise from the top. */
fun pixelPath(size: Size, u: Float, origin: Offset = Offset.Zero, steps: Int = 2): Path = Path().apply {
    val (x0, y0) = origin; val x1 = x0 + size.width; val y1 = y0 + size.height
    val n = steps.coerceAtMost((size.minDimension / u / 3).toInt()).coerceAtLeast(0)
    moveTo(x0 + n * u, y0)
    lineTo(x1 - n * u, y0)
    for (i in 1..n) { lineTo(x1 - (n - i) * u, y0 + (i - 1) * u); lineTo(x1 - (n - i) * u, y0 + i * u) }
    lineTo(x1, y1 - n * u)
    for (i in 1..n) { lineTo(x1 - (i - 1) * u, y1 - (n - i) * u); lineTo(x1 - i * u, y1 - (n - i) * u) }
    lineTo(x0 + n * u, y1)
    for (i in 1..n) { lineTo(x0 + (n - i) * u, y1 - (i - 1) * u); lineTo(x0 + (n - i) * u, y1 - i * u) }
    lineTo(x0, y0 + n * u)
    for (i in 1..n) { lineTo(x0 + (i - 1) * u, y0 + (n - i) * u); lineTo(x0 + i * u, y0 + (n - i) * u) }
    close()
}

// Shapes, smallest to largest. Every box in the app uses one of these.
private val pxShapes = listOf(PixelShape(1), PixelShape(2), PixelShape(3), PixelShape(3))
private val roundShapes = listOf(RoundedCornerShape(8.dp), RoundedCornerShape(12.dp), RoundedCornerShape(20.dp), RoundedCornerShape(50))
// Neon: the desktop's octagon clip (--n-clip): corners cut at 45°.
private val cutShapes = listOf(CutCornerShape(3.dp), CutCornerShape(6.dp), CutCornerShape(9.dp), CutCornerShape(6.dp))
private fun shape(k: Int) = if (L.pixel) pxShapes[k] else if (L.neon) cutShapes[k] else roundShapes[k]
val PxSmall: Shape get() = shape(0)
val Px: Shape get() = shape(1)
val PxBig: Shape get() = shape(2)
val Pill: Shape get() = shape(3)

@Composable
fun SummitTheme(content: @Composable () -> Unit) {
    val l = LookState.current
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = l.accent, onPrimary = l.ink, primaryContainer = l.raised2, onPrimaryContainer = l.accentLight,
            secondary = l.like, tertiary = l.accentLight,
            background = l.bg, onBackground = l.text, surface = l.panel, onSurface = l.text,
            surfaceVariant = l.raised, onSurfaceVariant = l.muted, surfaceContainer = l.raised, surfaceContainerHigh = l.raised2,
            surfaceContainerLow = l.panel, surfaceContainerHighest = l.raised2, surfaceContainerLowest = l.bg,
            outline = l.faint, outlineVariant = l.line, scrim = Color(0xCC02060C),
        ),
        typography = Type,
    ) { CompositionLocalProvider(LocalIndication provides if (l.pixel) PixelIndication else ripple(), content = content) }
}
