package com.willlauer.summit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** Shown when the player powers on (Summit as the home screen, a fresh start): tap anywhere to go in. */
object Landing {
    var show by mutableStateOf(false)
    /** Kept at 1 (the app shows normally); the landing page sets it to 0 while it's up. */
    var reveal by mutableFloatStateOf(1f)
    /** Bumped on the glitch cut into the app: SummitRoot jitters the app into place. */
    var jolt by mutableIntStateOf(0)
    /** Last touch anywhere in the app (SummitRoot): the app's rain holds still while you're using it. */
    @Volatile var lastTouch = 0L
}

/**
 * The landing page: the Ice Wire peak as a neon tube (like the app icon), SUMMIT with the desktop's chromatic split,
 * the time, what's on the card and the last track.
 * Entry: the tube draws itself along the ridge, the glint flares, SUMMIT flickers on like a sign, the rest fades in,
 * rain falls. Tap: a flash of light and the peak rushes forward into the app (SummitRoot eases the app in).
 */
@Composable
fun LandingScreen() {
    AnimatedVisibility(Landing.show, enter = androidx.compose.animation.fadeIn(tween(250)), exit = androidx.compose.animation.ExitTransition.None) {
        val app = LocalApp.current
        val lib by app.repo.lib.collectAsStateWithLifecycle()
        val ps by app.player.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var now by remember { mutableStateOf(java.util.Date()) }
        LaunchedEffect(Unit) { while (true) { now = java.util.Date(); delay(10_000) } }
        val appear = remember { androidx.compose.animation.core.Animatable(0f) }
        val leave = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { Landing.reveal = 0f; appear.animateTo(1f, tween(2400, easing = androidx.compose.animation.core.LinearEasing)) }
        val a = appear.value
        fun phase(from: Float, len: Float) = ((a - from) / len).coerceIn(0f, 1f)
        val draw = androidx.compose.animation.core.FastOutSlowInEasing.transform(phase(0f, .5f))
        val glint = phase(.42f, .2f)
        val titleT = phase(.55f, .25f)
        val chrome = androidx.compose.animation.core.FastOutSlowInEasing.transform(phase(.3f, .45f))
        // a neon sign striking: off, on, off, half, on
        val title = if (titleT >= 1f) 1f else floatArrayOf(0f, 0f, .9f, .1f, .6f, .2f, 1f, .7f, 1f)[(titleT * 8.99f).toInt()]
        val pulse = rememberInfiniteTransition(label = "landing")
        val breathe by pulse.animateFloat(.8f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "tube")
        val blink by pulse.animateFloat(.35f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "tap")
        // Going in: a glitch cut. The page tears into slices that jump sideways and its blue / ice colour split pulls
        // wide, harder each step; then it cuts to the app, which jitters into place (SummitRoot, Landing.jolt).
        val l = leave.value
        val sign = C.Sign; val tube = C.Tube
        Box(Modifier.fillMaxSize()
            .drawWithContent {
                if (l <= 0f) { drawContent(); return@drawWithContent }
                val w = size.width; val h = size.height
                val step = (l * 9).toInt()                                    // re-randomise every ~40 ms
                val rnd = { k: Int, m: Float -> val v = kotlin.math.sin((step * 31 + k) * 12.9898f * m) * 43758.547f; v - kotlin.math.floor(v) }
                val power = l * l
                val split = (2f + power * 10f).dp.toPx()
                // the colour split: blue copy pulled left, ice copy pulled right, added on top of each other
                drawIntoCanvas { c ->
                    for ((col, dx) in listOf(sign to -split, tube to split)) {
                        val paint = androidx.compose.ui.graphics.Paint().apply {
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(col, androidx.compose.ui.graphics.BlendMode.Modulate)
                            blendMode = androidx.compose.ui.graphics.BlendMode.Plus
                            alpha = .35f + power * .25f
                        }
                        c.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, w, h), paint)
                        c.translate(dx, 0f)
                        drawContent()
                        c.restore()
                    }
                }
                // the tear: horizontal slices of the page, each shoved sideways
                var y = 0f; var k = 0
                while (y < h) {
                    val sh = h * (.06f + rnd(k, 1.7f) * .16f)
                    val torn = rnd(k, 2.3f) < .18f + power * .25f
                    val dx = if (torn) (rnd(k, 3.1f) - .5f) * w * (.03f + power * .12f) else 0f
                    clipRect(0f, y, w, y + sh) { translate(dx, 0f) { this@drawWithContent.drawContent() } }
                    if (torn && rnd(k, 4.7f) < .15f) drawRect((if (rnd(k, 5.3f) < .5f) sign else tube).copy(alpha = .2f), Offset(0f, y), androidx.compose.ui.geometry.Size(w, sh * .12f))
                    y += sh; k++
                }
                // scanlines that flicker with the steps
                if (step % 2 == 0) for (sy in 0 until (h / 6.dp.toPx()).toInt()) drawRect(Color.Black.copy(alpha = .08f), Offset(0f, sy * 6.dp.toPx()), androidx.compose.ui.geometry.Size(w, 1.dp.toPx()))
            }
            .clickable(remember { MutableInteractionSource() }, null) {
                if (leave.value == 0f) scope.launch {
                    leave.animateTo(1f, tween(380, easing = androidx.compose.animation.core.LinearEasing))
                    Landing.reveal = 1f
                    Landing.jolt++          // the app jitters into place
                    Landing.show = false    // hard cut
                }
            }) {
            Box(Modifier.fillMaxSize().background(C.Bg)) {
                Backdrop(rain = false)
                Rain(Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val gone = 1f
                    Column(Modifier.graphicsLayer { alpha = chrome * gone; translationY = (1f - chrome) * -40f }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault()).format(now),
                            style = MaterialTheme.typography.displaySmall.copy(fontFamily = Display, fontSize = 44.sp, shadow = Shadow(C.Sign.copy(alpha = .6f), Offset.Zero, 26f)), color = C.Hi)
                        Text(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(now).uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp), color = C.Muted)
                    }
                    Spacer(Modifier.weight(1f))
                    Peak(Modifier.size(220.dp).alpha(if (a < 1f || l > 0f) 1f else breathe), draw, glint)
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.graphicsLayer { alpha = title * gone; scaleX = 1.12f - .12f * titleT; scaleY = 1.12f - .12f * titleT }) {
                        NeonTitle("Summit", style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp, letterSpacing = (6 + (1 - titleT) * 10).sp), maxLines = 1, flicker = false)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.graphicsLayer { alpha = chrome * gone; translationY = (1f - chrome) * 40f }, horizontalAlignment = Alignment.CenterHorizontally) {
                        val t = ps.current?.let { lib?.tracks?.getOrNull(it) }
                        if (t != null) Text("${t.name} · ${t.by}", style = MaterialTheme.typography.bodyMedium, color = C.Text, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        Text("TAP TO ENTER", Modifier.alpha(if (a < 1f) chrome * .6f else blink),
                            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 4.sp, shadow = Shadow(C.Tube, Offset.Zero, 14f)), color = C.Tube)
                    }
                }
            }
        }
    }
}

