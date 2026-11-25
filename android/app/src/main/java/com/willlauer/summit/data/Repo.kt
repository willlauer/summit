package com.willlauer.summit.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Status {
    data object Loading : Status
    data object Ready : Status
    data class Offline(val message: String) : Status   // showing the cached library; the server isn't reachable
    data class Failed(val message: String) : Status    // nothing to show
    data object Local : Status                          // a synced card (the JM21): everything from the card, no network
}

/**
 * App state shared by every screen: the library (cached on disk so the app opens instantly, then refreshed),
 * playlists, likes, the playlist being built ("active"), and map preferences. Playlist writes carry the version they
 * were based on; if the desktop changed the playlist meanwhile, edits are re-applied to the current version.
 */
class Repo(private val ctx: Context, val api: Api, private val scope: CoroutineScope) {
    private val prefs = ctx.getSharedPreferences("summit", Context.MODE_PRIVATE)
    private val cacheFile = File(ctx.filesDir, "library.json")

    val lib = MutableStateFlow<Library?>(null)
    val status = MutableStateFlow<Status>(Status.Loading)
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val folders = MutableStateFlow<List<Folder>>(emptyList())
    val closedFolders = MutableStateFlow(prefs.getStringSet("closedFolders", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet())
    val liked = MutableStateFlow<List<String>>(emptyList())         // track ids, most recent first
    val active = MutableStateFlow(prefs.getInt("active", -1).takeIf { it >= 0 })
    val space = MutableStateFlow(prefs.getString("space", null) ?: "combined")
    val view3d = MutableStateFlow(prefs.getBoolean("3d", false))
    /** How many "Sounds like" results search shows. */
    val vibeResults = MutableStateFlow(prefs.getInt("vibeResults", 30))
    fun setVibeResults(n: Int) { vibeResults.value = n; prefs.edit().putInt("vibeResults", n).apply() }
    /** How many tracks "Similar to" lists. */
    val similarResults = MutableStateFlow(prefs.getInt("similarResults", 25))
    fun setSimilarResults(n: Int) { similarResults.value = n; prefs.edit().putInt("similarResults", n).apply() }
    var lookKey: String
        get() = prefs.getString("look", null) ?: "pixel"
        set(v) { prefs.edit().putString("look", v).apply() }
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    var serverUrl: String
        get() = prefs.getString("server", null) ?: DEFAULT_SERVER
        set(v) { prefs.edit().putString("server", v.trimEnd('/')).apply(); api.base = v.trimEnd('/') }
    val hasServer get() = prefs.contains("server")

    init { api.base = serverUrl }

    fun say(msg: String) { messages.tryEmit(msg) }

    fun refresh() = scope.launch { load() }

    suspend fun load() {
        if (lib.value == null && cacheFile.exists()) runCatching {
            val raw = withContext(Dispatchers.IO) { cacheFile.readText() }
            apply(withContext(Dispatchers.Default) { api.parseLibrary(raw) })
        }
        // A synced card (the JM21) makes the app local: the card's library, files and art, or what the last Wi-Fi sync
        // brought if that's newer (data/Lan.kt); edits wait on the player until the next Wi-Fi sync. No Tailscale.
        val card = withContext(Dispatchers.IO) { runCatching { Card.load(ctx, api) }.onFailure { say("Couldn't read the card: ${it.message}") }.getOrNull() }
        if (card != null) {
            status.value = Status.Local
            applyLocal(card.first)
            if (Lan.paired) { api.base = Lan.url; Lan.kick(ctx as com.willlauer.summit.SummitApp) }
            return
        }
        // Phone: straight to the Mac while it's on the same Wi-Fi (paired), else the server address (Tailscale).
        api.base = if (Lan.paired && Lan.reachable(ctx as com.willlauer.summit.SummitApp)) Lan.url else serverUrl
        try {
            val raw = api.libraryRaw()
            val p = withContext(Dispatchers.Default) { api.parseLibrary(raw) }
            apply(p)
            withContext(Dispatchers.IO) { cacheFile.writeText(raw) }
            status.value = Status.Ready
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            status.value = if (lib.value != null) Status.Offline(why) else Status.Failed(why)
        }
    }

    /** The rekordbox / Music app playlists and their folders (read-only, negative ids) after the given ones: from the
     *  last Wi-Fi sync, else from the card. */
    private fun ext() = Lan.savedExt()?.takeIf { it.playlists.isNotEmpty() } ?: Card.state.value.info?.let { ExtPlaylists(it.playlists, it.folders) }
    private fun withCard(ps: List<Playlist>) = ps.filter { it.id >= 0 } + ext()?.playlists.orEmpty()
    private fun withCardFolders(fs: List<Folder>) = fs.filter { it.id >= 0 } + ext()?.folders.orEmpty()
    /** On a synced card: no server; edits are kept on the player ([Lan.pending]) until a Wi-Fi sync. */
    val local get() = status.value == Status.Local
    private val app get() = ctx as com.willlauer.summit.SummitApp

    /** The card's library, or the last Wi-Fi sync's if newer, with the edits still waiting replayed on top. */
    private suspend fun applyLocal(card: LibraryPayload) {
        val synced = Card.state.value.info?.synced ?: 0
        val newer = withContext(Dispatchers.IO) { Lan.savedLibrary(synced) }
        apply(newer?.let { withContext(Dispatchers.Default) { runCatching { api.parseLibrary(it) }.getOrNull() } } ?: card)
        replayPending()
    }

    /** After a Wi-Fi sync: show what it brought (phone: just reload from the Mac). */
    suspend fun afterLanSync() {
        if (!local) { load(); return }
        val c = withContext(Dispatchers.IO) { runCatching { Card.load(ctx, api) }.getOrNull() } ?: return
        applyLocal(c.first)
    }

    private fun replayPending() {
        val p = Lan.pending
        var ps = playlists.value.filter { it.id !in p.deleted }
        ps = ps.map { pl -> p.edits[pl.id]?.let { e -> pl.copy(ids = e.ids ?: pl.ids, name = e.name ?: pl.name, folder = e.folder ?: pl.folder) } ?: pl }
        ps = ps + p.created.map { Playlist(it.temp, it.name, "playlist", it.ids, 0, System.currentTimeMillis() / 1000.0, it.folder) }
        playlists.value = ps
        var lk = liked.value
        p.likes.forEach { (id, on) -> lk = if (on) listOf(id) + (lk - id) else lk - id }
        liked.value = lk
    }

    // ---- edits on a card's player, kept until the next Wi-Fi sync ----
    private fun localUpdate(id: Int, ids: List<String>? = null, name: String? = null, folder: Int? = null) {
        val p = playlist(id) ?: return
        adopt(p.copy(ids = ids ?: p.ids, name = name ?: p.name, folder = folder ?: p.folder, updated = System.currentTimeMillis() / 1000.0))
        val q = Lan.pending
        Lan.savePending(if (q.created.any { it.temp == id }) q.copy(created = q.created.map { if (it.temp == id) it.copy(ids = ids ?: it.ids, name = name ?: it.name, folder = folder ?: it.folder) else it })
            else { val e = q.edits[id] ?: PendingEdit(base = p.version); q.copy(edits = q.edits + (id to e.copy(ids = ids ?: e.ids, name = name ?: e.name, folder = folder ?: e.folder))) })
        Lan.kick(app)
    }
    private fun localCreate(name: String, ids: List<String>, folder: Int?): Int {
        val temp = maxOf(900_000_000, (playlists.value.maxOfOrNull { it.id } ?: 0) + 1)
        playlists.value = playlists.value + Playlist(temp, name, "playlist", ids, 0, System.currentTimeMillis() / 1000.0, folder)
        Lan.savePending(Lan.pending.copy(created = Lan.pending.created + PendingNew(temp, name, ids, folder)))
        Lan.kick(app)
        return temp
    }
    private fun localDelete(id: Int) {
        playlists.value = playlists.value.filter { it.id != id }
        val q = Lan.pending
        Lan.savePending(if (q.created.any { it.temp == id }) q.copy(created = q.created.filter { it.temp != id })
            else q.copy(edits = q.edits - id, deleted = q.deleted + id))
        if (active.value == id) setActive(null)
        Lan.kick(app)
    }
    private fun localOnly(): Boolean {
        if (!local) return false
        say("Folders are made in Summit on the Mac")
        return true
    }
    private fun readOnly(id: Int?): Boolean {
        if (id == null || id >= 0) return false
        say("This comes from rekordbox or the Music app: change it there, then sync the card")
        return true
    }

    private fun apply(p: LibraryPayload) {
        val L = Library(p)
        lib.value = L
        playlists.value = withCard(p.playlists)
        folders.value = withCardFolders(p.folders)
        liked.value = p.liked
        if (space.value !in L.spaces) space.value = L.defaultSpace
        if (active.value != null && p.playlists.none { it.id == active.value }) setActive(null)
    }

    fun spaceData(): SpaceData? = lib.value?.spaces?.get(space.value)
    fun setSpace(key: String) { space.value = key; prefs.edit().putString("space", key).apply() }
    fun set3D(on: Boolean) { view3d.value = on; prefs.edit().putBoolean("3d", on).apply() }
    fun setActive(id: Int?) { active.value = id; prefs.edit().putInt("active", id ?: -1).apply() }

    // ---- playlists ----
    fun playlist(id: Int?) = playlists.value.find { it.id == id }
    val userPlaylists get() = playlists.value.filter { it.kind == "playlist" }
    fun idxs(p: Playlist): List<Int> { val L = lib.value ?: return emptyList(); return p.ids.mapNotNull { L.byId[it] } }
    fun inActive(i: Int): Boolean { val L = lib.value ?: return false; return playlist(active.value)?.ids?.contains(L.tracks[i].id) == true }

    private fun adopt(p: Playlist) { playlists.value = playlists.value.map { if (it.id == p.id) p else it } }

    suspend fun syncPlaylists() {
        if (local) return
        runCatching { withCard(api.playlists()) }.onSuccess { fresh ->
            val sig = { ps: List<Playlist> -> ps.joinToString { "${it.id}:${it.version}" } }
            if (sig(fresh) != sig(playlists.value)) playlists.value = fresh
        }
        runCatching { withCardFolders(api.folders()) }.onSuccess { fresh -> if (fresh.toSet() != folders.value.toSet()) folders.value = fresh }
    }

    // ---- folders ----
    /** "Gig › Warmup": where a playlist is filed. Empty at the top level. */
    fun folderPath(id: Int?): String {
        val byId = folders.value.associateBy { it.id }
        val names = ArrayDeque<String>()
        val seen = HashSet<Int>()
        var f = id?.let { byId[it] }
        while (f != null && seen.add(f.id)) { names.addFirst(f.name); f = f.parent?.let { byId[it] } }
        return names.joinToString(" › ")
    }
    fun toggleFolder(id: Int) {
        closedFolders.value = if (id in closedFolders.value) closedFolders.value - id else closedFolders.value + id
        prefs.edit().putStringSet("closedFolders", closedFolders.value.map { it.toString() }.toSet()).apply()
    }
    /** A folder and everything below it: where it can't be moved to. */
    fun subtree(id: Int): Set<Int> = buildSet {
        fun walk(f: Int) { if (add(f)) folders.value.filter { it.parent == f }.forEach { walk(it.id) } }
        walk(id)
    }
    private fun adoptFolder(f: Folder) { folders.value = folders.value.filter { it.id != f.id } + f }
    suspend fun createFolder(name: String, parent: Int?): Int? = if (localOnly() || readOnly(parent)) null else guard {
        val f = api.createFolder(name, parent)
        adoptFolder(f)
        if (parent != null && parent in closedFolders.value) toggleFolder(parent)  // show the new folder
        f.id
    }
    suspend fun renameFolder(id: Int, name: String) = if (localOnly() || readOnly(id)) Unit else guard { adoptFolder(api.updateFolder(id, name = name)) }
    suspend fun moveFolder(id: Int, parent: Int?) = if (localOnly() || readOnly(id) || readOnly(parent)) Unit else guard { adoptFolder(api.updateFolder(id, parent = parent ?: 0)) }
    /** Its playlists and subfolders move up a level (on the server), so reload both. */
    suspend fun deleteFolder(id: Int) = if (localOnly() || readOnly(id)) Unit else guard {
        api.deleteFolder(id)
        folders.value = withCardFolders(api.folders())
        playlists.value = withCard(api.playlists())
    }

    suspend fun move(id: Int, folder: Int?) = if (readOnly(id) || readOnly(folder)) Unit else if (local) localUpdate(id, folder = folder ?: 0) else guard {
        val p = playlist(id) ?: return@guard
        try { adopt(api.updatePlaylist(id, p.version, folder = folder ?: 0)) }
        catch (c: ConflictException) { adopt(api.updatePlaylist(id, c.current.version, folder = folder ?: 0)) }
    }

    suspend fun create(name: String, idxs: List<Int> = emptyList(), folder: Int? = null): Int? = if (readOnly(folder)) null
        else if (local) lib.value?.let { L -> localCreate(name, idxs.map { L.tracks[it].id }, folder) } else guard {
        val L = lib.value!!
        val ids = idxs.map { L.tracks[it].id }
        val r = api.createPlaylist(name, ids, folder)
        playlists.value = playlists.value + Playlist(r.id, name, "playlist", ids, r.version, folder = folder)
        r.id
    }

    /** Add / remove style edits: re-applied to the current version if another device changed the playlist. */
    suspend fun edit(id: Int, change: (List<String>) -> List<String>) = if (readOnly(id)) Unit
        else if (local) playlist(id)?.let { localUpdate(id, ids = change(it.ids)) } ?: Unit else guard {
        val p = playlist(id) ?: return@guard
        try { adopt(api.updatePlaylist(id, p.version, ids = change(p.ids))) }
        catch (c: ConflictException) { adopt(api.updatePlaylist(id, c.current.version, ids = change(c.current.ids))) }
    }

    /** A whole new order: dropped (with a note) if the playlist changed elsewhere meanwhile. */
    suspend fun reorder(id: Int, ids: List<String>) = if (readOnly(id)) Unit else if (local) localUpdate(id, ids = ids) else guard {
        val p = playlist(id) ?: return@guard
        adopt(p.copy(ids = ids))  // optimistic, so the list doesn't jump back while saving
        try { adopt(api.updatePlaylist(id, p.version, ids = ids)) }
        catch (c: ConflictException) { adopt(c.current); say("Playlist changed on another device: showing the latest") }
    }

    suspend fun rename(id: Int, name: String) = if (readOnly(id)) Unit else if (local) localUpdate(id, name = name) else guard {
        val p = playlist(id) ?: return@guard
        try { adopt(api.updatePlaylist(id, p.version, name = name)) }
        catch (c: ConflictException) { adopt(api.updatePlaylist(id, c.current.version, name = name)) }
    }

    suspend fun delete(id: Int) = if (readOnly(id)) Unit else if (local) localDelete(id) else guard {
        api.deletePlaylist(id)
        playlists.value = playlists.value.filter { it.id != id }
        if (active.value == id) setActive(null)
    }

    suspend fun add(id: Int, idxs: List<Int>): Int {
        val L = lib.value ?: return 0
        if (readOnly(id)) return 0
        val p = playlist(id) ?: return 0
        val add = idxs.map { L.tracks[it].id }.distinct().filter { it !in p.ids }
        if (add.isNotEmpty()) edit(id) { ids -> ids + add.filter { it !in ids } }
        return add.size
    }
    suspend fun remove(id: Int, i: Int) { val t = lib.value?.tracks?.get(i) ?: return; edit(id) { ids -> ids.filter { it != t.id } } }

    /** ＋ on a song: toggles it in the playlist being built. Returns false when no playlist is active. */
    suspend fun toggleActive(i: Int): Boolean {
        val p = playlist(active.value) ?: return false
        val L = lib.value ?: return false
        if (L.tracks[i].id in p.ids) { remove(p.id, i); say("Removed from ${p.name}") }
        else { add(p.id, listOf(i)); say("Added to ${p.name}") }
        return true
    }

    // ---- likes ----
    fun isLiked(i: Int) = lib.value?.tracks?.get(i)?.id in liked.value
    fun toggleLike(i: Int) {
        val id = lib.value?.tracks?.get(i)?.id ?: return
        val on = id !in liked.value
        liked.value = if (on) listOf(id) + liked.value else liked.value - id
        if (local) { Lan.savePending(Lan.pending.copy(likes = Lan.pending.likes + (id to on))); Lan.kick(app); return }
        scope.launch { runCatching { api.like(id, on) }.onFailure { liked.value = if (on) liked.value - id else listOf(id) + liked.value; say("Couldn't save the like") } }
    }
    val likedIdxs: List<Int> get() { val L = lib.value ?: return emptyList(); return liked.value.mapNotNull { L.byId[it] } }

    // ---- similarity ----
    private val nbCache = HashMap<String, List<Int>>()
    suspend fun neighbors(i: Int, k: Int = 25): List<Int> {
        val key = "${space.value}:$i:$k"
        nbCache[key]?.let { return it }
        if (local) return nearest(spaceData()?.let { floatArrayOf(it.x[i], it.y[i]) }, k, setOf(i)).also { nbCache[key] = it }
        return runCatching { api.neighbors(i, k, space.value) }.getOrElse { say("Couldn't reach Summit"); emptyList() }.also { if (it.isNotEmpty()) nbCache[key] = it }
    }
    /** On the card: the closest tracks on the map (its positions came with the card's library) to a point. */
    private fun nearest(pt: FloatArray?, k: Int, skip: Set<Int>): List<Int> {
        val s = spaceData() ?: return emptyList()
        pt ?: return emptyList()
        return (0 until s.n).filter { it !in skip }.sortedBy { val dx = s.x[it] - pt[0]; val dy = s.y[it] - pt[1]; dx * dx + dy * dy }.take(k)
    }
    suspend fun suggest(p: Playlist, k: Int = 20): List<Int> = if (local) {
        val s = spaceData(); val ix = idxs(p).filter { s != null && it < s.n }
        if (s == null || ix.isEmpty()) emptyList()
        else nearest(floatArrayOf(ix.map { s.x[it] }.average().toFloat(), ix.map { s.y[it] }.average().toFloat()), k, ix.toSet())
    } else runCatching { api.suggest(p.ids, k, space.value) }.getOrElse { emptyList() }
    /** Tracks that sound like [q], by sound alone (name matches are listed separately). */
    suspend fun vibeSearch(q: String, n: Int): List<Int> = if (local) emptyList() else runCatching { api.search(q, n, mode = "vibe").map { it.i } }.getOrElse { emptyList() }
    fun prefetch(ids: List<String>, q: String = "high") { if (ids.isNotEmpty() && !local) scope.launch { runCatching { api.prefetch(ids, q) } } }

    private suspend fun <T> guard(block: suspend () -> T): T? = try { block() } catch (e: Exception) { say("Couldn't reach Summit: ${e.message}"); null }

    companion object { const val DEFAULT_SERVER = "http://127.0.0.1:8766" }
}
