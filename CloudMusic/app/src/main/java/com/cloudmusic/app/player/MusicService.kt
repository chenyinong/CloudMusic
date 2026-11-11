package com.cloudmusic.app.player

import android.app.PendingIntent
import android.content.Intent
import com.cloudmusic.app.MainActivity
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
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后台播放服务。通知栏、锁屏控制、耳机按键都依赖它。
 */
class MusicService : MediaSessionService() {

    companion object {
        const val ACTION_SLEEP_SET = "cm.sleep.set"
        const val ACTION_SLEEP_CANCEL = "cm.sleep.cancel"
        const val ACTION_SLEEP_TICK = "cm.sleep.tick"
        const val ACTION_SLEEP_CLEARED = "cm.sleep.cleared"
        const val SLEEP_OFF = 0
        const val SLEEP_COUNTDOWN = 1
        const val SLEEP_AFTER_TRACK = 2
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sleepJob: Job? = null
    private var sleepMode = SLEEP_OFF
    private var sleepEndsAtMs = 0L
    private var sleepAfterTrackId: String? = null

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
        p.addListener(playerListener)

        // 通知栏 / 锁屏点进来直接回主界面，不用每次都过一遍开屏页
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, p)
            .setCallback(sleepCallback)
            .setSessionActivity(sessionActivity)
            .build()

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
        sleepJob?.cancel()
        player?.removeListener(playerListener)
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

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && sleepMode == SLEEP_AFTER_TRACK) {
                val id = player?.currentMediaItem?.mediaId
                if (id != null && id == sleepAfterTrackId) doSleep()
            }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // 用户切歌也跟着滚：始终「播完此刻这首」才停
            if (sleepMode == SLEEP_AFTER_TRACK) sleepAfterTrackId = item?.mediaId
        }
    }

    private val sleepCallback = object : MediaSession.Callback {
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_SLEEP_SET -> {
                    val minutes = args.getInt("minutes", 0)
                    if (args.getBoolean("after_track", false)) startAfterTrack()
                    else if (minutes > 0) startCountdown(minutes)
                }
                ACTION_SLEEP_CANCEL -> cancelSleep()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** 倒计时 N 分钟到点暂停（带音量淡出）。 */
    private fun startCountdown(minutes: Int) {
        cancelSleepJob()
        sleepMode = SLEEP_COUNTDOWN
        sleepEndsAtMs = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = serviceScope.launch {
            while (true) {
                val remSec = ((sleepEndsAtMs - System.currentTimeMillis()) / 1000).toInt()
                broadcastSleep(SLEEP_COUNTDOWN, remSec.coerceAtLeast(0))
                if (remSec <= 0) break
                delay(1000)
            }
            doSleep()
        }
    }

    /** 播完当前这首后暂停。 */
    private fun startAfterTrack() {
        cancelSleepJob()
        sleepMode = SLEEP_AFTER_TRACK
        sleepAfterTrackId = player?.currentMediaItem?.mediaId
        broadcastSleep(SLEEP_AFTER_TRACK, -1)
    }

    private fun cancelSleep() {
        cancelSleepJob()
        sleepMode = SLEEP_OFF
        sleepAfterTrackId = null
        broadcastCleared()
    }

    private fun cancelSleepJob() {
        sleepJob?.cancel()
        sleepJob = null
    }

    /** 淡出音量 2 秒后暂停，并复位音量供下次使用。 */
    private fun doSleep() {
        val p = player ?: return
        serviceScope.launch {
            for (i in 0..40) {
                p.volume = 1f - i / 40f
                delay(50)
            }
            p.pause()
            p.volume = 1f
            sleepMode = SLEEP_OFF
            sleepAfterTrackId = null
            broadcastCleared()
        }
    }

    private fun broadcastSleep(mode: Int, remaining: Int) {
        val b = Bundle().apply {
            putInt("mode", mode)
            putInt("remaining", remaining)
        }
        mediaSession?.broadcastCustomCommand(SessionCommand(ACTION_SLEEP_TICK, Bundle.EMPTY), b)
    }

    private fun broadcastCleared() {
        mediaSession?.broadcastCustomCommand(SessionCommand(ACTION_SLEEP_CLEARED, Bundle.EMPTY), Bundle.EMPTY)
    }
}