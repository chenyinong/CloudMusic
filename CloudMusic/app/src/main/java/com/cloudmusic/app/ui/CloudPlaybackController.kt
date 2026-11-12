package com.cloudmusic.app.ui

import androidx.compose.runtime.snapshotFlow
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.cloud.CloudResolver
import com.cloudmusic.app.data.cloud.CloudTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 云端歌曲的在线混播。
 *
 * 云端只存「歌是哪首」，直链得在本机解析才有 —— 解析要几秒，
 * 所以队列里先摆占位歌（没有直链），轮到它之前再解析好插进去，
 * 顺序不乱、播放不中断。跟歌单页的在线混播是同一个套路。
 */
class CloudPlaybackController(
    private val resolver: CloudResolver,
    private val player: PlayerConnection,
) {

    companion object {
        /** 提前解析几首，趁当前这首还没播完把直链填好。 */
        private const val LOOKAHEAD = 3
    }

    /** 队列里还没解析出直链的歌：cloud key → 曲目。 */
    private val pending = mutableMapOf<String, CloudTrack>()

    /** 已经解析过的 key，避免重复解析 / 死循环。 */
    private val resolved = mutableSetOf<String>()

    /** 队列顺序（cloud key），用来定位「当前这首」前后要解析哪些。 */
    private var order: List<String> = emptyList()

    private var job: Job? = null
    private var scope: CoroutineScope? = null

    /** 播放列表面板点了还没解析的占位歌时接管。 */
    var onUnresolvedPick: ((Track) -> Boolean)? = null

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        player.onUnresolvedPick = { track ->
            val r = onUnresolvedPick?.invoke(track)
            r == true
        }
    }

    /** 接管「播放列表点了还没解析的占位歌」。 */
    fun setUnresolvedHandler(handler: ((Track) -> Boolean)?) {
        onUnresolvedPick = handler
    }

    /**
     * 从第 [startIndex] 首开始播。
     *
     * @return null 表示成功；非 null 是给用户看的失败原因
     */
    suspend fun play(items: List<CloudTrack>, startIndex: Int): String? {
        if (items.isEmpty()) return "这里还没有歌"
        val idx = startIndex.coerceIn(0, items.lastIndex)
        val target = items[idx]

        val res = resolver.resolve(target)
        val candidate = res.candidate
        if (candidate == null || candidate.audioUrl.isBlank()) {
            return CloudResolver.failText(res.reason)
        }
        val ready = resolver.toTrack(target, candidate)

        pending.clear()
        resolved.clear()
        order = items.map { it.key }
        for (t in items) pending[t.key] = t
        resolved.add(target.key)

        val queue = items.map { if (it.key == target.key) ready else resolver.placeholder(it) }
        player.play(queue, idx)
        startWatching()
        return null
    }

    /** 盯着当前在放的那首，把它后面几首里还没解析的占位歌解析掉。 */
    private fun startWatching() {
        job?.cancel()
        val sc = scope ?: return
        job = sc.launch {
            snapshotFlow { player.currentTrack?.key }
                .collect { resolveAround(it) }
        }
    }

    private suspend fun resolveAround(rawKey: String?) {
        val cur = rawKey?.removePrefix("cloud:") ?: return
        val idx = order.indexOf(cur)
        if (idx < 0) return
        for (i in idx..minOf(idx + LOOKAHEAD, order.lastIndex)) {
            val key = order[i]
            if (key in resolved) continue
            val t = pending[key] ?: continue
            // 解析不到也记成已处理，避免反复重试拖死循环
            resolved.add(key)
            val res = resolver.resolve(t)
            val candidate = res.candidate ?: continue
            if (candidate.audioUrl.isBlank()) continue
            val track = resolver.toTrack(t, candidate)
            player.replaceMediaItem(order.indexOf(key), track)
        }
    }

    /**
     * 播放列表面板点了占位歌：解析这一首，原地插进媒体队列再跳过去。
     */
    fun resolvePicked(track: Track): Boolean {
        val key = track.key.removePrefix("cloud:")
        val t = pending[key] ?: return false
        val idx = order.indexOf(key)
        if (idx < 0) return false
        val sc = scope ?: return false
        sc.launch {
            resolved.add(key)
            val res = resolver.resolve(t)
            val candidate = res.candidate ?: return@launch
            if (candidate.audioUrl.isBlank()) return@launch
            val ready = resolver.toTrack(t, candidate)
            player.replaceMediaItem(idx, ready)
            player.jumpTo(ready)
        }
        return true
    }

    fun release() {
        job?.cancel()
        job = null
        scope = null
        player.onUnresolvedPick = null
        pending.clear()
        resolved.clear()
        order = emptyList()
    }
}
