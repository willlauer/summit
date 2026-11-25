package com.willlauer.summit.data

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** The "card" section of Summit/library.json (written by the Mac's device sync, src/summit/sync/fiio.py). */
@Serializable
data class CardInfo(
    val version: Int = 1,
    val synced: Long = 0,
    val files: Map<String, String> = emptyMap(),   // Summit track id -> path on the card ("Music/Artist/Album/x.m4a")
    val playlists: List<Playlist> = emptyList(),    // rekordbox / Music app playlists (negative ids, read-only)
    val folders: List<Folder> = emptyList(),
)

/**
 * A music player's microSD card (the FiiO JM21) that Summit on the Mac synced: the whole library or picked playlists
 * under Music/, and Summit/library.json, the same library the server sends plus where each file is. With it the app
 * plays straight from the card (no network), shows art from Summit/art/, and lists the rekordbox and Music app
 * playlists that were synced. Reading it needs "All files access" (Settings › Card).
 */
object Card {
    data class State(val root: File? = null, val info: CardInfo? = null, val access: Boolean = true, val note: String = "")

    val state = MutableStateFlow(State())
    @Volatile private var files: Map<String, File> = emptyMap()
    @Volatile private var artDir: File? = null

    fun hasAccess() = Environment.isExternalStorageManager()

    /** Removable volumes (the card), newest Android API first, then the /storage fallback. */
    private fun volumes(ctx: Context): List<File> {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val found = sm.storageVolumes.filter { it.isRemovable }.mapNotNull { it.directory }
        val extra = File("/storage").listFiles().orEmpty().filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
        return (found + extra).distinctBy { it.absolutePath }
    }

    fun libraryFile(ctx: Context): File? = volumes(ctx).map { File(it, "Summit/library.json") }.firstOrNull { it.canRead() }

    /** Reads the card's library (or null) and remembers where its files are. Call off the main thread. */
    fun load(ctx: Context, api: Api): Pair<LibraryPayload, String>? {
        if (!hasAccess()) {
            val vols = volumes(ctx)
            state.value = State(root = vols.firstOrNull(), access = false, note = if (vols.isEmpty()) "No card" else "Allow All files access to play from the card")
            return null
        }
        val f = libraryFile(ctx) ?: run { state.value = State(note = "No Summit library on a card (sync it from Summit on the Mac)"); files = emptyMap(); return null }
        val raw = f.readText()
        val p = api.parseLibrary(raw)
        val root = f.parentFile!!.parentFile!!
        val info = p.card ?: CardInfo()
        files = info.files.mapValues { File(root, it.value) }
        artDir = File(root, "Summit/art")
        state.value = State(root, info, true, "${info.files.size} tracks on the card")
        return p to raw
    }

    /** The track's file on the card, when it's there. */
    fun file(id: String): File? = files[id]?.takeIf { it.exists() }
    fun has(id: String) = files.containsKey(id)
    fun art(id: String): File? = artDir?.let { File(it, "$id.jpg") }?.takeIf { files.containsKey(id) && it.exists() }
}
