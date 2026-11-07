package com.cloudmusic.app.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cloudmusic.app.R
import com.cloudmusic.app.data.Prefs
import java.io.File

/**
 * 后台播放服务。通知栏、锁屏控制、耳机按键都依赖它。
 */
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var cache: SimpleCache? = null

    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)

        val upstream = StreamDataSourceFactory(prefs)
        val dataSourceFactory = buildCacheFactory(upstream)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        p.setHandleAudioBecomingNoisy(true)
        player = p

        mediaSession = MediaSession.Builder(this, p).build()

        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId("cloudmusic_playback")
            .setChannelName(R.string.app_name)
            .build()
        provider.setSmallIcon(R.drawable.ic_stat_music)
        setMediaNotificationProvider(provider)
    }

    private fun buildCacheFactory(upstream: StreamDataSourceFactory) = try {
        val c = cache ?: SimpleCache(
            File(cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
            StandaloneDatabaseProvider(this)
        ).also { cache = it }
        CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    } catch (t: Throwable) {
        upstream
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        cache?.release()
        cache = null
        super.onDestroy()
    }
}