/** Falling rain: full speed on the landing page; the app's backdrop uses it slower ([speed]). */
@Composable
fun Rain(modifier: Modifier, speed: Float = 1f, boost: () -> Float = { 0f }) {
    var t by remember { mutableFloatStateOf(0f) }
    // The slow rain behind the app moves at 30 fps (it can't tell), the landing page's at full rate.
    // Behind the app it also holds still while you're touching the screen or typing (and for 2 s after), so it never
    // costs a frame while you're doing something; then it carries on from where it stopped.
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            if (speed < 1f && (Keys.target != null || System.currentTimeMillis() - Landing.lastTouch < 2000)) { delay(250); last = withFrameNanos { it }; continue }
            val now = withFrameNanos { it }
            t += (now - last) / 1e9f * (1f + boost() * 5f); last = now
            if (speed < 1f) delay(33)
        }
    }
    // Its own layer: each frame redraws only the rain, never the screen above it.
    Canvas(modifier.graphicsLayer()) {
        val h = size.height; val w = size.width
        val b = boost()
        for (k in 0 until 60 + (b * 80).toInt()) {
            val seed = k * 12.9898f
            val r = { m: Float -> val v = kotlin.math.sin(seed * m) * 43758.547f; v - kotlin.math.floor(v) }
            val len = (30 + r(1.3f) * 70).dp.toPx() * (1f + b * 2.5f)
            val fall = h * (.9f + r(2.1f) * .8f) * speed
            val y = ((r(3.7f) * h + t * fall) % (h + len)) - len
            val x = (r(5.3f) * (w + h * .2f)) - y * .18f
            drawLine(Color(0xFFC8D7FF).copy(alpha = (.06f + r(7.1f) * .1f) * (1f + b * 2.5f)), Offset(x, y), Offset(x - len * .18f, y + len), (1f + b).dp.toPx())
        }
    }
}

