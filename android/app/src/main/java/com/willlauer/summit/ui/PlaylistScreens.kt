package com.willlauer.summit.ui

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.willlauer.summit.data.Folder
import com.willlauer.summit.data.Playlist
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ---------------- Playlists ----------------
@Composable
fun PlaylistsScreen(bottomPad: Dp) {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val pls by app.repo.playlists.collectAsStateWithLifecycle()
    val active by app.repo.active.collectAsStateWithLifecycle()
    val folders by app.repo.folders.collectAsStateWithLifecycle()
    val closed by app.repo.closedFolders.collectAsStateWithLifecycle()
    // Folders first (each one's subfolders, then its playlists by name, as in the rekordbox export); then everything unfiled.
    val known = folders.map { it.id }.toSet()
    val home = { id: Int? -> id?.takeIf { it in known } }  // a dangling folder id counts as the top level
    fun inside(fid: Int): Int = pls.count { home(it.folder) == fid } + folders.filter { home(it.parent) == fid }.sumOf { inside(it.id) }
    val tree = buildList<Pair<Int, Any>> {
        fun walk(fid: Int, d: Int) {
            folders.filter { home(it.parent) == fid }.forEach { f -> add(d to f); if (f.id !in closed) walk(f.id, d + 1) }
            pls.filter { home(it.folder) == fid }.sortedBy { it.name.lowercase() }.forEach { add(d to it) }
        }
        folders.filter { home(it.parent) == null }.forEach { f -> add(0 to f); if (f.id !in closed) walk(f.id, 1) }
    }
    val loose = pls.filter { home(it.folder) == null }
    val mine = loose.filter { it.kind == "playlist" }.sortedByDescending { it.updated ?: 0.0 }
    val flows = loose.filter { it.kind == "flow" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad + 16.dp)) {
        item {
            Header("Playlists", actions = {
                TextButton(onClick = { app.dialog = Dialog.NewFolder() }) { Icon(PI.Folder, null); Spacer(Modifier.width(4.dp)); Text("Folder") }
                TextButton(onClick = { app.dialog = Dialog.NewPlaylist(makeActive = true) }) { Icon(PI.Add, null); Spacer(Modifier.width(4.dp)); Text("New") }
            })
        }
        if (mine.isEmpty() && tree.isEmpty()) item { Empty("Start one with New, or ＋ on any song", PI.Queue) }
        items(tree, key = { (_, x) -> if (x is Folder) "d${x.id}" else "p${(x as Playlist).id}" }) { (d, x) ->
            if (x is Folder) {
                val open = x.id !in closed
                Row(Modifier.fillMaxWidth().clickable { app.repo.toggleFolder(x.id) }.padding(start = 16.dp + 20.dp * d, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (open) PI.Down else PI.Right, if (open) "Close" else "Open", Modifier.size(20.dp), tint = C.Muted)
                    Spacer(Modifier.width(6.dp))
                    Icon(PI.Folder, null, tint = C.Cyan)
                    Spacer(Modifier.width(12.dp))
                    Text(x.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${inside(x.id)}", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    IconButton(onClick = { app.sheet = Sheet.FolderActions(x.id) }) { Icon(PI.More, "Folder actions", tint = C.Muted) }
                }
            } else PlaylistRow(x as Playlist, active, indent = 20.dp * d)
        }
        if (tree.isNotEmpty() && mine.isNotEmpty()) item { SectionTitle("Not in a folder") }
        items(mine, key = { "p${it.id}" }) { p -> PlaylistRow(p, active) }
        if (flows.isNotEmpty()) {
            item { SectionTitle("Flows from the desktop") }
            items(flows, key = { "f${it.id}" }) { p ->
                Row(Modifier.fillMaxWidth().clickable { app.playlist(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mosaic(p, 48.dp); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1); Text("${p.ids.size} songs", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(p: Playlist, active: Int?, indent: Dp = 0.dp) {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val dur = remember(p.ids) { app.repo.idxs(p).sumOf { lib.tracks[it].dur } }
    Row(Modifier.fillMaxWidth().clickable { app.playlist(p.id) }.padding(start = 16.dp + indent, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Mosaic(p, if (indent > 0.dp) 48.dp else 60.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.ids.size} songs · ${fmtLong(dur)}" + if (p.kind == "flow") " · flow" else "", style = MaterialTheme.typography.bodySmall)
        }
        if (p.id == active) Text("Building", Modifier.clip(Pill).background(C.Green.copy(alpha = .12f)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = C.Green)
    }
}

// ---------------- One playlist ----------------
@Composable
fun PlaylistScreen(id: Int, bottomPad: Dp) {
    val app = LocalApp.current
    val repo = app.repo
    val lib = repo.lib.collectAsStateWithLifecycle().value ?: return
    val pls by repo.playlists.collectAsStateWithLifecycle()
    val active by repo.active.collectAsStateWithLifecycle()
    val p = pls.find { it.id == id } ?: run { Empty("This playlist was deleted"); return }
    val editable = p.kind == "playlist"
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Local order while dragging; saved on drop.
    var order by remember(p.ids) { mutableStateOf(repo.idxs(p)) }
    val dur = order.sumOf { lib.tracks[it].dur }
    var suggestions by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(p.ids) { suggestions = if (p.ids.isEmpty()) emptyList() else repo.suggest(p, 20) }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val track = { key: Any -> (key as? String)?.takeIf { it.startsWith("t") }?.drop(1)?.toIntOrNull() }
        val a = order.indexOf(track(from.key) ?: return@rememberReorderableLazyListState)
        val b = order.indexOf(track(to.key) ?: return@rememberReorderableLazyListState)
        if (a < 0 || b < 0) return@rememberReorderableLazyListState
        order = order.toMutableList().apply { add(b, removeAt(a)) }
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    fun saveOrder() {
        val offMap = p.ids.filter { lib.byId[it] == null }  // tracks not on the map keep their place at the end
        val ids = order.map { lib.tracks[it].id } + offMap
        if (ids != p.ids) scope.launch { repo.reorder(p.id, ids) }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPad + 16.dp)) {
        item(key = "head") {
            Column {
                Header("", back = true, actions = {
                    if (editable) TextButton(onClick = { if (editing) saveOrder(); editing = !editing }) { Text(if (editing) "Done" else "Edit") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(PI.More, "More") }
                        DropdownMenu(menu, { menu = false }, containerColor = C.Raised2) {
                            if (editable) DropdownMenuItem({ Text("Rename") }, { menu = false; app.dialog = Dialog.Rename(p.id) }, leadingIcon = { Icon(PI.Edit, null) })
                            DropdownMenuItem({ Text("Move to folder…") }, { menu = false; app.sheet = Sheet.MoveTo(p.id) }, leadingIcon = { Icon(PI.Folder, null) })
                            DropdownMenuItem({ Text("Show route on map") }, { menu = false; app.showOnMap(MapFocus(order, p.name, path = true)) }, leadingIcon = { Icon(PI.Route, null) })
                            DropdownMenuItem({ Text("Delete", color = C.Magenta) }, { menu = false; app.dialog = Dialog.Delete(p.id) }, leadingIcon = { Icon(PI.Delete, null, tint = C.Magenta) })
                        }
                    }
                })
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mosaic(p, 112.dp, corner = 14.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = editable) { app.dialog = Dialog.Rename(p.id) })
                        Text("${order.size} songs · ${fmtLong(dur)}", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                        if (editable) {
                            Spacer(Modifier.height(8.dp))
                            val building = active == p.id
                            Row(Modifier.clip(Pill).background(if (building) C.Green.copy(alpha = .12f) else C.Raised2)
                                .clickable { repo.setActive(if (building) null else p.id); repo.say(if (building) "Stopped building ${p.name}" else "＋ now adds to ${p.name}") }
                                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (building) PI.Snowflake else PI.PlaylistAdd, null, Modifier.size(16.dp), tint = if (building) C.Green else C.Text)
                                Spacer(Modifier.width(6.dp))
                                Text(if (building) "Building" else "Build this", style = MaterialTheme.typography.labelMedium, color = if (building) C.Green else C.Text)
                            }
                        }
                    }
                }
                PlayShuffle(order, p.name) {
                    IconButton(onClick = { app.showOnMap(MapFocus(order, p.name, path = true)) }) { Icon(PI.Route, "Route on map", tint = C.Muted) }
                }
            }
        }
        if (order.isEmpty()) item(key = "empty") { Empty("Empty for now. Tap ＋ on songs anywhere while this playlist is the one you're building", PI.PlaylistAdd) }
        itemsIndexed(order, key = { _, i -> "t$i" }) { k, i ->  // playlists never hold a track twice
            ReorderableItem(reorder, key = "t$i", enabled = editing) { dragging ->
                Box(Modifier.background(if (dragging) C.Raised2 else androidx.compose.ui.graphics.Color.Transparent)) {
                    SongRow(i, { if (!editing) app.player.play(order, k, p.name) }, number = k + 1, playlistId = if (editable) p.id else null, showAdd = false,
                        trailing = if (editing) ({
                            IconButton(onClick = { scope.launch { repo.remove(p.id, i) } }) { Icon(PI.RemoveBox, "Remove", tint = C.Magenta) }
                            Icon(PI.Drag, "Reorder", Modifier.draggableHandle(onDragStopped = { saveOrder() }).padding(12.dp), tint = C.Muted)
                        }) else null)
                }
            }
        }
        if (editable && suggestions.isNotEmpty() && !editing) {
            item(key = "sugg") { SectionTitle("Fits this playlist", action = { Icon(PI.Sparkle, null, Modifier.padding(end = 12.dp).size(18.dp), tint = C.Violet) }) }
            items(suggestions.filter { lib.tracks[it].id !in p.ids }, key = { "g$it" }) { i ->
                SongRow(i, { app.player.play(suggestions, suggestions.indexOf(i), "Fits ${p.name}") }, showAdd = false, trailing = {
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); scope.launch { repo.add(p.id, listOf(i)); repo.say("Added to ${p.name}") } }) {
                        Icon(PI.AddBox, "Add to ${p.name}", tint = C.Cyan)
                    }
                })
            }
        }
    }
}

