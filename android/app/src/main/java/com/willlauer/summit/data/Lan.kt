package com.willlauer.summit.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.willlauer.summit.SummitApp
import com.willlauer.summit.playback.Streaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Edits made on a synced card's player (the JM21) with no Mac in reach, waiting for the next Wi-Fi sync. */
@Serializable
data class PendingNew(val temp: Int, val name: String, val ids: List<String>, val folder: Int? = null)
@Serializable
data class PendingEdit(val base: Int, val ids: List<String>? = null, val name: String? = null, val folder: Int? = null)
@Serializable
data class Pending(
    val created: List<PendingNew> = emptyList(),
    val edits: Map<Int, PendingEdit> = emptyMap(),
    val deleted: List<Int> = emptyList(),
    val likes: Map<String, Boolean> = emptyMap(),
) {
    val count get() = created.size + edits.size + deleted.size + likes.size
}

/** The Mac's rekordbox / Music app playlists (GET /api/extplaylists), read-only in the app. */
@Serializable
data class ExtPlaylists(val playlists: List<Playlist> = emptyList(), val folders: List<Folder> = emptyList())

data class LanState(
    val paired: Boolean = false, val name: String = "", val url: String = "",
    val reachable: Boolean? = null, val syncing: Boolean = false, val note: String = "", val lastSync: Long = 0,
    val pending: Int = 0, val searching: Boolean = false, val found: List<Pair<String, String>> = emptyList(),
    val done: Int = 0, val total: Int = 0,
)

/**
 * Wi-Fi sync with Summit on the Mac (src/summit/lan.py): same Wi-Fi, no Tailscale. The Mac advertises "_summit._tcp"
 * over Bonjour; the app finds it, pairs once with the code from the Mac's Settings › Wi-Fi sync, and then sends a
 * token with every request to it.
 * - Phone: while the Mac is reachable on the Wi-Fi the app talks to it directly (faster than Tailscale).
 * - A synced card's player (the JM21, 100% local): playlist and like edits are kept on the player ([Pending]) and
 *   pushed when it syncs; it then pulls the Mac's library, playlists and rekordbox / Music app playlists, and
 *   downloads any playlist tracks the card doesn't have into the offline store. Syncs on Wi-Fi, after edits, and
 *   from Settings › Wi-Fi sync.
 */
@OptIn(UnstableApi::class)
object Lan {
    const val SERVICE = "_summit._tcp."
    val state = MutableStateFlow(LanState())
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var kickJob: Job? = null
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var dir: File
    @Volatile private var host: HttpUrl? = null
    @Volatile var token: String? = null; private set
    var pending = Pending(); private set

