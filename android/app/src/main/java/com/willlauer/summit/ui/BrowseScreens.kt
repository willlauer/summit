package com.willlauer.summit.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchUi { var query by mutableStateOf("") }

// ---------------- Search ----------------
@Composable
fun SearchScreen(ui: SearchUi, bottomPad: Dp) {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val q = ui.query
    val songs = remember(q, lib) { lib.searchSongs(q) }
    val artists = remember(q, lib) { lib.searchArtists(q) }
    var vibe by remember { mutableStateOf<List<Int>>(emptyList()) }
    var vibeLoading by remember { mutableStateOf(false) }
    var showAll by remember(q) { mutableStateOf(false) }
    val vibeN by app.repo.vibeResults.collectAsStateWithLifecycle()
    var opts by remember { mutableStateOf(false) }
    LaunchedEffect(q, vibeN) {  // "Sounds like": the server's vibe search (CLAP text embedding), after typing pauses
        vibe = emptyList()
        if (q.trim().length < 3) return@LaunchedEffect
        delay(350); vibeLoading = true
        val have = songs.toSet()  // already listed under Songs
        vibe = app.repo.vibeSearch(q, vibeN + have.size.coerceAtMost(200)).filter { it !in have }.take(vibeN)
        vibeLoading = false
    }
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    LaunchedEffect(Unit) { if (q.isEmpty()) runCatching { focus.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad + 16.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(Pill).background(C.Raised).border(1.dp, C.Line, Pill).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PI.Search, null, tint = C.Muted)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (q.isEmpty()) Text("Songs, artists, or a vibe like “rainy late night”", color = C.Faint, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicTextField(q, { ui.query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge, cursorBrush = SolidColor(C.Cyan),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { fm.clearFocus() }))
                    }
                    if (q.isNotEmpty()) IconButton(onClick = { ui.query = "" }) { Icon(PI.Close, "Clear", tint = C.Muted) }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActiveChip()
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { opts = true }) { Icon(PI.Gear, "Search options", tint = C.Muted) }
                        DropdownMenu(opts, { opts = false }, containerColor = C.Raised2) {
                            Text("“Sounds like” results", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = C.Muted)
                            listOf(15, 30, 60, 150, 500).forEach { n ->
                                DropdownMenuItem({ Text("$n" + if (n == vibeN) "  ✓" else "") }, { opts = false; app.repo.setVibeResults(n) })
                            }
                        }
                    }
                    if (songs.isNotEmpty() || vibe.isNotEmpty()) TextButton(onClick = { app.showOnMap(MapFocus(songs.take(100) + vibe, "“$q”")) }) {
                        Icon(PI.Compass, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("On the map")
                    }
                }
            }
        }
        if (q.isBlank()) {
            item { SectionTitle("Recently added") }
            val recent = lib.byAdded.take(40)
            items(recent, key = { "r$it" }) { i -> SongRow(i, { app.player.play(recent, recent.indexOf(i), "Recently added") }) }
            return@LazyColumn
        }
        if (artists.isNotEmpty()) {
            item { SectionTitle("Artists") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    artists.forEach { a ->
                        Row(Modifier.clip(Pill).background(C.Raised).clickable { app.artist(a.name) }.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Art(a.tracks[0], 32.dp, corner = 16.dp, px = 96)
                            Spacer(Modifier.width(8.dp))
                            Column { Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1); Text("${a.tracks.size} songs", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        if (songs.isNotEmpty()) {
            item { SectionTitle("Songs", action = { Text("${songs.size}", color = C.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp)) }) }
            val shown = if (showAll) songs else songs.take(12)
            items(shown, key = { "s$it" }) { i -> SongRow(i, { app.player.play(songs, songs.indexOf(i), "Search: $q") }) }
            if (songs.size > shown.size) item { TextButton(onClick = { showAll = true }, Modifier.padding(start = 8.dp)) { Text("Show all ${songs.size}") } }
        }
        if (vibe.isNotEmpty() || vibeLoading) {
            item { SectionTitle("Sounds like “$q”", action = { if (vibeLoading) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 8.dp), strokeWidth = 2.dp) }) }
            items(vibe, key = { "v$it" }) { i -> SongRow(i, { app.player.play(vibe, vibe.indexOf(i), "Sounds like “$q”") }) }
        }
        if (songs.isEmpty() && artists.isEmpty() && vibe.isEmpty() && !vibeLoading) item { Empty("Nothing matches “$q”", PI.SearchOff) }
    }
}

