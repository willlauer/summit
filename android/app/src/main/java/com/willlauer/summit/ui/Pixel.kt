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
import kotlin.math.sin

// The pixel kit: every surface, control and effect is built on the pixel unit P, in the ice palette.

/** Polar night: a smooth, very dark gradient; [snow] adds square flakes snapped to the pixel grid. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, snow: Boolean = false) {
    var t by remember { mutableFloatStateOf(0f) }
    if (snow) LaunchedEffect(Unit) { val t0 = withFrameNanos { it }; while (true) { t = (withFrameNanos { it } - t0) / 1e9f } }
    Canvas(modifier.fillMaxSize()) {
        val u = P.toPx()
        drawRect(Brush.verticalGradient(listOf(Color(0xFF07101B), Color(0xFF050B14), Color(0xFF03070D))))
        if (snow) for (k in 0 until 64) {
            val depth = hash(k * 2.3f)                              // far flakes: one unit, dim, slow
            val y = ((hash(k * 5.3f) + t * (.015f + depth * .05f)) % 1f) * size.height
            val x = ((hash(k * 3.1f) * size.width + sin(t * (.3f + depth * .5f) + k) * 22.dp.toPx()) % size.width + size.width) % size.width
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
    val face = when { !enabled -> C.Raised; primary -> C.Cyan; else -> C.Raised2 }
    val lip = when { !enabled -> C.Raised; primary -> C.Deep; else -> C.Line }
    val fg = when { !enabled -> C.Faint; primary -> C.Ink; else -> C.Text }
    val drop = if (pressed && enabled) P else P * 2
    Box(modifier.clip(Px).background(lip).clickable(src, null, enabled = enabled, onClick = onClick).padding(bottom = drop, top = P * 2 - drop), propagateMinConstraints = true) {
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
    var k by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(140); k = (k + 1) % 3 } }
    Row(modifier.defaultMinSize(24.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(P * 2, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { Box(Modifier.size(P * 3).background(color.copy(alpha = if (it == k) 1f else .3f))) }
    }
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier, color: Color = C.Line) {
    Canvas(modifier.fillMaxWidth().height(P)) { val u = P.toPx(); var x = 0f; while (x < size.width) { drawRect(color, Offset(x, 0f), Size(u, u)); x += u * 2 } }
}

/** Seek bar: a pixel track, the played part in ice, and a square thumb. */
@Composable
fun Slider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, onValueChangeFinished: (() -> Unit)? = null) {
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
    Box(modifier.size(48.dp, 28.dp).clip(Px).background(if (checked) C.Cyan else C.Raised2).border(P, if (checked) C.Deep else C.Line, Px)
        .clickable(role = Role.Switch) { onCheckedChange(!checked) }.padding(P * 2), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(20.dp).clip(PxSmall).background(if (checked) C.Ink else C.Muted))
    }
}

@Composable
fun RadioButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
