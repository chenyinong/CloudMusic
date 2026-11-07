package com.cloudmusic.app.data

import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * 这条搜索结果来自哪些源。
 *
 * 同一首歌两个源都有时会合并成一条 —— 那就是 [BOTH]，
 * 「只看网易云」和「只看夸克网盘」两个过滤都要留着它。
 */
enum class HitSource {
    NETEASE, QUARK, BOTH;

    val hasNetease: Boolean get() = this == NETEASE || this == BOTH
    val hasQuark: Boolean get() = this == QUARK || this == BOTH
}

/**
 * 搜到的一首歌。
 *
 * 现在有两个来源：
 * - **网易云**：快、准，但有版权的歌只能下到 30 秒试听片段；
 * - **夸克网盘**（经由下歌吧）：慢一些、要绕一圈，但网盘里的是完整版。
 */
data class MusicHit(
    /** 平台上的歌曲 id */
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    /** 时长（秒） */
    val durationSec: Int,
    val source: String = "网易云",
    /**
     * 已经探好的音频直链。
     *
     * 歌单导入时音源层已经把直链试出来了，走这条能省掉一次重定向。
     */
    val directUrl: String? = null,
    /**
     * 下载时要额外带的请求头。
     *
     * 夸克的直链是**会话绑定**的：URL 里的 `token=5-...` 要跟 Cookie 对得上，
     * 光拿直链去 GET 会被 CDN 用 412 拦掉。所以夸克那边要把登录 Cookie 带上。
     */
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * 音频的真实扩展名（不带点）。
     *
     * 夸克那边给的是 FLAC / WAV，存成 .mp3 的话播放器按扩展名解析会失败。
     * 搜索阶段只能按音质描述预估，下载之后才是准的。
     */
    val ext: String = "mp3",
    /**
     * 这首歌在「下歌吧 → 夸克网盘」那边的 id。
     *
     * 有它就说明网盘里也有这份资源 —— 优先走网盘直链，能拿到完整版
     * （网易云很多歌只有 30 秒试听）。
     */
    val liumingyeId: String? = null,
    /**
     * 同名的网盘资源 id（备选）。
     *
     * 夸克的结果现在会**各自独立**列出来（同一首歌网盘上可能有好几份，
     * 合成一条会把它们互相覆盖，用户就看不到另外那几份了）。
     *
     * 但网易云那条还是得有个拿完整版的机会 —— 所以把同名的网盘 id 挂上来，
     * 网易云只下得到试听时再回头找网盘。
     */
    val liumingyeIds: List<String> = emptyList(),
    /**
     * 这条来自哪些源，用来过滤结果。
     *
     * 跟 [source] 分开：[source] 是给人看的（「网易云 · 夸克网盘」），
     * 这个是给代码判断的。
     */
    val sourceKind: HitSource = HitSource.NETEASE,
)

/** 搜索结果 + 每个源的结论。 */
data class SearchResult(
    val hits: List<MusicHit>,
    /**
     * 每个源的结论，一行一个。
     *
     * 「没搜到」这种话对用户没用 —— 到底哪个源没参与、是搜不到还是
     * 没登录夸克，得说清楚。
     */
    val notes: List<String>,
)

/** 下载结果。 */
/**
 * 一次导入各阶段的耗时（毫秒）。
 *
 * 用来定位「下载慢」到底慢在哪一段：
 * - [resolveMs] 解析直链（分享详情 → 下载地址 → 探活，几个串行请求）
 * - [downloadMs] 真正传音频字节
 * - [verifyMs] 校验文件头和真实时长
 * - [uploadMs] 传到网盘
 */
