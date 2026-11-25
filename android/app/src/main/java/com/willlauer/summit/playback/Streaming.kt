package com.willlauer.summit.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.willlauer.summit.SummitApp
import com.willlauer.summit.data.Card
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
 * - Ahead: the next [AHEAD] tracks in play order are downloaded whole into the playback cache while the current one
 *   plays, so skips start instantly and a dropped signal doesn't stop the music.
 * - Offline: the newest [OfflineSync.BUDGET_MB] of the library is kept on the phone (synced on Wi-Fi).
 * Two stores: the offline store (SummitApp.offlineCache, read first) and the playback cache (SummitApp.mediaCache).
 * The quality setting lives in Settings and Library › ⚙ (Streaming).
 */
object Streaming {
    const val AHEAD = 3
    val QUALITIES = listOf("auto" to "Auto", "high" to "High", "low" to "Data saver")
    val quality = MutableStateFlow("auto")
    /** Queued track ids downloaded whole onto the phone (in either quality): Up next marks them. */
    val onPhone = MutableStateFlow<Set<String>>(emptySet())

    private val sticky = ConcurrentHashMap<String, String>()  // track id → quality it started in
    private var prefs: android.content.SharedPreferences? = null
    private var caches: List<SimpleCache> = emptyList()

    fun init(ctx: Context, stores: List<SimpleCache>) {
        prefs = ctx.getSharedPreferences("summit", Context.MODE_PRIVATE)
        quality.value = prefs?.getString("stream_q", "auto") ?: "auto"
        caches = stores
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

    fun onPhone(key: String) = caches.any { fullyCached(it, key) }
    fun trackOnPhone(id: String) = Card.has(id) || onPhone(cacheKey(id, "high")) || onPhone(cacheKey(id, "low"))

    fun metered(ctx: Context) = (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    /** The quality to fetch this track in now (see the class comment). */
    fun qualityFor(ctx: Context, id: String): String = sticky.getOrPut(id) {
        when {
            onPhone(cacheKey(id, "high")) -> "high"
            onPhone(cacheKey(id, "low")) -> "low"
            quality.value == "high" || quality.value == "low" -> quality.value
            else -> if (metered(ctx)) "low" else "high"
        }
    }

    fun streamSpec(uri: Uri, id: String, q: String): DataSpec =
        DataSpec.Builder().setUri(uri.buildUpon().clearQuery().appendQueryParameter("q", q).build()).setKey(cacheKey(id, q)).build()

    /** /stream/<id> → the file on a synced card (data/Card.kt) when it's there, else /stream/<id>?q=… with a cache key
     *  per quality. */
    @OptIn(UnstableApi::class)
    fun resolve(ctx: Context, spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.pathSegments.getOrNull(uri.pathSegments.size - 2) != "stream") return spec
        val id = uri.lastPathSegment ?: return spec
        Card.file(id)?.let { return spec.buildUpon().setUri(Uri.fromFile(it)).setKey(null).build() }
        val q = qualityFor(ctx, id)
        return spec.buildUpon().setUri(uri.buildUpon().clearQuery().appendQueryParameter("q", q).build()).setKey(cacheKey(id, q)).build()
    }

    @OptIn(UnstableApi::class)
    fun resolver(ctx: Context) = ResolvingDataSource.Resolver { spec -> resolve(ctx, spec) }
}

/** Downloads the next [Streaming.AHEAD] queued tracks whole while the current one plays (see [Streaming]). */
@OptIn(UnstableApi::class)
class Precacher(private val ctx: Context, private val cacheFactory: CacheDataSource.Factory) {
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
                if (idx == C.INDEX_UNSET) return@repeat
                idx = tl.getNextWindowIndex(idx, if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode, player.shuffleModeEnabled)
                if (idx == C.INDEX_UNSET) return@repeat
                val item = player.getMediaItemAt(idx)
                item.localConfiguration?.uri?.let { upcoming += it; ids += item.mediaId }
            }
        }
        val current = player.currentMediaItem?.mediaId
        val watch = listOfNotNull(current) + ids
        refreshOnPhone(watch)
        job?.cancel(); writer?.cancel()
        job = scope.launch {
            for (uri in upcoming) {
                if (!isActive) break
                val spec = Streaming.resolve(ctx, DataSpec(uri))
                val key = spec.key ?: continue  // on the card: nothing to download
                if (Streaming.onPhone(key)) continue
                runCatching {
                    val w = CacheWriter(cacheFactory.createDataSource(), spec, null, null)
                    writer = w
                    w.cache()
                }
                refreshOnPhone(watch)
            }
        }
    }

    private fun refreshOnPhone(ids: List<String>) { Streaming.onPhone.value = ids.filter(Streaming::trackOnPhone).toSet() }

    fun release() { job?.cancel(); writer?.cancel() }
}

