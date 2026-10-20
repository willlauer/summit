package com.willlauer.summit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

// The look kit: surfaces, controls and effects. In the pixel look everything is built on the pixel unit P; in Classic
// the same names draw the smooth versions (Material controls, rounded frosted buttons, soft glows).
private val pixelLook get() = LookState.current.pixel

/** Polar night: a smooth, very dark gradient; [snow] adds square flakes snapped to the pixel grid. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, snow: Boolean = false) {
    var t by remember { mutableFloatStateOf(0f) }
    if (snow) LaunchedEffect(Unit) { val t0 = withFrameNanos { it }; while (true) { t = (withFrameNanos { it } - t0) / 1e9f } }
    val look = LookState.current
    Canvas(modifier.fillMaxSize()) {
        val u = P.toPx()
        drawRect(Brush.verticalGradient(listOf(look.skyTop, look.bg, look.skyBottom)))
        if (!look.pixel) {  // Classic: soft violet and cyan glows at the edges, round flakes
            drawRect(Brush.radialGradient(listOf(Color(0x1FA874FF), Color.Transparent), center = Offset(size.width * .9f, -size.height * .05f), radius = size.width * 1.1f))
            drawRect(Brush.radialGradient(listOf(Color(0x143EE6FF), Color.Transparent), center = Offset(-size.width * .2f, size.height), radius = size.width))
        }
        if (snow) for (k in 0 until 64) {
            val depth = hash(k * 2.3f)                              // far flakes: one unit, dim, slow
            val y = ((hash(k * 5.3f) + t * (.015f + depth * .05f)) % 1f) * size.height
            val x = ((hash(k * 3.1f) * size.width + sin(t * (.3f + depth * .5f) + k) * 22.dp.toPx()) % size.width + size.width) % size.width
            if (!look.pixel) { drawCircle(Color.White.copy(alpha = .18f + depth * .55f), (0.8f + depth * 2.2f).dp.toPx(), Offset(x, y)); continue }
            val s = if (depth > .7f) 2 * u else u
            drawRect(Color.White.copy(alpha = .25f + depth * .55f), Offset(floor(x / u) * u, floor(y / u) * u), Size(s, s))
        }
    }
}

private fun hash(v: Float): Float { val s = sin(v * 12.9898f) * 43758.547f; return s - floor(s) }

/** The like control everywhere: a pixel heart. */
@Composable
fun LikeIcon(on: Boolean, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    Icon(if (on) PI.Heart else PI.HeartOutline, if (on) "Unlike" else "Like", modifier.size(size), tint = if (on) C.Magenta else C.Muted)

/** A chunky game button: pixel corners and a darker lip it presses down into. */
@Composable
fun GameButton(text: String, icon: ImageVector?, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    if (!pixelLook) {  // Classic: a frosted pill; primary is solid accent
        val fg = when { !enabled -> C.Faint; primary -> C.Ink; else -> C.Text }
        Row(modifier.clip(Pill).background(if (primary && enabled) C.Cyan else Color(0x1FFFFFFF)).border(1.dp, if (primary) Color.Transparent else C.Line, Pill)
            .clickable(src, androidx.compose.material3.ripple(), enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(19.dp), tint = fg); Spacer(Modifier.width(7.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
        }
        return
    }
    val face = when { !enabled -> C.Raised; primary -> C.Cyan; else -> C.Raised2 }
    val lip = when { !enabled -> C.Raised; primary -> C.Deep; else -> C.Line }
    val fg = when { !enabled -> C.Faint; primary -> C.Ink; else -> C.Text }
    val drop = if (pressed && enabled) P else P * 2
    Box(modifier.then(if (primary) Modifier.snowCap(text.hashCode() % 97, icicles = false, depth = 2) else Modifier)
        .clip(Px).background(lip).clickable(src, null, enabled = enabled, onClick = onClick).padding(bottom = drop, top = P * 2 - drop), propagateMinConstraints = true) {
        Row(Modifier.clip(Px).background(face).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = fg); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

/** A square touch target for an icon (replaces Material's round IconButton). */
@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(modifier.minimumInteractiveComponentSize().clip(Px).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(10.dp),
        contentAlignment = Alignment.Center) { CompositionLocalProvider(LocalContentColor provides C.Text) { content() } }
}
@Composable private fun Modifier.minimumInteractiveComponentSize() = this.defaultMinSize(44.dp, 44.dp)

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Row(modifier.clip(Px).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) C.Cyan else C.Faint) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(color = if (enabled) C.Cyan else C.Faint)) { content() }
        }
    }
}

