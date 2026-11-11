package com.cloudmusic.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudmusic.app.App
import com.cloudmusic.app.data.CloudProvider
import com.cloudmusic.app.data.DavProvider
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.playlist.AudioCandidate
import com.cloudmusic.app.data.playlist.OnlineFailReason
import com.cloudmusic.app.data.playlist.OnlineResolution
import com.cloudmusic.app.data.playlist.PlaylistTrackImporter
import com.cloudmusic.app.data.playlist.SavedPlaylist
import com.cloudmusic.app.data.playlist.SavedTrack
import com.cloudmusic.app.data.playlist.TrackMatcher
import com.cloudmusic.app.data.playlist.TrackPlan
import com.cloudmusic.app.data.playlist.TrackState
import com.cloudmusic.app.data.playlist.TrackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow

/** 一首歌在导入过程中的临时状态。 */
data class TrackPhase(
    val text: String = "",
    val busy: Boolean = false,
    val failed: Boolean = false,
    val message: String = "",
)

/** 等用户点头的一次导入。 */
data class PendingImport(
    val track: SavedTrack,
    val plan: TrackPlan,
    /** 为什么要确认 */
    val reason: String,
)

/**
 * 在线播放的提示，按条目挂。
 *
 * 之前统一放在列表顶部，用户往下滑几屏就完全看不见了 ——
 * 提示必须跟它对应的那首歌待在一起。
 */
data class OnlineHint(
    val key: String,
    val text: String,
    val error: Boolean = false,
)

/**
 * 歌单详情：看着整张单，想听哪首点哪首。
 *
 * 点「导入」才去找音源、下载、传网盘 —— 其余的歌一个字节都不动。
 */
class PlaylistDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef: App = app as App
    private val store = appRef.playlistStore
    private val prefs = appRef.prefs

    var playlist by mutableStateOf<SavedPlaylist?>(null)
        private set

    var tracks by mutableStateOf<List<SavedTrack>>(emptyList())
        private set

    /** 网盘里已经有的歌（按 trackKey 记） */
    var inLibrary by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * 歌单条目 → 网盘里真实存在的那个文件。
     *
     * 播放要用它：歌单条目本身没有播放地址，得对上网盘里的文件才行。
     */
    var libraryTracks by mutableStateOf<Map<String, Track>>(emptyMap())
        private set

    /** 顶部大封面：借用歌单里某首歌的封面，和列表页是同一张 */
    var coverTrack by mutableStateOf<Track?>(null)
        private set

    /** 播放器（全局单例） */
    val player get() = appRef.player

    /**
     * 这张歌单里能播的歌，按歌单顺序排。
     *
     * 下载好的直接用网盘文件；没下载的先用空直链占位 —— 轮到它之前
     * 再在线解析（见 startMixedPlayback），所以整张单都能成列表连续播，
     * 不用先一首首导进网盘。
     */
    val playableTracks: List<Track>
        get() = tracks.map { libraryTracks[it.key] ?: placeholderTrack(it) }

    /** 没下载的歌：先用空直链占位进播放队列，播到它之前再解析。 */
    private fun placeholderTrack(t: SavedTrack) = Track(
        key = t.key,
        id = t.key,
        url = "",
        fileName = "",
        title = t.title,
        artist = t.artist,
        album = t.album,
        folder = "",
        size = 0L,
        mime = "audio/*",
        durationSec = 0.0,
        provider = "online-pending",
    )

    /** 在线混播时，当前这首往后预解析几首，趁当前这首还没播完把直链填好。 */
    private val LOOKAHEAD = 3

    /** 混播里还没解析的占位歌：key → 歌单条目。 */
    private val pendingByKey = mutableMapOf<String, SavedTrack>()
    /** 已经在线解析过直链的 key，避免重复解析 / 死循环。 */
    private val resolvedKeys = mutableSetOf<String>()
    /** 混播时的歌单顺序，用来定位「当前这首」前后要解析哪些。 */
    private var queueOrder: List<String> = emptyList()
    private var resolveJob: Job? = null

    init {
        // 播放列表面板点了还没解析的占位歌：在线解析这一首，再精准跳过去。
        // 以前 jumpTo 对占位歌只会「跳到后面第一首能播的」，点 A 播出别的歌。
        player.onUnresolvedPick = { track -> onQueuePlaceholderPicked(track) }
    }

    var phases by mutableStateOf<Map<String, TrackPhase>>(emptyMap())
        private set

    var pendingConfirm by mutableStateOf<PendingImport?>(null)
        private set

    var providerId by mutableStateOf(prefs.importProvider)
        private set

    var dir by mutableStateOf(prefs.importDir)
        private set

    var query by mutableStateOf("")

    var message by mutableStateOf("")

    var loading by mutableStateOf(false)
        private set

    /** 网盘源（下歌吧）要登录夸克才能用，没登录就等于只有两个公开源。 */
    var cloudReady by mutableStateOf(false)
        private set

    /** 正在解析直链的 key（夸克那几步请求要几秒，按钮转圈）。 */
    var onlineLoadingKey by mutableStateOf<String?>(null)
        private set

    /** 播放器报错（夸克 412 / 直链失效会落到这里）。 */
    val playbackError: String? get() = appRef.player.errorText

    /** 在线播放的行内提示：成功 / 解析失败，只挂在对应的那首歌下面。 */
    var onlineHint by mutableStateOf<OnlineHint?>(null)
        private set

    /**
     * 当前播放器里那首歌，对应到这张歌单的哪个条目。
     *
     * 在线混播的歌 key 是 "online:<条目key>"；已下载的歌用网盘文件
     * 自己的 key，得反查 libraryTracks。不属于这张单就返回 null ——
     * 在别的页面点歌时，歌单里不会留一个假高亮。
     */
    val currentPlaylistKey: String?
        get() {
            val key = player.currentTrack?.key ?: return null
            if (key.startsWith("online:")) {
                val k = key.removePrefix("online:")
                return k.takeIf { c -> tracks.any { it.key == c } }
            }
            return libraryTracks.entries.firstOrNull { it.value.key == key }?.key
        }

    /** 当前这首是不是在线解析来的（决定要不要显示「停止在线播放」）。 */
    val currentIsOnline: Boolean
        get() = player.currentTrack?.key?.startsWith("online:") == true

    /**
     * 给列表行取提示：只有出错才给。
     *
     * 播放中不再挂「在线播放中 …」那行字 —— 封面角标、歌名染色和右侧
     * 暂停按钮已经能表明状态，多一行提示反而把列表撑高。
     * 解析/播放失败的原因仍留在它自己那一行，并带「去别处找」的出口。
     */
    fun hintFor(key: String): OnlineHint? {
        onlineHint?.takeIf { it.error && it.key == key }?.let { return it }
        if (key != currentPlaylistKey || !currentIsOnline) return null
        val err = playbackError ?: return null
        return OnlineHint(
            key,
            "在线播放失败：$err · 夸克 Cookie 可能已过期，去设置重新登录再试",
            error = true
        )
    }

    /** 在线解析 / 起播的协程，快速切歌时把上一首的活取消掉。 */
    private var onlineJob: Job? = null

    /** 只清行内试听 UI，不动播放队列、也不取消正在跑的起播协程。 */
    private fun clearOnlineUi() {
        onlineLoadingKey = null
        onlineHint = null
    }

    // ------------------------------------------------------------ 载入

    fun load(key: String) {
        loading = true
        cloudReady = appRef.quarkClient.loggedIn
        viewModelScope.launch {
            val pl = withContext(Dispatchers.IO) { store.playlist(key) }
            if (pl == null) {
                message = "这张歌单不见了"
                loading = false
                return@launch
            }
            val list = withContext(Dispatchers.IO) { store.tracks(key) }
            val matched = withContext(Dispatchers.IO) {
                matchLibrary(list, appRef.repository.cachedTracks())
            }
            playlist = pl
            tracks = list
            // 保留之前手工挂上去的（曲库索引还没同步上的）
            libraryTracks = matched + libraryTracks.filterKeys { it !in matched }
            coverTrack = pickCoverTrack(list, libraryTracks)
            inLibrary = libraryTracks.keys + list.filter { it.state == TrackState.IMPORTED }.map { it.key }
            loading = false

            // 有歌标着「已导入」但曲库索引里找不到 —— 索引过期了，扫一遍补上。
            // 不补的话这些歌既播不了，「播放全部」也数不上它们。
            val orphan = list.any {
                it.state == TrackState.IMPORTED && it.key !in matched
            }
            if (orphan) refreshLibrary()
        }
    }

    /**
     * 删掉这张歌单。
     *
     * 只删 App 里的这张清单，已经导进网盘的歌不动 —— 它们还在曲库里，
     * 照常能播。删完 [onDone] 回调让页面退回列表。
     */
    fun deletePlaylist(key: String, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(key) }
            onDone()
        }
    }

    /** 重新扫一遍网盘，把曲库索引补齐（后台跑，不挡界面）。 */
    private fun refreshLibrary() {
        val provider = providerOf(ProviderKind.of(providerId)) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appRef.repository.scan(provider) { _, _ -> }
            } catch (e: Exception) {
                // 扫不到就算了，不影响已有的
            }
            withContext(Dispatchers.Main) { reloadTracks() }
        }
    }

    /** 刷新曲目状态（导入完一首之后）。 */
    private fun reloadTracks() {
        val key = playlist?.key ?: return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.tracks(key) }
            val matched = withContext(Dispatchers.IO) {
                matchLibrary(list, appRef.repository.cachedTracks())
            }
            tracks = list
            // 刚上传的文件还没进曲库索引，别把手工挂上去的那条冲掉
            libraryTracks = matched + libraryTracks.filterKeys { it !in matched }
            coverTrack = pickCoverTrack(list, libraryTracks)
            inLibrary = libraryTracks.keys + list.filter { it.state == TrackState.IMPORTED }.map { it.key }
        }
    }

    /** 前几首里挑第一首能对上网盘文件的；一首都对不上就用第一首走在线检索。 */
    private fun pickCoverTrack(
        list: List<SavedTrack>,
        matched: Map<String, Track>,
    ): Track? {
        val first = list.firstOrNull() ?: return null
        val hit = list.take(8).firstNotNullOfOrNull { matched[it.key] }
        return coverTrackFor(first, hit)
    }

    /**
     * 找出网盘里已经有的歌，并且记住是**哪个文件**。
     *
     * 先按歌名分桶，不然几百首歌对着几百首曲库一首首比，
     * 打开页面就得卡一下。
     *
     * 匹配用两条路：
     * 1. 导入时记下的文件名（note）精确对 —— 最准
     * 2. 歌名归一化相等 + 歌手命中 —— 兜底
     */
    private fun matchLibrary(
        list: List<SavedTrack>,
        existing: List<Track>,
    ): Map<String, Track> {
        if (existing.isEmpty()) return emptyMap()
        val byFileName = existing.associateBy { it.fileName }
        val byTitle = existing.groupBy {
            TrackMatcher.norm(TrackMatcher.stripBrackets(it.title))
        }
        val hit = mutableMapOf<String, Track>()
        for (t in list) {
            // 1. 文件名精确匹配（导入时存过文件名）
            val exact = t.note.takeIf { it.isNotBlank() }?.let { byFileName[it] }
            if (exact != null) {
                hit[t.key] = exact
                continue
            }
            // 2. 歌名 + 歌手
            val bucket = byTitle[TrackMatcher.norm(TrackMatcher.stripBrackets(t.title))] ?: continue
            val found = bucket.firstOrNull { TrackMatcher.artistHit(t.artist, it.artist) } ?: continue
            hit[t.key] = found
        }
        return hit
    }

    /**
     * 从这张歌单的某一首开始播。
     *
     * 队列是整张单（下载好的 + 没下载的占位），没下载的歌轮到之前
     * 会在线解析，所以能一首接一首听下去。
     */
    fun playTrack(t: SavedTrack) {
        val queue = playableTracks
        // 已下载的歌用网盘文件的 key，没下载的占位歌用歌单条目 key
        val targetKey = libraryTracks[t.key]?.key ?: t.key
        val index = queue.indexOfFirst { it.key == targetKey }
        if (index < 0) return
        viewModelScope.launch { startMixedPlayback(queue, index) }
    }

    /** 从头开始播整张单（下载好的 + 没下载的占位，播到再解析）。 */
    fun playAll() {
        val queue = playableTracks
        if (queue.isEmpty()) return
        viewModelScope.launch { startMixedPlayback(queue, 0) }
    }

    /** 在线解析失败的文案：说清楚卡在哪一步，别再甩给「登录夸克」。 */
    private fun onlineFailText(reason: OnlineFailReason): String = when (reason) {
        OnlineFailReason.ARTIST_MISMATCH ->
            "音源里有同名版本，但歌手标的不一样，没能直接播"
        OnlineFailReason.NO_SAME_TITLE ->
            "几个音源都没搜到同名歌曲"
        OnlineFailReason.NO_DIRECT_URL ->
            "找到同名同歌手，但拿不到可播的直链"
        OnlineFailReason.SOURCE_ERROR ->
            "音源请求都失败了，检查一下网络"
    }

    /**
     * 歌单「在线混播」：已下载的直接播，没下载的以占位进队列，
     * 轮到它之前再在线解析直链、原地替掉（resolveAround），顺序不乱、播放不中断。
     */
    private suspend fun startMixedPlayback(
        rawQueue: List<Track>,
        startIndex: Int,
        /** 从某一行点进来的：队列建好后给那行挂提示 / 高亮 */
        hintKey: String? = null,
    ) {
        clearOnlineUi()
        appRef.player.clearError()
        if (hintKey != null) onlineLoadingKey = hintKey
        pendingByKey.clear()
        resolvedKeys.clear()
        val queue = rawQueue.toMutableList()
        queueOrder = queue.map { it.key }
        queue.forEach { t ->
            if (t.url.isBlank()) {
                tracks.firstOrNull { it.key == t.key }?.let { pendingByKey[t.key] = it }
            }
        }
        // 第一首若是占位（没下载），先解析出直链再建队列；
        // 解析不出来的占位歌根本不会进媒体队列，播放器也不会去拉空 URL。
        var startFailure: OnlineFailReason? = null
        queue.getOrNull(startIndex)?.takeIf { it.url.isBlank() }?.let { first ->
            tracks.firstOrNull { it.key == first.key }?.let { saved ->
                val result = try {
                    appRef.playlistImport.resolveForOnlinePlay(saved.toPlaylistTrack(), appRef.quarkClient.loggedIn)
                } catch (e: Exception) {
                    OnlineResolution(reason = OnlineFailReason.SOURCE_ERROR)
                }
                // 不论成败都记下来，别让预解析再花几秒重试同一首
                resolvedKeys.add(first.key)
                val c = result.candidate
                if (c != null && c.audioUrl.isNotBlank()) {
                    queue[startIndex] = buildOnlineTrack(saved.key, c)
                } else {
                    startFailure = result.reason ?: OnlineFailReason.NO_SAME_TITLE
                }
            }
        }
        // 队列始终是整张歌单：能播的进媒体队列，占位歌轮到之前解析好再插进去
        player.play(queue, startIndex)
        startResolver(queue.getOrNull(startIndex)?.key)
        onlineLoadingKey = null
        // 只有解析失败要挂提示（留在那一行）；「在线播放中」由 hintFor
        // 按当前曲目推导，播到下一首会自动挪过去
        val failure = startFailure
        if (hintKey != null && failure != null) {
            onlineHint = OnlineHint(hintKey, onlineFailText(failure), error = true)
        }
    }

    /**
     * 盯着当前在放的那首，把它后面几首里还没解析的占位歌在线解析掉，
     * 趁当前这首还没播完（一首歌好几分钟）把直链填进队列。
     */
    private fun startResolver(startKey: String? = null) {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            // 起播那一刻就先跑一轮：如果第一首没解析出来、媒体队列一个可播的
            // 都没有，就不会有「切歌」事件来触发后面的预解析了。
            if (startKey != null) resolveAround(startKey)
            snapshotFlow { player.currentTrack?.key }
                .collect { resolveAround(it) }
        }
    }

    private suspend fun resolveAround(rawKey: String?) {
        val curKey = rawKey?.removePrefix("online:") ?: return
        val idx = queueOrder.indexOf(curKey)
        if (idx < 0) return
        val quarkFirst = appRef.quarkClient.loggedIn
        for (i in idx..minOf(idx + LOOKAHEAD, queueOrder.lastIndex)) {
            val key = queueOrder[i]
            if (key in resolvedKeys) continue
            val saved = pendingByKey[key] ?: continue
            val result = try {
                appRef.playlistImport.resolveForOnlinePlay(saved.toPlaylistTrack(), quarkFirst)
            } catch (e: Exception) {
                OnlineResolution()
            }
            // 解析不到也标记为已处理，避免反复重试拖死循环
            resolvedKeys.add(key)
            val c = result.candidate ?: continue
            if (c.audioUrl.isBlank()) continue
            player.replaceMediaItem(i, buildOnlineTrack(key, c))
        }
        // 全部解析完就收摊，别一直挂着
        if (pendingByKey.isNotEmpty() && resolvedKeys.containsAll(pendingByKey.keys)) {
            resolveJob?.cancel()
        }
    }

    /** 把在线解析结果拼成一首可播的 Track（和单曲试听同一个拼法）。 */
    private fun buildOnlineTrack(key: String, c: AudioCandidate) = Track(
        key = "online:$key",
        id = key,
        url = c.audioUrl,
        fileName = "${c.title}.${c.ext.ifBlank { "mp3" }}",
        title = c.title,
        artist = c.artist,
        album = c.album,
        folder = "",
        size = 0L,
        mime = "audio/*",
        durationSec = c.durationSec.toDouble(),
        provider = "online",
        extraHeaders = c.extraHeaders,
    )

    /**
     * 点没下载的歌：从这首开始播整张歌单。
     *
     * 以前这里会把队列换成「只有这一首」再事后恢复，结果不是跳歌就是
     * 播放列表只剩一首。现在统一走混播队列 —— 队列里始终是整张单，
     * 这首在线解析出来后接着往后播。
     */
    fun playOnline(t: SavedTrack) {
        // 点当前正在播的这首在线歌 = 暂停 / 继续，队列保持整张歌单不动
        if (currentPlaylistKey == t.key && currentIsOnline) {
            player.toggle()
            return
        }
        stopOnline()
        val queue = playableTracks
        val idx = queue.indexOfFirst { it.key == (libraryTracks[t.key]?.key ?: t.key) }
        if (idx < 0) return
        onlineJob = viewModelScope.launch {
            startMixedPlayback(queue, idx, hintKey = t.key)
        }
    }

    /**
     * 播放列表面板点了占位歌（还没解析出直链的那首）。
     *
     * 解析这一首的直链，原地插进媒体队列再跳过去 —— 和点歌单行是同一个语义，
     * 只是入口从行点击变成了播放列表。不是这张歌单的队列就不接管。
     */
    private fun onQueuePlaceholderPicked(track: Track): Boolean {
        val savedKey = track.key.removePrefix("online:")
        val idx = queueOrder.indexOf(savedKey)
        if (idx < 0) return false
        val saved = tracks.firstOrNull { it.key == savedKey } ?: return false
        onlineJob?.cancel()
        onlineJob = viewModelScope.launch {
            onlineLoadingKey = savedKey
            val result = try {
                appRef.playlistImport.resolveForOnlinePlay(
                    saved.toPlaylistTrack(), appRef.quarkClient.loggedIn
                )
            } catch (e: Exception) {
                OnlineResolution()
            }
            // 解析过就记下来，别让预解析再花几秒重试同一首
            resolvedKeys.add(savedKey)
            onlineLoadingKey = null
            val candidate = result.candidate
            if (candidate == null || candidate.audioUrl.isBlank()) {
                onlineHint = OnlineHint(
                    savedKey,
                    onlineFailText(result.reason ?: OnlineFailReason.NO_SAME_TITLE),
                    error = true
                )
                return@launch
            }
            val onlineTrack = buildOnlineTrack(savedKey, candidate)
            // 原地插进媒体队列（不打断当前播放），再精准跳到这一首
            player.replaceMediaItem(idx, onlineTrack)
            player.jumpTo(onlineTrack)
        }
        return true
    }

    /**
     * 清掉行内在线试听 UI、取消没跑完的解析。
     *
     * 以前这里会「回放试听前的旧队列」，离开页面要么跳到别的歌、
     * 要么播放列表只剩一首。现在队列始终由混播持有，这里绝不动播放器。
     */
    fun stopOnline() {
        onlineJob?.cancel()
        onlineJob = null
        clearOnlineUi()
    }

    override fun onCleared() {
        // 只收 UI 和协程：队列归播放器，别在这里动
        stopOnline()
        player.onUnresolvedPick = null
        super.onCleared()
    }

    // ------------------------------------------------------------ 筛选

    fun filtered(): List<SavedTrack> {
        val q = query.trim()
        if (q.isEmpty()) return tracks
        return tracks.filter {
            it.title.contains(q, true) ||
                it.artist.contains(q, true) ||
                it.album.contains(q, true)
        }
    }

    // ------------------------------------------------------------ 设置

    fun setProvider(kind: ProviderKind) {
        providerId = kind.id
        prefs.importProvider = kind.id
    }

    fun changeDir(path: String) {
        dir = path
        prefs.importDir = path
    }

    /** 单曲存到网盘的哪个目录。 */
    fun targetDir(): String =
        PlaylistTrackImporter.targetDir(dir, playlist?.name.orEmpty())

    /** 换个 base 时的目标目录，给对话框预览用。 */
    fun targetDirWith(base: String): String =
        PlaylistTrackImporter.targetDir(base, playlist?.name.orEmpty())

    /** 没有音源时给用户的兜底搜索链接（去聚合站自己找）。 */
    fun fallbackUrl(t: SavedTrack): String =
        appRef.playlistImport.fallbackUrl(t.toPlaylistTrack())

    private fun providerOf(kind: ProviderKind): CloudProvider? = when (kind) {
        ProviderKind.WEBDAV ->
            if (prefs.hasCredentials) DavProvider(appRef.repository.client(), prefs) else null
        ProviderKind.ONEDRIVE ->
            if (prefs.oneDriveAccessToken.isNotBlank()) appRef.oneDriveProvider else null
    }

    // ------------------------------------------------------------ 导入单曲

    fun importTrack(t: SavedTrack) {
        if (phases[t.key]?.busy == true) return
        viewModelScope.launch {
            setPhase(t.key, TrackPhase(text = "找音源中", busy = true))
            try {
                val existing = appRef.repository.cachedTracks()
                val plan = appRef.playlistImport.analyzeTrack(t.toPlaylistTrack(), existing)
                when (plan.status) {
                    TrackStatus.EXISTING -> {
                        store.updateTrackState(t.key, TrackState.IMPORTED, "已在网盘")
                        setPhase(t.key, TrackPhase(text = "已在网盘"))
                        reloadTracks()
                    }
                    TrackStatus.EXISTING_FUZZY -> {
                        pendingConfirm = PendingImport(
                            track = t,
                            plan = plan,
                            reason = "网盘里有一首同名但歌手不同的歌，还是要把这首也下下来吗？"
                        )
                        setPhase(t.key, TrackPhase(text = "疑似已有"))
                    }
                    TrackStatus.VARIANT -> {
                        pendingConfirm = PendingImport(
                            track = t,
                            plan = plan,
                            reason = "只找到「${plan.candidate?.variantTag ?: "别的版本"}」：" +
                                "${plan.candidate?.title} - ${plan.candidate?.artist}\n" +
                                "（来源：${plan.candidate?.sourceLabel ?: "?"}）\n" +
                                "原版拿不到，下这个版本吗？"
                        )
                        setPhase(t.key, TrackPhase(text = "只有${plan.candidate?.variantTag ?: "别的版本"}"))
                    }
                    TrackStatus.READY -> {
                        val c = plan.candidate
                        if (c == null) {
                            setPhase(
                                t.key,
                                TrackPhase(text = "没找到音源", failed = true, message = "没有可下的版本")
                            )
                        } else {
                            doImport(t, plan)
                        }
                    }
                    TrackStatus.PARTIAL -> {
                        setPhase(
                            t.key,
                            TrackPhase(
                                text = "只有片段",
                                failed = true,
                                message = buildString {
                                    append("公开渠道只有试听片段，完整版下不到")
                                    appendDetail(plan.notes)
                                }
                            )
                        )
                    }
                    TrackStatus.NO_SOURCE -> {
                        setPhase(
                            t.key,
                            TrackPhase(
                                text = "没找到音源",
                                failed = true,
                                message = buildString {
                                    append("所有音源都找不到这首")
                                    appendDetail(plan.notes)
                                }
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                setPhase(
                    t.key,
                    TrackPhase(text = "失败", failed = true, message = e.message ?: "出错了")
                )
            }
        }
    }

    fun confirmPending() {
        val p = pendingConfirm ?: return
        pendingConfirm = null
        val candidate = p.plan.candidate
        if (candidate == null) {
            setPhase(
                p.track.key,
                TrackPhase(text = "没找到音源", failed = true, message = "没有可下的版本")
            )
            return
        }
        viewModelScope.launch { doImport(p.track, p.plan) }
    }

    fun cancelPending() {
        val p = pendingConfirm ?: return
        pendingConfirm = null
        setPhase(p.track.key, TrackPhase(text = ""))
    }

    /**
     * 下载并上传。
     *
     * 一个候选下砸了（比如那个分享的文件坏了、直链过期），不该就此放弃 ——
     * 同一首歌往往还有别的分享（MP3 / FLAC / WAV 各一条），
     * 挨个试过去，只要有一个能下成就行。
     */
    private suspend fun doImport(t: SavedTrack, plan: TrackPlan) {
        val kind = ProviderKind.of(providerId)
        val provider = providerOf(kind)
        if (provider == null) {
            setPhase(
                t.key,
                TrackPhase(
                    text = "没连网盘",
                    failed = true,
                    message = "${kind.shortLabel} 还没连上，先去设置里连一下"
                )
            )
            return
        }

        // 片段候选不用重试 —— 下了也还是片段，浪费流量
        val candidates = (listOfNotNull(plan.candidate) + plan.alternatives)
            .filter { !it.partial }
            .distinctBy { it.sourceId }

        var lastMsg = ""
        for ((i, candidate) in candidates.withIndex()) {
            if (i > 0) {
                setPhase(
                    t.key,
                    TrackPhase(text = "换下一个版本（${i + 1}/${candidates.size}）", busy = true)
                )
            } else {
                setPhase(t.key, TrackPhase(text = "准备中", busy = true))
            }
            val result = appRef.playlistImporter.import(
                track = t.toPlaylistTrack(),
                candidate = candidate,
                provider = provider,
                dir = targetDir(),
                cacheDir = appRef.cacheDir,
            ) { phase ->
                setPhase(t.key, TrackPhase(text = phase, busy = true))
            }
            when (result) {
                is PlaylistTrackImporter.Result.Done -> {
                    store.updateTrackState(t.key, TrackState.IMPORTED, result.fileName)
                    // 刚传上去的文件直接写进曲库索引 + 挂进可播列表，
                    // 不用等重新扫描整个网盘
                    result.track?.let { fresh ->
                        appRef.repository.upsertTrack(fresh)
                        libraryTracks = libraryTracks + (t.key to fresh)
                        inLibrary = inLibrary + t.key
                    }
                    setPhase(t.key, TrackPhase(text = "已导入 · ${candidate.sourceLabel}"))
                    reloadTracks()
                    return
                }
                is PlaylistTrackImporter.Result.Failed -> {
                    lastMsg = result.message
                }
            }
        }
        store.updateTrackState(t.key, TrackState.FAILED, lastMsg)
        setPhase(t.key, TrackPhase(text = "失败", failed = true, message = lastMsg))
    }

    private fun setPhase(key: String, p: TrackPhase) {
        phases = phases + (key to p)
    }

    /**
     * 把各音源的结论接在消息后面。
     *
     * 「公开渠道只有试听片段」这句话用户没法据此行动，
     * 得告诉他：网易云是片段、酷我搜不到、下歌吧因为没登录夸克没参与。
     */
    private fun appendDetail(notes: List<String>): String {
        if (notes.isEmpty()) return ""
        val sb = StringBuilder("\n").append(notes.joinToString("\n"))
        if (notes.any { it.contains("没登录夸克") }) {
            sb.append("\n→ 去设置里登录夸克网盘，还能再试一次网盘源")
        }
        return sb.toString()
    }
}
