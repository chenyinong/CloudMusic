package com.cloudmusic.app.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudmusic.app.App
import com.cloudmusic.app.data.AudioTypes
import com.cloudmusic.app.data.CloudProvider
import com.cloudmusic.app.data.DavProvider
import com.cloudmusic.app.data.DeleteResult
import com.cloudmusic.app.data.DownloadResult
import com.cloudmusic.app.data.ImportTimings
import com.cloudmusic.app.data.MusicHit
import com.cloudmusic.app.data.MusicSearchRepository
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.UploadResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** 一首歌的导入进度。 */
data class ImportState(
    val phase: String = "",
    val done: Boolean = false,
    val failed: Boolean = false,
    val message: String = "",
    /**
     * 导入成功后它在网盘里的曲目。
     *
     * 有这个就能直接播放 —— 不用再下一遍。点「已导入」那条时走它。
     */
    val track: Track? = null,
    /**
     * 这次导入各阶段耗时。
     *
     * 用来回答「到底哪一步慢」——解析直链、传输、校验、传网盘，
     * 分开计时，慢的那段一眼就看出来。
     */
    val timings: ImportTimings? = null,
)

/**
 * 一条「已经导进网盘」的记录。
 *
 * 存在本地是为了重启 App 后还认得出它 —— 否则用户回来看到的又是「导入」按钮。
 */
private data class ImportedRecord(
    /** 导到哪个网盘（webdav / onedrive） */
    val provider: String,
    /** 网盘里的 id：WebDAV 是 href，OneDrive 是 item id */
    val entryId: String,
    /** 落盘的文件名 */
    val fileName: String,
    /** 存在哪个目录 */
    val dir: String,
    /** 文件大小（字节） */
    val size: Long,
)

/**
 * 搜索结果的来源过滤。
 *
 * 「全部」之外单选一个源 —— 用户想找完整版时直接切到夸克网盘，
 * 不想看网盘的那些条目就切回网易云。
 */
enum class SourceFilter(val label: String) {
    ALL("全部"),
    NETEASE("网易云"),
    QUARK("夸克网盘"),
}

