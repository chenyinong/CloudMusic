package com.cloudmusic.app.data.playlist

import com.cloudmusic.app.data.quark.QuarkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * liumingye（下歌吧）—— 第三个音源。
 *
 * 网易云、酷我都下不到的歌，这里能搜到（它是网盘资源站，曲库比那两个全）。
 * 详情接口还会白送一份**完整歌词**。
 *
 * 代价是要绕一圈：
 * ```
 * liumingye 搜索 → 详情拿到夸克分享链接
 *                → 夸克三段式解析出带签名的直链
 *                → 下载时必须带登录 Cookie，否则 CDN 412
 * ```
 * 所以它排在最后当兜底，而且没登录夸克就当没有这个源。
 */
class LiumingyeAudioSource(
    private val liumingye: LiumingyeSource,
    private val quark: QuarkClient,
    /** 夸克登录 cookie */
    private val cookieProvider: () -> String,
) : AudioSource {

    override val id = "liumingye"
    override val label = "下歌吧"

    @Volatile
    override var lastNote: String = ""
        private set

    /** 没登录夸克就下不了，这个源直接不参与。 */
    private val available: Boolean get() = cookieProvider().contains("__pus")

    override suspend fun search(
        title: String,
        artist: String,
        durationSec: Int,
    ): List<AudioCandidate> = withContext(Dispatchers.IO) {
        if (!available) {
            lastNote = "没登录夸克网盘，这个源没参与"
            return@withContext emptyList()
        }
        val keywords = listOf("$title $artist", title)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val merged = collect(keywords)
        merged.sortedByDescending { rank(it, title, artist, durationSec) }
    }

    /**
     * 按关键词搜 —— 「在线搜歌」页面用。
     *
     * 跟 [search] 的区别：那边是「已知歌名 + 歌手」精确找，
     * 这边只有用户随手敲的几个字，没有时长之类的约束。
     *
     * 还有一点：**搜索本身不需要登录夸克**（下歌吧的搜索接口是公开的），
     * 只有取直链、下载才需要。所以没登录也照样搜得到，
     * 只是告诉用户「下载得先登录夸克网盘」，而不是把这个源整个藏起来。
     */
    suspend fun searchByKeyword(keyword: String): List<AudioCandidate> =
        withContext(Dispatchers.IO) {
            val out = collect(listOf(keyword.trim()).filter { it.isNotEmpty() })
            if (out.isNotEmpty() && !available) {
                lastNote = "搜到 ${out.size} 条（下载要登录夸克网盘）"
            }
            out
        }

    /** 挨个关键词搜，同一个 id 只留一条。 */
    private suspend fun collect(keywords: List<String>): List<AudioCandidate> {
        val merged = LinkedHashMap<String, AudioCandidate>()
        for (kw in keywords) {
            for (h in liumingye.search(kw)) {
                if (h.id.isBlank() || merged.containsKey(h.id)) continue
                merged[h.id] = AudioCandidate(
                    sourceId = "liumingye:${h.id}",
                    sourceLabel = label,
                    songId = h.id,
                    title = h.title,
                    artist = h.artist,
                    album = h.album,
                    // 搜索结果不带时长，resolve 之后能从夸克那边拿到真实的
                    durationSec = 0,
                    variantTag = TrackMatcher.variantTag(h.title, h.artist),
                    // 音质描述里能看出是什么格式，先记个大概，resolve 之后才是准的
                    ext = extFromQuality(h.quality),
                )
            }
        }
        lastNote = if (merged.isEmpty()) "没搜到" else "搜到 ${merged.size} 条"
        return merged.values.toList()
    }

    override suspend fun resolve(candidate: AudioCandidate): AudioCandidate? =
        withContext(Dispatchers.IO) {
            if (!available) {
                lastNote = "没登录夸克网盘"
                return@withContext null
            }
            val hitId = candidate.songId
            val detail = try {
                liumingye.detail(hitId)
            } catch (e: Exception) {
                lastNote = "详情接口失败"
                return@withContext null
            }

            // 挑夸克分享链接（百度云要 BDUSS，解析不了）
            val shares = detail.downloads.filter { it.url.contains("pan.quark.cn") }
            if (shares.isEmpty()) {
                lastNote = "这条没有夸克分享"
                return@withContext null
            }

            var why = "分享里没有音频文件"
            // 一条歌可能有多个分享（MP3 / FLAC / WAV），挨个试，
            // 第一个分享失效（文件名是数字、分享已过期）不能就此放弃。
            for (share in shares) {
                val pwdId = QuarkClient.pwdIdOf(share.url) ?: continue
                val token = try {
                    quark.shareToken(pwdId)
                } catch (e: Exception) {
                    why = "分享链接打不开"
                    continue
                }
                val files = try {
                    quark.shareDetail(pwdId, token.stoken)
                } catch (e: Exception) {
                    why = "分享链接打不开"
                    continue
                }
                // 压缩包下下来还得解压，这里只认直出的音频文件
                val audioFiles = files.filter { !it.isDir && isAudioName(it.fileName) }
                if (audioFiles.isEmpty()) {
                    why = "分享里没有音频文件"
                    continue
                }
                // 音质从高到低试：flac > wav > mp3
                for (file in audioFiles.sortedByDescending { qualityRank(it.fileName) }) {
                    val url = try {
                        quark.downloadUrl(pwdId, token.stoken, file.fid).downloadUrl
                    } catch (e: Exception) {
                        why = "拿不到下载地址"
                        continue
                    }
                    if (url.isBlank()) {
                        why = "拿不到下载地址"
                        continue
                    }
                    // 验一下这个直链真能下（直链是会话绑定的，必须带 Cookie）
                    val probe = quark.probe(url, readBytes = 8 * 1024)
                    if (!probe.looksLikeAudio) {
                        why = "直链读不到音频（HTTP ${probe.httpCode}）"
                        continue
                    }
                    lastNote = "拿到完整版 ${file.durationSec} 秒（${file.fileName}）"
                    return@withContext candidate.copy(
                        audioUrl = url,
                        // 用夸克那边的真实时长，判定「是不是完整版」要用
                        durationSec = file.durationSec,
                        partial = false,
                        // 真实扩展名：FLAC 就存成 .flac，别一律 .mp3
                        ext = extOf(file.fileName),
                        extraHeaders = mapOf(
                            "Cookie" to cookieProvider(),
                            "Referer" to "https://pan.quark.cn/",
                        ),
                    )
                }
            }
            lastNote = why
            null
        }

    private fun isAudioName(name: String): Boolean =
        name.endsWith(".mp3", true) || name.endsWith(".flac", true) ||
            name.endsWith(".wav", true) || name.endsWith(".m4a", true) ||
            name.endsWith(".aac", true) || name.endsWith(".ogg", true)

    /** 音质排序：FLAC > WAV > MP3 */
    private fun qualityRank(name: String): Int = when {
        name.endsWith(".flac", true) -> 3
        name.endsWith(".wav", true) -> 2
        name.endsWith(".mp3", true) -> 1
        else -> 0
    }

    /** 取文件名的扩展名，认不出来就当 mp3。 */
    private fun extOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return "mp3"
        val ext = fileName.substring(dot + 1).lowercase()
        return if (ext in KNOWN_EXT) ext else "mp3"
    }

    /**
     * 从音质描述里推扩展名：「夸克FLAC」→ flac。
     *
     * 搜索结果里还没有真实文件名，只能先按音质描述估一个，
     * resolve 拿到真名之后会覆盖掉。
     */
    private fun extFromQuality(qualities: List<String>): String {
        for (q in qualities) {
            val u = q.uppercase()
            when {
                u.contains("FLAC") -> return "flac"
                u.contains("WAV") -> return "wav"
                u.contains("M4A") -> return "m4a"
                u.contains("AAC") -> return "aac"
                u.contains("MP3") -> return "mp3"
            }
        }
        return "mp3"
    }

    companion object {
        private val KNOWN_EXT = setOf(
            "mp3", "flac", "wav", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wma", "ape", "alac",
        )
    }
}
