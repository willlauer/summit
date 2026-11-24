package com.willlauer.summit.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Streaming that rarely stalls (like the big services):
 * - Quality: the server has every track as 128k AAC ("low") and as 256k AAC / the original lossy file ("high").
 *   Auto plays high on unmetered networks (Wi-Fi) and low on metered ones (cellular). A track already fully on the
 *   phone plays from there in whichever quality it was saved, network or not. Once a track has started in one quality
 *   it stays in it (a mid-song switch would mix two different files).
 * - Ahead: the next [AHEAD] tracks in play order are downloaded whole into the on-phone cache while the current one
 *   plays, so skips start instantly and a dropped signal doesn't stop the music.
 * The quality setting lives in Library › ⚙ (Streaming).
 */
object Streaming {
    const val AHEAD = 3
    val QUALITIES = listOf("auto" to "Auto", "high" to "High", "low" to "Data saver")
    val quality = MutableStateFlow("auto")
    /** Track ids downloaded whole onto the phone (in either quality): the queue marks them. */
    val onPhone = MutableStateFlow<Set<String>>(emptySet())

    private val sticky = ConcurrentHashMap<String, String>()  // track id → quality it started in
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("summit", Context.MODE_PRIVATE)
        quality.value = prefs?.getString("stream_q", "auto") ?: "auto"
    }

    fun setQuality(q: String) {
        quality.value = q
        prefs?.edit()?.putString("stream_q", q)?.apply()
        sticky.clear()  // tracks not yet started follow the new setting
    }

    fun cacheKey(id: String, q: String) = if (q == "low") "$id~low" else id  // "high" keeps the older cache entries

    @OptIn(UnstableApi::class)
    fun fullyCached(cache: SimpleCache, key: String): Boolean {
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return len > 0 && cache.isCached(key, 0, len)
    }

    /** The quality to fetch this track in now (see the class comment). */
    @OptIn(UnstableApi::class)
    fun qualityFor(ctx: Context, cache: SimpleCache, id: String): String = sticky.getOrPut(id) {
        when {
            fullyCached(cache, cacheKey(id, "high")) -> "high"
            fullyCached(cache, cacheKey(id, "low")) -> "low"
            quality.value == "high" || quality.value == "low" -> quality.value
            else -> if ((ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) "low" else "high"
        }
    }

    /** /stream/<id> → /stream/<id>?q=…, with a cache key per quality. */
    @OptIn(UnstableApi::class)
    fun resolve(ctx: Context, cache: SimpleCache, spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.pathSegments.getOrNull(uri.pathSegments.size - 2) != "stream") return spec
        val id = uri.lastPathSegment ?: return spec
        val q = qualityFor(ctx, cache, id)
        val withQ = uri.buildUpon().clearQuery().appendQueryParameter("q", q).build()
        return spec.buildUpon().setUri(withQ).setKey(cacheKey(id, q)).build()
    }

    @OptIn(UnstableApi::class)
    fun resolver(ctx: Context, cache: SimpleCache) = ResolvingDataSource.Resolver { spec -> resolve(ctx, cache, spec) }
}

/** Downloads the next [Streaming.AHEAD] queued tracks whole while the current one plays (see [Streaming]). */
@OptIn(UnstableApi::class)
class Precacher(private val ctx: Context, private val cache: SimpleCache, private val cacheFactory: CacheDataSource.Factory) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var writer: CacheWriter? = null

    /** Call on every queue / track change: works out what comes next and downloads it, replacing any earlier plan. */
    fun update(player: Player) {
        val tl = player.currentTimeline
        val upcoming = mutableListOf<Uri>()
        val ids = mutableListOf<String>()
        if (!tl.isEmpty) {
            var idx = player.currentMediaItemIndex
            repeat(Streaming.AHEAD) {
                idx = tl.getNextWindowIndex(idx, if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode, player.shuffleModeEnabled)
                if (idx == C.INDEX_UNSET) return@repeat
                val item = player.getMediaItemAt(idx)
                item.localConfiguration?.uri?.let { upcoming += it; ids += item.mediaId }
            }
        }
        val current = player.currentMediaItem?.mediaId
        refreshOnPhone((listOfNotNull(current) + ids))
        job?.cancel(); writer?.cancel()
        job = scope.launch {
            for (uri in upcoming) {
                if (!isActive) break
                val spec = Streaming.resolve(ctx, cache, DataSpec(uri))
                if (Streaming.fullyCached(cache, spec.key!!)) continue
                runCatching {
                    val w = CacheWriter(cacheFactory.createDataSource(), spec, null, null)
                    writer = w
                    w.cache()
                }
                refreshOnPhone(listOfNotNull(current) + ids)
            }
        }
    }

    private fun refreshOnPhone(ids: List<String>) {
        Streaming.onPhone.value = ids.filter { id ->
            Streaming.fullyCached(cache, Streaming.cacheKey(id, "high")) || Streaming.fullyCached(cache, Streaming.cacheKey(id, "low"))
        }.toSet()
    }

    fun release() { job?.cancel(); writer?.cancel() }
}
