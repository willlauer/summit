package com.willlauer.summit.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.willlauer.summit.data.Repo
import com.willlauer.summit.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Sheet {
    data class Track(val i: Int, val playlistId: Int? = null) : Sheet   // song actions (in a playlist: also "remove")
    data class AddTo(val idxs: List<Int>) : Sheet                       // pick a playlist to add to
    data class MoveTo(val playlistId: Int? = null, val folderId: Int? = null) : Sheet  // pick a folder for a playlist or folder
    data class FolderActions(val id: Int) : Sheet
    data object Active : Sheet                                          // pick the playlist being built
    data object MapOptions : Sheet
}

sealed interface Dialog {
    data class NewPlaylist(val add: List<Int> = emptyList(), val makeActive: Boolean = false, val folder: Int? = null) : Dialog
    data class NewFolder(val parent: Int? = null, val movePlaylist: Int? = null, val moveFolder: Int? = null) : Dialog  // optionally file something in it
    data class RenameFolder(val id: Int) : Dialog
    data class DeleteFolder(val id: Int) : Dialog
    data class Rename(val id: Int) : Dialog
    data class Delete(val id: Int) : Dialog
}

/** What the map should show when you get there: one track, or a set (a playlist's route, similar songs, search results). */
data class MapFocus(val idxs: List<Int>, val label: String, val path: Boolean = false, val select: Int? = null)

/** UI-wide state: which sheet / dialog is open, Now Playing, and requests to show things on the map. */
class AppState(val repo: Repo, val player: PlayerConnection, val scope: CoroutineScope) {
    var sheet by mutableStateOf<Sheet?>(null)
    var dialog by mutableStateOf<Dialog?>(null)
    var nowPlaying by mutableStateOf(false)
    var mapFocus by mutableStateOf<MapFocus?>(null)
    var tab by mutableStateOf(Tab.Map)
    lateinit var nav: NavHostController

    /** ＋ on a song: straight into the playlist being built, or ask which playlist. */
    fun add(i: Int) = scope.launch { if (!repo.toggleActive(i)) sheet = Sheet.AddTo(listOf(i)) }

    fun go(route: String) { nowPlaying = false; sheet = null; nav.navigate(route) { launchSingleTop = true } }
    /** Switch tabs, keeping each tab's own back stack. Tapping the current tab again returns to its top. */
    fun openTab(t: Tab) {
        nowPlaying = false; sheet = null
        if (t == tab) nav.popBackStack(t.route, inclusive = false) else switchTo(t)
    }
    private fun switchTo(t: Tab) {
        tab = t
        nav.navigate(t.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    fun artist(name: String) = go("artist/" + android.net.Uri.encode(name))
    fun playlist(id: Int) = go("playlist/$id")
    fun similar(chain: List<Int>) = go("similar/" + chain.joinToString(","))
    fun showOnMap(f: MapFocus) {
        mapFocus = f; nowPlaying = false; sheet = null
        if (tab == Tab.Map) nav.popBackStack(Tab.Map.route, inclusive = false) else switchTo(Tab.Map)
    }
}

val LocalApp = compositionLocalOf<AppState> { error("no AppState") }

enum class Tab(val route: String, val label: String) { Map("map", "Map"), Search("search", "Search"), Library("library", "Library"), Playlists("playlists", "Playlists") }
