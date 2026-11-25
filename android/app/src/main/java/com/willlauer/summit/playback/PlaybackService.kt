package com.willlauer.summit.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.willlauer.summit.MainActivity
import com.willlauer.summit.SummitApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Plays in the background with the system media notification, lock screen and Bluetooth controls.
 * Audio streams from the Mac (/stream/<id>?q=…) through an on-phone cache keyed by track id and quality, so a track
 * heard once replays without the network, whichever address the server was reached at. Quality choice and downloading
 * the next tracks ahead: [Streaming], [Precacher].
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var precacher: Precacher? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as SummitApp
        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(app.http))
        val cached = CacheDataSource.Factory()
            .setCache(app.mediaCache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.lastPathSegment ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // The offline store (newest 6 GB, OfflineSync) is read first and never written here; then the playback cache.
        val offline = CacheDataSource.Factory()
            .setCache(app.offlineCache)
            .setUpstreamDataSourceFactory(cached)
            .setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.lastPathSegment ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // The resolver points a track on a synced card at its file (read directly, never cached) and adds ?q= and the
        // cache key to everything else, which goes through the two stores.
        val data = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, offline), Streaming.resolver(this))
        // Read far ahead (the rest of the song and into the next) so a patchy connection doesn't stall playback;
        // the default ~50 s / ~3 MB runs dry on cellular. Start after 0.5 s of audio (it arrives almost at once at
        // 128k), resume after a stall once 1.5 s is in.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5 * 60_000, 10 * 60_000, 500, 1_500)
            .setTargetBufferBytes(32 shl 20)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // Hi-res: 24-bit files reach the audio system as float instead of being cut to 16-bit. The JM21's FiiO DPA
        // already follows each track's sample rate (no resampling).
        @Suppress("DEPRECATION")
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableAudioFloatOutput(true)
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data, AiffExtractor.factory))  // + AIFF (AiffExtractor)
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(Resume(app)).build()
        val pre = Precacher(this, cached).also { precacher = it }
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) pre.update(p)
            }
        })
    }

    /** The hardware play button (or Bluetooth) with nothing loaded: pick up the last queue where it stopped. */
    private class Resume(val app: SummitApp) : MediaSession.Callback {
        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo):
            com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val f = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            app.scope.launch {
                kotlinx.coroutines.withTimeoutOrNull(15_000) { app.repo.lib.first { it != null } }
                val r = app.player.resumable()
                if (r == null) f.setException(IllegalStateException("nothing to resume"))
                else f.set(MediaSession.MediaItemsWithStartPosition(r.first, r.second, r.third))
            }
            return f
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        precacher?.release()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