data class ImportTimings(
    val resolveMs: Long = 0,
    val downloadMs: Long = 0,
    val verifyMs: Long = 0,
    val uploadMs: Long = 0,
) {
    val totalMs: Long get() = resolveMs + downloadMs + verifyMs + uploadMs

    /** 人话版：解析 2.3s · 下载 12.4s · 上传 8.1s · 共 23s */
    fun summary(): String = buildString {
        append("解析 ").append(fmtMs(resolveMs))
        append(" · 下载 ").append(fmtMs(downloadMs))
        if (verifyMs > 0) append(" · 校验 ").append(fmtMs(verifyMs))
        if (uploadMs > 0) append(" · 上传 ").append(fmtMs(uploadMs))
        append(" · 共 ").append(fmtMs(totalMs))
    }
}

private fun fmtMs(ms: Long): String =
    if (ms < 1000) "${ms}ms" else String.format("%.1fs", ms / 1000.0)

/** 从某个 nanoTime 起点算到现在的毫秒数 */
private fun elapsedMs(startNanos: Long): Long =
    (System.nanoTime() - startNanos) / 1_000_000

sealed class DownloadResult {
    /** 各阶段耗时，失败时也有（能看到卡在哪一步挂的） */
    abstract val timings: ImportTimings

    /** 拿到了完整音频 */
    data class Ok(
        val file: File,
        val bytes: Long,
        val durationSec: Int,
        /** 真实扩展名（不带点），落盘时要按它来 */
        val ext: String = "mp3",
        override val timings: ImportTimings = ImportTimings(),
    ) : DownloadResult()

    /** 只拿到试听片段（平台版权限制） */
    data class Partial(
        val file: File,
        val bytes: Long,
        val gotSec: Int,
        val wantSec: Int,
        val ext: String = "mp3",
        /** 补充说明（比如「网盘那条不通，已退回网易云」） */
        val note: String = "",
        override val timings: ImportTimings = ImportTimings(),
    ) : DownloadResult()

    data class Failed(
        val message: String,
        /** 补充说明 */
        val note: String = "",
        override val timings: ImportTimings = ImportTimings(),
    ) : DownloadResult()
}

/**
 * 在线搜歌 + 下载。
 *
 * 两个来源：
 * - **网易云**：`/api/search/get/web` 搜，`/song/media/outer/url` 下；
 * - **夸克网盘**（经由下歌吧）：网盘资源站搜到分享链接，解析成带签名的直链。
 *
 * ⚠️ 网易云有版权保护的旧歌只能下到 30 秒试听片段，下完会按文件大小反推时长做校验，
 *    明显短于标称时长就判为片段，如实告诉用户而不是塞一个残废文件进网盘。
 *    夸克那边是网盘原文件，完整版拿得到，所以两个源的结果会合并显示。
 */
