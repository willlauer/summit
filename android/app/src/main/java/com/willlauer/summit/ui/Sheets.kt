package com.willlauer.summit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.asImageBitmap
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
                is Sheet.MoveTo -> MoveToSheet(sheet.playlistId, sheet.folderId)
                is Sheet.FolderActions -> FolderSheet(sheet.id)
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
    if (lib.hasAlbum(i)) SheetItem(PI.Library, "Go to album: ${t.album}") { app.album(t.album!!) }
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
                    Text(listOfNotNull("${p.ids.size} songs", app.repo.folderPath(p.folder).ifEmpty { null }, "building".takeIf { p.id == active }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (has) Icon(PI.CheckBox, "In this playlist", tint = C.Green)
            }
        }
    }
}

/** Where to file a playlist ([playlistId]) or a folder ([folderId]; never into itself or its own subfolders). */
@Composable
private fun MoveToSheet(playlistId: Int?, folderId: Int?) {
    val app = LocalApp.current
    val folders by app.repo.folders.collectAsStateWithLifecycle()
    val p = playlistId?.let { app.repo.playlist(it) }
    val f = folderId?.let { id -> folders.find { it.id == id } }
    val name = p?.name ?: f?.name ?: return
    val here = if (p != null) p.folder else f?.parent
    val banned = f?.let { app.repo.subtree(it.id) }.orEmpty()
    val move = { to: Int? ->
        app.sheet = null
        if (to != here) app.scope.launch { if (p != null) app.repo.move(p.id, to) else app.repo.moveFolder(f!!.id, to); app.repo.say("Moved $name") }
    }
    Text("Move $name to…", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    SheetItem(PI.Add, "New folder…", C.Cyan) { app.sheet = null; app.dialog = Dialog.NewFolder(movePlaylist = p?.id, moveFolder = f?.id) }
    SheetItem(PI.Queue, "Top level" + if (here == null) " ✓" else "") { move(null) }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        items(folders.filter { it.id !in banned }.map { it.id to app.repo.folderPath(it.id) }.sortedBy { it.second.lowercase() }, key = { it.first }) { (fid, path) ->
            SheetItem(PI.Folder, path + if (fid == here) " ✓" else "") { move(fid) }
        }
    }
}

@Composable
private fun FolderSheet(id: Int) {
    val app = LocalApp.current
    val folders by app.repo.folders.collectAsStateWithLifecycle()
    val f = folders.find { it.id == id } ?: return
    val path = app.repo.folderPath(f.parent)
    Text(f.name, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (path.isNotEmpty()) Text("In $path", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = C.Muted)
    Spacer(Modifier.height(6.dp))
    SheetItem(PI.PlaylistAdd, "New playlist here") { app.sheet = null; app.dialog = Dialog.NewPlaylist(makeActive = true, folder = f.id) }
    SheetItem(PI.Folder, "New folder inside") { app.sheet = null; app.dialog = Dialog.NewFolder(parent = f.id) }
    SheetItem(PI.Edit, "Rename") { app.sheet = null; app.dialog = Dialog.RenameFolder(f.id) }
    SheetItem(PI.Route, "Move to…") { app.sheet = Sheet.MoveTo(folderId = f.id) }
    SheetItem(PI.Delete, "Delete folder", C.Magenta) { app.sheet = null; app.dialog = Dialog.DeleteFolder(f.id) }
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
        StreamingPicker()
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
                val id = app.repo.create(name, d.add, d.folder) ?: return@launch
                if (d.makeActive || app.repo.active.value == null) app.repo.setActive(id)
                app.repo.say(if (d.add.isEmpty()) "Building $name: tap ＋ on songs to add them" else "Added to $name")
            }
            close()
        }
        is Dialog.NewFolder -> NameDialog(if (d.parent != null) "New folder in ${app.repo.folderPath(d.parent)}" else "New folder", "", "Create") { name ->
            app.scope.launch {
                val id = app.repo.createFolder(name, d.parent) ?: return@launch
                when {
                    d.movePlaylist != null -> { app.repo.move(d.movePlaylist, id); app.repo.say("Moved to $name") }
                    d.moveFolder != null -> { app.repo.moveFolder(d.moveFolder, id); app.repo.say("Moved to $name") }
                }
            }
            close()
        }
        is Dialog.RenameFolder -> {
            val f = app.repo.folders.value.find { it.id == d.id } ?: run { close(); return }
            NameDialog("Rename folder", f.name, "Rename") { name -> app.scope.launch { app.repo.renameFolder(f.id, name) }; close() }
        }
        is Dialog.DeleteFolder -> {
            val f = app.repo.folders.value.find { it.id == d.id } ?: run { close(); return }
            AlertDialog(onDismissRequest = close, containerColor = C.Raised2, shape = PxBig,
                title = { Text("Delete folder “${f.name}”?") }, text = { Text("Only the folder goes: its playlists and folders move up a level.") },
                confirmButton = { TextButton(onClick = { app.scope.launch { app.repo.deleteFolder(f.id) }; close() }) { Text("Delete", color = C.Magenta) } },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
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
    KeyDialog({ app.dialog = null }) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(14.dp))
        PixelField(name, { name = it }, Modifier.focusRequester(focus), placeholder = "Name",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), onDone = { if (name.isNotBlank()) onDone(name.trim()) })
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { app.dialog = null }) { Text("Cancel") }
            TextButton(onClick = { if (name.isNotBlank()) onDone(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) }
        }
    }
}

