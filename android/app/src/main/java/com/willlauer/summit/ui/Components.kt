package com.willlauer.summit.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.willlauer.summit.data.Playlist

fun fmtDur(s: Int): String { val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r) }
fun fmtLong(s: Int): String { val h = s / 3600; val m = (s % 3600) / 60; return if (h > 0) "${h} h ${m} min" else "$m min" }

/** Cover art, over a glow in the track's cluster colour (which is all you see when a file has no artwork). */
@Composable
fun Art(i: Int, size: Dp, modifier: Modifier = Modifier, corner: Dp = 8.dp, @Suppress("UNUSED_PARAMETER") px: Int = 0) {
    val app = LocalApp.current
    val space = app.repo.spaceData()
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val c = Color(space?.colorOf(i) ?: 0xFF3EE6FF.toInt())
    Box(modifier.size(size).clip(if (corner >= 16.dp) PxBig else if (corner >= 8.dp) Px else PxSmall)
        .background(Brush.linearGradient(listOf(c.copy(alpha = .55f), Color(0xFF11111B), c.copy(alpha = .25f))))) {
        Icon(PI.Note, null, Modifier.align(Alignment.Center).size(size * 0.4f), tint = c.copy(alpha = .7f))
        val cells = (size / P).toInt().coerceIn(16, 200)  // one source pixel per pixel unit, scaled up without smoothing
        AsyncImage(model = app.repo.api.artUrl(lib.tracks[i].id, cells), contentDescription = null, contentScale = ContentScale.Crop,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None, modifier = Modifier.fillMaxSize())
    }
}

/** A playlist's cover: its first four tracks' art. */
@Composable
fun Mosaic(p: Playlist, size: Dp, corner: Dp = 10.dp) {
    val app = LocalApp.current
    val idxs = remember(p.ids) { app.repo.idxs(p).take(4) }
    Box(Modifier.size(size).clip(Px).background(C.Raised)) {
        when {
            idxs.isEmpty() -> Icon(PI.Queue, null, Modifier.align(Alignment.Center).size(size * .4f), tint = C.Faint)
            idxs.size < 4 -> Art(idxs[0], size, corner = 0.dp)
            else -> Column {
                Row { Art(idxs[0], size / 2, corner = 0.dp, px = 128); Art(idxs[1], size / 2, corner = 0.dp, px = 128) }
                Row { Art(idxs[2], size / 2, corner = 0.dp, px = 128); Art(idxs[3], size / 2, corner = 0.dp, px = 128) }
            }
        }
    }
}

/** ＋ / ✓ for the playlist being built. */
@Composable
fun AddButton(i: Int) {
    val app = LocalApp.current
    val pls by app.repo.playlists.collectAsStateWithLifecycle()
    val active by app.repo.active.collectAsStateWithLifecycle()
    val has = remember(pls, active, i) { app.repo.inActive(i) }
    val haptic = LocalHapticFeedback.current
    val tint by animateColorAsState(if (has) C.Green else C.Muted, label = "add")
    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); app.add(i) }) {
        Icon(if (has) PI.CheckBox else PI.AddBox, if (has) "In playlist" else "Add", tint = tint)
    }
}