/** The app icon's peak as a neon tube: wide blue glow, tighter glow, ice core, white-hot centre, and the glint.
 *  [draw] 0..1 traces it along the ridge; [glint] 0..1 flares the star in. */
@Composable
private fun Peak(modifier: Modifier, draw: Float = 1f, glint: Float = 1f) {
    val sign = C.Sign; val tube = C.Tube
    Canvas(modifier) {
        val s = size.minDimension
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val full = Path().apply {
            val pts = listOf(p(.10f, .80f), p(.38f, .38f), p(.50f, .50f), p(.62f, .26f), p(.90f, .80f))
            moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val pm = androidx.compose.ui.graphics.PathMeasure().apply { setPath(full, false) }
        val peak = Path().also { pm.getSegment(0f, pm.length * draw, it, true) }
        val ridge = Path().apply {
            moveTo(p(.54f, .40f).x, p(.54f, .40f).y); lineTo(p(.62f, .26f).x, p(.62f, .26f).y); lineTo(p(.70f, .40f).x, p(.70f, .40f).y)
        }
        val ridgeOn = ((draw - .55f) / .3f).coerceIn(0f, 1f)
        for ((w, c, a) in listOf(Triple(.10f, sign, .10f), Triple(.065f, sign, .18f), Triple(.04f, sign, .35f), Triple(.022f, tube, .9f), Triple(.009f, Color.White, 1f))) {
            val st = Stroke(width = w * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(peak, c.copy(alpha = a), style = st)
            if (ridgeOn > 0f) drawPath(ridge, c.copy(alpha = a * .8f * ridgeOn), style = Stroke(width = w * s * .7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (draw in 0.01f..0.99f) {  // the bright head of the tube as it draws
            val head = pm.getPosition(pm.length * draw)
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White, tube.copy(alpha = .6f), Color.Transparent), center = head, radius = .07f * s), .07f * s, head)
        }
        if (glint > 0f) {  // the glint: a four-point star that flares big, then settles
            val g = p(.62f, .10f)
            val flare = 1f + kotlin.math.sin(glint * Math.PI.toFloat()) * 1.2f
            val r = .07f * s * flare * glint.coerceAtLeast(.2f); val k = r * .22f
            val star = Path().apply {
                moveTo(g.x, g.y - r); lineTo(g.x + k, g.y - k); lineTo(g.x + r, g.y); lineTo(g.x + k, g.y + k)
                lineTo(g.x, g.y + r); lineTo(g.x - k, g.y + k); lineTo(g.x - r, g.y); lineTo(g.x - k, g.y - k); close()
            }
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(sign.copy(alpha = .55f), Color.Transparent), center = g, radius = r * 1.6f), r * 1.6f, g)
            drawPath(star, Color.White.copy(alpha = glint))
        }
    }
}
