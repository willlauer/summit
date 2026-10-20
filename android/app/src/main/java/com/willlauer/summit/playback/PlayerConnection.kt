package com.willlauer.summit.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.willlauer.summit.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class PlayerState(
    val current: Int? = null,          // library index of the track playing
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Int> = emptyList(), // in play order (shuffle applied)
    val queuePos: Int = 0,
    val label: String = "",            // where the queue came from ("Liked", a playlist, "Similar to …")
)

/** The UI side of playback: a MediaController on [PlaybackService], exposed as [state]. */
class PlayerConnection(private val ctx: Context, private val repo: Repo, private val scope: CoroutineScope) {
    private var c: MediaController? = null
    val state = MutableStateFlow(PlayerState())
    private val prefs = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)
    private var label = prefs.getString("label", "") ?: ""
    private var ticker: Job? = null

    fun connect() {
        if (c != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            val mc = runCatching { f.get() }.getOrNull() ?: return@addListener
            c = mc
            mc.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { publish() }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { save(); prefetchAhead() }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    repo.say("Couldn't play this track (${error.errorCodeName})")
                    if (mc.hasNextMediaItem()) { mc.seekToNextMediaItem(); mc.prepare(); mc.play() }
                }
            })
            if (mc.mediaItemCount == 0) restore()
            publish()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun item(i: Int): MediaItem? {
        val t = repo.lib.value?.tracks?.getOrNull(i) ?: return null
        return MediaItem.Builder().setMediaId(t.id).setUri(repo.api.streamUrl(t.id))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.name).setArtist(t.by).setAlbumTitle(t.album)
                .setArtworkUri(Uri.parse(repo.api.artUrl(t.id, 512))).setIsPlayable(true).setIsBrowsable(false).build())
            .build()
    }

    /** Play [list] starting at [start] (an index into the list); shuffle shuffles the rest. */
    fun play(list: List<Int>, start: Int = 0, label: String = "", shuffle: Boolean = false) {
        val mc = c ?: return
        val items = list.mapNotNull { item(it) }
        if (items.isEmpty()) return
        this.label = label
        val first = if (shuffle && start == 0) list.indices.random() else start.coerceIn(0, items.size - 1)
        mc.shuffleModeEnabled = shuffle
        mc.setMediaItems(items, first, 0)
        mc.prepare(); mc.play()
        publish(); save()
    }
    fun playOne(i: Int, label: String = "") = play(listOf(i), 0, label)
    fun playNext(i: Int) { val mc = c ?: return; val it = item(i) ?: return; if (mc.mediaItemCount == 0) play(listOf(i)) else mc.addMediaItem(mc.currentMediaItemIndex + 1, it); repo.say("Plays next") }
    fun addToQueue(i: Int) { val mc = c ?: return; val it = item(i) ?: return; if (mc.mediaItemCount == 0) play(listOf(i)) else mc.addMediaItem(it); repo.say("Added to the queue") }
    fun toggle() { val mc = c ?: return; if (mc.isPlaying) mc.pause() else { if (mc.playbackState == Player.STATE_IDLE) mc.prepare(); mc.play() } }
    fun next() { c?.seekToNextMediaItem() }
    fun prev() { val mc = c ?: return; if (mc.currentPosition > 3000 || !mc.hasPreviousMediaItem()) mc.seekTo(0) else mc.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) { c?.seekTo(ms); publish() }
    fun setShuffle(on: Boolean) { c?.shuffleModeEnabled = on }
    fun cycleRepeat() { val mc = c ?: return; mc.repeatMode = when (mc.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
    /** Jump to the [k]th track of the queue as shown (play order). */
    fun jumpTo(k: Int) { val mc = c ?: return; val order = order(mc); order.getOrNull(k)?.let { mc.seekTo(it, 0); mc.play() } }
    fun removeAt(k: Int) { val mc = c ?: return; order(mc).getOrNull(k)?.let { if (it != mc.currentMediaItemIndex) mc.removeMediaItem(it) } }

    private fun order(mc: Player): List<Int> {  // media item indexes in play order
        val tl = mc.currentTimeline
        if (tl.isEmpty) return emptyList()
        val out = ArrayList<Int>()
        var w = tl.getFirstWindowIndex(mc.shuffleModeEnabled)
        while (w != androidx.media3.common.C.INDEX_UNSET && out.size < tl.windowCount) {
            out.add(w); w = tl.getNextWindowIndex(w, Player.REPEAT_MODE_OFF, mc.shuffleModeEnabled)
        }
        return out
    }

    private fun publish() {
        val mc = c ?: return
        val L = repo.lib.value
        val ord = order(mc)
        val queue = ord.mapNotNull { L?.byId?.get(mc.getMediaItemAt(it).mediaId) }
        state.value = PlayerState(
            current = mc.currentMediaItem?.mediaId?.let { L?.byId?.get(it) },
            playing = mc.isPlaying, buffering = mc.playbackState == Player.STATE_BUFFERING,
            positionMs = mc.currentPosition.coerceAtLeast(0), durationMs = mc.duration.takeIf { it > 0 } ?: 0,
            shuffle = mc.shuffleModeEnabled, repeat = mc.repeatMode,
            queue = queue, queuePos = ord.indexOf(mc.currentMediaItemIndex).coerceAtLeast(0), label = label,
        )
        if (mc.isPlaying && ticker?.isActive != true) ticker = scope.launch {
            while (c?.isPlaying == true) { state.value = state.value.copy(positionMs = c!!.currentPosition, durationMs = c!!.duration.takeIf { it > 0 } ?: 0); delay(500) }
        }
    }

    // Convert the next few tracks on the Mac before they're needed.
    private fun prefetchAhead() {
        val mc = c ?: return
        val ord = order(mc); val k = ord.indexOf(mc.currentMediaItemIndex)
        repo.prefetch(ord.drop(k).take(4).map { mc.getMediaItemAt(it).mediaId })
    }

    // The queue survives the app being closed: restored paused, where it was.
    private fun save() {
        val mc = c ?: return
        val ids = (0 until mc.mediaItemCount).map { mc.getMediaItemAt(it).mediaId }
        prefs.edit().putString("ids", ids.joinToString(",")).putInt("index", mc.currentMediaItemIndex).putString("label", label)
            .putBoolean("shuffle", mc.shuffleModeEnabled).apply()
    }
    /** The library arrived (it may load after the controller connects): restore the last queue, map ids to tracks. */
    fun onLibrary() { val mc = c ?: return; if (mc.mediaItemCount == 0) restore() else publish() }

    private fun restore() {
        val mc = c ?: return
        val L = repo.lib.value ?: return
        val ids = prefs.getString("ids", "")!!.split(",").filter { it.isNotEmpty() }
        val items = ids.mapNotNull { L.byId[it] }.mapNotNull { item(it) }
        if (items.isEmpty()) return
        mc.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        mc.setMediaItems(items, prefs.getInt("index", 0).coerceIn(0, items.size - 1), 0)
        mc.prepare()
        publish()
    }
}
