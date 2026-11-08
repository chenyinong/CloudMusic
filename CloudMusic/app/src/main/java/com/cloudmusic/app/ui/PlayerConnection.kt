package com.cloudmusic.app.ui

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.player.MusicService

/**
 * 与后台播放服务 (MusicService) 的连接封装。
 */
class PlayerConnection(private val context: Context) {

    var controller by mutableStateOf<MediaController?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var currentTitle by mutableStateOf("")
        private set
    var currentArtist by mutableStateOf("")
        private set
    var errorText by mutableStateOf<String?>(null)
        private set

    /** 当前正在播放的曲目，歌词面板据此去取词。 */
    var currentTrack by mutableStateOf<Track?>(null)
        private set

    /** 播放列表里的下一首，用来提前把歌词准备好。 */
    var nextTrack by mutableStateOf<Track?>(null)
        private set

    /** 当前播放队列，播放列表面板用。 */
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set

    /**
     * 循环模式：列表循环 / 单曲循环 / 不循环。
     * 跟网易云的控制栏一致，点一下切一个。
     */
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_ALL)
        private set

    /** 随机播放开关。 */
    var shuffle by mutableStateOf(false)
        private set

    private var playlist: List<Track> = emptyList()

    private fun updateNextTrack() {
        val cur = currentTrack
        val idx = if (cur == null) -1 else playlist.indexOfFirst { it.key == cur.key }
        nextTrack = if (idx >= 0 && idx + 1 < playlist.size) playlist[idx + 1] else null
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            playing = isPlayingNow
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            positionMs = 0L
            item?.mediaMetadata?.title?.let { currentTitle = it.toString() }
            item?.mediaMetadata?.artist?.let { currentArtist = it.toString() }
            currentTrack = playlist.firstOrNull { it.key == item?.mediaId }
            updateNextTrack()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                durationMs = controller?.duration ?: 0L
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            errorText = "播放失败：${error.errorCodeName}"
        }
    }

    private var pendingTracks: List<Track>? = null
    private var pendingIndex: Int = 0
    private var pendingPositionMs: Long = 0L

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                c.addListener(listener)
                controller = c
                pendingTracks?.let {
                    play(it, pendingIndex, pendingPositionMs)
                    pendingTracks = null
                }
            } catch (e: Exception) {
                errorText = e.message ?: "无法连接播放服务"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * @param startPositionMs 起播位置，默认从头。
     *        试听结束要恢复原来的播放进度，靠这个而不是播完再 seekTo。
     */
    fun play(tracks: List<Track>, startIndex: Int, startPositionMs: Long = 0L) {
        if (tracks.isEmpty()) return
        val c = controller
        if (c == null) {
            pendingTracks = tracks
            pendingIndex = startIndex
            pendingPositionMs = startPositionMs
            connect()
            return
        }
        playlist = tracks
        queue = tracks
        val items = tracks.map { toMediaItem(it) }
        c.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), startPositionMs)
        c.prepare()
        c.play()
        currentTitle = tracks.getOrNull(startIndex)?.title.orEmpty()
        currentArtist = tracks.getOrNull(startIndex)?.artist.orEmpty()
        currentTrack = tracks.getOrNull(startIndex)
        updateNextTrack()
    }

    private fun toMediaItem(t: Track): MediaItem =
        MediaItem.Builder()
            .setUri(t.url)
            .setMediaId(t.key)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .build()
            )
            .build()

    /**
     * 往播放队列末尾追加一首，不打断当前播放。
     *
     * MediaController 不能直接改队列（那得往播放服务发指令），
     * 所以这里把队列重建一遍，但把当前曲目和播放位置都保住 ——
     * 听起来像「加进队列」，实际效果也是。
     */
    fun enqueue(track: Track) {
        val c = controller ?: return
        val cur = currentTrack
        val base = if (playlist.isEmpty()) listOf(track) else playlist + track
        val idx = cur?.let { t -> base.indexOfFirst { it.key == t.key } } ?: 0
        val pos = c.currentPosition
        playlist = base
        queue = base
        c.setMediaItems(
            base.map { toMediaItem(it) },
            idx.coerceIn(0, base.size - 1),
            pos
        )
        c.prepare()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(position: Long) {
        controller?.seekTo(position)
    }

    /** 切循环模式：列表循环 → 单曲循环 → 不循环 → 列表循环。 */
    fun cycleRepeatMode() {
        val c = controller ?: return
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
        c.repeatMode = next
        repeatMode = next
    }

    /** 切随机播放。 */
    fun toggleShuffle() {
        val c = controller ?: return
        val next = !c.shuffleModeEnabled
        c.shuffleModeEnabled = next
        shuffle = next
    }

    /** 点队列里的某一首。 */
    fun jumpTo(track: Track) {
        val c = controller ?: return
        val idx = playlist.indexOfFirst { it.key == track.key }
        if (idx >= 0) {
            c.seekTo(idx, 0L)
            c.play()
        }
    }

    fun tick() {
        val c = controller ?: return
        positionMs = c.currentPosition
        val d = c.duration
        durationMs = if (d > 0) d else 0L
    }

    fun clearError() {
        errorText = null
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }
}