package com.cloudmusic.app.data

import com.cloudmusic.app.data.playlist.LiumingyeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** QQ 音乐搜索结果里的一首歌。 */
private data class QqSong(val mid: String, val name: String, val artists: List<String>)

/** 歌词获取结果。 */
data class LyricsResult(
    val lyrics: Lyrics = Lyrics(),
    /** 来源说明：本地 LRC / QQ 音乐 / 下歌吧 */
    val source: String = "",
    val error: String? = null,
)

/** 在线歌词：正文 + 来源。 */
private data class OnlineLyrics(val text: String, val source: String)

/**
 * 歌词有三个来源：
 * 1. 网盘里跟歌曲同目录的 .lrc（自己下载的歌词文件，最准）
 * 2. 在线歌词（QQ 音乐公开接口，按歌名 + 歌手匹配）
 * 3. 下歌吧（下歌吧详情接口自带完整 LRC，QQ 搜不到的冷门歌靠它）
 *
 * 拿到的歌词会缓存进本地 SQLite，同一首歌不再重复请求。
 */
class LyricsRepository(
    private val prefs: Prefs,
    private val db: LibraryDb,
    private val http: OkHttpClient,
    /** OneDrive 曲目也要能从网盘里读 .lrc，所以这里留个口子 */
    private val oneDrive: CloudProvider? = null,
    /** 下歌吧：QQ 音乐搜不到时的歌词兜底 */
    private val liumingye: LiumingyeSource? = null,
) {

    companion object {
        private const val QQ_SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val QQ_LYRIC = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        private const val QQ_REFERER = "https://y.qq.com/"

        /** 低于这个分数就不敢用，宁可没有也不要错配 */
        private const val MATCH_THRESHOLD = 10

        private const val UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }

    suspend fun load(track: Track): LyricsResult = withContext(Dispatchers.IO) {
        // 1) 本地缓存
        db.lyrics(track.key)?.let { row ->
            val parsed = LrcParser.parse(row.lrc)
            if (!parsed.isEmpty) return@withContext LyricsResult(parsed, row.source)
        }

        // 2) 网盘里同目录的 .lrc
        fetchLocalLrc(track)?.let { text ->
            val parsed = LrcParser.parse(text)
            if (!parsed.isEmpty) {
                db.saveLyrics(track.key, text, "本地 LRC")
                return@withContext LyricsResult(parsed, "本地 LRC")
            }
        }

        // 3) 在线歌词
        if (prefs.onlineLyrics) {
            fetchOnline(track)?.let { online ->
                val parsed = LrcParser.parse(online.text)
                if (!parsed.isEmpty) {
                    db.saveLyrics(track.key, online.text, online.source)
                    return@withContext LyricsResult(parsed, online.source)
                }
            }
        }

        LyricsResult(error = "没有找到歌词")
    }

    /**
     * 用户手动指定歌名 / 歌手再搜一次。
     * 文件名千奇百怪，自动匹配不上时可以用这个兜底。
     */
    suspend fun manualSearch(title: String, artist: String): LyricsResult =
        withContext(Dispatchers.IO) {
            val t = title.trim()
            if (t.isBlank()) return@withContext LyricsResult(error = "请填写歌名")

            val qq = fetchByTitleArtist(t, artist.trim())
            val lrc = qq ?: fetchLiumingyeLyrics(t, artist.trim())
            val source = if (qq != null) "QQ 音乐" else "下歌吧"
            if (lrc.isNullOrBlank()) {
                return@withContext LyricsResult(error = "没搜到这首歌的歌词")
            }
            val parsed = LrcParser.parse(lrc)
            if (parsed.isEmpty) {
                LyricsResult(error = "搜到的歌词无法解析")
            } else {
                LyricsResult(parsed, source)
            }
        }

    // ---------------- 本地 .lrc ----------------

    private suspend fun fetchLocalLrc(track: Track): String? {
        val isOneDrive = track.provider == ProviderKind.ONEDRIVE.id
        val href = track.id

        // OneDrive 的 id 是 item id，没有扩展名可换，只能靠扫描时建的索引去找同名 .lrc
        val decodedFolder = decode(track.folder)
        val base: String
        val candidates = LinkedHashSet<String>()

        if (isOneDrive) {
            // OneDrive 的 id 是不透明的 item id，文件名要另外存
            if (track.fileName.isBlank()) return null
            base = track.fileName.substringBeforeLast('.')
            db.lyricFile(ProviderKind.ONEDRIVE.id, decodedFolder, base)
                ?.let { candidates.add(it) }
            if (candidates.isEmpty()) return null
        } else {
            val dot = href.lastIndexOf('.')
            val slash = href.lastIndexOf('/')
            if (dot <= slash || dot < 0) return null
            // href 是服务器给的 URL 编码路径，直接换扩展名能保持编码
            val encodedBase = href.substring(0, dot)
            val decodedName = decode(href.substring(slash + 1))
            base = decodedName.substringBeforeLast('.')
            db.lyricFile(ProviderKind.WEBDAV.id, decodedFolder, base)
                ?.let { candidates.add(it) }
            candidates.add("$encodedBase.lrc")
            candidates.add("$encodedBase.txt")
        }

        for (c in candidates) {
            val text = if (isOneDrive) {
                oneDrive?.fetchText(c)
            } else {
                davClient().fetchText(c)
            } ?: continue
            if (text.isBlank()) continue
            // 不是 LRC 格式就试下一个候选（比如同名但其实是说明文件的 .txt）
            if (LrcParser.parse(text).isEmpty) continue
            return text
        }
        return null
    }

    // ---------------- 在线歌词（QQ 音乐） ----------------

    /**
     * 网易云的公开搜索接口现在基本靠不住（搜"稻香 周杰伦"会返回《刀马旦》这种无关结果），
     * QQ 音乐的搜索 + 歌词接口反而准确得多，所以在线歌词走 QQ。
     */
    private suspend fun fetchOnline(track: Track): OnlineLyrics? {
        val combos = titleCombos(track)
        if (combos.isEmpty()) return null

        // 关键词集合在两种解释下是一样的，搜一次缓存起来，避免重复请求
        val cache = HashMap<String, List<QqSong>>()
        for ((artist, title) in combos) {
            val lrc = fetchByTitleArtist(title, artist, cache)
            if (!lrc.isNullOrBlank()) return OnlineLyrics(lrc, "QQ 音乐")
        }

        // QQ 音乐也没有的（冷门曲、老歌），下歌吧的详情接口自带完整 LRC
        for ((artist, title) in combos) {
            val lrc = fetchLiumingyeLyrics(title, artist)
            if (!lrc.isNullOrBlank()) return OnlineLyrics(lrc, "下歌吧")
        }
        return null
    }

    /** 按指定的歌名/歌手去搜歌词。 */
    private suspend fun fetchByTitleArtist(
        title: String,
        artist: String,
        cache: HashMap<String, List<QqSong>> = HashMap()
    ): String? {
        if (title.isBlank()) return null
        for (kw in keywordVariants(title, artist)) {
            var songs = cache[kw]
            if (songs == null) {
                songs = qqSearch(kw)
                cache[kw] = songs
            }
            if (songs.isEmpty()) continue
            for (s in rankSongs(songs, title, artist)) {
                val lyric = qqLyric(s.mid) ?: continue
                if (lyric.isNotBlank()) return lyric
            }
        }
        return null
    }

    /**
     * 下歌吧（下歌吧）歌词兜底。
     *
     * 它的详情接口直接带一份完整 LRC，不需要另外找歌词接口；
     * 而且它是网盘资源站，曲库比 QQ 音乐全，冷门歌经常只有它有。
     *
     * 代价是限流（30 次/60 秒），所以关键词只试两种，打分不够高就直接放弃。
     */
    private suspend fun fetchLiumingyeLyrics(title: String, artist: String): String? {
        val src = liumingye ?: return null
        if (title.isBlank()) return null
        val wantTitle = normalize(title)
        val wantArtist = normalize(artist)

        val keywords = LinkedHashSet<String>()
        if (artist.isNotBlank()) keywords.add("$title $artist")
        keywords.add(title)

        for (kw in keywords) {
            val hits = try {
                src.search(kw)
            } catch (e: Exception) {
                emptyList()
            }
            if (hits.isEmpty()) continue

            val ranked = hits
                .map { it to score(it.title, splitArtists(it.artist), wantTitle, wantArtist) }
                .filter { it.second >= MATCH_THRESHOLD }
                .sortedByDescending { it.second }
            for ((hit, _) in ranked) {
                val detail = try {
                    src.detail(hit.id)
                } catch (e: Exception) {
                    continue
                }
                val lrc = detail.lyrics
                if (lrc.isBlank()) continue
                // 详情接口偶尔给纯文本歌词，没有时间轴就没法滚动显示，丢掉
                if (LrcParser.parse(lrc).isEmpty) continue
                return lrc
            }
        }
        return null
    }

    /** 歌手字段可能是「A & B」或者「A/B」，拆开逐个比对。 */
    private fun splitArtists(raw: String): List<String> =
        raw.split("&", "/", "、", ",", "，", "|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * 网盘里的命名习惯有"歌手 - 歌名"和"歌名 - 歌手"两种，
     * 文件名本身分不出谁是谁，索性两种解释都拿去搜，谁匹配上用谁。
     */
    private fun titleCombos(track: Track): List<Pair<String, String>> {
        val title = track.title.trim()
        val artist = track.artist.trim()
        val out = mutableListOf<Pair<String, String>>()
        if (title.isNotBlank()) out.add(artist to title)
        if (artist.isNotBlank() && title.isNotBlank() && artist != title) out.add(title to artist)
        return out
    }

    /** 搜索关键词的各种写法，按命中率从高到低尝试。 */
    private fun keywordVariants(title: String, artist: String): List<String> {
        val out = LinkedHashSet<String>()
        if (artist.isNotBlank()) {
            out.add("$title $artist")
            out.add("$artist $title")
        }
        out.add(title)
        return out.toList()
    }

    private fun qqSearch(keyword: String): List<QqSong> {
        val url = "$QQ_SEARCH?format=json&p=1&n=12&w=${enc(keyword)}&t=0&cr=1"
        val body = get(url, QQ_REFERER) ?: return emptyList()
        return try {
            val list = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("list")
                ?: return emptyList()
            val out = mutableListOf<QqSong>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val mid = o.optString("songmid")
                val name = o.optString("songname")
                if (mid.isBlank() || name.isBlank()) continue
                val singers = mutableListOf<String>()
                o.optJSONArray("singer")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        singers.add(arr.optJSONObject(j)?.optString("name").orEmpty())
                    }
                }
                out.add(QqSong(mid, name, singers))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun qqLyric(mid: String): String? {
        val url = "$QQ_LYRIC?songmid=$mid&format=json&nobase64=1"
        val body = get(url, QQ_REFERER) ?: return null
        return try {
            JSONObject(body).optString("lyric").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 给候选歌曲打分并排序。
     * 歌名：完全相同 10 分，互相包含 6 分，有点像 4 分
     * 歌手：完全相同 8 分，互相包含 5 分
     * 不到 10 分宁缺毋滥，避免张冠李戴。
     */
    private fun rankSongs(songs: List<QqSong>, title: String, artist: String): List<QqSong> {
        val wantTitle = normalize(title)
        val wantArtist = normalize(artist)
        return songs
            .map { it to score(it, wantTitle, wantArtist) }
            .filter { it.second >= MATCH_THRESHOLD }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun score(song: QqSong, wantTitle: String, wantArtist: String): Int =
        score(song.name, song.artists, wantTitle, wantArtist)

    /** 歌名 + 歌手的通用打分，QQ 音乐和下歌吧共用一套口径。 */
    private fun score(
        name: String,
        artists: List<String>,
        wantTitle: String,
        wantArtist: String,
    ): Int {
        if (wantTitle.isEmpty()) return 0
        val n = normalize(name)
        val minLen = minOf(n.length, wantTitle.length)
        var s = when {
            n == wantTitle -> 10
            (n.contains(wantTitle) || wantTitle.contains(n)) && minLen >= 2 -> 6
            wantTitle.length >= 2 && (commonPrefix(n, wantTitle) >= 1 || commonPart(n, wantTitle) >= 2) -> 4
            else -> 0
        }
        if (wantArtist.isEmpty()) {
            // 没有歌手信息时，歌名必须足够接近才敢用
            if (s == 6 && minLen >= 3) s = 10
            return s
        }
        return s + artistScore(artists, wantArtist)
    }

    private fun artistScore(artists: List<String>, wantArtist: String): Int {
        var best = 0
        for (raw in artists) {
            val a = normalize(raw)
            val sc = when {
                a == wantArtist -> 8
                (a.contains(wantArtist) || wantArtist.contains(a)) &&
                    minOf(a.length, wantArtist.length) >= 2 -> 5
                else -> 0
            }
            if (sc > best) best = sc
        }
        return best
    }

    /** 最长公共前缀长度 */
    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /** 最长公共子串长度（歌名很短，暴力比对即可） */
    private fun commonPart(a: String, b: String): Int {
        var best = 0
        for (i in a.indices) {
            for (j in b.indices) {
                var k = 0
                while (i + k < a.length && j + k < b.length && a[i + k] == b[j + k]) k++
                if (k > best) best = k
            }
        }
        return best
    }

    private fun get(url: String, referer: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun davClient(): DavClient = DavClient(
        DavClient.normalizeServerUrl(prefs.serverUrl),
        prefs.credentials,
        http
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /** 去掉空格与常见标点，便于比较歌名歌手。 */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }
}