/** 「搜歌 → 下载 → 传进网盘」这条链路的状态。 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef: App = app as App
    private val prefs = appRef.prefs

    var keyword by mutableStateOf("")
    var hits by mutableStateOf<List<MusicHit>>(emptyList())

    /** 每个音源的结论（「网易云：12 条」「夸克网盘：没登录…」），给用户看清楚。 */
    var notes by mutableStateOf<List<String>>(emptyList())

    /** 结果按来源过滤：全部 / 只看网易云 / 只看夸克网盘。 */
    var sourceFilter by mutableStateOf(SourceFilter.ALL)
    var searching by mutableStateOf(false)
    var states by mutableStateOf<Map<String, ImportState>>(emptyMap())

    /**
     * 最近一次导入的各阶段耗时，页面顶部显示一行。
     *
     * 「下载慢」到底慢在解析直链还是传输还是传网盘，看这行就知道。
     */
    var lastTimings by mutableStateOf<ImportTimings?>(null)
    var providerId by mutableStateOf(prefs.importProvider)
    var dir by mutableStateOf(prefs.importDir)
    var message by mutableStateOf("")
    var importedCount by mutableStateOf(0)
        private set

    /** 已经导进网盘的记录（搜索结果 id → 网盘里的位置）。 */
    private val imported = mutableMapOf<String, ImportedRecord>()

    /** 正在删除的条目，避免用户连点。 */
    var deletingIds by mutableStateOf(setOf<String>())
        private set

    /** 自动搜索的计时任务（打字时反复重置） */
    private var autoSearchJob: Job? = null

    /** 搜的过程中词又变了 —— 这次搜完接着搜最新的 */
    private var searchAgain = false

    companion object {
        private const val TAG = "CloudMusicImport"
        /** 停手多久算「打完了」 */
        private const val AUTO_SEARCH_DELAY_MS = 600L
    }

    init {
        // 重启后把「已导入」恢复出来，用户点那条就是直接播放
        reloadImported()
    }

    /**
     * 从本地记录里重新读一遍「已导入」。
     *
     * 记录存在 SharedPreferences 里，别的页面（曲库）删了文件也会改它 ——
     * 所以每次动记录之前先重新读，别拿内存里那份旧的当真相。
     */
    fun reloadImported() {
        val fresh = readImported()
        imported.clear()
        imported.putAll(fresh)
        // 网盘上文件已经没了的条目，别再显示「已导入 · 播放」
        val gone = states.filterValues { it.track != null }.keys - fresh.keys
        if (gone.isNotEmpty()) states = states - gone
        // 新读到的记录补上状态（点一下就能播）
        for ((hitId, rec) in fresh) {
            if (states[hitId]?.track != null) continue
            val kind = ProviderKind.of(rec.provider)
            val provider = providerOf(kind) ?: continue
            val track = trackOf(provider, kind, rec.entryId, rec.fileName, rec.size) ?: continue
            states = states + (hitId to ImportState(
                phase = "已导入",
                done = true,
                message = "已存到 ${kind.shortLabel}",
                track = track
            ))
        }
    }

    private fun providerOf(kind: ProviderKind): CloudProvider? = when (kind) {
        ProviderKind.WEBDAV ->
            if (prefs.hasCredentials) DavProvider(appRef.repository.client(), prefs) else null
        ProviderKind.ONEDRIVE ->
            if (prefs.oneDriveAccessToken.isNotBlank()) appRef.oneDriveProvider else null
    }

    fun setProvider(kind: ProviderKind) {
        providerId = kind.id
        prefs.importProvider = kind.id
    }

    /** 过滤后要显示的结果。 */
    val visibleHits: List<MusicHit>
        get() = hits.filter { hit ->
            when (sourceFilter) {
                SourceFilter.ALL -> true
                SourceFilter.NETEASE -> hit.sourceKind.hasNetease
                SourceFilter.QUARK -> hit.sourceKind.hasQuark
            }
        }

    /**
     * 某个过滤条件下有多少条 —— 显示在 chip 上，
     * 让用户知道切过去会不会是空的。
     */
    fun countOf(filter: SourceFilter): Int = when (filter) {
        SourceFilter.ALL -> hits.size
        SourceFilter.NETEASE -> hits.count { it.sourceKind.hasNetease }
        SourceFilter.QUARK -> hits.count { it.sourceKind.hasQuark }
    }

    fun changeDir(path: String) {
        dir = path
        prefs.importDir = path
    }

    /**
     * 关键词变了 —— 停一下就自动搜。
     *
     * 不用再点「搜索」按钮了：打字时每隔半秒会重新计时，
     * 停手 0.6 秒才真的发请求，免得每个字母都去搜一次。
     */
    fun onKeywordChange(text: String) {
        keyword = text
        autoSearchJob?.cancel()
        if (text.isBlank()) {
            // 词都删了，结果也就没意义了
            hits = emptyList()
            notes = emptyList()
            message = ""
            return
        }
        autoSearchJob = viewModelScope.launch {
            delay(AUTO_SEARCH_DELAY_MS)
            search()
        }
    }

    /** 键盘上的搜索键 —— 立刻搜，不用等那半秒 */
    fun searchNow() {
        autoSearchJob?.cancel()
        search()
    }

    fun search() {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            hits = emptyList()
            notes = emptyList()
            message = "输入歌名和歌手，比如「稻香 周杰伦」"
            return
        }
        if (searching) {
            // 已经在搜了 —— 记下来，等这次搜完接着搜最新的词
            searchAgain = true
            return
        }
        searching = true
        message = ""
        viewModelScope.launch {
            val r = appRef.musicSearch.searchMulti(kw)
            searching = false
            if (kw != keyword.trim()) {
                // 搜的时候用户又改了词，这次的结果已经过期了，别显示
                searchAgain = true
            } else {
                hits = r.hits
                notes = r.notes
                if (r.hits.isEmpty()) message = "没搜到，换个写法试试（比如加上歌手名）"
            }
            if (searchAgain) {
                searchAgain = false
                search()
            }
        }
    }

    fun import(hit: MusicHit) {
        val kind = ProviderKind.of(providerId)
        val provider = providerOf(kind)
        if (provider == null) {
            message = "${kind.shortLabel} 还没连上，先去设置里连一下"
            return
        }
        val cur = states[hit.id]
        if (cur != null && cur.phase.isNotBlank() && !cur.done && !cur.failed) return

        setPhase(hit.id, ImportState(phase = "解析直链中"))
        viewModelScope.launch {
            val tmp = File(
                appRef.cacheDir,
                "import_${safeName(hit.id)}.${hit.ext.ifBlank { "mp3" }}"
            )
            setPhase(hit.id, ImportState(phase = "下载中"))
            val dl = appRef.musicSearch.download(hit, tmp) { got, total ->
                // 有总长度就报百分比，没有就报已下多少 MB
                val phase = if (total > 0) {
                    "下载中 ${got * 100 / total}%"
                } else {
                    "下载中 ${got / 1048576} MB"
                }
                setPhase(hit.id, ImportState(phase = phase))
            }
            if (dl is DownloadResult.Failed) {
                tmp.delete()
                lastTimings = dl.timings
                setPhase(
                    hit.id,
                    ImportState(
                        phase = "失败",
                        failed = true,
                        message = listOfNotNull(dl.message, dl.note.takeIf { it.isNotBlank() })
                            .joinToString(" · "),
                        timings = dl.timings
                    )
                )
                return@launch
            }
            if (dl is DownloadResult.Partial) {
                tmp.delete()
                lastTimings = dl.timings
                setPhase(
                    hit.id,
                    ImportState(
                        phase = "下不到完整版",
                        failed = true,
                        message = listOfNotNull(
                            "公开渠道只能拿到 ${dl.gotSec} 秒试听（完整 ${dl.wantSec} 秒）",
                            dl.note.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        timings = dl.timings
                    )
                )
                return@launch
            }
            val ok = dl as DownloadResult.Ok
            // 文件名要用**下载完才知道**的真实扩展名 —— 夸克那边给的是 FLAC / WAV，
            // 存成 .mp3 播放器按扩展名解析会失败
            val fileName = buildFileName(hit, ok.ext)
            setPhase(hit.id, ImportState(phase = "上传中"))
            val tUpload = System.nanoTime()
            val up = provider.upload(ok.file, dir, fileName) { sent, total ->
                val phase = if (total > 0) {
                    "上传中 ${sent * 100 / total}%"
                } else {
                    "上传中 ${sent / 1048576} MB"
                }
                setPhase(hit.id, ImportState(phase = phase))
            }
            val uploadMs = (System.nanoTime() - tUpload) / 1_000_000
            ok.file.delete()
            val timings = (ok.timings ?: ImportTimings()).copy(uploadMs = uploadMs)
            lastTimings = timings
            Log.i(TAG, "《${hit.title}》导入完成 $timings")
            if (up is UploadResult.Failed) {
                setPhase(
                    hit.id,
                    ImportState(phase = "上传失败", failed = true, message = up.message, timings = timings)
                )
                return@launch
            }
            val entryId = (up as UploadResult.Ok).entryId
            val track = trackOf(provider, kind, entryId, fileName, ok.bytes)
            // 记下来：以后点这条直接播放，不用再下一遍
            // 先把记录跟本地同步一次，免得把别处删掉的条目又写回去
            reloadImported()
            rememberImported(hit.id, kind, entryId, fileName, ok.bytes)
            setPhase(
                hit.id,
                ImportState(
                    phase = "已导入",
                    done = true,
                    message = "已存到 ${kind.shortLabel}",
                    track = track,
                    timings = timings
                )
            )
            // 顺手写进曲库索引，不等整盘扫描就能在「我的」里看到
            track?.let { appRef.repository.upsertTrack(it) }
            importedCount++
        }
    }

    /**
     * 点一条「已导入」的记录 → 直接播放它。
     *
     * @return false 表示这条没法播（网盘没连上之类），调用方退回「重新导入」
     */
    fun playImported(hit: MusicHit): Boolean {
        val t = states[hit.id]?.track ?: return false
        appRef.player.play(listOf(t), 0)
        return true
    }

    /**
     * 把已经导进网盘的那首歌删掉。
     *
     * 删的是**网盘里的文件本身**，不是只把列表里的那一行藏起来 ——
     * 用户的场景是「导错了，传上去的这份是多余的，得清掉」。
     *
     * 顺带把三处一起清干净：
     * - 曲库索引（不然曲库里还点得到一首播不了的歌）
     * - 「已导入」记录（这条要退回「导入」按钮）
     * - 正在播放的话也停掉
     */
    fun deleteImported(hit: MusicHit) {
        val st = states[hit.id] ?: return
        val track = st.track ?: return
        val rec = imported[hit.id] ?: return
        if (hit.id in deletingIds) return
        deletingIds = deletingIds + hit.id

        viewModelScope.launch {
            val kind = ProviderKind.of(rec.provider)
            val provider = providerOf(kind)
            val result = if (provider == null) {
                DeleteResult.Failed("${kind.shortLabel} 还没连上，先去设置里连一下")
            } else {
                provider.delete(track.id)
            }

            when (result) {
                is DeleteResult.Ok -> {
                    // 索引里的这条也得去掉
                    appRef.repository.deleteTrack(track.key)
                    // 同一个网盘文件可能被两条搜索结果各记了一次（比如网易云那条也指过来），
                    // 只删一条的话另一条会变成「点得进、播不出」的死记录 —— 一起清掉
                    prefs.removeImportedEntries(rec.provider, rec.entryId)
                    reloadImported()
                    message = "已从${kind.shortLabel}删除《${track.fileName}》"
                    // 正在播的就是它？停掉，免得播一个已经不存在的东西
                    if (appRef.player.currentTrack?.key == track.key) {
                        appRef.player.controller?.stop()
                    }
                }
                is DeleteResult.Failed -> {
                    setPhase(hit.id, st.copy(failed = true, phase = "删除失败", message = result.message))
                }
            }
            deletingIds = deletingIds - hit.id
        }
    }

    /**
     * 把上传结果拼成一条能播的曲目。
     *
     * 播放地址跟曲库里那批是同一套（[CloudProvider.playUrl]），
     * 所以认证、Range 请求那些都不用另写一套。
     */
    private fun trackOf(
        provider: CloudProvider,
        kind: ProviderKind,
        entryId: String,
        fileName: String,
        size: Long,
    ): Track? {
        if (entryId.isBlank()) return null
        val parsed = AudioTypes.parseTitle(fileName)
        return Track(
            key = "${kind.id}:$entryId",
            id = entryId,
            url = provider.playUrl(entryId),
            fileName = fileName,
            title = parsed.second.ifBlank { fileName },
            artist = parsed.first,
            album = dir.substringAfterLast('/').ifBlank { kind.shortLabel },
            folder = dir,
            size = size,
            mime = AudioTypes.mimeOf(fileName),
            provider = kind.id
        )
    }

    // ------------------------------------------------------- 已导入记录

    /** 记下「这条已经导进网盘了」，并把状态刷成「已导入」。 */
    private fun rememberImported(
        hitId: String,
        kind: ProviderKind,
        entryId: String,
        fileName: String,
        size: Long,
    ) {
        imported[hitId] = ImportedRecord(kind.id, entryId, fileName, dir, size)
        persistImported()
    }

    private fun persistImported() {
        val json = JSONObject()
        for ((k, v) in imported) {
            json.put(
                k,
                JSONObject().apply {
                    put("provider", v.provider)
                    put("entryId", v.entryId)
                    put("fileName", v.fileName)
                    put("dir", v.dir)
                    put("size", v.size)
                }
            )
        }
        prefs.importedTracksJson = json.toString()
    }

    /**
     * 启动时把「已导入」的记录恢复出来。
     *
     * 恢复不了的不硬来 —— 比如网盘还没连上，那就等连上再说，
     * 免得点下去报一个莫名其妙的错。
     */
    private fun readImported(): Map<String, ImportedRecord> {
        val raw = prefs.importedTracksJson
        if (raw.isBlank()) return emptyMap()
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = mutableMapOf<String, ImportedRecord>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = json.optJSONObject(k) ?: continue
            out[k] = ImportedRecord(
                provider = o.optString("provider"),
                entryId = o.optString("entryId"),
                fileName = o.optString("fileName"),
                dir = o.optString("dir"),
                size = o.optLong("size"),
            )
        }
        return out
    }

    private fun setPhase(id: String, s: ImportState) {
        states = states + (id to s)
    }

    /** 文件名用「歌手 - 歌名」，App 解析文件名时正好能拆出歌手和歌名。 */
    private fun buildFileName(hit: MusicHit, ext: String): String =
        MusicSearchRepository.buildFileName(hit.artist, hit.title, ext)

    /** 临时文件名里不能有 `:`（夸克来源的 id 是 `quark:123`），换掉。 */
    private fun safeName(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
