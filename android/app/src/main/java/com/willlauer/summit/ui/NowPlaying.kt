package com.willlauer.summit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player

/** The bar above the tabs: art, title, play / pause, and a thin progress line. Tap to open Now Playing. */
@Composable
fun MiniPlayer() {
    val app = LocalApp.current
    val ps by app.player.state.collectAsStateWithLifecycle()
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val i = ps.current ?: return
    val t = lib.tracks[i]
    Column(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(PxBig).background(C.Raised2.copy(alpha = .94f)).border(1.dp, C.Line, PxBig).clickable { app.nowPlaying = true }
            .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -12) app.nowPlaying = true } },
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(i, 44.dp, corner = 9.dp, px = 128)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.by, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AddButton(i)
            IconButton(onClick = { app.player.toggle() }) {
                if (ps.buffering) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(if (ps.playing) PI.Pause else PI.Play, "Play / pause", Modifier.size(30.dp))
            }
        }
        val f = if (ps.durationMs > 0) ps.positionMs.toFloat() / ps.durationMs else 0f
        Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(2.dp).clip(Pill).background(C.Line)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(C.Cyan))
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Full-screen player: art over a glow in the track's colour, seek bar, transport, and the queue. */
@Composable
fun NowPlaying() {
    val app = LocalApp.current
    val ps by app.player.state.collectAsStateWithLifecycle()
    val liked by app.repo.liked.collectAsStateWithLifecycle()
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val i = ps.current ?: run { app.nowPlaying = false; return }
    val t = lib.tracks[i]
    val space = app.repo.spaceData()
    val col = Color(space?.colorOf(i) ?: 0xFF3EE6FF.toInt())
    var drag by remember { mutableFloatStateOf(0f) }
    var seeking by remember { mutableStateOf<Float?>(null) }

    Box(Modifier.fillMaxSize().background(C.Bg).pointerInput(Unit) {
        detectVerticalDragGestures(onDragEnd = { if (drag > 160) app.nowPlaying = false; drag = 0f }) { _, dy -> drag = (drag + dy).coerceAtLeast(0f) }
    }) {
        Backdrop(snow = true)
        Box(Modifier.fillMaxWidth().fillMaxHeight(.6f).background(Brush.verticalGradient(listOf(col.copy(alpha = .28f), Color.Transparent))))
        LazyColumn(Modifier.fillMaxSize().offset(y = (drag / 3).dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { app.nowPlaying = false }, Modifier.offset(x = (-12).dp)) { Icon(PI.Down, "Close", Modifier.size(30.dp)) }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Playing from", style = MaterialTheme.typography.labelSmall, color = C.Muted)
                            Text(ps.label.ifEmpty { "Your library" }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { app.sheet = Sheet.Track(i) }, Modifier.offset(x = 12.dp)) { Icon(PI.More, "More") }
                    }
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize(.9f).blur(40.dp).background(col.copy(alpha = .45f), PxBig))
                        Art(i, 320.dp, Modifier.fillMaxSize(), corner = 20.dp, px = 768)
                    }
                    Spacer(Modifier.height(26.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(t.by, style = MaterialTheme.typography.bodyLarge, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { app.artist(com.willlauer.summit.data.splitArtists(t.by).firstOrNull() ?: t.by) })
                        }
                        val on = t.id in liked
                        IconButton(onClick = { app.repo.toggleLike(i) }) { LikeIcon(on, 30.dp) }
                    }
                    Text(listOfNotNull(t.bpm?.let { "$it BPM" }, t.key, space?.groupName(i)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = col.copy(alpha = .9f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(14.dp))
                    val dur = ps.durationMs.coerceAtLeast(1)
                    Slider(value = seeking ?: (ps.positionMs.toFloat() / dur).coerceIn(0f, 1f), onValueChange = { seeking = it },
                        onValueChangeFinished = { seeking?.let { app.player.seekTo((it * dur).toLong()) }; seeking = null })
                    Row {
                        Text(fmtDur(((seeking?.times(dur) ?: ps.positionMs.toFloat()) / 1000).toInt()), style = MaterialTheme.typography.labelMedium, color = C.Muted)
                        Spacer(Modifier.weight(1f))
                        Text(fmtDur((ps.durationMs / 1000).toInt().takeIf { it > 0 } ?: t.dur), style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { app.player.setShuffle(!ps.shuffle) }) { Icon(PI.Shuffle, "Shuffle", tint = if (ps.shuffle) C.Cyan else C.Muted) }
                        IconButton(onClick = { app.player.prev() }, Modifier.size(56.dp)) { Icon(PI.Prev, "Previous", Modifier.size(38.dp)) }
                        Box(Modifier.size(76.dp).clip(Pill).background(Brush.verticalGradient(listOf(Color.White, C.Glacier))).clickable { app.player.toggle() }, contentAlignment = Alignment.Center) {
                            if (ps.buffering) CircularProgressIndicator(Modifier.size(30.dp), color = C.Bg, strokeWidth = 3.dp)
                            else Icon(if (ps.playing) PI.Pause else PI.Play, "Play / pause", Modifier.size(42.dp), tint = C.Bg)
                        }
                        IconButton(onClick = { app.player.next() }, Modifier.size(56.dp)) { Icon(PI.Next, "Next", Modifier.size(38.dp)) }
                        IconButton(onClick = { app.player.cycleRepeat() }) {
                            Icon(if (ps.repeat == Player.REPEAT_MODE_ONE) PI.RepeatOne else PI.Repeat, "Repeat", tint = if (ps.repeat != Player.REPEAT_MODE_OFF) C.Cyan else C.Muted)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Similar", PI.Similar, { app.similar(listOf(i)) }, modifier = Modifier.weight(1f))
                        PillButton("Add", PI.PlaylistAdd, { app.sheet = Sheet.AddTo(listOf(i)) }, modifier = Modifier.weight(1f))
                        PillButton("Map", PI.Compass, { app.showOnMap(MapFocus(listOf(i), t.name, select = i)) }, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("Up next", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                }
            }
            val upNext = ps.queue.withIndex().drop(ps.queuePos + 1).take(60)
            if (upNext.isEmpty()) item { Text("Nothing after this one", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = C.Muted) }
            itemsIndexed(upNext, key = { _, v -> "q${v.index}:${v.value}" }) { _, v ->
                Row(Modifier.fillMaxWidth().clickable { app.player.jumpTo(v.index) }.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(v.value, 40.dp, px = 128)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lib.tracks[v.value].name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lib.tracks[v.value].by, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { app.player.removeAt(v.index) }) { Icon(PI.Close, "Remove from queue", tint = C.Faint) }
                }
            }
        }
    }
}