data class OfflineState(
    val running: Boolean = false, val done: Int = 0, val total: Int = 0, val bytesOnPhone: Long = 0, val bytesTotal: Long = 0,
    val note: String = "", val lastSync: Long = 0,
)

/**
 * Keeps the newest [BUDGET_MB] of the library on the phone, in data-saver quality: asks the server which tracks
 * (GET /api/offline), drops ones that left the set, downloads the rest. Only on Wi-Fi (unmetered); it pauses if the
 * network turns metered and picks up where it stopped. Runs when the app starts, when Wi-Fi comes back, and from
 * Settings › Offline.
 */
@OptIn(UnstableApi::class)
object OfflineSync {
    const val BUDGET_MB = 6144
    val state = MutableStateFlow(OfflineState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var writer: CacheWriter? = null

    fun start(app: SummitApp) {
        if (job?.isActive == true || app.repo.local) return  // a synced card is the offline copy; never contact the Mac
        job = scope.launch { runCatching { sync(app) }.onFailure { state.value = state.value.copy(running = false, note = "Couldn't sync: ${it.message}") } }
    }

    fun stop() { writer?.cancel(); job?.cancel(); state.value = state.value.copy(running = false, note = "Stopped") }

    /** Start again whenever an unmetered network (Wi-Fi) comes up. */
    fun watchWifi(app: SummitApp) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) start(app) else writer?.cancel()
            }
        })
    }

    private suspend fun sync(app: SummitApp) {
        val cache = app.offlineCache
        if (Streaming.metered(app)) { state.value = state.value.copy(running = false, note = "Waiting for Wi-Fi"); return }
        state.value = state.value.copy(running = true, note = "Checking…")
        val all = app.repo.api.offline(BUDGET_MB)
        val set = all.copy(tracks = all.tracks.filter { !Card.has(it.id) }, bytes = all.tracks.filter { !Card.has(it.id) }.sumOf { it.bytes })  // the card has the rest
        val want = set.tracks.associateBy { Streaming.cacheKey(it.id, "low") }
        cache.keys.filter { it !in want }.forEach { cache.removeResource(it) }  // left the newest 6 GB
        val todo = set.tracks.filter { !Streaming.fullyCached(cache, Streaming.cacheKey(it.id, "low")) }
        val total = set.tracks.size
        fun onPhoneBytes() = cache.cacheSpace
        state.value = OfflineState(true, total - todo.size, total, onPhoneBytes(), set.bytes, if (todo.isEmpty()) "Up to date" else "Downloading")
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(OkHttpDataSource.Factory(app.http))
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
        var done = total - todo.size
        for (t in todo) {
            if (!(job?.isActive ?: false)) return
            if (Streaming.metered(app)) { state.value = state.value.copy(running = false, note = "Paused: not on Wi-Fi"); return }
            val spec = Streaming.streamSpec(Uri.parse(app.repo.api.streamUrl(t.id)), t.id, "low")
            val ok = runCatching { CacheWriter(source.createDataSource(), spec, null, null).also { writer = it }.cache() }.isSuccess
            if (ok) done++
            state.value = state.value.copy(done = done, bytesOnPhone = onPhoneBytes())
        }
        state.value = state.value.copy(running = false, note = if (done == total) "Up to date" else "${total - done} couldn't download; will retry",
            lastSync = System.currentTimeMillis())
    }
}