// ---------------- Library ----------------
class LibraryUi { var tab by mutableIntStateOf(0); var sort by mutableIntStateOf(0) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(ui: LibraryUi, bottomPad: Dp) {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val liked by app.repo.liked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Header("Library", actions = { IconButton(onClick = { app.go("settings") }) { Icon(PI.Gear, "Settings", tint = C.Muted) } })
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Artists", "Songs", "Liked").forEachIndexed { k, s -> Chip(s, ui.tab == k) { ui.tab = k } }
        }
        when (ui.tab) {
            0 -> {
                val state = rememberLazyListState()
                val groups = remember(lib) { lib.artists.groupBy { it.letter } }
                val starts = remember(groups) { var k = 0; groups.mapValues { (_, v) -> k.also { k += v.size + 1 } } }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = state, contentPadding = PaddingValues(bottom = bottomPad + 16.dp, end = 24.dp)) {
                        groups.forEach { (letter, list) ->
                            stickyHeader(key = "h$letter") { LetterHeader(letter) }
                            items(list, key = { "a" + it.norm }) { a ->
                                Row(Modifier.fillMaxWidth().clickable { app.artist(a.name) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Art(a.tracks[0], 44.dp, corner = 22.dp, px = 128)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (a.tracks.size == 1) "1 song" else "${a.tracks.size} songs", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    LetterScrubber(groups.keys.toList(), Modifier.align(Alignment.CenterEnd).fillMaxHeight(.82f)) { c -> starts[c]?.let { scope.launch { state.scrollToItem(it) } } }
                }
            }
            1 -> {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("A–Z", "Recently added", "BPM").forEachIndexed { k, s -> Chip(s, ui.sort == k, small = true) { ui.sort = k } }
                }
                val list = when (ui.sort) { 1 -> lib.byAdded; 2 -> lib.byBpm; else -> lib.byTitle }
                val state = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = state, contentPadding = PaddingValues(bottom = bottomPad + 16.dp, end = if (ui.sort == 0) 22.dp else 0.dp)) {
                        item { PlayShuffle(list, "All songs") }
                        items(list, key = { "t$it" }) { i -> SongRow(i, { app.player.play(list, list.indexOf(i), "All songs") }) }
                    }
                    if (ui.sort == 0) {
                        val firsts = remember(list) { val m = LinkedHashMap<Char, Int>(); list.forEachIndexed { k, i -> m.putIfAbsent(lib.titleLetter(i), k + 1) }; m }
                        LetterScrubber(firsts.keys.toList(), Modifier.align(Alignment.CenterEnd).fillMaxHeight(.8f)) { c -> firsts[c]?.let { scope.launch { state.scrollToItem(it) } } }
                    }
                }
            }
            else -> {
                val list = remember(liked, lib) { app.repo.likedIdxs }
                LazyColumn(contentPadding = PaddingValues(bottom = bottomPad + 16.dp)) {
                    if (list.isEmpty()) item { Empty("Songs you ♥ show up here", PI.HeartOutline) }
                    else {
                        item { PlayShuffle(list, "Liked") }
                        items(list, key = { "l$it" }) { i -> SongRow(i, { app.player.play(list, list.indexOf(i), "Liked") }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterHeader(c: Char) {
    Text("$c", Modifier.fillMaxWidth().background(C.Bg.copy(alpha = .85f)).padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium, color = C.Cyan)
}

@Composable
fun Chip(text: String, on: Boolean, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(Pill).background(if (on) C.Cyan else C.Raised).border(1.dp, if (on) Color.Transparent else C.Line, Pill).clickable(onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 6.dp else 8.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = if (small) 13.sp else 15.sp), color = if (on) C.Ink else C.Text) }
}

@Composable
fun PlayShuffle(list: List<Int>, label: String, extra: (@Composable RowScope.() -> Unit)? = null) {
    val app = LocalApp.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Play", PI.Play, { app.player.play(list, 0, label) }, primary = true, enabled = list.isNotEmpty())
        PillButton("Shuffle", PI.Shuffle, { app.player.play(list, 0, label, shuffle = true) }, enabled = list.isNotEmpty())
        extra?.invoke(this)
    }
}

// ---------------- Artist ----------------
@Composable
fun ArtistScreen(name: String, bottomPad: Dp) {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val a = remember(name, lib) { lib.artist(name) } ?: return
    val list = a.tracks.toList()
    val dur = remember(a) { list.sumOf { lib.tracks[it].dur } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad + 16.dp)) {
        item { Header(a.name, "${list.size} songs · ${fmtLong(dur)}", back = true) }
        item {
            PlayShuffle(list, a.name) {
                IconButton(onClick = { app.showOnMap(MapFocus(list, a.name)) }) { Icon(PI.Compass, "On the map", tint = C.Muted) }
            }
        }
        items(list, key = { "a$it" }) { i -> SongRow(i, { app.player.play(list, list.indexOf(i), a.name) }, subtitle = listOfNotNull(lib.tracks[i].album, lib.tracks[i].bpm?.let { "$it BPM" }).joinToString(" · ")) }
    }
}