/** Busy: three squares lighting in turn. */
@Composable
fun CircularProgressIndicator(modifier: Modifier = Modifier, color: Color = C.Cyan, strokeWidth: Dp = 0.dp) {
    if (!pixelLook) { androidx.compose.material3.CircularProgressIndicator(modifier.size(22.dp), color = color, strokeWidth = 2.dp); return }
    var k by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(140); k = (k + 1) % 3 } }
    Row(modifier.defaultMinSize(24.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(P * 2, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { Box(Modifier.size(P * 3).background(color.copy(alpha = if (it == k) 1f else .3f))) }
    }
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier, color: Color = C.Line) {
    if (!pixelLook) { androidx.compose.material3.HorizontalDivider(modifier, color = color); return }
    Canvas(modifier.fillMaxWidth().height(P)) { val u = P.toPx(); var x = 0f; while (x < size.width) { drawRect(color, Offset(x, 0f), Size(u, u)); x += u * 2 } }
}

/** Seek bar: a pixel track, the played part in ice, and a square thumb. */
@Composable
fun Slider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, onValueChangeFinished: (() -> Unit)? = null) {
    if (!pixelLook) {
        androidx.compose.material3.Slider(value, onValueChange, modifier, onValueChangeFinished = onValueChangeFinished,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.Cyan, inactiveTrackColor = C.Line))
        return
    }
    var w by remember { mutableIntStateOf(1) }
    Canvas(modifier.fillMaxWidth().height(28.dp).onSizeChanged { w = it.width }
        .pointerInput(Unit) { detectTapGestures { onValueChange((it.x / w).coerceIn(0f, 1f)); onValueChangeFinished?.invoke() } }
        .pointerInput(Unit) { detectHorizontalDragGestures(onDragEnd = { onValueChangeFinished?.invoke() }) { ch, _ -> onValueChange((ch.position.x / w).coerceIn(0f, 1f)) } }) {
        val u = P.toPx(); val cy = floor(size.height / 2 / u) * u
        drawRect(C.Line, Offset(0f, cy - u), Size(size.width, u * 2))
        val x = floor(value * (size.width - u * 6) / u) * u
        drawRect(C.Cyan, Offset(0f, cy - u), Size(x + u * 3, u * 2))
        drawRect(C.Shade, Offset(x, cy - u * 3 + u), Size(u * 6, u * 6))
        drawRect(Color.White, Offset(x, cy - u * 3), Size(u * 6, u * 6))
    }
}

@Composable
fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    if (!pixelLook) {
        androidx.compose.material3.Switch(checked, onCheckedChange, modifier,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Cyan, checkedThumbColor = C.Ink))
        return
    }
    Box(modifier.size(48.dp, 28.dp).clip(Px).background(if (checked) C.Cyan else C.Raised2).border(P, if (checked) C.Deep else C.Line, Px)
        .clickable(role = Role.Switch) { onCheckedChange(!checked) }.padding(P * 2), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(20.dp).clip(PxSmall).background(if (checked) C.Ink else C.Muted))
    }
}

@Composable
fun RadioButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!pixelLook) {
        androidx.compose.material3.RadioButton(selected, onClick, modifier, colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = C.Green))
        return
    }
    Box(modifier.minimumInteractiveComponentSize().clickable(role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).clip(PxSmall).border(P, if (selected) C.Green else C.Faint, PxSmall).padding(P * 2), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.fillMaxSize().background(C.Green))
        }
    }
}

/** Text input in a pixel frame. */
@Composable
fun PixelField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null, placeholder: String? = null,
               keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    Column(modifier) {
        if (label != null) { Text(label, style = MaterialTheme.typography.labelMedium, color = C.Muted); Spacer(Modifier.height(6.dp)) }
        Box(Modifier.fillMaxWidth().clip(Px).background(C.Raised).border(P, C.Line, Px).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = C.Faint)
            BasicTextField(value, onValueChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
                cursorBrush = SolidColor(C.Cyan), keyboardOptions = keyboardOptions)
        }
    }
}

/** Pressing anything flashes a flat frost overlay in its own (pixel) shape, instead of a round ripple. */
object PixelIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7
    private class Node(private val src: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var pressed = false
        override fun onAttach() {
            coroutineScope.launch {
                src.interactions.collect {
                    pressed = when (it) { is PressInteraction.Press -> true; is PressInteraction.Release, is PressInteraction.Cancel -> false; else -> pressed }
                    invalidateDraw()
                }
            }
        }
        override fun ContentDrawScope.draw() { drawContent(); if (pressed) drawRect(Color(0x2EBFEFFF)) }
    }
}

/**
 * Pixel look: a drift of snow along the top edge (drawn just over it, so it sits *on* the box), and here and there an
 * icicle hanging inside. [seed] varies the drift so neighbouring boxes don't match. Does nothing in Classic.
 */
fun Modifier.snowCap(seed: Int = 0, icicles: Boolean = true, depth: Int = 3, inside: Boolean = false): Modifier = this.drawWithContent {
    drawContent()
    if (!LookState.current.pixel) return@drawWithContent
    val u = P.toPx()
    val cols = (size.width / u).toInt()
    val s = seed * 7.31f
    val base = if (inside) depth * u else 0f  // inside: the drift lies within the box (when its shape clips everything above)
    for (c in 0 until cols) {
        val x = c * u
        // a soft drift: two slow waves plus a little grain, in whole pixel units (0..depth)
        val h = (1.5f + sin(c * 0.09f + s) * 1.1f + sin(c * 0.23f + s * 2.1f) * 0.7f + (hash(c + s) - .5f) * .8f).roundToInt().coerceIn(0, depth)
        if (h == 0 || c < 2 || c > cols - 3) continue  // keep clear of the pixel corners
        for (k in 0 until h) drawRect(if (k == h - 1) Color(0xFFF2FBFF) else Color(0xFFD3EFFF), Offset(x, base - k * u - u), Size(u, u))
        drawRect(Color(0x669DCBE6), Offset(x, base), Size(u, u))  // where the snow meets the box
        if (icicles && h >= 2 && hash(c * 1.7f + s) > .975f) {  // the odd icicle
            val len = 2 + (hash(c * 3.1f + s) * 3).toInt()
            for (k in 1..len) drawRect(Color(0xFFCFEFFF).copy(alpha = .9f - k * .15f), Offset(x, base + k * u), Size(u, u))
        }
    }
}
