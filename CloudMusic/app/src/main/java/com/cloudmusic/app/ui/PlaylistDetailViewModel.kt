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
import com.cloudmusic.app.data.playlist.PlaylistTrackImporter
import com.cloudmusic.app.data.playlist.SavedPlaylist
import com.cloudmusic.app.data.playlist.SavedTrack
import com.cloudmusic.app.data.playlist.TrackMatcher
import com.cloudmusic.app.data.playlist.TrackPlan
import com.cloudmusic.app.data.playlist.TrackState
import com.cloudmusic.app.data.playlist.TrackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** 播放器（全局单例） */
    val player get() = appRef.player

    /**
     * 这张歌单里能播的歌，按歌单顺序排。
     *
     * 播放队列就是它 —— 从歌单里点一首，能连着把整张单听下去。
     */
    val playableTracks: List<Track>
        get() = tracks.mapNotNull { libraryTracks[it.key] }

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
            inLibrary = libraryTracks.keys + list.filter { it.state == TrackState.IMPORTED }.map { it.key }
        }
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
     * 播放歌单里的这一首。
     *
     * 播放队列是这张歌单里所有已导入的歌（按歌单顺序），
     * 所以能一首接一首听下去。
     */
    fun playTrack(t: SavedTrack) {
        val target = libraryTracks[t.key] ?: return
        val queue = playableTracks
        val index = queue.indexOfFirst { it.key == target.key }
        if (index < 0) {
            message = "这首歌在网盘里找不到了"
            return
        }
        player.play(queue, index)
    }

    /** 从头开始播这张歌单里已导入的所有歌。 */
    fun playAll() {
        val queue = playableTracks
        if (queue.isEmpty()) return
        player.play(queue, 0)
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
