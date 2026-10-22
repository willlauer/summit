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
    val liked = MutableStateFlow<List<String>>(emptyList())         // track ids, most recent first
    val active = MutableStateFlow(prefs.getInt("active", -1).takeIf { it >= 0 })
    val space = MutableStateFlow(prefs.getString("space", null) ?: "combined")
    val view3d = MutableStateFlow(prefs.getBoolean("3d", false))
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

    private fun apply(p: LibraryPayload) {
        val L = Library(p)
        lib.value = L
        playlists.value = p.playlists
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
        runCatching { api.playlists() }.onSuccess { fresh ->
            val sig = { ps: List<Playlist> -> ps.joinToString { "${it.id}:${it.version}" } }
            if (sig(fresh) != sig(playlists.value)) playlists.value = fresh
        }
    }

    suspend fun create(name: String, idxs: List<Int> = emptyList()): Int? = guard {
        val L = lib.value!!
        val ids = idxs.map { L.tracks[it].id }
        val r = api.createPlaylist(name, ids)
        playlists.value = playlists.value + Playlist(r.id, name, "playlist", ids, r.version)
        r.id
    }

    /** Add / remove style edits: re-applied to the current version if another device changed the playlist. */
    suspend fun edit(id: Int, change: (List<String>) -> List<String>) = guard {
        val p = playlist(id) ?: return@guard
        try { adopt(api.updatePlaylist(id, p.version, ids = change(p.ids))) }
        catch (c: ConflictException) { adopt(api.updatePlaylist(id, c.current.version, ids = change(c.current.ids))) }
    }

    /** A whole new order: dropped (with a note) if the playlist changed elsewhere meanwhile. */
    suspend fun reorder(id: Int, ids: List<String>) = guard {
        val p = playlist(id) ?: return@guard
        adopt(p.copy(ids = ids))  // optimistic, so the list doesn't jump back while saving
        try { adopt(api.updatePlaylist(id, p.version, ids = ids)) }
        catch (c: ConflictException) { adopt(c.current); say("Playlist changed on another device: showing the latest") }
    }

    suspend fun rename(id: Int, name: String) = guard {
        val p = playlist(id) ?: return@guard
        try { adopt(api.updatePlaylist(id, p.version, name = name)) }
        catch (c: ConflictException) { adopt(api.updatePlaylist(id, c.current.version, name = name)) }
    }

    suspend fun delete(id: Int) = guard {
        api.deletePlaylist(id)
        playlists.value = playlists.value.filter { it.id != id }
        if (active.value == id) setActive(null)
    }

    suspend fun add(id: Int, idxs: List<Int>): Int {
        val L = lib.value ?: return 0
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
        scope.launch { runCatching { api.like(id, on) }.onFailure { liked.value = if (on) liked.value - id else listOf(id) + liked.value; say("Couldn't save the like") } }
    }
    val likedIdxs: List<Int> get() { val L = lib.value ?: return emptyList(); return liked.value.mapNotNull { L.byId[it] } }

    // ---- similarity ----
    private val nbCache = HashMap<String, List<Int>>()
    suspend fun neighbors(i: Int, k: Int = 25): List<Int> {
        val key = "${space.value}:$i:$k"
        nbCache[key]?.let { return it }
        return runCatching { api.neighbors(i, k, space.value) }.getOrElse { say("Couldn't reach Summit"); emptyList() }.also { if (it.isNotEmpty()) nbCache[key] = it }
    }
    suspend fun suggest(p: Playlist, k: Int = 20): List<Int> = runCatching { api.suggest(p.ids, k, space.value) }.getOrElse { emptyList() }
    suspend fun vibeSearch(q: String): List<Int> = runCatching { api.search(q, 40).map { it.i } }.getOrElse { emptyList() }
    fun prefetch(ids: List<String>) { if (ids.isNotEmpty()) scope.launch { runCatching { api.prefetch(ids) } } }

    private suspend fun <T> guard(block: suspend () -> T): T? = try { block() } catch (e: Exception) { say("Couldn't reach Summit: ${e.message}"); null }

    companion object { const val DEFAULT_SERVER = "http://127.0.0.1:8766" }
}
