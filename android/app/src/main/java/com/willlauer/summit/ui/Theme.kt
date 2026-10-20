package com.willlauer.summit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

// Ice, in pixels. One pixel unit (P = 2 dp) for everything: corners, borders, icons, cover art, map stars, snow.
// Palette: a deep polar night, glacier blues and aquas; warmth only for likes.
val P: Dp = 2.dp

object C {
    val Bg = Color(0xFF050B14)
    val Panel = Color(0xFF0A1422)
    val Raised = Color(0xFF0F1D30)
    val Raised2 = Color(0xFF16283F)
    val Text = Color(0xFFEAF6FF)
    val Muted = Color(0xFF8DA6BF)
    val Faint = Color(0xFF51697F)
    val Line = Color(0xFF1C3350)        // pixel borders (opaque, so edges stay crisp)
    val Cyan = Color(0xFF7FDBFF)        // ice: primary actions, selection
    val Glacier = Color(0xFFBFEFFF)
    val Deep = Color(0xFF2F8FD8)        // the lip under primary buttons
    val Magenta = Color(0xFFFF7BA8)     // likes
    val Yellow = Color(0xFFD7F5FF)      // frost white (selection on the map)
    val Green = Color(0xFF7AF2DC)       // in the playlist being built: aqua
    val Violet = Color(0xFFA9B8FF)      // similar / suggestions: periwinkle
    val Ink = Color(0xFF031220)         // text on ice
    val Shade = Color(0xFF02060C)       // hard pixel shadows and outlines
}

private fun pixel(w: Int) = Font(R.font.pixelify, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
/** All type is pixel type: titles, lists, buttons, everything. */
val Pixel = FontFamily(pixel(400), pixel(500), pixel(600), pixel(700))
val Sans = Pixel

private val title = TextStyle(fontFamily = Pixel, color = C.Text, shadow = Shadow(C.Shade, Offset(0f, 5f), 0f))  // hard, one unit down
private val text = TextStyle(fontFamily = Pixel, color = C.Text)
val Type = Typography(
    displaySmall = title.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = title.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineSmall = title.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = title.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = title.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = text.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = text.copy(fontSize = 16.sp),
    bodyMedium = text.copy(fontSize = 15.sp),
    bodySmall = text.copy(fontSize = 13.sp, color = C.Muted),
    labelLarge = text.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = text.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = Pixel, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

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
val PxSmall = PixelShape(1)
val Px = PixelShape(2)
val PxBig = PixelShape(3)
val Pill = PixelShape(3)

@Composable
fun SummitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Cyan, onPrimary = C.Ink, primaryContainer = Color(0xFF123A5A), onPrimaryContainer = C.Glacier,
            secondary = C.Magenta, tertiary = C.Glacier,
            background = C.Bg, onBackground = C.Text, surface = C.Panel, onSurface = C.Text,
            surfaceVariant = C.Raised, onSurfaceVariant = C.Muted, surfaceContainer = C.Raised, surfaceContainerHigh = C.Raised2,
            surfaceContainerLow = C.Panel, surfaceContainerHighest = C.Raised2, surfaceContainerLowest = C.Bg,
            outline = C.Faint, outlineVariant = C.Line, scrim = Color(0xCC02060C),
        ),
        typography = Type,
    ) { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides PixelIndication, content = content) }
}
