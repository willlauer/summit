package com.willlauer.summit.ui

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.willlauer.summit.data.SpaceData
import com.willlauer.summit.map.GalaxyView
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

/** A map label: centred on its tracks, shown within a zoom range (1 = whole map). */
private class MapLabel(val text: String, val color: Color, val idxs: IntArray, val family: Boolean, val min: Float, val max: Float)

private const val LEAF_ZOOM = 2.6f
private const val MAX_LABELS = 9  // a sky chart names a few constellations, not all of them

private fun labelsFor(s: SpaceData): List<MapLabel> {
    val fams = HashMap<Int, MutableList<Int>>(); val leaves = HashMap<Int, MutableList<Int>>()
    for (i in 0 until s.n) { fams.getOrPut(s.family[i]) { mutableListOf() }.add(i); leaves.getOrPut(s.cluster[i]) { mutableListOf() }.add(i) }
    val out = ArrayList<MapLabel>()
    fams.forEach { (f, m) -> out.add(MapLabel(s.familyName(f), Color(s.familyColor(f)), m.toIntArray(), true, 0f, if (s.isSplit(f)) LEAF_ZOOM else Float.MAX_VALUE)) }
    leaves.forEach { (c, m) -> if (s.isSplit(s.familyOfLeaf(c))) out.add(MapLabel(s.leafName(c), Color(s.leafColor(c)), m.toIntArray(), false, LEAF_ZOOM, Float.MAX_VALUE)) }
    return out.sortedWith(compareBy({ !it.family }, { -it.idxs.size }))
}

/**
 * Each subgenre's bright stars: a handful of its tracks, spread out by farthest-point sampling over its core (so
 * outliers don't get picked), drawn with a glint.
 */
private class Constellation(val stars: IntArray)

private fun constellationsFor(s: SpaceData): List<Constellation> {
    val groups = HashMap<Int, MutableList<Int>>()
    for (i in 0 until s.n) groups.getOrPut(s.cluster[i]) { mutableListOf() }.add(i)
    return groups.values.mapNotNull { m ->
        if (m.size < 4) return@mapNotNull null
        val cx = m.sumOf { s.x[it].toDouble() }.toFloat() / m.size; val cy = m.sumOf { s.y[it].toDouble() }.toFloat() / m.size
        val r = m.associateWith { (s.x[it] - cx) * (s.x[it] - cx) + (s.y[it] - cy) * (s.y[it] - cy) }
        val core = m.sortedBy { r[it] }.take(maxOf(4, (m.size * .8f).toInt()))
        val k = (m.size / 14).coerceIn(3, 11)
        val chosen = mutableListOf(core.first())
        val dmin = FloatArray(core.size) { Float.MAX_VALUE }
        repeat(k - 1) {
            val last = chosen.last()
            var best = 0
            core.forEachIndexed { j, i -> val d = (s.x[i] - s.x[last]).let { it * it } + (s.y[i] - s.y[last]).let { it * it }; if (d < dmin[j]) dmin[j] = d; if (dmin[j] > dmin[best]) best = j }
            if (dmin[best] > 0f) chosen.add(core[best])
        }
        Constellation(chosen.toIntArray())
    }
}