/**
 * The song row used everywhere. Tap plays (with the list as the queue); long-press or ⋯ opens actions.
 * Songs already in the playlist being built are dimmed with a ✓.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    i: Int, onClick: () -> Unit, modifier: Modifier = Modifier, number: Int? = null, playlistId: Int? = null,
    showAdd: Boolean = true, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null,
) {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val ps by app.player.state.collectAsStateWithLifecycle()
    val pls by app.repo.playlists.collectAsStateWithLifecycle()
    val active by app.repo.active.collectAsStateWithLifecycle()
    val dim = remember(pls, active, i) { playlistId == null && app.repo.inActive(i) }
    val t = lib.tracks[i]
    val playing = ps.current == i
    Row(
        modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { app.sheet = Sheet.Track(i, playlistId) })
            .background(if (playing) C.Cyan.copy(alpha = .07f) else Color.Transparent)
            .padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) Text("$number", Modifier.width(26.dp), style = MaterialTheme.typography.labelMedium, color = C.Faint)
        Box(Modifier.alpha(if (dim) .45f else 1f)) {
            Art(i, 46.dp)
            if (playing) Box(Modifier.matchParentSize().clip(Px).background(Color(0x88000000)), contentAlignment = Alignment.Center) { Equalizer(ps.playing) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (dim) .5f else 1f)) {
            Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                color = if (playing) C.Cyan else C.Text)
            Text(subtitle ?: listOfNotNull(t.by, t.bpm?.let { "$it BPM" }).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (trailing != null) trailing()
        if (showAdd) AddButton(i)
        IconButton(onClick = { app.sheet = Sheet.Track(i, playlistId) }) { Icon(PI.More, "More", tint = C.Faint) }
    }
}

@Composable
fun Equalizer(on: Boolean, color: Color = Color.White) {
    val t = rememberInfiniteTransitionCompat(on)
    Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0.9f, 0.5f, 0.75f).forEachIndexed { k, base ->
            val h = if (on) (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(t * (2.2f + k) + k))) else base * 0.4f
            Box(Modifier.width(P * 2).height(P * (7 * h).toInt().coerceAtLeast(1)).background(color))
        }
    }
}

@Composable
private fun rememberInfiniteTransitionCompat(on: Boolean): Float {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(on) { if (on) { val t0 = withFrameNanosCompat(); while (true) { t = (withFrameNanosCompat() - t0) / 1e9f * 3f } } }
    return t
}
private suspend fun withFrameNanosCompat(): Long = androidx.compose.runtime.withFrameNanos { it }

/** The playlist being built: tap to switch, start a new one, or stop. */
@Composable
fun ActiveChip(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val pls by app.repo.playlists.collectAsStateWithLifecycle()
    val active by app.repo.active.collectAsStateWithLifecycle()
    val p = pls.find { it.id == active }
    Row(
        modifier.clip(Pill).background(if (p != null) C.Green.copy(alpha = .1f) else C.Raised)
            .border(1.dp, if (p != null) C.Green.copy(alpha = .35f) else C.Line, Pill)
            .clickable { app.sheet = Sheet.Active }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (p != null) PI.Snowflake else PI.PlaylistAdd, null, Modifier.size(16.dp), tint = if (p != null) C.Green else C.Muted)
        Spacer(Modifier.width(6.dp))
        Text(if (p != null) "${p.name} · ${p.ids.size}" else "Build a playlist", style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (p != null) C.Green else C.Muted, modifier = Modifier.widthIn(max = 180.dp))
    }
}

/** Large screen title with an optional back arrow and trailing actions. */
@Composable
fun Header(title: String, subtitle: String? = null, back: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    val app = LocalApp.current
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back) IconButton(onClick = { app.nav.popBackStack() }) { Icon(PI.Back, "Back") } else Spacer(Modifier.width(12.dp))
            Spacer(Modifier.weight(1f))
            actions()
        }
        Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodyMedium, color = C.Muted)
    }
}

@Composable
fun PillButton(text: String, icon: ImageVector?, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) =
    GameButton(text, icon, onClick, primary, enabled, modifier)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

@Composable
fun Empty(text: String, icon: ImageVector = PI.Note) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = C.Faint)
        Spacer(Modifier.height(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = C.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** A–Z strip on the right edge: drag along it to jump through a long list. */
@Composable
fun LetterScrubber(letters: List<Char>, modifier: Modifier = Modifier, onLetter: (Char) -> Unit) {
    var height by remember { mutableIntStateOf(1) }
    var active by remember { mutableStateOf<Char?>(null) }
    val haptic = LocalHapticFeedback.current
    fun at(y: Float) { val c = letters[(y / height * letters.size).toInt().coerceIn(0, letters.size - 1)]; if (c != active) { active = c; haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onLetter(c) } }
    Box(modifier) {
        Column(
            Modifier.width(26.dp).fillMaxHeight().onSizeChanged { height = it.height }
                .pointerInput(letters) { detectVerticalDragGestures(onDragStart = { at(it.y) }, onDragEnd = { active = null }, onDragCancel = { active = null }) { ch, _ -> at(ch.position.y) } }
                .pointerInput(letters) { detectTapGestures { at(it.y); active = null } },
            verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally,
        ) { letters.forEach { Text("$it", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = if (it == active) C.Cyan else C.Muted) } }
        active?.let {
            Box(Modifier.offset(x = (-64).dp).align(Alignment.Center).size(52.dp).clip(PxBig).background(C.Raised2), contentAlignment = Alignment.Center) {
                Text("$it", style = MaterialTheme.typography.headlineSmall, color = C.Cyan)
            }
        }
    }
}
