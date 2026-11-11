package com.cloudmusic.app.data

import com.cloudmusic.app.data.playlist.LiumingyeHit
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

/** 手动搜索列出的一首候选歌曲，用户点哪首就用哪首的歌词。 */
data class LyricsCandidate(
    /** QQ 音乐是 songmid，下歌吧是详情 id */
    val id: String,
    val title: String,
    val artist: String,
    val source: String,
    /** 打分最高且过阈值，UI 上标个「推荐」 */
    val recommended: Boolean = false,
)

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

        /** 手动指定的歌词不再参与缓存校验：用户就是为纠正文件名才手动搜的。 */
        private const val MANUAL_SOURCE = "手动指定"

        private val BRACKETS = Regex("""[（(【\[][^）)】\]]*[）)】\]]""")
        private val VERSION_TAIL = Regex(
            """(?i)(?:\s+|^)(?:live|remix|demo|acoustic|instrumental|karaoke|dj|现场|重制|翻唱|伴奏|纯音乐|版).*$"""
        )
        private val LEADING_NO = Regex("""^\s*\d{1,3}[\s.\-_]+""")
        private val VERSION_TAG = Regex(
            """live|现场|演唱会|remix|混音|伴奏|纯音乐|翻唱|acoustic|demo|karaoke|不插电|dj""",
            RegexOption.IGNORE_CASE
        )
        /** 明显不是同一版本的候选：普通录音室版不该拿伴奏/翻唱的歌词。 */
        private val BAD_VERSION = Regex(
            """伴奏|纯音乐|试听|铃声|片段|翻唱|remix|instrumental|karaoke|demo""",
            RegexOption.IGNORE_CASE
        )
        private val SPACES = Regex("""\s+""")

        private const val UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }

    suspend fun load(track: Track, forceRefresh: Boolean = false): LyricsResult =
        withContext(Dispatchers.IO) {
            if (forceRefresh) db.clearLyrics(track.key)

            // 1) 本地缓存。早期打分太松存下过错歌词，这里用 [ti:]/[ar:] 核对一下，
            //    对不上就丢掉重新找，用户不用手动清数据。
            if (!forceRefresh) {
                db.lyrics(track.key)?.let { row ->
                    val parsed = LrcParser.parse(row.lrc)
                    if (!parsed.isEmpty &&
                        (row.source.startsWith(MANUAL_SOURCE) || cachedLyricsFit(parsed, track))
                    ) return@withContext LyricsResult(parsed, row.source)
                    db.clearLyrics(track.key)
                }
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
     * 文件名千奇百怪，自动匹配不上时可以用这个兜底；结果会覆盖旧缓存，
     * 下次启动不会再退回错的那份。
     */
    suspend fun manualSearch(
        title: String,
        artist: String,
        track: Track? = null,
    ): LyricsResult = withContext(Dispatchers.IO) {
        val t = title.trim()
        if (t.isBlank()) return@withContext LyricsResult(error = "请填写歌名")

        val qq = fetchByTitleArtist(t, artist.trim())
        val lrc = qq ?: fetchLiumingyeLyrics(t, artist.trim())
        if (lrc.isNullOrBlank()) {
            return@withContext LyricsResult(error = "没搜到这首歌的歌词")
        }
        val parsed = LrcParser.parse(lrc)
        if (parsed.isEmpty) {
            LyricsResult(error = "搜到的歌词无法解析")
        } else {
            val origin = if (qq != null) "QQ 音乐" else "下歌吧"
            val source = if (track != null) "$MANUAL_SOURCE · $origin" else origin
            if (track != null) {
                db.clearLyrics(track.key)
                db.saveLyrics(track.key, lrc, source)
            }
            LyricsResult(parsed, source)
        }
    }

    /**
     * 手动搜索的第一步：列出候选歌曲，交给用户自己挑。
     *
     * 和自动匹配不同，这里**不按阈值砍结果** —— 自动匹配宁缺毋滥，
     * 但手动搜索就是来纠正「系统挑错了」的，得把沾边的都摆出来。
     * 只按匹配度排序，把最像的那条标成「推荐」。
     */
    suspend fun searchCandidates(title: String, artist: String): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            val raw = title.trim()
            if (raw.isBlank()) return@withContext emptyList()
            val clean = cleanTitle(raw).ifBlank { raw }
            val a = cleanArtist(artist)
            val wantTitle = normalize(clean)
            val wantArtist = normalize(a)

            // 几个关键词的结果按 id 去重，同一首保留最高分
            val qq = LinkedHashMap<String, Pair<QqSong, Int>>()
            for (kw in keywordVariants(raw, a).take(3)) {
                for (s in qqSearch(kw)) {
                    val sc = score(s, wantTitle, wantArtist, raw)
                    val old = qq[s.mid]
                    if (old == null || sc > old.second) qq[s.mid] = s to sc
                }
                if (qq.size >= 12) break
            }
            val out = qq.values
                .sortedByDescending { it.second }
                .take(15)
                .mapIndexed { i, (s, sc) ->
                    LyricsCandidate(
                        id = s.mid,
                        title = s.name,
                        artist = s.artists.filter { it.isNotBlank() }.joinToString(" / "),
                        source = "QQ 音乐",
                        recommended = i == 0 && sc >= MATCH_THRESHOLD,
                    )
                }
                .toMutableList()

            // QQ 一首都没有的冷门歌再问下歌吧；它限流，最多两个关键词
            if (out.isEmpty()) {
                val src = liumingye
                if (src != null) {
                    val hits = LinkedHashMap<String, Pair<LiumingyeHit, Int>>()
                    val keywords = listOfNotNull(
                        "$clean $a".trim().takeIf { a.isNotBlank() && it.isNotBlank() },
                        clean
                    )
                    for (kw in keywords) {
                        val list = try {
                            src.search(kw)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        for (h in list) {
                            val sc = score(h.title, splitArtists(h.artist), wantTitle, wantArtist, raw)
                            val old = hits[h.id]
                            if (old == null || sc > old.second) hits[h.id] = h to sc
                        }
                        if (hits.isNotEmpty()) break
                    }
                    out.addAll(
                        hits.values
                            .sortedByDescending { it.second }
                            .take(15)
                            .mapIndexed { i, (h, sc) ->
                                LyricsCandidate(
                                    id = h.id,
                                    title = h.title,
                                    artist = h.artist,
                                    source = "下歌吧",
                                    recommended = i == 0 && sc >= MATCH_THRESHOLD,
                                )
                            }
                    )
                }
            }
            out
        }

    /** 手动搜索的第二步：取用户选中的那首的歌词，并覆盖当前曲目的缓存。 */
    suspend fun fetchCandidate(
        candidate: LyricsCandidate,
        track: Track? = null,
    ): LyricsResult = withContext(Dispatchers.IO) {
        val lrc = when (candidate.source) {
            "QQ 音乐" -> qqLyric(candidate.id)
            else -> try {
                liumingye?.detail(candidate.id)?.lyrics
            } catch (e: Exception) {
                null
            }
        }
        if (lrc.isNullOrBlank()) {
            return@withContext LyricsResult(error = "这首歌没有可用歌词")
        }
        val parsed = LrcParser.parse(lrc)
        if (parsed.isEmpty) {
            return@withContext LyricsResult(error = "搜到的歌词无法解析")
        }
        val source = if (track != null) "$MANUAL_SOURCE · ${candidate.source}" else candidate.source
        if (track != null) {
            db.clearLyrics(track.key)
            db.saveLyrics(track.key, lrc, source)
        }
        LyricsResult(parsed, source)
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
        val clean = cleanTitle(title).ifBlank { title }
        val wantTitle = normalize(clean)
        val wantArtist = normalize(cleanArtist(artist))

        val keywords = LinkedHashSet<String>()
        if (wantArtist.isNotBlank()) keywords.add("$clean $artist")
        keywords.add(clean)

        for (kw in keywords) {
            val hits = try {
                src.search(kw)
            } catch (e: Exception) {
                emptyList()
            }
            if (hits.isEmpty()) continue

            val ranked = hits
                .map { it to score(it.title, splitArtists(it.artist), wantTitle, wantArtist, title) }
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
        val rawTitle = track.title.trim()
        val title = cleanTitle(rawTitle).ifBlank { rawTitle }
        val artist = cleanArtist(track.artist)
        val out = mutableListOf<Pair<String, String>>()
        if (title.isNotBlank()) out.add(artist to title)
        if (artist.isNotBlank() && title.isNotBlank() && artist != title) out.add(title to artist)
        return out
    }

    /**
     * 搜索关键词按命中率从高到低尝试：
     * 先试带版本标签的同版本歌词（Live 现场版别拿录音室版的时间轴），
     * 再试洗干净的歌名，最后才用原始文件名。
     */
    private fun keywordVariants(title: String, artist: String): List<String> {
        val raw = title.trim()
        val clean = cleanTitle(raw).ifBlank { raw }
        val a = cleanArtist(artist)
        val tags = versionTags(raw)
        val out = LinkedHashSet<String>()
        if (a.isNotBlank()) {
            if (tags.isNotEmpty()) out.add("$clean ${tags.first()} $a")
            out.add("$clean $a")
            out.add("$a $clean")
            if (clean != raw) out.add("$raw $a")
        }
        out.add(clean)
        if (clean != raw) out.add(raw)
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
     * 歌名：完全相同 10 分，互相包含 6 分，长度相近且有公共片段 4 分
     * 歌手：完全相同 8 分，互相包含 5 分
     * 模糊命中必须歌手完全一致，避免「歌名沾边 + 歌手沾边」凑够分数张冠李戴。
     */
    private fun rankSongs(songs: List<QqSong>, title: String, artist: String): List<QqSong> {
        val wantTitle = normalize(cleanTitle(title).ifBlank { title.trim() })
        val wantArtist = normalize(cleanArtist(artist))
        return songs
            .map { it to score(it, wantTitle, wantArtist, title) }
            .filter { it.second >= MATCH_THRESHOLD }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun score(
        song: QqSong,
        wantTitle: String,
        wantArtist: String,
        rawWantTitle: String = "",
    ): Int = score(song.name, song.artists, wantTitle, wantArtist, rawWantTitle)

    /** 歌名 + 歌手的通用打分，QQ 音乐和下歌吧共用一套口径。 */
    private fun score(
        name: String,
        artists: List<String>,
        wantTitle: String,
        wantArtist: String,
        rawWantTitle: String = "",
    ): Int {
        if (wantTitle.isEmpty()) return 0
        val n = normalize(name)
        val minLen = minOf(n.length, wantTitle.length)
        val titleScore = when {
            n == wantTitle -> 10
            (n.contains(wantTitle) || wantTitle.contains(n)) && minLen >= 2 -> 6
            // 原来「1 个字的公共前缀」也算命中，太松了；改成长度相近 + 有公共片段
            minLen >= 3 && commonPart(n, wantTitle) >= 2 &&
                maxOf(n.length, wantTitle.length) - minLen <= 2 -> 4
            else -> 0
        }
        if (titleScore == 0) return 0
        // 文件名没写版本，却搜到伴奏 / 翻唱 / Remix，宁可不要
        if (BAD_VERSION.containsMatchIn(name) && !BAD_VERSION.containsMatchIn(rawWantTitle)) return 0

        if (wantArtist.isEmpty()) {
            // 没有歌手信息时，歌名必须足够接近才敢用
            return when {
                titleScore == 10 -> 10
                titleScore == 6 && minLen >= 3 -> 10
                else -> 0
            }
        }
        val a = artistScore(artists, wantArtist)
        if (titleScore == 4 && a < 8) return 0
        return titleScore + a
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

    /** 去掉括号版本信息、Live/伴奏之类尾巴、以及开头的曲目序号。 */
    private fun cleanTitle(s: String): String {
        var out = s.trim()
        repeat(3) {
            val next = BRACKETS.replace(out, " ")
            if (next == out) return@repeat
            out = next
        }
        out = VERSION_TAIL.replace(out, " ")
        out = LEADING_NO.replace(out, "")
        return SPACES.replace(out, " ").trim().trim('-', '_', '.')
    }

    /** 歌手字段也可能带括号，但别像歌名那样砍掉 live/版 之类的尾巴。 */
    private fun cleanArtist(s: String): String =
        SPACES.replace(BRACKETS.replace(s.trim(), " "), " ").trim()

    /** 版本标签，用于优先找同版本歌词（Live 现场版别拿录音室版的时间轴）。 */
    private fun versionTags(s: String): List<String> =
        VERSION_TAG.findAll(s).map { it.value.lowercase() }.distinct().take(2).toList()

    /** 早期缓存里存过错歌，[ti:] / [ar:] 和当前曲目明显对不上就重新找。 */
    private fun cachedLyricsFit(parsed: Lyrics, track: Track): Boolean {
        val ti = parsed.meta["ti"]?.takeIf { it.isNotBlank() } ?: return true
        val want = normalize(cleanTitle(track.title))
        val got = normalize(cleanTitle(ti))
        if (want.length >= 2 && got.length >= 2) {
            val fit = got.contains(want) || want.contains(got) || commonPart(got, want) >= 2
            if (!fit) return false
        }
        val ar = parsed.meta["ar"]?.takeIf { it.isNotBlank() } ?: return true
        val wantA = normalize(cleanArtist(track.artist))
        val gotA = normalize(cleanArtist(ar))
        if (wantA.length >= 2 && gotA.length >= 2 &&
            !gotA.contains(wantA) && !wantA.contains(gotA)
        ) return false
        return true
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