    val paired get() = token != null && url.isNotEmpty()
    val url: String get() = state.value.url

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("summit", Context.MODE_PRIVATE)
        dir = ctx.filesDir
        token = prefs.getString("lan_token", null)
        val u = prefs.getString("lan_url", "") ?: ""
        host = u.toHttpUrlOrNull()
        pending = runCatching { json.decodeFromString<Pending>(File(dir, "pending.json").readText()) }.getOrDefault(Pending())
        state.value = LanState(paired = token != null && u.isNotEmpty(), name = prefs.getString("lan_name", "") ?: "", url = u,
            lastSync = prefs.getLong("lan_last", 0), pending = pending.count)
    }

    /** The token for requests to the paired Mac (an OkHttp interceptor adds it). */
    fun tokenFor(u: HttpUrl): String? { val h = host ?: return null; return if (u.host == h.host && u.port == h.port) token else null }

    fun savePending(p: Pending) {
        pending = p
        runCatching { File(dir, "pending.json").writeText(json.encodeToString(Pending.serializer(), p)) }
        state.value = state.value.copy(pending = p.count)
    }

    // ---- what the last sync brought (used on a card's player instead of the card's older copy) ----
    fun savedLibrary(newerThan: Long): String? {
        val f = File(dir, "lan_library.json")
        return if (f.exists() && f.lastModified() / 1000 > newerThan) f.readText() else null
    }
    fun savedExt(): ExtPlaylists? = runCatching { json.decodeFromString<ExtPlaylists>(File(dir, "lan_ext.json").readText()) }.getOrNull()

    // ---- finding and pairing ----
    /** Looks for Summit on this Wi-Fi for a few seconds: [(name, url)]. */
    suspend fun discover(ctx: Context): List<Pair<String, String>> {
        state.value = state.value.copy(searching = true, found = emptyList(), note = "")
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = java.util.concurrent.CopyOnWriteArrayList<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { found += s }
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }
        delay(3500)
        runCatching { nsd.stopServiceDiscovery(listener) }
        val out = mutableListOf<Pair<String, String>>()
        for (s in found.distinctBy { it.serviceName }) {
            val r = withTimeoutOrNull(3000) { resolve(nsd, s) } ?: continue
            val addr = r.host?.hostAddress ?: continue
            val h = if (':' in addr) "[$addr]" else addr
            out += s.serviceName to "http://$h:${r.port}"
        }
        state.value = state.value.copy(searching = false, found = out, note = if (out.isEmpty()) "No Summit found on this Wi-Fi. Is Wi-Fi sync on in the Mac's Settings?" else "")
        return out
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, s: NsdServiceInfo): NsdServiceInfo? = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, e: Int) { if (c.isActive) c.resumeWith(Result.success(null)) }
            override fun onServiceResolved(si: NsdServiceInfo) { if (c.isActive) c.resumeWith(Result.success(si)) }
        })
    }

    /** Pair with the Mac at [u] using the code shown in its Settings › Wi-Fi sync. */
    suspend fun pair(app: SummitApp, name: String, u: String, code: String): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("code", code.trim()); put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim()) }.toString()
        val req = Request.Builder().url("$u/api/lan/pair").post(body.toRequestBody("application/json".toMediaType())).build()
        val res = runCatching { app.http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.string() else null } }.getOrNull()
        val tok = res?.let { runCatching { json.parseToJsonElement(it).jsonObject["token"]?.jsonPrimitive?.content }.getOrNull() }
        if (tok == null) { state.value = state.value.copy(note = "Pairing failed: check the code on the Mac"); return@withContext false }
        val mac = name.removePrefix("Summit on ").ifBlank { "Mac" }
        prefs.edit().putString("lan_token", tok).putString("lan_url", u).putString("lan_name", mac).apply()
        token = tok; host = u.toHttpUrlOrNull()
        state.value = state.value.copy(paired = true, name = mac, url = u, reachable = true, found = emptyList(), note = "Paired")
        sync(app)
        true
    }

    fun unpair() {
        prefs.edit().remove("lan_token").remove("lan_url").remove("lan_name").apply()
        token = null; host = null
        state.value = LanState(pending = pending.count)
    }

    /** Is the paired Mac answering on this Wi-Fi? If its address changed, finds it again over Bonjour. */
    suspend fun reachable(app: SummitApp): Boolean = withContext(Dispatchers.IO) {
        if (!paired) return@withContext false
        val quick = app.http.newBuilder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(3, TimeUnit.SECONDS).build()
        fun ok(u: String) = runCatching { quick.newCall(Request.Builder().url("$u/api/playlists").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
        if (ok(url)) { state.value = state.value.copy(reachable = true); return@withContext true }
        if (Streaming.metered(app)) { state.value = state.value.copy(reachable = false); return@withContext false }
        val again = discover(app).firstOrNull { it.first.removePrefix("Summit on ") == state.value.name } ?: discover(app).firstOrNull()
        if (again != null) {
            host = again.second.toHttpUrlOrNull()
            if (ok(again.second)) {
                prefs.edit().putString("lan_url", again.second).apply()
                state.value = state.value.copy(url = again.second, reachable = true, found = emptyList(), note = "")
                return@withContext true
            }
            host = url.toHttpUrlOrNull()
        }
        state.value = state.value.copy(reachable = false, found = emptyList())
        false
    }

    /** Sync soon (after an edit): quietly, only if the Mac is in reach. */
    fun kick(app: SummitApp) {
        if (!paired) return
        kickJob?.cancel()
        kickJob = scope.launch { delay(3000); if (reachable(app)) sync(app) }
    }

    /** Sync whenever an unmetered network (Wi-Fi) comes up. */
    fun watchWifi(app: SummitApp) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) kick(app)
            }
        })
    }

    fun start(app: SummitApp) { scope.launch { sync(app) } }

    /** Push edits made on the player, pull the Mac's library and playlists, fetch tracks the card lacks. */
    suspend fun sync(app: SummitApp) = lock.withLock {
        if (!paired) return@withLock
        state.value = state.value.copy(syncing = true, note = "Syncing…", done = 0, total = 0)
        try {
            if (!reachable(app)) { state.value = state.value.copy(syncing = false, note = "Mac not found on this Wi-Fi"); return@withLock }
            val api = Api(app.http, url)
            push(api)
            val raw = api.libraryRaw()
            val ext = runCatching { api.extPlaylists() }.getOrNull()
            withContext(Dispatchers.IO) {
                File(dir, "lan_library.json").writeText(raw)
                if (ext != null) File(dir, "lan_ext.json").writeText(json.encodeToString(ExtPlaylists.serializer(), ext))
            }
            val now = System.currentTimeMillis()
            prefs.edit().putLong("lan_last", now).apply()
            withContext(Dispatchers.Main) { app.repo.afterLanSync() }
            if (Updater.check(app) != null) app.repo.say("Summit update available: Settings › Updates")
            if (app.repo.local) fetchMissing(app, api)
            state.value = state.value.copy(syncing = false, lastSync = now, note = "Up to date", done = 0, total = 0)
        } catch (e: Exception) {
            state.value = state.value.copy(syncing = false, note = "Sync failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun push(api: Api) {
        var p = pending
        if (p.count == 0) return
        val real = HashMap<Int, Int>()
        for (n in p.created) {
            val c = api.createPlaylist(n.name, n.ids, n.folder?.takeIf { it > 0 })
            real[n.temp] = c.id
            p = p.copy(created = p.created.filter { it.temp != n.temp }); savePending(p)
        }
        for ((id, e) in p.edits) {
            val target = real[id] ?: id
            try { api.updatePlaylist(target, e.base, ids = e.ids, name = e.name, folder = e.folder) }
            catch (c: ConflictException) {  // changed on the Mac too: this device's version, plus what the Mac added
                val ids = e.ids?.let { mine -> mine + c.current.ids.filter { it !in mine } }
                api.updatePlaylist(target, c.current.version, ids = ids, name = e.name, folder = e.folder)
            } catch (h: HttpError) { if (h.code != 404) throw h }  // deleted on the Mac meanwhile
            p = p.copy(edits = p.edits - id); savePending(p)
        }
        for (id in p.deleted) {
            runCatching { api.deletePlaylist(id) }
            p = p.copy(deleted = p.deleted - id); savePending(p)
        }
        for ((id, on) in p.likes) {
            api.like(id, on)
            p = p.copy(likes = p.likes - id); savePending(p)
        }
    }

    /** Tracks in playlists / likes that aren't on the card: downloaded whole into the offline store (high quality). */
    private suspend fun fetchMissing(app: SummitApp, api: Api) = withContext(Dispatchers.IO) {
        val repo = app.repo
        val want = (repo.playlists.value.flatMap { it.ids } + repo.liked.value).distinct()
            .filter { !Card.has(it) && !Streaming.fullyCached(app.offlineCache, Streaming.cacheKey(it, "high")) }
        if (want.isEmpty()) return@withContext
        val source = CacheDataSource.Factory().setCache(app.offlineCache).setUpstreamDataSourceFactory(OkHttpDataSource.Factory(app.http))
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
        want.forEachIndexed { k, id ->
            state.value = state.value.copy(note = "Downloading tracks the card doesn't have", done = k, total = want.size)
            val spec = Streaming.streamSpec(Uri.parse(api.streamUrl(id)), id, "high")
            runCatching { CacheWriter(source.createDataSource(), spec, null, null).cache() }
        }
    }
}

/** Adds the Wi-Fi sync token to requests for the paired Mac (API, audio, art). */
class LanTokenInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val r = chain.request()
        val t = Lan.tokenFor(r.url) ?: return chain.proceed(r)
        return chain.proceed(r.newBuilder().header("X-Summit-Token", t).build())
    }
}
