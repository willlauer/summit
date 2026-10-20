package com.willlauer.summit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.willlauer.summit.data.splitArtists
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost() {
    val app = LocalApp.current
    val sheet = app.sheet ?: return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = sheet !is Sheet.Track)
    ModalBottomSheet(onDismissRequest = { app.sheet = null }, sheetState = state, containerColor = C.Raised, scrimColor = Color(0x9902060C), shape = PxBig,
        dragHandle = {  // a drift of snow along the top of the sheet, under the handle
            Box(Modifier.fillMaxWidth().snowCap(3, inside = true), contentAlignment = Alignment.Center) { Box(Modifier.padding(vertical = 14.dp).size(40.dp, P * 2).background(C.Faint)) }
        }) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            when (sheet) {
                is Sheet.Track -> TrackSheet(sheet.i, sheet.playlistId)
                is Sheet.AddTo -> AddToSheet(sheet.idxs)
                Sheet.Active -> ActiveSheet()
                Sheet.MapOptions -> MapOptionsSheet()
            }
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector?, text: String, tint: Color = C.Text, lead: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) lead() else if (icon != null) Icon(icon, null, tint = if (tint == C.Text) C.Muted else tint)
        Spacer(Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
private fun TrackSheet(i: Int, playlistId: Int?) {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val liked by app.repo.liked.collectAsStateWithLifecycle()
    val t = lib.tracks[i]
    val close = { app.sheet = null }
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(i, 56.dp, corner = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.by, t.bpm?.let { "$it BPM" }, t.key).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = C.Line)
    SheetItem(PI.PlayNext, "Play next") { app.player.playNext(i); close() }
    SheetItem(PI.Queue, "Add to queue") { app.player.addToQueue(i); close() }
    SheetItem(PI.PlaylistAdd, "Add to playlist…") { app.sheet = Sheet.AddTo(listOf(i)) }
    SheetItem(PI.Similar, "Similar songs") { app.similar(listOf(i)) }
    splitArtists(t.by).take(3).forEach { a -> SheetItem(PI.Person, "Go to $a") { app.artist(a) } }
    SheetItem(PI.Compass, "Show on the map") { app.showOnMap(MapFocus(listOf(i), t.name, select = i)) }
    val on = t.id in liked
    SheetItem(null, if (on) "Unlike" else "Like", lead = { LikeIcon(on) }) { app.repo.toggleLike(i); close() }
    if (playlistId != null) {
        val p = app.repo.playlist(playlistId)
        if (p != null && p.kind == "playlist") SheetItem(PI.RemoveBox, "Remove from ${p.name}", C.Magenta) { app.scope.launch { app.repo.remove(p.id, i) }; close() }
    }
}

@Composable
private fun AddToSheet(idxs: List<Int>) {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val pls by app.repo.playlists.collectAsStateWithLifecycle()
    val active by app.repo.active.collectAsStateWithLifecycle()
    val ids = idxs.map { lib.tracks[it].id }
    val mine = pls.filter { it.kind == "playlist" }.sortedByDescending { if (it.id == active) Double.MAX_VALUE else it.updated ?: 0.0 }
    Text(if (idxs.size == 1) "Add to playlist" else "Add ${idxs.size} songs to…", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
    SheetItem(PI.Add, "New playlist…", C.Cyan) { app.sheet = null; app.dialog = Dialog.NewPlaylist(add = idxs) }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        items(mine, key = { it.id }) { p ->
            val has = ids.all { it in p.ids }
            Row(Modifier.fillMaxWidth().clickable {
                app.scope.launch {
                    if (has && idxs.size == 1) { app.repo.remove(p.id, idxs[0]); app.repo.say("Removed from ${p.name}") }
                    else { val n = app.repo.add(p.id, idxs); app.repo.say(if (n > 0) "Added to ${p.name}" else "Already in ${p.name}") }
                }
                app.sheet = null
            }.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Mosaic(p, 44.dp, corner = 8.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.ids.size} songs" + if (p.id == active) " · building" else "", style = MaterialTheme.typography.bodySmall)
                }
                if (has) Icon(PI.CheckBox, "In this playlist", tint = C.Green)
            }
        }
    }
}