/** Switch the whole app between looks (Classic, Pixel). Remembered on this phone. */
@Composable
fun LookPicker() {
    val app = LocalApp.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Looks.all.forEach { l -> Chip(l.label, LookState.current == l) { LookState.set(l); app.repo.lookKey = l.key } }
    }
}

/** Streaming quality (playback.Streaming): Auto (high on Wi-Fi, data saver on cellular), High, or Data saver. */
@Composable
fun StreamingPicker() {
    // On a synced card's player (the JM21) nothing streams: the card and Wi-Fi sync cover it.
    val status by (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.willlauer.summit.SummitApp).repo.status.collectAsStateWithLifecycle()
    if (status == com.willlauer.summit.data.Status.Local) return
    val q by com.willlauer.summit.playback.Streaming.quality.collectAsStateWithLifecycle()
    Text("Streaming", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.willlauer.summit.playback.Streaming.QUALITIES.forEach { (k, label) -> Chip(label, q == k) { com.willlauer.summit.playback.Streaming.setQuality(k) } }
    }
    Text(when (q) {
        "high" -> "256k AAC or the original file, on every network."
        "low" -> "128k AAC everywhere: a third of the data."
        else -> "High quality on Wi-Fi, data saver (128k) on cellular."
    } + " The next three tracks download ahead, so skips start instantly.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
    OfflinePanel()
}

/** The offline store (playback.OfflineSync): the newest 6 GB, synced on Wi-Fi, with progress and Sync / Stop. */
@Composable
fun OfflinePanel() {
    val summit = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.willlauer.summit.SummitApp
    val st by com.willlauer.summit.playback.OfflineSync.state.collectAsStateWithLifecycle()
    val gb = { b: Long -> "%.1f".format(b / 1073741824.0) }
    Text("Offline", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(if (st.total == 0) "The newest ${com.willlauer.summit.playback.OfflineSync.BUDGET_MB / 1024} GB of your library, kept on the phone in data-saver quality. Syncs on Wi-Fi."
        else "${st.done} of ${st.total} tracks · ${gb(st.bytesOnPhone)} of ${gb(st.bytesTotal)} GB on the phone", style = MaterialTheme.typography.bodyMedium)
    if (st.total > 0) LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = C.Cyan, trackColor = C.Line)
    if (st.note.isNotEmpty()) Text(st.note, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (st.running) Chip("Stop", false) { com.willlauer.summit.playback.OfflineSync.stop() }
        else Chip("Sync now", false) { com.willlauer.summit.playback.OfflineSync.start(summit) }
    }
}

/** Settings › Card: the synced microSD card (data/Card.kt): what's on it, access, reload. Shown only where a card is. */
@Composable
fun CardPanel() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val summit = ctx.applicationContext as com.willlauer.summit.SummitApp
    val st by com.willlauer.summit.data.Card.state.collectAsStateWithLifecycle()
    if (st.root == null && st.info == null) return
    Text("Card", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    val info = st.info
    if (info != null) {
        val other = info.playlists.size
        val synced = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(info.synced * 1000))
        Text("${info.files.size} tracks play from the card, no network needed" + if (other > 0) " · $other rekordbox / Music playlists" else "",
            style = MaterialTheme.typography.bodyMedium)
        Text("Synced $synced from Summit on the Mac (Sync › FiiO MicroSD)", style = MaterialTheme.typography.bodySmall, color = C.Muted)
    } else Text(st.note, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!st.access) Chip("Allow access", false) {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Chip("Reload card", false) { summit.repo.refresh() }
    }
}

