package com.cloudmusic.app.ui

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.player.MusicService
import com.cloudmusic.app.player.PlaybackHeaders

/**
 * 播放模式：把 ExoPlayer 的 repeatMode + shuffle 合成一个概念，
 * 界面上每种模式一个专属图标，不用再靠「图标颜色深浅」猜状态。
 */
enum class PlayMode {
    /** 列表循环 */
    LIST_LOOP,
    /** 随机播放 */
    SHUFFLE,
    /** 顺序播放：按列表放完就停 */
    SEQUENTIAL,
    /** 单曲循环 */
    SINGLE_LOOP,
}

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
     * 播放列表点了还没解析出直链的占位歌（歌单在线混播场景）。
     * 返回 true 表示上层（歌单页）接管、去在线解析这一首 —— 播放器别自己乱跳。
     */
    var onUnresolvedPick: ((Track) -> Boolean)? = null

    /**
     * 循环模式：列表循环 / 单曲循环 / 不循环。
     * 跟网易云的控制栏一致，点一下切一个。
     */
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_ALL)
        private set

    /** 随机播放开关。 */
    var shuffle by mutableStateOf(false)
        private set

    /** 当前播放模式（UI 直接读这个，不再自己拼 repeatMode + shuffle）。 */
    var playMode by mutableStateOf(PlayMode.LIST_LOOP)
        private set

    /** 定时关闭状态：0=关 1=倒计时 2=播完当前曲。 */
    var sleepMode by mutableStateOf(0)
        private set
    /** 倒计时剩余秒数，-1 表示未知（如播完当前曲模式）。 */
    var sleepRemainingSec by mutableStateOf(-1)
        private set

    private var playlist: List<Track> = emptyList()

    /**
     * 媒体队列下标 → 逻辑队列（整张歌单）下标。
     *
     * 没下载、也还没解析出直链的占位歌只待在逻辑队列里（播放列表照常显示整张单），
     * 不交给 ExoPlayer —— 空 URL 会直接报 Malformed URL 并把自动连播打断。
     * 解析成功后按歌单顺序插进媒体队列，靠这张表换算位置。
     */
    private var mediaToLogical: List<Int> = emptyList()

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

    // 接收后台服务回传的定时关闭状态（MediaSession 自定义命令在 MediaController.Listener 上）
    private val controllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            sessionCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (sessionCommand.customAction) {
                MusicService.ACTION_SLEEP_TICK -> {
                    sleepMode = args.getInt("mode")
                    sleepRemainingSec = args.getInt("remaining")
                }
                MusicService.ACTION_SLEEP_CLEARED -> {
                    sleepMode = 0
                    sleepRemainingSec = -1
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private var pendingTracks: List<Track>? = null
    private var pendingIndex: Int = 0
    private var pendingPositionMs: Long = 0L

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).setListener(controllerListener).buildAsync()
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
        val playable = tracks.withIndex().filter { it.value.url.isNotBlank() }
        mediaToLogical = playable.map { it.index }
        val items = playable.map { toMediaItem(it.value) }

        // 整张单都还没解析出直链：只摆好逻辑队列，一首空 URL 都不交给
        // ExoPlayer。等在线解析成功，replaceMediaItem 会插进来并起播。
        if (items.isEmpty()) {
            Log.d("CM/PlayerQueue", "逻辑队列 ${tracks.size} 首全部待解析，暂不建媒体队列")
            currentTitle = tracks.getOrNull(startIndex)?.title.orEmpty()
            currentArtist = tracks.getOrNull(startIndex)?.artist.orEmpty()
            currentTrack = null
            nextTrack = null
            return
        }

        val wanted = startIndex.coerceIn(0, tracks.lastIndex)
        val mediaIndex = mediaToLogical.indexOfFirst { it >= wanted }
            .takeIf { it >= 0 } ?: items.lastIndex
        c.setMediaItems(items, mediaIndex, startPositionMs)
        c.prepare()
        c.play()
        val start = tracks[mediaToLogical[mediaIndex]]
        currentTitle = start.title
        currentArtist = start.artist
        currentTrack = start
        updateNextTrack()
        Log.d(
            "CM/PlayerQueue",
            "建队列：逻辑 ${tracks.size} 首 / 可播 ${items.size} 首，从「${start.title}」起播"
        )
    }

    /**
     * 把逻辑队列第 index 首替换成已解析好直链的 Track，并按歌单顺序插进媒体队列。
     *
     * 歌单「在线混播」用：占位歌一开始不进媒体队列，轮到它之前解析好，
     * 再插到「后面第一首已进队列的歌」之前 —— 顺序不乱、播放不中断。
     */
    fun replaceMediaItem(index: Int, track: Track) {
        val c = controller ?: return
        if (index < 0 || index >= playlist.size || track.url.isBlank()) return
        val next = playlist.toMutableList().also { it[index] = track }
        playlist = next
        queue = next

        val existing = mediaToLogical.indexOf(index)
        if (existing >= 0) {
            c.replaceMediaItem(existing, toMediaItem(track))
            Log.d("CM/PlayerQueue", "原地替换第 ${index + 1} 首「${track.title}」")
            return
        }
        val insertAt = mediaToLogical.indexOfFirst { it > index }
            .takeIf { it >= 0 } ?: mediaToLogical.size
        mediaToLogical = mediaToLogical.toMutableList().also { it.add(insertAt, index) }
        if (c.mediaItemCount == 0) {
            // 起播时一首可播的都没有，现在终于有了：直接起播
            c.setMediaItem(toMediaItem(track))
            c.prepare()
            c.play()
            currentTrack = track
            currentTitle = track.title
            currentArtist = track.artist
            updateNextTrack()
        } else {
            c.addMediaItem(insertAt, toMediaItem(track))
        }
        Log.d("CM/PlayerQueue", "插入第 ${index + 1} 首「${track.title}」→ 媒体位置 $insertAt")
    }

    private fun toMediaItem(t: Track): MediaItem {
        // MediaItem 带不了自定义请求头（tag 也不随 MediaSession 传），
        // 但夸克直链必须用它自己那份 Cookie（会话绑定），所以按 URL 存一份给
        // 播放层取（见 PlaybackHeaders / StreamDataSourceFactory）。
        PlaybackHeaders.put(t.url, t.extraHeaders)
        return MediaItem.Builder()
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
    }

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
        val logicalIdx = cur?.let { t -> base.indexOfFirst { it.key == t.key } }?.takeIf { it >= 0 } ?: 0
        val pos = c.currentPosition
        playlist = base
        queue = base
        val playable = base.withIndex().filter { it.value.url.isNotBlank() }
        mediaToLogical = playable.map { it.index }
        val items = playable.map { toMediaItem(it.value) }
        if (items.isEmpty()) return
        val mediaIdx = mediaToLogical.indexOfFirst { it >= logicalIdx }
            .takeIf { it >= 0 } ?: items.lastIndex
        c.setMediaItems(items, mediaIdx, pos)
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

    /** 直接选定一种播放模式（控制栏菜单用）。 */
    fun applyPlayMode(mode: PlayMode) {
        val c = controller ?: return
        c.shuffleModeEnabled = mode == PlayMode.SHUFFLE
        c.repeatMode = when (mode) {
            PlayMode.LIST_LOOP, PlayMode.SHUFFLE -> Player.REPEAT_MODE_ALL
            PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
            PlayMode.SINGLE_LOOP -> Player.REPEAT_MODE_ONE
        }
        shuffle = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        playMode = mode
    }

    /** 从播放器实际状态回推模式，保证外部改了 repeat/shuffle 也不会显示错。 */
    private fun syncPlayMode() {
        val c = controller ?: return
        playMode = when {
            c.shuffleModeEnabled -> PlayMode.SHUFFLE
            c.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.SINGLE_LOOP
            c.repeatMode == Player.REPEAT_MODE_OFF -> PlayMode.SEQUENTIAL
            else -> PlayMode.LIST_LOOP
        }
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
        syncPlayMode()
    }

    /** 切随机播放。 */
    fun toggleShuffle() {
        val c = controller ?: return
        val next = !c.shuffleModeEnabled
        c.shuffleModeEnabled = next
        shuffle = next
        syncPlayMode()
    }

    /** 定时关闭：N 分钟后暂停。 */
    fun startSleep(minutes: Int) {
        val b = Bundle().apply { putInt("minutes", minutes) }
        controller?.sendCustomCommand(SessionCommand(MusicService.ACTION_SLEEP_SET, Bundle.EMPTY), b)
    }

    /** 定时关闭：播完当前这首后暂停。 */
    fun startSleepAfterTrack() {
        val b = Bundle().apply { putBoolean("after_track", true) }
        controller?.sendCustomCommand(SessionCommand(MusicService.ACTION_SLEEP_SET, Bundle.EMPTY), b)
    }

    /** 取消定时关闭。 */
    fun cancelSleep() {
        controller?.sendCustomCommand(SessionCommand(MusicService.ACTION_SLEEP_CANCEL, Bundle.EMPTY), Bundle.EMPTY)
    }

    /** 点队列里的某一首。 */
    fun jumpTo(track: Track) {
        val c = controller ?: return
        val idx = playlist.indexOfFirst { it.key == track.key }
        if (idx < 0) return
        val mediaIdx = mediaToLogical.indexOf(idx)
        if (mediaIdx >= 0) {
            c.seekTo(mediaIdx, 0L)
            c.play()
            return
        }
        // 占位歌还没解析出直链：先让上层接管去在线解析（歌单混播），
        // 没人接管才退回旧行为 —— 跳到它后面第一首能播的
        if (onUnresolvedPick?.invoke(track) == true) return
        val fallback = mediaToLogical.indexOfFirst { it > idx }.takeIf { it >= 0 } ?: return
        c.seekTo(fallback, 0L)
        c.play()
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