@Composable
private fun ActiveSheet() {
    val app = LocalApp.current
    val pls by app.repo.playlists.collectAsStateWithLifecycle()
    val active by app.repo.active.collectAsStateWithLifecycle()
    Text("Build a playlist", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
    Text("＋ on any song adds it to this playlist. Songs already in it are dimmed with a ✓.", Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium, color = C.Muted)
    SheetItem(PI.Add, "New playlist…", C.Cyan) { app.sheet = null; app.dialog = Dialog.NewPlaylist(makeActive = true) }
    LazyColumn(Modifier.heightIn(max = 380.dp)) {
        items(pls.filter { it.kind == "playlist" }.sortedByDescending { it.updated ?: 0.0 }, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().clickable { app.repo.setActive(p.id); app.sheet = null }.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Mosaic(p, 44.dp, corner = 8.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.ids.size} songs", style = MaterialTheme.typography.bodySmall)
                }
                RadioButton(selected = p.id == active, onClick = { app.repo.setActive(p.id); app.sheet = null })
            }
        }
    }
    if (active != null) {
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = C.Line)
        SheetItem(PI.Queue, "Open ${app.repo.playlist(active)?.name ?: "playlist"}") { app.playlist(active!!) }
        SheetItem(PI.Close, "Stop building") { app.repo.setActive(null); app.sheet = null }
    }
}

@Composable
private fun MapOptionsSheet() {
    val app = LocalApp.current
    val lib = app.repo.lib.collectAsStateWithLifecycle().value ?: return
    val space by app.repo.space.collectAsStateWithLifecycle()
    val ui = LocalMapUi.current
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text("Look", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        LookPicker()
        Spacer(Modifier.height(20.dp))
        Text("Map", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(14.dp))
        Text("Arrange by", style = MaterialTheme.typography.labelLarge, color = C.Muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { lib.spaces.values.forEach { s -> Chip(s.label, s.key == space) { app.repo.setSpace(s.key) } } }
        Text("CLAP hears the sound and vibe, EffNet the genre and production; Combined blends both.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(18.dp))
        Text("Colour", style = MaterialTheme.typography.labelLarge, color = C.Muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("cluster" to "Subgenre", "family" to "Genre", "bpm" to "BPM").forEach { (k, l) -> Chip(l, ui.color == k) { ui.color = k } }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Genre labels", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(ui.labels, { ui.labels = it })
        }
        Spacer(Modifier.height(16.dp))
        Text("Drag to move · pinch to zoom · tap a dot to see it · long-press a dot to add it to the playlist you're building. In 3D, twist with two fingers to turn and move them up / down to tilt.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
    }
}

val LocalMapUi = compositionLocalOf { MapUi() }

@Composable
fun DialogHost() {
    val app = LocalApp.current
    val d = app.dialog ?: return
    val close = { app.dialog = null }
    when (d) {
        is Dialog.NewPlaylist -> NameDialog("New playlist", "", if (d.add.isEmpty()) "Create" else "Create and add") { name ->
            app.scope.launch {
                val id = app.repo.create(name, d.add) ?: return@launch
                if (d.makeActive || app.repo.active.value == null) app.repo.setActive(id)
                app.repo.say(if (d.add.isEmpty()) "Building $name: tap ＋ on songs to add them" else "Added to $name")
            }
            close()
        }
        is Dialog.Rename -> {
            val p = app.repo.playlist(d.id) ?: run { close(); return }
            NameDialog("Rename playlist", p.name, "Rename") { name -> app.scope.launch { app.repo.rename(p.id, name) }; close() }
        }
        is Dialog.Delete -> {
            val p = app.repo.playlist(d.id) ?: run { close(); return }
            AlertDialog(onDismissRequest = close, containerColor = C.Raised2, shape = PxBig,
                title = { Text("Delete “${p.name}”?") }, text = { Text("This removes the playlist on the Mac too. The songs stay in your library.") },
                confirmButton = { TextButton(onClick = { app.scope.launch { app.repo.delete(p.id) }; close(); app.nav.popBackStack() }) { Text("Delete", color = C.Magenta) } },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit) {
    val app = LocalApp.current
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(onDismissRequest = { app.dialog = null }, containerColor = C.Raised2, shape = PxBig, title = { Text(title) },
        text = {
            PixelField(name, { name = it }, Modifier.focusRequester(focus), placeholder = "Name",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onDone(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { app.dialog = null }) { Text("Cancel") } })
}

/** Switch the whole app between looks (Classic, Pixel). Remembered on this phone. */
@Composable
fun LookPicker() {
    val app = LocalApp.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Looks.all.forEach { l -> Chip(l.label, LookState.current == l) { LookState.set(l); app.repo.lookKey = l.key } }
    }
}
