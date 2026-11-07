package com.cloudmusic.app.data.playlist

import com.cloudmusic.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

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

        /** 没有音源时给用户的兜底搜索链接（实测该站搜索参数是 q）。 */
        private const val FALLBACK_SEARCH = "https://so.liumingye.cn/search?q="
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

    /** 网盘里是不是已经有这首了。 */
    fun existingStatus(track: PlaylistTrack, existing: List<Track>): TrackStatus? =
        findExisting(track, existing)

    /** 没有音源时给用户的兜底搜索链接。 */
    fun fallbackUrl(track: PlaylistTrack): String {
        val kw = URLEncoder.encode("${track.title} ${track.artist}", "UTF-8")
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