// ---------------- Similar (drill down) ----------------
@Composable
fun SimilarScreen(chain: List<Int>, bottomPad: Dp) {
    val app = LocalApp.current
    val repo = app.repo
    val lib = repo.lib.collectAsStateWithLifecycle().value ?: return
    val seed = chain.last()
    var list by remember { mutableStateOf<List<Int>?>(null) }
    val n by repo.similarResults.collectAsStateWithLifecycle()
    var opts by remember { mutableStateOf(false) }
    LaunchedEffect(chain, n) { list = null; val skip = chain.toSet(); list = repo.neighbors(seed, n + skip.size).filter { it !in skip }.take(n) }
    val t = lib.tracks[seed]
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPad + 16.dp)) {
        item { Header("Similar to ${t.name}", t.by, back = true, actions = {
            ActiveChip()
            Box {
                IconButton(onClick = { opts = true }) { Icon(PI.Gear, "How many", tint = C.Muted) }
                DropdownMenu(opts, { opts = false }, containerColor = C.Raised2) {
                    Text("Similar songs to show", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    listOf(10, 25, 50, 100, 250).forEach { k -> DropdownMenuItem({ Text("$k" + if (k == n) "  ✓" else "") }, { opts = false; repo.setSimilarResults(k) }) }
                }
            }
        }) }
        if (chain.size > 1) item {  // the trail you followed; tap to go back to any step
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                chain.forEachIndexed { k, i ->
                    if (k > 0) Icon(PI.Right, null, Modifier.size(16.dp), tint = C.Faint)
                    Text(lib.tracks[i].name, Modifier.clip(Pill).background(if (k == chain.lastIndex) C.Raised2 else C.Raised)
                        .clickable(enabled = k < chain.lastIndex) { repeat(chain.lastIndex - k) { app.nav.popBackStack() } }.padding(horizontal = 10.dp, vertical = 5.dp)
                        .widthIn(max = 160.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (k == chain.lastIndex) C.Text else C.Muted)
                }
            }
        }
        item {
            val l = list.orEmpty()
            PlayShuffle(listOf(seed) + l, "Similar to ${t.name}") {
                IconButton(onClick = { app.showOnMap(MapFocus(listOf(seed) + l, "Like ${t.name}", select = seed)) }) { Icon(PI.Compass, "On the map", tint = C.Muted) }
            }
        }
        item { SongRow(seed, { app.player.play(listOf(seed) + list.orEmpty(), 0, "Similar to ${t.name}") }) }
        item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = C.Line) }
        val l = list
        if (l == null) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 2.dp) } }
        else items(l, key = { "n$it" }) { i ->
            SongRow(i, { app.player.play(listOf(seed) + l, l.indexOf(i) + 1, "Similar to ${t.name}") }, trailing = {
                IconButton(onClick = { app.similar(chain + i) }) { Icon(PI.Similar, "Similar to this", tint = C.Violet) }
            })
        }
    }
}