/** Settings › Wi-Fi sync (data/Lan.kt): find Summit on this Wi-Fi, pair with the Mac's code, sync now. */
@Composable
fun WifiPanel() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val summit = ctx.applicationContext as com.willlauer.summit.SummitApp
    val st by com.willlauer.summit.data.Lan.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var code by remember { mutableStateOf("") }
    Text("Wi-Fi sync", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    if (st.paired) {
        Text("Paired with ${st.name}" + when (st.reachable) { true -> " · on this Wi-Fi"; false -> " · not on this Wi-Fi"; null -> "" },
            style = MaterialTheme.typography.bodyMedium)
        val last = if (st.lastSync > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(st.lastSync)) else "never"
        Text("Last synced $last" + if (st.pending > 0) " · ${st.pending} change${if (st.pending == 1) "" else "s"} waiting" else "",
            style = MaterialTheme.typography.bodySmall, color = C.Muted)
    } else Text("Sync playlists and likes with Summit on the Mac over your Wi-Fi (no Tailscale). Turn on Wi-Fi sync in the Mac's Settings, then find it here.",
        style = MaterialTheme.typography.bodyMedium)
    if (st.total > 0) LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = C.Cyan, trackColor = C.Line)
    if (st.note.isNotEmpty()) Text(st.note + if (st.total > 0) " (${st.done} / ${st.total})" else "", style = MaterialTheme.typography.bodySmall, color = C.Muted)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (st.paired) {
            Chip(if (st.syncing) "Syncing…" else "Sync now", false) { if (!st.syncing) scope.launch { com.willlauer.summit.data.Lan.sync(summit) } }
            Chip("Unpair", false) { com.willlauer.summit.data.Lan.unpair() }
        } else Chip(if (st.searching) "Looking…" else "Find my Mac", false) { if (!st.searching) scope.launch { com.willlauer.summit.data.Lan.discover(summit) } }
    }
    st.found.forEach { f ->
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().clip(Px).border(1.dp, C.Line, Px).clickable { pairing = f; code = "" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(f.first, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("Pair", style = MaterialTheme.typography.labelLarge, color = C.Cyan)
        }
    }
    pairing?.let { f ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val go = { if (code.length == 6) { val c = code; pairing = null; scope.launch { com.willlauer.summit.data.Lan.pair(summit, f.first, f.second, c) } } }
        KeyDialog({ pairing = null }) {
            Text("Pair with ${f.first.removePrefix("Summit on ")}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Enter the code from the Mac: Summit › Settings › Wi-Fi sync.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            PixelField(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.focusRequester(focus), placeholder = "6-digit code",
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword), onDone = { go() })
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { pairing = null }) { Text("Cancel") }
                TextButton(onClick = { go() }, enabled = code.length == 6) { Text("Pair") }
            }
        }
    }
}


/** Settings › Updates: install the Mac's newer build over Wi-Fi (data/Updater.kt). */
@Composable
fun UpdatePanel() {
    val summit = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.willlauer.summit.SummitApp
    val lan by com.willlauer.summit.data.Lan.state.collectAsStateWithLifecycle()
    val st by com.willlauer.summit.data.Updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (!lan.paired) return
    LaunchedEffect(lan.url) { com.willlauer.summit.data.Updater.check(summit) }
    Text("Updates", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    val a = st.available
    Text(if (a != null) "New version on the Mac: ${a.versionName}" else "This is ${com.willlauer.summit.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
    if (st.installing) LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = C.Cyan, trackColor = C.Line)
    if (st.note.isNotEmpty()) Text(st.note, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (a != null) Chip(if (st.installing) "Installing…" else "Install update", true) { if (!st.installing) scope.launch { com.willlauer.summit.data.Updater.install(summit) } }
        else Chip("Check", false) { scope.launch { com.willlauer.summit.data.Updater.check(summit) } }
    }
}

/** Settings › Home screen & apps: Summit can be the device's home screen (the JM21); the other apps open from here. */
@Composable
fun AppsPanel() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pm = ctx.packageManager
    val isHome = remember {
        pm.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName == ctx.packageName
    }
    var open by remember { mutableStateOf(false) }
    Text("Home screen & apps", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(if (isHome) "Summit is the home screen: the Home button comes back here." else "Make Summit the home screen so the player starts in it and Home comes back to it.",
        style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (isHome) "Change home app" else "Make home screen", false) {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Chip(if (open) "Hide apps" else "Other apps", open) { open = !open }
        Chip("Android settings", false) {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    if (open) {
        val apps = remember {
            pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map { it to it.loadLabel(pm).toString() }.sortedBy { it.second.lowercase() }
        }
        Spacer(Modifier.height(8.dp))
        apps.forEach { (ri, label) ->
            val icon = remember(ri) { runCatching { ri.loadIcon(pm).let { d -> android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(b)) } }.asImageBitmap() }.getOrNull() }
            Row(Modifier.fillMaxWidth().clip(Px).clickable {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) androidx.compose.foundation.Image(icon, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
