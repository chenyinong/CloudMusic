package com.cloudmusic.app.data.playlist

import android.util.Log
import com.cloudmusic.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** 在线解析没成功时，卡在哪一步（给用户看的分类，不是异常堆栈）。 */
enum class OnlineFailReason {
    /** 几个音源连同名都没搜到 */
    NO_SAME_TITLE,

    /** 搜到同名了，但歌手全不一致，放宽之后也没拿到直链 */
    ARTIST_MISMATCH,

    /** 同名同歌手有，但直链全探测失败 */
    NO_DIRECT_URL,

    /** 音源请求全挂了 */
    SOURCE_ERROR,
}

/** 在线解析结论：拿到直链，或者一个能说清楚的失败原因。 */
data class OnlineResolution(
    val candidate: AudioCandidate? = null,
    val reason: OnlineFailReason? = null,
    /** 命中的是「同名异歌手」的放宽候选，日志和 UI 要能区分 */
    val relaxed: Boolean = false,
    /** 所有源都只有试听片段，这个是兜底返回的 —— UI 要提示用户 */
    val partialOnly: Boolean = false,
)

/**
 * 歌单导入的编排：拉清单 → 比对曲库 → 找音源 → 给出每首歌的处理方案。
 *
 * 匹配口径卡得很死，因为实测两个源都会返回一堆「同名不同歌」：
 * 酷我搜「热爱 郑钧」第一条是「渐渐被你吸引」，网易云搜「青春 筠子」能翻出
 * 一堆别的歌的青春。歌名 + 歌手 + 时长三项都得对得上才算命中。
 */
