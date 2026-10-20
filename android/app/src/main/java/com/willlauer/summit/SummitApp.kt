package com.willlauer.summit

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.willlauer.summit.data.Api
import com.willlauer.summit.data.Repo
import com.willlauer.summit.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class SummitApp : Application(), SingletonImageLoader.Factory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    }
    lateinit var repo: Repo
    lateinit var player: PlayerConnection

    @get:OptIn(UnstableApi::class)
    val mediaCache: SimpleCache by lazy {  // songs heard recently, so replays don't need the network
        SimpleCache(File(cacheDir, "media"), LeastRecentlyUsedCacheEvictor(4L shl 30), StandaloneDatabaseProvider(this))
    }

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this, Api(http, Repo.DEFAULT_SERVER), scope)
        player = PlayerConnection(this, repo, scope)
        player.connect()
        scope.launch { repo.lib.filterNotNull().first(); player.onLibrary() }
        repo.refresh()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "art")).maxSizeBytes(256L shl 20).build() }
        .crossfade(true)
        .build()
}