private fun colorsFor(s: SpaceData, mode: String, lib: com.willlauer.summit.data.Library): IntArray = IntArray(s.n) { i ->
    when (mode) {
        "family" -> s.familyHue(s.family[i])
        "bpm" -> bpmColor(lib.tracks[i].bpm)
        else -> s.colorOf(i)
    }
}
private fun bpmColor(bpm: Int?): Int {
    val stops = listOf(0f to 0xFF5AA9FF.toInt(), .45f to 0xFF3EE6FF.toInt(), .75f to 0xFFFFD84D.toInt(), 1f to 0xFFFF4D8D.toInt())
    val v = (((bpm ?: 120) - 100) / 40f).coerceIn(0f, 1f)
    val k = stops.indexOfFirst { it.first >= v }.coerceAtLeast(1)
    val (a, ca) = stops[k - 1]; val (b, cb) = stops[k]; val t = (v - a) / (b - a)
    fun ch(c: Int, sh: Int) = (c shr sh) and 255
    fun mix(sh: Int) = (ch(ca, sh) + (ch(cb, sh) - ch(ca, sh)) * t).toInt()
    return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

class MapUi {  // survives leaving the Map tab
    var view by mutableStateOf<GalaxyView?>(null)
    var selected by mutableStateOf<Int?>(null)
    var nbrs by mutableStateOf<List<Int>>(emptyList())
    var color by mutableStateOf("cluster")
    var labels by mutableStateOf(true)
    var shownKey: String? = null
}

@Composable
fun MapScreen(ui: MapUi, bottomPad: androidx.compose.ui.unit.Dp) {
    val app = LocalApp.current
    val repo = app.repo
    val lib = repo.lib.collectAsStateWithLifecycle().value ?: return
    val spaceKey by repo.space.collectAsStateWithLifecycle()
    val v3d by repo.view3d.collectAsStateWithLifecycle()
    val ps by app.player.state.collectAsStateWithLifecycle()
    val space = lib.spaces[spaceKey] ?: return
    val scope = rememberCoroutineScope()
    var frame by remember { mutableLongStateOf(0L) }
    val focus = app.mapFocus
    val look = LookState.current
    val labels = remember(space, ui.color, look) { labelsFor(space) }
    val sky = remember(space) { constellationsFor(space) }

    AndroidView(
        factory = { ctx ->
            (ui.view ?: GalaxyView(ctx.applicationContext).also { ui.view = it }).also { (it.parent as? ViewGroup)?.removeView(it) }
        },
        modifier = Modifier.fillMaxSize(),
        update = { v ->
            v.listener = object : GalaxyView.Listener {
                override fun onTap(i: Int?, x: Float, y: Float) { ui.selected = i }
                override fun onLongPress(i: Int) { ui.selected = i; app.add(i) }
                override fun onFrame() { frame = v.frameNo }
            }
        },
    )
    val gv = ui.view

    // Space / colour changes morph the points; the first time, fit the whole map.
    LaunchedEffect(gv, look) { gv?.setStyle(look.pixel, look.wind, look.skyTop.toArgb(), look.skyBottom.toArgb()) }
    LaunchedEffect(gv, space, ui.color, look) {
        val v = gv ?: return@LaunchedEffect
        val key = "${space.key}:${ui.color}"
        if (ui.shownKey?.substringBefore(':') != space.key) { v.setPositions(space.x, space.y, space.xyz, animate = ui.shownKey != null); v.fit() }
        v.setColors(colorsFor(space, ui.color, lib))
        ui.shownKey = key
    }
    LaunchedEffect(gv, v3d) { gv?.set3D(v3d) }
    // Entering 3D: say how to move around it, briefly.
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(v3d) { if (v3d) { hint = true; kotlinx.coroutines.delay(5000) }; hint = false }
    LaunchedEffect(ui.selected, spaceKey) { ui.nbrs = ui.selected?.let { repo.neighbors(it, 12) } ?: emptyList() }
    LaunchedEffect(gv, focus) {
        val v = gv ?: return@LaunchedEffect
        if (focus == null) { v.setAlpha(null); return@LaunchedEffect }
        val a = FloatArray(v.n) { 0.1f }; focus.idxs.forEach { if (it in a.indices) a[it] = 1f }
        v.setAlpha(a)
        if (focus.select != null) { ui.selected = focus.select; v.centerOn(focus.select) } else v.fit(idxs = focus.idxs.toIntArray())
    }

    // ---- overlay: bright-star glints, route, rings, playing pulse, labels. Pixel look: all in whole pixel units. ----
    val measurer = rememberTextMeasurer()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().let { with(androidx.compose.ui.platform.LocalDensity.current) { it.toPx() } }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "p")
    val famStyle = TextStyle(fontFamily = Pixel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    val leafStyle = TextStyle(fontFamily = Pixel, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
    // Group names: capitals on a dark frosted pixel plate, edged in the group's colour.
    val measured = remember(labels, look) {
        labels.map {
            val st = if (it.family) famStyle else leafStyle
            measurer.measure(it.text.uppercase(), st.copy(color = androidx.compose.ui.graphics.lerp(it.color, Color.White, .6f))) to it.color
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        frame  // redraw on every map frame
        val v = gv ?: return@Canvas
        if (v.n == 0) return@Canvas
        val px = look.pixel
        val u = if (px) P.toPx() else 1f
        val snap = { x: Float -> if (px) floor(x / u) * u else x }
        // A line as pixel cells stepped along it (one cell per unit), in one draw call per colour.
        fun cells(a: FloatArray, b: FloatArray, out: MutableList<Offset>) {
            val n = (maxOf(abs(b[0] - a[0]), abs(b[1] - a[1])) / u).toInt().coerceIn(1, 600)
            for (k in 0..n) { val t = k / n.toFloat(); out.add(Offset(snap(a[0] + (b[0] - a[0]) * t) + u / 2, snap(a[1] + (b[1] - a[1]) * t) + u / 2)) }
        }
        fun ring(i: Int, r: Float, color: Color, width: Int = 1) {
            val p = v.screenOf(i)
            if (!px) { drawCircle(color, r * P.toPx() / 2f * 1.15f + 2.dp.toPx(), Offset(p[0], p[1]), style = Stroke(1.5.dp.toPx() * width)); return }
            val half = snap(r)
            drawPath(pixelPath(Size(half * 2, half * 2), u, Offset(snap(p[0]) - half + u / 2, snap(p[1]) - half + u / 2), steps = 2), color, style = Stroke(u * width))
        }
        val pts = ArrayList<Offset>()
        if (px) for (c in sky) {
            for (i in c.stars) {  // a pixel glint (a plus) on each bright star
                if (v.alphaOf(i) < .5f) continue
                val p = v.screenOf(i); val x = snap(p[0]); val y = snap(p[1])
                drawRect(C.Glacier.copy(alpha = .4f), Offset(x - 2 * u, y), Size(5 * u, u))
                drawRect(C.Glacier.copy(alpha = .4f), Offset(x, y - 2 * u), Size(u, 5 * u))
                drawRect(C.Glacier.copy(alpha = .85f), Offset(x, y), Size(u, u))
            }
        }
        if (focus != null && focus.path && focus.idxs.size > 1 && !px) {
            val path = Path()
            focus.idxs.forEachIndexed { k, i -> val s = v.screenOf(i); if (k == 0) path.moveTo(s[0], s[1]) else path.lineTo(s[0], s[1]) }
            drawPath(path, Color.White.copy(alpha = .1f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Color.White.copy(alpha = .85f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else if (focus != null && focus.path && focus.idxs.size > 1) {
            pts.clear()
            for (k in 1 until focus.idxs.size) cells(v.screenOf(focus.idxs[k - 1]), v.screenOf(focus.idxs[k]), pts)
            drawPoints(pts, PointMode.Points, C.Glacier.copy(alpha = .18f), strokeWidth = u * 4, cap = StrokeCap.Square)
            drawPoints(pts, PointMode.Points, C.Glacier, strokeWidth = u, cap = StrokeCap.Square)
        }
        ui.nbrs.forEach { ring(it, 3.5f * u, Color.White.copy(alpha = .8f)) }
        ps.current?.let { i ->
            ring(i, 5 * u, Color.White, 1)
            if (ps.playing) ring(i, (5 + floor(pulse * 6)) * u, Color.White.copy(alpha = (1 - pulse) * .7f))
        }
        ui.selected?.let { ring(it, 4.5f * u, C.Yellow, 1) }
        if (ui.labels) {
            val z = v.zoomLevel
            val taken = ArrayList<FloatArray>()
            val topClear = statusTop + 120.dp.toPx(); val bottomClear = bottomPad.toPx()
            val pad = 3 * P.toPx()
            labels.forEachIndexed { k, l ->
                if (z < l.min || z >= l.max) return@forEachIndexed
                // Follow only the members on screen: a label whose stars have scrolled away goes with them.
                var sx = 0f; var sy = 0f; var seen = 0
                val s = v.screen
                for (i in l.idxs) {
                    val x = s[i * 2]; val y = s[i * 2 + 1]
                    if (x in 0f..size.width && y in 0f..size.height) { sx += x; sy += y; seen++ }
                }
                if (seen < maxOf(3, (l.idxs.size * .15f).toInt())) return@forEachIndexed
                val (m, tint) = measured[k]; val w = m.size.width + pad * 2; val h = m.size.height + pad * 1.5f
                val edge = 6.dp.toPx()  // a label near the edge slides inward rather than getting cut off
                val cx = (sx / seen).let { if (w + 2 * edge < size.width) it.coerceIn(edge + w / 2, size.width - edge - w / 2) else it }
                val cy = sy / seen
                val box = floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                if (box[2] < 0 || box[0] > size.width || box[1] < topClear || box[3] > size.height - bottomClear) return@forEachIndexed  // not under the controls
                if (taken.size >= MAX_LABELS || taken.any { box[0] < it[2] && box[2] > it[0] && box[1] < it[3] && box[3] > it[1] }) return@forEachIndexed
                taken.add(box)
                val plate = if (px) pixelPath(Size(snap(w), snap(h)), u, Offset(snap(box[0]), snap(box[1])), steps = 1)
                    else Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(box[0], box[1], box[0] + w, box[1] + h, CornerRadius(h / 2))) }
                drawPath(plate, if (px) Color(0xD9030A14) else Color(0xC008080D))
                drawPath(plate, tint.copy(alpha = .4f), style = Stroke(if (px) u else 1.dp.toPx()))
                drawText(m, topLeft = Offset(snap(box[0] + pad), snap(box[1] + pad * .75f)))
            }
        }
    }

    // ---- floating controls ----
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(48.dp).snowCap(2).clip(Pill).background(C.Panel.copy(alpha = .88f)).border(1.dp, C.Line, Pill)
                    .clickable { app.openTab(Tab.Search) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PI.Search, null, tint = C.Muted)
                Spacer(Modifier.width(10.dp))
                Text("Songs, artists, vibes", color = C.Muted, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.width(8.dp))
            RoundIcon(if (v3d) PI.Cube else PI.Flat, if (v3d) "Flat map" else "3D", active = v3d, enabled = space.xyz != null) { repo.set3D(!v3d) }
            Spacer(Modifier.width(8.dp))
            RoundIcon(PI.Layers, "Map options") { app.sheet = Sheet.MapOptions }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActiveChip()
            if (focus != null) {
                Spacer(Modifier.width(8.dp))
                Row(Modifier.clip(Pill).background(C.Panel.copy(alpha = .88f)).border(1.dp, C.Line, Pill).clickable { app.mapFocus = null }
                    .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(focus.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                    Spacer(Modifier.width(4.dp)); Icon(PI.Close, "Clear", Modifier.size(16.dp), tint = C.Muted)
                }
            }
        }
    }

    if (hint) Box(Modifier.fillMaxSize().padding(bottom = bottomPad + 12.dp), contentAlignment = Alignment.BottomCenter) {
        Text("Drag to move · twist to turn · two fingers up / down to tilt · pinch to zoom", Modifier.padding(horizontal = 24.dp).clip(Px)
            .background(C.Raised2.copy(alpha = .95f)).padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = C.Text)
    }

    // ---- the tapped track ----
    Box(Modifier.fillMaxSize().padding(bottom = bottomPad), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(ui.selected != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            val i = ui.selected ?: return@AnimatedVisibility
            TrackCard(i, space, onClose = { ui.selected = null }, onPlay = {
                scope.launch { val q = listOf(i) + repo.neighbors(i, 12); app.player.play(q, 0, "Similar to ${lib.tracks[i].name}") }
            })
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).snowCap(desc.length, icicles = false, depth = 2).clip(Pill).background(if (active) C.Cyan else C.Panel.copy(alpha = .88f)).border(1.dp, if (active) Color.Transparent else C.Line, Pill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (active) C.Ink else if (enabled) C.Text else C.Faint) }
}

@Composable
private fun TrackCard(i: Int, space: SpaceData, onClose: () -> Unit, onPlay: () -> Unit) {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val liked by app.repo.liked.collectAsStateWithLifecycle()
    val t = lib.tracks[i]
    Column(
        Modifier.padding(10.dp).fillMaxWidth().snowCap(6).clip(PxBig).background(Color(0xF014141F)).border(1.dp, C.Line, PxBig)
            .clickable { app.sheet = Sheet.Track(i) }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Art(i, 64.dp, corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(space.groupName(i), style = MaterialTheme.typography.labelSmall, color = Color(space.colorOf(i)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(t.by, t.bpm?.let { "$it BPM" }, t.key).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onClose) { Icon(PI.Close, "Close", tint = C.Muted) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Play", PI.Play, onPlay, primary = true)
            Spacer(Modifier.width(8.dp))
            PillButton("Similar", PI.Similar, { app.similar(listOf(i)) })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { app.repo.toggleLike(i) }) {
                LikeIcon(t.id in liked)
            }
            AddButton(i)
        }
    }
}