class PlaylistImportService(
    private val playlists: PlaylistRepository,
    private val sources: List<AudioSource>,
    /**
     * 本机夸克 Cookie。
     *
     * 夸克直链是会话绑定的：Cookie 一换，缓存里的旧直链就废了（CDN 412），
     * 所以缓存的 key 里要带上 Cookie 的摘要。
     */
    private val quarkCookie: () -> String = { "" },
) {

    companion object {
        /** 时长差在这个范围内算同一首。 */
        private const val DURATION_TOLERANCE = 25

        /** 每个源最多探测几个候选的直链，避免一首歌发几十个请求。 */
        private const val MAX_RESOLVE_PER_SOURCE = 3

        /**
         * liumingye 单独放宽。
         *
         * 它一个歌名会返回好几条分享（同一首歌的 MP3/FLAC/WAV 各一条），
         * 排在前面��条经常是失效的或者只有 38 秒片段，
         * 试少了会白白丢掉后面能用的完整版。
         */
        private const val MAX_RESOLVE_LIUMINGYE = 6

        /** 放宽匹配每个源最多再试几个候选，别让一首歌发十几个请求。 */
        private const val MAX_RELAXED_PER_SOURCE = 3

        /**
         * 歌单没给时长时，用绝对时长兜底判片段：正常单曲不会短于 45 秒，
         * 30 秒上下的基本都是平台试听片段。
         */
        private const val ABSOLUTE_PARTIAL_MAX_SEC = 45

        /** 没有音源时给用户的兜底搜索链接（实测该站搜索参数是 q）。 */
        private const val FALLBACK_SEARCH = "https://so.liumingye.cn/search?q="

        /**
         * 解析成功的缓存留多久（毫秒）。
         *
         * 直链会过期（还绑着 Cookie），不敢给太长，30 分钟够覆盖
         * 「在歌单里来回点、上一首下一首」这种用法。
         */
        private const val RESOLVE_CACHE_TTL = 30 * 60 * 1000L

        /**
         * 失败的解析也缓存一小会儿。
         *
         * 解析不出来的歌反复重试只是白等，3 分钟够了。
         */
        private const val RESOLVE_FAIL_TTL = 3 * 60 * 1000L

        /** 缓存最多留多少条，超了整体清掉。 */
        private const val RESOLVE_CACHE_MAX = 200
    }

    /**
     * 解析结果缓存。
     *
     * 同一首歌反复切（歌单里来回点、上一首下一首、这张单和那张单里都有）
     * 不该每次都重新搜源 + 重新签发直链 —— 那是切歌慢的大头。
     *
     * 这是 App 级单例上的缓存，所以本机歌单、云端歌单、在线混播全都共用一份。
     */
    private val resolveCache = ConcurrentHashMap<String, CachedResolve>()

    private class CachedResolve(
        val at: Long,
        val resolution: OnlineResolution,
        /**
         * 这一条是给哪首歌解析的（歌名 + 歌手，规范化后）。
         *
         * 直链失效时要按它把缓存删掉 —— 缓存 key 里还有时长和平台 id，
         * 而播放失败时手里只有解析结果那一份信息，对不上。
         */
        val subject: String,
    )

    /**
     * 缓存的 key：歌名 + 歌手 + 时长 + 平台 id + 夸克 Cookie 摘要。
     *
     * Cookie 摘要必须带上 —— 换了 Cookie 之后旧直链直接 412，
     * 不隔离开的话用户会看到「刚才还能播的歌突然播不了」。
     */
    private fun cacheKey(track: PlaylistTrack): String = listOf(
        track.title.trim().lowercase(),
        track.artist.trim().lowercase(),
        track.durationSec.toString(),
        track.platformId.orEmpty(),
        quarkCookie().trim().hashCode().toString(),
    ).joinToString("|")

    /** 一首歌的「主体」：规范化后的歌名 + 歌手。失效时按它找缓存。 */
    private fun subjectOf(title: String, artist: String): String =
        TrackMatcher.norm(TrackMatcher.stripBrackets(title)).lowercase() + "|" +
            TrackMatcher.norm(TrackMatcher.stripBrackets(artist)).lowercase()

    /** 缓存还在有效期内就返回，过期的顺手清掉。 */
    private fun cachedResolve(key: String): OnlineResolution? {
        val hit = resolveCache[key] ?: return null
        val ttl = if (hit.resolution.candidate != null) RESOLVE_CACHE_TTL else RESOLVE_FAIL_TTL
        if (System.currentTimeMillis() - hit.at > ttl) {
            resolveCache.remove(key)
            return null
        }
        return hit.resolution
    }

    private fun cacheResolve(key: String, res: OnlineResolution, subject: String) {
        if (resolveCache.size >= RESOLVE_CACHE_MAX) resolveCache.clear()
        resolveCache[key] = CachedResolve(System.currentTimeMillis(), res, subject)
    }

    /**
     * 作废某一首的解析缓存。
     *
     * 直链会过期（还绑着夸克 Cookie），播放报 412 / 打不开的时候，
     * 把这一首从缓存里清掉 —— 下次解析就会重新搜源、重新签发直链。
     */
    fun invalidateCached(title: String, artist: String) {
        val subject = subjectOf(title, artist)
        val removed = resolveCache.entries.removeIf { it.value.subject == subject }
        if (removed) Log.d("CM/Resolve", "「$title」直链可能失效，清掉解析缓存")
    }

    suspend fun fetchPlaylist(ref: PlaylistRef): ImportedPlaylist = playlists.fetch(ref)

    /** 认出歌单。短链要先跟一次重定向。 */
    suspend fun resolveRef(input: String): PlaylistRef? = playlists.resolveRef(input)

    suspend fun analyze(playlist: ImportedPlaylist, existing: List<Track>): List<TrackPlan> =
        withContext(Dispatchers.IO) {
            playlist.tracks.map { analyzeOne(it, existing) }
        }

    /**
     * 只分析一首歌。
     *
     * 歌单详情页里点单曲「导入」时用 —— 整张单几百首，
     * 一次全分析既慢又没意义，用户只想听其中一首。
     */
    suspend fun analyzeTrack(track: PlaylistTrack, existing: List<Track>): TrackPlan =
        withContext(Dispatchers.IO) { analyzeOne(track, existing) }

    /**
     * 歌单「在线播放」用：只解析出一条能直接拉的音频，不下载、不写曲库。
     *
     * 夸克登录时把网盘源提到队首。匹配分两遍：
     * 1. 先用「同名 + 同歌手」的严格口径把所有源试一遍；
     * 2. 严格全灭才放宽到「歌名完全一致 + 时长不冲突 + 不是衍生版」。
     *
     * 为什么要放宽：儿歌 / 翻唱在歌单平台和音源站的歌手标注经常对不上。
     * 实测「清早听到公鸡叫」歌单写「宝宝巴士」，三个音源 48 条候选里
     * 歌名全对得上，歌手却是「彭野新儿歌 / 段丽阳 / 群星 …」，
     * 严格口径把 48 条全刷掉，连取直链都没试就报「没找到可用音源」。
     *
     * 放宽**只用于在线试听**：导入入库仍走 [findCandidates] 的严格口径，
     * 不会因为一次试听把别的演唱者的版本下进网盘。
     */
    suspend fun resolveForOnlinePlay(
        track: PlaylistTrack,
        quarkFirst: Boolean,
    ): OnlineResolution = withContext(Dispatchers.IO) {
        val quark = sources.filter { it.id == "liumingye" }
        val public = sources.filter { it.id != "liumingye" }
        val ordered = if (quarkFirst) quark + public else public
        Log.d(
            "CM/Resolve",
            "开始「${track.title}」/「${track.artist}」 quarkFirst=$quarkFirst " +
                "源=${ordered.joinToString(",") { it.id }} " +
                "关键词=${SearchKeywords.forTrack(track.title, track.artist)}"
        )

        // 缓存命中就直接返回：同一首歌反复切，不该每次都重新搜源 + 重新签发直链
        val key = cacheKey(track)
        cachedResolve(key)?.let {
            Log.d("CM/Resolve", "「${track.title}」命中解析缓存")
            return@withContext it
        }

        val targetTag = TrackMatcher.variantTag(track.title, track.artist)
        val strictBySource = LinkedHashMap<String, List<AudioCandidate>>()
        val relaxedBySource = LinkedHashMap<String, List<AudioCandidate>>()

        // 各源并行搜索。
        //
        // 原来是串行的：夸克不中才查网易云，网易云不中才查酷我，
        // 等待时间一层层叠加，点一首歌要等好几秒（网页版那边早就改成并行了）。
        // 现在三个源同时搜，等最慢的那个就行；
        // 下面的探测顺序不变（夸克优先），所以「夸克拿到完整版立刻返回」
        // 这个老规矩还留着。
        val searched = coroutineScope {
            ordered.map { source -> async { source to searchCandidates(source, track) } }.awaitAll()
        }
        for ((source, raw) in searched) {
            val strict = raw.filter {
                TrackMatcher.sameTitle(it.title, track.title) &&
                    TrackMatcher.artistHit(track.artist, it.artist)
            }.take(resolveCap(source))
            // 只在「严格全灭」时兜底用；时长有一边未知就先放行，
            // 下歌吧搜索结果不带时长（0），卡死它等于把这个源整个排除掉。
            val relaxed = raw.filter {
                TrackMatcher.sameTitle(it.title, track.title) &&
                    !TrackMatcher.artistHit(track.artist, it.artist) &&
                    durationUnknownOrClose(it.durationSec, track.durationSec) &&
                    (it.variantTag == null || it.variantTag == targetTag)
            }.take(MAX_RELAXED_PER_SOURCE)
            strictBySource[source.id] = strict
            relaxedBySource[source.id] = relaxed
            Log.d(
                "CM/Resolve",
                "[${source.id}] 搜到 ${raw.size} 条 / 同名同歌手 ${strict.size} 条 / " +
                    "同名异歌手 ${relaxed.size} 条；${source.lastNote}"
            )
        }

        // 片段只记下来、不当成结果返回：
        // 实测《野子 (Live)》第一条命中的就是 27 秒的 aac，以前一命中直接返回，
        // 后面 5 条同名同歌手候选和另外两个源全都不试，播出来就是 30 秒试听。
        var partialHit: AudioCandidate? = null

        // 两遍走完才放宽：宁可先让别的源用严格口径命中，也别一上来就播翻唱
        for (pass in 0..1) {
            val bySource = if (pass == 0) strictBySource else relaxedBySource
            for (source in ordered) {
                for (c in bySource[source.id].orEmpty()) {
                    val hit = tryResolve(source, c) ?: continue
                    if (isPartialHit(hit, track)) {
                        if (partialHit == null) {
                            partialHit = hit
                            Log.d(
                                "CM/Resolve",
                                "「${track.title}」[${source.id}] 只有试听片段 " +
                                    "${hit.durationSec}s（目标 ${track.durationSec}s），继续找完整版"
                            )
                        }
                        continue
                    }
                    if (pass == 1) {
                        Log.d("CM/Resolve", "放宽匹配命中「${c.title}」-「${c.artist}」")
                    }
                    val res = OnlineResolution(hit, relaxed = pass == 1)
                    cacheResolve(key, res, subjectOf(track.title, track.artist))
                    return@withContext res
                }
            }
        }

        // 所有源都只有片段才拿第一个兜底，并明确告诉上层「这只是片段」
        partialHit?.let {
            Log.w("CM/Resolve", "「${track.title}」所有源都只有试听片段")
            val res = OnlineResolution(it, partialOnly = true)
            cacheResolve(key, res, subjectOf(track.title, track.artist))
            return@withContext res
        }

        val reason = when {
            strictBySource.isEmpty() && relaxedBySource.isEmpty() -> OnlineFailReason.SOURCE_ERROR
            strictBySource.values.any { it.isNotEmpty() } -> OnlineFailReason.NO_DIRECT_URL
            relaxedBySource.values.any { it.isNotEmpty() } -> OnlineFailReason.ARTIST_MISMATCH
            else -> OnlineFailReason.NO_SAME_TITLE
        }
        Log.w("CM/Resolve", "「${track.title}」所有源都没拿到直链：$reason")
        val failed = OnlineResolution(reason = reason)
        cacheResolve(key, failed, subjectOf(track.title, track.artist))
        failed
    }

    /**
     * 一个源搜出来的候选。
     *
     * 单独抽出来是为了能并行跑 —— 原来三个源是一个搜完再搜下一个，
     * 等待时间一层层叠加。
     */
    private suspend fun searchCandidates(
        source: AudioSource,
        track: PlaylistTrack,
    ): List<AudioCandidate> = try {
        when {
            // 网易云歌单自带歌曲 id，不用再搜一遍
            source.id == "netease" && track.platformId != null -> listOf(
                AudioCandidate(
                    sourceId = "netease",
                    sourceLabel = "网易云",
                    songId = track.platformId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationSec = track.durationSec,
                    variantTag = TrackMatcher.variantTag(track.title, track.artist),
                )
            )
            else -> source.search(track.title, track.artist, track.durationSec)
        }
    } catch (e: Exception) {
        // 一个源挂了就换下一个，不能连累整首歌
        Log.w("CM/Resolve", "[${source.id}] 整源搜索失败：${e.message}", e)
        emptyList()
    }

    /**
     * 这条直链是不是只有试听片段。
     *
     * 各源 resolve 后会带真实时长：网易云/酷我用 HEAD 长度标 partial，
     * 下歌吧按歌单目标时长比；这里再用 tooShort 兜一层，
     * 免得某个源没标就把 27 秒的片段当完整版返回。
     */
    private fun isPartialHit(c: AudioCandidate, track: PlaylistTrack): Boolean {
        if (c.partial) return true
        if (TrackMatcher.tooShort(c.durationSec, track.durationSec)) return true
        // 歌单没带时长时 tooShort 判不了，用绝对时长兜底
        return track.durationSec <= 0 && c.durationSec in 1..ABSOLUTE_PARTIAL_MAX_SEC
    }

    /** 探测一个候选的直链，顺手把每一步的失败原因落日志。 */
    private suspend fun tryResolve(source: AudioSource, c: AudioCandidate): AudioCandidate? {
        val r = try {
            source.resolve(c)
        } catch (e: Exception) {
            Log.w("CM/Resolve", "[${source.id}] ${c.songId} 取直链异常：${e.message}")
            return null
        }
        if (r == null) {
            Log.d("CM/Resolve", "[${source.id}] ${c.songId} 取不到直链：${source.lastNote}")
            return null
        }
        if (r.audioUrl.isBlank()) {
            Log.d("CM/Resolve", "[${source.id}] ${c.songId} 直链为空：${source.lastNote}")
            return null
        }
        Log.d("CM/Resolve", "[${source.id}] 命中 ${c.songId}：${source.lastNote}")
        return r
    }

    private fun resolveCap(source: AudioSource): Int =
        if (source.id == "liumingye") MAX_RESOLVE_LIUMINGYE else MAX_RESOLVE_PER_SOURCE

    /**
     * 时长能不能对上。有一边没报时长（下歌吧搜索结果就是 0）时不能
     * 直接判不等 —— 得等 resolve 之后才知道真实时长，这里先放行。
     */
    private fun durationUnknownOrClose(candidateSec: Int, targetSec: Int): Boolean {
        if (candidateSec <= 0 || targetSec <= 0) return true
        return TrackMatcher.durationClose(candidateSec, targetSec, DURATION_TOLERANCE)
    }

    /** 网盘里是不是已经有这首了。 */
    fun existingStatus(track: PlaylistTrack, existing: List<Track>): TrackStatus? =
        findExisting(track, existing)

    /** 没有音源时给用户的兜底搜索链接。 */
    fun fallbackUrl(track: PlaylistTrack): String {
        val kw = URLEncoder.encode(SearchKeywords.fallback(track.title, track.artist), "UTF-8")
        return FALLBACK_SEARCH + kw
    }

    private suspend fun analyzeOne(track: PlaylistTrack, existing: List<Track>): TrackPlan {
        // 1) 已经在网盘里了就不用折腾
        val existingStatus = findExisting(track, existing)
        if (existingStatus != null) return TrackPlan(track, existingStatus)

        // 2) 找音源
        val found = findCandidates(track)
        val resolved = found.candidates
        val notes = found.notes
        if (resolved.isEmpty()) {
            return TrackPlan(
                track,
                TrackStatus.NO_SOURCE,
                fallbackUrl = fallbackUrl(track),
                notes = notes,
            )
        }

        val targetTag = TrackMatcher.variantTag(track.title, track.artist)

        // 版本一致且是完整文件 —— 这是最理想的情况
        val ready = resolved.firstOrNull { isStrict(it.candidate, track, targetTag) }
        if (ready != null) {
            return TrackPlan(
                track = track,
                status = TrackStatus.READY,
                candidate = ready.candidate,
                alternatives = resolved.map { it.candidate }.filter { it !== ready.candidate },
                notes = notes,
            )
        }

        // 只能下到衍生版 —— 不自动入库，交给用户点头。
        // 这个判断要排在片段前面：衍生版虽然版本不对，好歹是完整文件；
        // 30 秒试听比错版本更没用。
        val variant = resolved.firstOrNull { isVariant(it.candidate, track, targetTag) }
        if (variant != null) {
            return TrackPlan(
                track = track,
                status = TrackStatus.VARIANT,
                candidate = variant.candidate,
                alternatives = resolved.map { it.candidate }.filter { it !== variant.candidate },
                notes = notes,
            )
        }

        // 只拿得到试听片段
        val partial = resolved.firstOrNull { isPartial(it.candidate, track) }
        if (partial != null) {
            return TrackPlan(
                track = track,
                status = TrackStatus.PARTIAL,
                candidate = partial.candidate,
                alternatives = resolved.map { it.candidate }.filter { it !== partial.candidate },
                fallbackUrl = fallbackUrl(track),
                notes = notes,
            )
        }

        return TrackPlan(track, TrackStatus.NO_SOURCE, fallbackUrl = fallbackUrl(track), notes = notes)
    }

    /**
     * 判断网盘里是不是已经有了。
     *
     * 歌名和歌手都对上 = 确定已有；只对上歌名 = 可能是同名歌，标成「疑似已有」让人看一眼。
     */
    private fun findExisting(track: PlaylistTrack, existing: List<Track>): TrackStatus? {
        var fuzzy = false
        for (t in existing) {
            if (!TrackMatcher.sameTitle(track.title, t.title)) continue
            if (TrackMatcher.artistHit(track.artist, t.artist)) return TrackStatus.EXISTING
            fuzzy = true
        }
        return if (fuzzy) TrackStatus.EXISTING_FUZZY else null
    }

    /** 搜 + 探测直链，把命中的候选收回来。 */
    private suspend fun findCandidates(track: PlaylistTrack): Found {
        val out = mutableListOf<Resolved>()
        val notes = mutableListOf<String>()
        for (source in sources) {
            val raw = try {
                when {
                    // 网易云歌单本身就带歌曲 id，不用再搜，直接打直链
                    source.id == "netease" && track.platformId != null -> listOf(
                        AudioCandidate(
                            sourceId = "netease",
                            sourceLabel = "网易云",
                            songId = track.platformId,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            durationSec = track.durationSec,
                            variantTag = TrackMatcher.variantTag(track.title, track.artist)
                        )
                    )
                    else -> source.search(track.title, track.artist, track.durationSec)
                }
            } catch (e: Exception) {
                // 一个源挂了就跳过它，不能连累整首歌
                notes.add("${source.label}：${e.message?.take(40) ?: "请求失败"}")
                continue
            }

            val matched = raw.filter {
                TrackMatcher.sameTitle(it.title, track.title) &&
                    TrackMatcher.artistHit(track.artist, it.artist)
            }.take(if (source.id == "liumingye") MAX_RESOLVE_LIUMINGYE else MAX_RESOLVE_PER_SOURCE)

            if (matched.isEmpty()) {
                notes.add(
                    if (raw.isEmpty()) "${source.label}：${source.lastNote.ifBlank { "没搜到" }}"
                    else "${source.label}：搜到 ${raw.size} 条候选，但没有同名同歌手的"
                )
                continue
            }

            val targetTag = TrackMatcher.variantTag(track.title, track.artist)
            var hit = false
            for (c in matched) {
                val r = try {
                    source.resolve(c)
                } catch (e: Exception) {
                    notes.add("${source.label}：${e.message?.take(40) ?: "解析失败"}")
                    null
                } ?: continue
                hit = true
                out.add(Resolved(source, r))
                // 已经拿到完全匹配的版本，后面那些慢源（酷我、下歌吧）就不用再查了
                if (isStrict(r, track, targetTag)) {
                    notes.add("${source.label}：${source.lastNote.ifBlank { "拿到完整版" }}")
                    return Found(out, notes)
                }
            }
            notes.add(
                "${source.label}：" + (
                    if (hit) source.lastNote.ifBlank { "试了 ${matched.size} 个候选" }
                    else "试了 ${matched.size} 个候选，都拿不到"
                    )
            )
        }
        return Found(out, notes)
    }

    /** 版本一致、时长对得上、还是完整文件。 */
    private fun isStrict(c: AudioCandidate, track: PlaylistTrack, targetTag: String?): Boolean {
        if (c.partial) return false
        if (!TrackMatcher.sameTitle(c.title, track.title)) return false
        if (!TrackMatcher.artistHit(track.artist, c.artist)) return false
        if (!TrackMatcher.durationClose(c.durationSec, track.durationSec, DURATION_TOLERANCE)) return false
        if (TrackMatcher.tooShort(c.durationSec, track.durationSec)) return false
        // 候选是正片就接受；候选带版本标签时，必须跟歌单要的版本对得上
        return c.variantTag == null || c.variantTag == targetTag
    }

    /** 歌名歌手对得上，但版本不一样，而且文件是完整的。 */
    private fun isVariant(c: AudioCandidate, track: PlaylistTrack, targetTag: String?): Boolean {
        if (c.partial) return false
        if (!TrackMatcher.sameTitle(c.title, track.title)) return false
        if (!TrackMatcher.artistHit(track.artist, c.artist)) return false
        if (TrackMatcher.tooShort(c.durationSec, track.durationSec)) return false
        return c.variantTag != null && c.variantTag != targetTag
    }

    /** 只剩下试听片段了。 */
    private fun isPartial(c: AudioCandidate, track: PlaylistTrack): Boolean {
        if (!TrackMatcher.sameTitle(c.title, track.title)) return false
        if (!TrackMatcher.artistHit(track.artist, c.artist)) return false
        return c.partial || TrackMatcher.tooShort(c.durationSec, track.durationSec)
    }

    private data class Resolved(val source: AudioSource, val candidate: AudioCandidate)

    /** 候选 + 每个源的结论（给用户看清楚到底卡在哪一步）。 */
    private data class Found(
        val candidates: List<Resolved>,
        val notes: List<String>,
    )
}
