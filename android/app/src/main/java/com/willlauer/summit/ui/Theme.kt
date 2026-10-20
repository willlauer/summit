package com.willlauer.summit.ui

import androidx.compose.foundation.LocalIndication
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

    val all = listOf(Classic, Pixel)
    fun byKey(k: String?) = all.find { it.key == k } ?: Pixel
}

/** The look in use (persisted by Repo). Reading it in a composable re-renders that composable when it changes. */
object LookState {
    var current by mutableStateOf(Looks.Pixel); private set
    fun set(l: Look) { com.willlauer.summit.data.PALETTE = l.palette; current = l }
    init { com.willlauer.summit.data.PALETTE = Looks.Pixel.palette }
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
}

private fun pixel(w: Int) = Font(R.font.pixelify, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private val PixelFont = FontFamily(pixel(400), pixel(500), pixel(600), pixel(700))
private val InterFont = FontFamily(inter(400), inter(500), inter(600), inter(700))
/** The look's typeface (pixel type in the pixel look, Inter in Classic). */
val Pixel: FontFamily get() = if (L.pixel) PixelFont else InterFont

private fun typography(l: Look): Typography {
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
val PxSmall: Shape get() = if (L.pixel) pxShapes[0] else roundShapes[0]
val Px: Shape get() = if (L.pixel) pxShapes[1] else roundShapes[1]
val PxBig: Shape get() = if (L.pixel) pxShapes[2] else roundShapes[2]
val Pill: Shape get() = if (L.pixel) pxShapes[3] else roundShapes[3]

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