class MusicSearchRepository(
    private val http: OkHttpClient,
    /**
     * 网盘源（下歌吧 → 夸克网盘）。
     *
     * 没登录夸克网盘的话这个源不参与，行为跟以前一模一样。
     */
    private val quarkSource: com.cloudmusic.app.data.playlist.LiumingyeAudioSource? = null,
) {

    companion object {
        private const val TAG = "CloudMusicImport"
        private const val HOST = "https://music.163.com"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        /**
         * PC 端 UA。
         *
         * 夸克的直链是 `dl-pc-*` 的 PC 端域名，用手机 UA 去访问
         * 行为跟 PC UA 不一样（实测踩过），所以走夸克时统一用这个。
         */
        private const val UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"

        /** 128kbps 的 mp3 大约 16KB/秒，用它粗略反推时长 */
        private const val BYTES_PER_SEC = 16000L

        /**
         * 网盘来源的 id 前缀。
         *
         * 网易云的 id 是数字、下歌吧的是长字符串，虽然撞不上，
         * 但加个前缀能一眼看出这条是哪儿来的 —— 而且「这条是不是纯网盘条目」
         * 靠它判断（纯网盘条目没有网易云可以退回）。
         */
        const val QUARK_ID_PREFIX = "quark:"

        /** 下到的时长至少要达到标称时长的这个比例才算完整 */
        private const val COMPLETE_RATIO = 0.75

        /**
         * 文件名用「歌手 - 歌名」，App 解析文件名时正好能拆出歌手和歌名。
         *
         * 跟 [com.cloudmusic.app.data.AudioTypes.parseTitle] 配套，别改格式。
         *
         * @param ext 音频真实扩展名。夸克那边给的可能是 FLAC / WAV，
         *   存成 .mp3 的话播放器按扩展名解析会失败。
         */
        fun buildFileName(artist: String, title: String, ext: String = "mp3"): String {
            val a = artist.replace("/", "、").trim().ifBlank { "未知歌手" }
            val t = title.replace("/", "、").trim().ifBlank { "未知歌曲" }
            val e = ext.trim().trimStart('.').ifBlank { "mp3" }
            return "$a - $t.$e"
        }

        /**
         * 衍生版本关键词。
         *
         * 搜「稻香 周杰伦」时网易云会返回一堆「稻香 (钢琴版) [原唱: 周杰伦]」这种，
         * 它们靠括号里的"原唱:周杰伦"蹭匹配，得压下去，
         * 否则排第一的反而是钢琴版。
         */
        private val DERIVED = listOf(
            "钢琴版", "钢琴曲", "钢琴", "纯音乐", "伴奏", "instrumental", "cover",
            "翻唱", "改编", "remix", "demo", "live", "演唱会", "现场", "type beat",
            "beat", "铃声", "背景音乐", "bgm", "影视", "剧场版", "铃声版"
        )
    }

    /** 搜索歌曲，结果按"歌名+歌手"匹配度排序。 */
    suspend fun search(keyword: String): List<MusicHit> = searchMulti(keyword).hits

    /**
     * 搜歌 + 收集每个源的结论。
     *
     * 两个源并行搜，谁先回先用谁的，合并去重之后再排序。
     */
    suspend fun searchMulti(keyword: String): SearchResult = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@withContext SearchResult(emptyList(), emptyList())

        val netease: List<MusicHit>
        val quark: List<MusicHit>
        kotlinx.coroutines.coroutineScope {
            val a = async { searchNetease(kw) }
            val b = async { searchQuark(kw) }
            netease = a.await()
            quark = b.await()
        }

        val notes = mutableListOf<String>()
        notes += "网易云：${if (netease.isEmpty()) "没搜到" else "${netease.size} 条"}"
        quarkSource?.lastNote?.takeIf { it.isNotBlank() }?.let { notes += "夸克网盘：$it" }

        // 合并两个源的结果。
        //
        // 以前是把夸克条目跟网易云条目「合成一条」，同一首歌只留一条。
        // 但同一首歌在网盘上往往有好几份（不同音质、不同来源、不同版本），
        // 一合成它们就互相覆盖 —— 搜「晴天 周杰伦」时夸克明明有 20 条，
        // 列表里只剩 4 条，用户完全看不到另外那十几份。
        //
        // 现在夸克的结果**各自独立**成一条（它本身就是最完整的选择），
        // 网易云的条目保持原样。想只看网盘就切上面的「夸克网盘」过滤。
        //
        // 同时把同名的网盘 id 挂到网易云那条上：网易云很多歌只给 30 秒试听，
        // 下载时先拿网盘的完整版（见 download 里的回退逻辑）。
        val merged = mutableListOf<MusicHit>()
        for (h in netease) merged += h
        for (h in quark) {
            merged += h
            val i = merged.indexOfFirst {
                it.sourceKind == HitSource.NETEASE && sameSong(it, h)
            }
            if (i >= 0) {
                val old = merged[i]
                if (h.liumingyeId != null && h.liumingyeId !in old.liumingyeIds) {
                    merged[i] = old.copy(liumingyeIds = old.liumingyeIds + h.liumingyeId)
                }
            }
        }

        // 用户搜的往往就是"歌名 歌手"，把对得上的排到前面
        val want = kw.split(" ", "　", "-").filter { it.isNotBlank() }
        SearchResult(merged.sortedByDescending { score(it, want) }, notes)
    }

    /** 网易云搜索。 */
    private suspend fun searchNetease(kw: String): List<MusicHit> {
        val url = "$HOST/api/search/get/web?s=${enc(kw)}&type=1&limit=25&offset=0"
        val body = get(url) ?: return emptyList()

        val list = try {
            JSONObject(body).optJSONObject("result")
                ?.optJSONArray("songs") ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        val out = mutableListOf<MusicHit>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optLong("id").takeIf { it > 0 }?.toString() ?: continue
            val title = o.optString("name").trim()
            if (title.isEmpty()) continue
            val artists = mutableListOf<String>()
            o.optJSONArray("artists")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }
                        ?.let { artists.add(it) }
                }
            }
            val album = o.optJSONObject("album")?.optString("name").orEmpty()
            val durMs = o.optLong("duration")
            out.add(
                MusicHit(
                    id = id,
                    title = title,
                    artist = artists.joinToString(" / "),
                    album = album,
                    durationSec = (durMs / 1000).toInt(),
                    source = "网易云",
                    sourceKind = HitSource.NETEASE,
                )
            )
        }
        return out
    }

    /**
     * 夸克网盘（经由下歌吧）搜索。
     *
     * 搜到的只是「网盘里有这份资源」，直链要等下载时才解析 ——
     * 解析要连着走分享链接 → 分享详情 → 下载地址 → 探活，一轮下来好几秒，
     * 搜索阶段全探一遍的话会卡得没法用。
     */
    private suspend fun searchQuark(kw: String): List<MusicHit> {
        val src = quarkSource ?: return emptyList()
        return try {
            src.searchByKeyword(kw).map { c ->
                MusicHit(
                    // 加前缀，UI 用 id 当 key，也用它区分「纯网盘条目」
                    id = "$QUARK_ID_PREFIX${c.songId}",
                    title = c.title,
                    artist = c.artist,
                    album = c.album,
                    durationSec = c.durationSec,
                    source = "夸克网盘",
                    ext = c.ext,
                    liumingyeId = c.songId,
                    sourceKind = HitSource.QUARK,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 是不是同一首歌：歌名一致（去掉括号补充）且歌手能对上。
     *
     * 两个源的歌名字段写法常常不一样（一个带「(Live)」一个不带），
     * 歌手字段也有「周杰伦」和「周杰伦 Jay Chou」的区别，所以都放宽点比。
     */
    private fun sameSong(a: MusicHit, b: MusicHit): Boolean {
        if (!com.cloudmusic.app.data.playlist.TrackMatcher.sameTitle(a.title, b.title)) return false
        val m = com.cloudmusic.app.data.playlist.TrackMatcher
        return m.artistHit(a.artist, b.artist) || m.artistHit(b.artist, a.artist)
    }

    /** 下载到本地文件。 */
    /**
     * 下载到本地文件。
     *
     * @param onProgress 下载进度回调（已下字节 / 总字节）
     */
    suspend fun download(
        hit: MusicHit,
        dest: File,
        onProgress: ((got: Long, total: Long) -> Unit)? = null,
    ): DownloadResult =
        withContext(Dispatchers.IO) {
            // 夸克网盘来源：先把分享链接解析成带签名的直链。
            // 直链是会话绑定的，解析时顺带把登录 Cookie 带上，不然 CDN 会 412。
            //
            // 解析不出来就退回网易云 —— 哪怕只能拿到 30 秒试听，也比什么都没有强。
            // 但「纯网盘条目」（网易云压根没这首歌）没有退路，只能如实报错。
            var note = ""
            // ── 阶段计时：解析直链 / 传输字节 / 校验 ──────────────
            // 「下载慢」十有八九慢在第一段（几个串行的分享请求），
            // 把三段分开计时，慢在哪一眼就看出来。
            val tResolve = System.nanoTime()
            var resolveMs = 0L
            var downloadMs = 0L
            var verifyMs = 0L

            // 网盘资源有两种来路：
            // - 这条本身就是网盘条目（liumingyeId 有值）；
            // - 这条是网易云的，但挂着同名的网盘资源（liumingyeIds）——
            //   网易云很多歌只给 30 秒试听，网盘上才是完整版。
            val cloudId = hit.liumingyeId ?: hit.liumingyeIds.firstOrNull()
            val effective = if (cloudId != null) {
                val q = resolveQuark(hit.copy(liumingyeId = cloudId))
                if (q == null) {
                    if (hit.liumingyeId != null) {
                        // 纯网盘条目：没有退路，只能如实报错
                        val why = quarkSource?.lastNote?.takeIf { it.isNotBlank() } ?: "拿不到直链"
                        resolveMs = elapsedMs(tResolve)
                        return@withContext DownloadResult.Failed(
                            "夸克网盘这条路走不通：$why",
                            timings = ImportTimings(resolveMs = resolveMs)
                        )
                    }
                    // 网易云条目：网盘拿不到就退回网易云（可能只有试听，但总比没有强）
                    note = "网盘那份拿不到直链，已退回网易云"
                    hit.copy(liumingyeId = null)
                } else {
                    q
                }
            } else {
                hit
            }

            // 带 Cookie 的就是夸克直链 —— 它的地址已经是最终地址了；
            // 网易云的 outer/url 要先跟一次重定向拿真实地址。
            val isCloud = effective.extraHeaders.containsKey("Cookie")
            val url = if (isCloud) {
                effective.directUrl?.takeIf { it.isNotBlank() }
                    ?: return@withContext DownloadResult.Failed("没有下载地址", note)
            } else {
                val apiUrl = "$HOST/song/media/outer/url?id=${effective.id}.mp3"
                // 网易云的音频 CDN 只给 http 地址，而 App 默认禁止明文流量。
                // 同一路径用 https 访问是等价的（实测确认），所以先把重定向后的真实地址取出来再升级。
                // 歌单导入时音源层已经把直链试出来了，直接用现成的，省一次重定向。
                effective.directUrl?.takeIf { it.isNotBlank() }
                    ?: (resolveAudioUrl(apiUrl) ?: apiUrl)
            }
            // 直链拿到了，这一段（含分享详情/下载地址/探活/302 升级）到此为止
            resolveMs = elapsedMs(tResolve)

            // 夸克那边对「完整请求」和「Range 请求」
            // 的处理不一样：完整请求有可能拿回来的不是音频字节（实测踩过），
            // 而 Range 请求是稳的。所以夸克直链先按 Range 姿势试，不行再换姿势。
            var lastMsg = "下载失败"
            val attempts = if (isCloud) {
                listOf(
                    Attempt(range = true, pcUa = true),
                    Attempt(range = false, pcUa = true),
                    Attempt(range = false, pcUa = false),
                )
            } else {
                listOf(Attempt(range = false, pcUa = false))
            }

            for ((i, a) in attempts.withIndex()) {
                if (i > 0) delay(900L * i)
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", if (a.pcUa) UA_PC else UA)
                    .header("Referer", if (a.pcUa) "https://pan.quark.cn/" else "$HOST/")
                    .apply { for ((k, v) in effective.extraHeaders) header(k, v) }
                    .apply { if (a.range) header("Range", "bytes=0-") }
                    .build()
                try {
                    val tBody = System.nanoTime()
                    http.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            lastMsg = when (resp.code) {
                                412 -> "被 CDN 拦了（412），多半是这次请求没带登录 Cookie"
                                403 -> "直链签名或权限有问题（403）"
                                416 -> "这个直链不支持分段下载"
                                else -> "下载失败（HTTP ${resp.code}）"
                            }
                            return@use
                        }
                        val type = resp.header("Content-Type").orEmpty()
                        if (type.contains("text/html") || type.contains("application/json")) {
                            lastMsg = "这一首拿不到音频，可能没有版权"
                            return@use
                        }
                        val body = resp.body ?: run {
                            lastMsg = "没有内容"
                            return@use
                        }
                        dest.parentFile?.mkdirs()
                        // 自己拷贝是为了报进度。copyTo 复制完了才知道多大，
                        // 中间是黑箱 —— 用户只看到界面不动。
                        val total = body.contentLength()
                        var got = 0L
                        body.byteStream().use { input ->
                            dest.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    got += n
                                    if (onProgress != null) {
                                        // 每 5% 报一次
                                        val step = if (total > 0) (total / 20).coerceAtLeast(64 * 1024L)
                                        else 1024L * 1024L
                                        if (got % step < n) onProgress(got, total)
                                    }
                                }
                            }
                        }
                        val tVerify = System.nanoTime()
                        val v = verify(effective, dest, resp)
                        verifyMs += elapsedMs(tVerify)
                        downloadMs += elapsedMs(tBody)
                        val timings = ImportTimings(resolveMs, downloadMs, verifyMs)
                        Log.i(TAG, "《${effective.title}》 $timings")
                        if (v is DownloadResult.Failed && v.message.contains("不是音频")) {
                            // 内容不对，换下一种请求姿势再试
                            lastMsg = v.message
                            dest.delete()
                            return@use
                        }
                        if (v is DownloadResult.Partial && note.isNotBlank()) {
                            return@withContext v.copy(note = note, timings = timings)
                        }
                        return@withContext when (v) {
                            is DownloadResult.Ok -> v.copy(timings = timings)
                            is DownloadResult.Partial -> v.copy(timings = timings)
                            is DownloadResult.Failed -> v.copy(timings = timings)
                        }
                    }
                } catch (e: Exception) {
                    lastMsg = e.message ?: "网络异常"
                }
            }
            dest.delete()
            DownloadResult.Failed(
                lastMsg,
                note,
                timings = ImportTimings(resolveMs, downloadMs, verifyMs)
            )
        }

    /**
     * 把「下歌吧 → 夸克网盘」这条链路走完，拿到带签名的直链。
     *
     * 分享链接 → 分享详情 → 下载地址 → 探活，四步。
     * 直链是**会话绑定**的：URL 里那个 token 要跟登录 Cookie 对得上，
     * 光拿直链去 GET 会被 CDN 用 412 拦掉，所以 Cookie 一起带上。
     */
    private suspend fun resolveQuark(hit: MusicHit): MusicHit? {
        val src = quarkSource ?: return null
        val id = hit.liumingyeId ?: return null
        val cand = com.cloudmusic.app.data.playlist.AudioCandidate(
            sourceId = "liumingye:$id",
            sourceLabel = "夸克网盘",
            songId = id,
            title = hit.title,
            artist = hit.artist,
            album = hit.album,
            durationSec = hit.durationSec,
        )
        val resolved = try {
            src.resolve(cand)
        } catch (e: Exception) {
            null
        } ?: return null
        return hit.copy(
            directUrl = resolved.audioUrl,
            extraHeaders = resolved.extraHeaders,
            ext = resolved.ext,
            // 夸克那边的真实时长 —— 判定是不是完整版要用它
            durationSec = resolved.durationSec,
        )
    }

    /** 一次请求的姿势：要不要走 Range、用不用 PC 端 UA。 */
    private data class Attempt(val range: Boolean, val pcUa: Boolean)

    /**
     * 取出音频的真实地址，并把 http 升级成 https。
     *
     * `outer/url` 会 302 到 CDN（m*.music.126.net），那个地址只有 http 版本。
     * OkHttp 跟随重定向时**不会**再走 application interceptor，
     * 所以不能靠拦截器统一升级，只能在这里手动把地址换掉。
     *
     * @return 升级后的地址；拿不到就返回 null（表示直接用原地址）
     */
    private fun resolveAudioUrl(apiUrl: String): String? {
        val noRedirect = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", UA)
            .header("Referer", "$HOST/")
            .build()
        return try {
            noRedirect.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) return null
                val loc = resp.header("Location")
                if (loc.isNullOrBlank()) return null
                if (loc.startsWith("http://")) {
                    "https://" + loc.substring(7)
                } else {
                    loc
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 校验下载到的是不是完整音频。 */
    private fun verify(hit: MusicHit, dest: File, resp: Response? = null): DownloadResult {
        val bytes = dest.length()
        if (bytes < 64 * 1024) {
            dest.delete()
            return DownloadResult.Failed("下载到的内容太小，不是完整音频")
        }

        // 校验是不是音频（魔数规则跟探测直链那边共用一套，别再各写各的）
        if (!AudioMagic.looksLikeAudio(dest)) {
            // 把证据都带上：文件头 + 响应头。
            // 只说「不是音频」没法定位，得看出到底是 HTML 错误页还是压缩流。
            val head = AudioMagic.hexOf(AudioMagic.readHead(dest)).take(12)
            dest.delete()
            val info = resp?.let {
                " · ${it.code} ${it.header("Content-Type") ?: "?"}" +
                    " len=${it.header("Content-Length") ?: "?"}" +
                    " enc=${it.header("Content-Encoding") ?: "无"}"
            } ?: ""
            return DownloadResult.Failed("拿到的不是音频文件（文件头 $head$info）")
        }

        val gotSec = readDuration(dest) ?: (bytes / BYTES_PER_SEC).toInt()
        if (hit.durationSec > 0 && gotSec < (hit.durationSec * COMPLETE_RATIO).toInt()) {
            return DownloadResult.Partial(dest, bytes, gotSec, hit.durationSec, hit.ext)
        }
        return DownloadResult.Ok(dest, bytes, gotSec, hit.ext)
    }

    /**
     * 用系统解码器读出音频的真实时长。
     *
     * 比按文件大小估算准得多 —— 同一个 250 秒的歌，96kbps 和 320kbps 的文件大小差三倍，
     * 估算会把低码率误判成「只有试听片段」。
     * 顺带也验证了这文件确实能被解码，而不只是看文件头魔数。
     */
    private fun readDuration(file: File): Int? = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
        } finally {
            mmr.release()
        }
    } catch (e: Exception) {
        null
    }

    /** 匹配打分：歌名/歌手和关键词对得上就加分，衍生版本（翻唱/钢琴版/remix）降权。 */
    private fun score(hit: MusicHit, want: List<String>): Int {
        if (want.isEmpty()) return 0
        val title = norm(hit.title)
        val bare = norm(stripBrackets(hit.title))
        val artist = norm(hit.artist)
        var s = 0

        for (w in want) {
            val n = norm(w)
            if (n.isEmpty()) continue
            when {
                title == n -> s += 10
                // 「稻香 (钢琴版) [原唱: 周杰伦]」去掉括号后正好是歌名
                bare == n -> s += 7
                title.contains(n) || n.contains(title) -> s += 5
                else -> continue
            }
            if (artist.contains(n)) s += 8
        }

        // 衍生版本降权：这些不是原唱，导入进去也没意思
        val raw = hit.title + " " + hit.artist
        for (k in DERIVED) {
            if (raw.contains(k)) {
                s -= 8
                break
            }
        }
        // 歌手名里带着歌名，多半是「XX"稻香"beat」这种二创
        if (want.isNotEmpty() && artist.contains(norm(want.first()))) s -= 6
        // 专辑名和歌名一样 → 通常是正版专辑里的原唱（翻唱一般挂在别的专辑下）
        if (hit.album.isNotBlank() && norm(hit.album) == title) s += 5

        return s
    }

    /** 去掉括号里的补充说明：「稻香 (钢琴版) [原唱: 周杰伦]」→「稻香」 */
    private fun stripBrackets(s: String): String =
        s.replace(Regex("[（(【\\[].*?[)）】\\]]"), " ").trim()

    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) if (c.isLetterOrDigit()) sb.append(c)
        return sb.toString()
    }

    private fun get(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "$HOST/")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
