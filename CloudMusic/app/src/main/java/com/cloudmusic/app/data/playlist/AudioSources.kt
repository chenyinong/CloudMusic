package com.cloudmusic.app.data.playlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 音频下载源。
 *
 * 分成「搜」和「取直链」两步：搜索能一次拿一批候选，
 * 但直链必须一个个探测（有的歌平台不给放），所以先搜再挑着探测。
 */
interface AudioSource {
    val id: String
    val label: String

    /** 按歌名 + 歌手搜候选，按匹配度从高到低排。直链还没探测，audioUrl 是空串。 */
    suspend fun search(title: String, artist: String, durationSec: Int): List<AudioCandidate>

    /** 探测一个候选的直链。拿不到（没版权 / 没资源）返回 null。 */
    suspend fun resolve(candidate: AudioCandidate): AudioCandidate?

    /**
     * 最近一次调用的结论，一句话。
     *
     * 用户只会看到「没找到音源 / 只有片段」这种笼统的话，
     * 出了问题很难定位到底是哪个源、哪一步卡住。
     * 这里把每个源的结论收上来，直接显示给用户看。
     */
    val lastNote: String get() = ""
}

/** 共用的探测工具。 */
internal object AudioProbe {

    /** 128kbps 的 mp3 大约 16KB/秒。 */
    const val BYTES_PER_SEC = 16000L

    /** 低于标称时长这个比例就算试听片段。 */
    const val COMPLETE_RATIO = 0.85

    /** 拿响应头里的长度。返回 null 表示这不是音频（多半是 404 页）。 */
    fun headLength(http: OkHttpClient, url: String, referer: String, ua: String): Long? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .head()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                null
            } else {
                val type = resp.header("Content-Type").orEmpty()
                if (type.contains("text/html") || type.contains("application/json")) {
                    null
                } else {
                    resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    /** 手动跟一次重定向，只取 Location。 */
    fun redirect(http: OkHttpClient, url: String, referer: String, ua: String): String? = try {
        val noRedirect = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .build()
        noRedirect.newCall(request).execute().use { resp ->
            resp.header("Location")
        }
    } catch (e: Exception) {
        null
    }

    /** 把 http 的地址升级成 https —— App 禁止明文流量，但这两家 CDN 的 https 是等价的。 */
    fun https(url: String): String =
        if (url.startsWith("http://")) "https://" + url.substring(7) else url
}

/** 候选的匹配打分：歌名对得上、歌手命中、时长接近就加分，衍生版本降权。 */
internal fun rank(
    c: AudioCandidate,
    title: String,
    artist: String,
    targetDurationSec: Int
): Int {
    var s = 0
    if (TrackMatcher.sameTitle(c.title, title)) s += 10
    if (TrackMatcher.artistHit(artist, c.artist)) s += 8
    if (TrackMatcher.durationClose(c.durationSec, targetDurationSec)) s += 5
    if (c.variantTag != null) s -= 8
    return s
}

/** 网易云。主下载源，匹配率最高，但有版权的歌拿不到完整版。 */
class NeteaseAudioSource(private val http: OkHttpClient) : AudioSource {

    override val id = "netease"
    override val label = "网易云"

    @Volatile
    override var lastNote: String = ""
        private set

    companion object {
        private const val HOST = "https://music.163.com"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }

    override suspend fun search(title: String, artist: String, durationSec: Int): List<AudioCandidate> =
        withContext(Dispatchers.IO) {
            val merged = LinkedHashMap<String, AudioCandidate>()
            val keywords = listOf("$title $artist", title)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            for (kw in keywords) {
                for (c in rawSearch(kw)) if (!merged.containsKey(c.songId)) merged[c.songId] = c
            }
            merged.values.sortedByDescending { rank(it, title, artist, durationSec) }
        }

    private fun rawSearch(keyword: String): List<AudioCandidate> {
        val text = get("$HOST/api/search/get?s=${enc(keyword)}&type=1&limit=25&offset=0")
            ?: return emptyList()
        return try {
            val songs = JSONObject(text).optJSONObject("result")?.optJSONArray("songs")
                ?: return emptyList()
            val out = mutableListOf<AudioCandidate>()
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val id = s.optLong("id").takeIf { it > 0 }?.toString() ?: continue
                val title = s.optString("name").trim()
                if (title.isEmpty()) continue
                val artists = mutableListOf<String>()
                s.optJSONArray("artists")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        arr.optJSONObject(j)?.optString("name")
                            ?.takeIf { it.isNotBlank() }?.let { artists.add(it) }
                    }
                }
                val artistText = artists.joinToString(" / ")
                out.add(
                    AudioCandidate(
                        sourceId = id,
                        sourceLabel = "网易云",
                        songId = id,
                        title = title,
                        artist = artistText,
                        album = s.optJSONObject("album")?.optString("name").orEmpty(),
                        durationSec = (s.optLong("duration") / 1000).toInt(),
                        variantTag = TrackMatcher.variantTag(title, artistText)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun resolve(candidate: AudioCandidate): AudioCandidate? =
        withContext(Dispatchers.IO) {
            val api = "$HOST/song/media/outer/url?id=${candidate.songId}.mp3"
            val location = AudioProbe.redirect(http, api, "$HOST/", UA)
            if (location == null) {
                lastNote = "直链探测失败"
                return@withContext null
            }
            // 平台侧没版权时，outer/url 会 302 到 /404
            if (location.contains("/404")) {
                lastNote = "平台没有版权"
                return@withContext null
            }
            val url = AudioProbe.https(location)
            val bytes = AudioProbe.headLength(http, url, "$HOST/", UA)
            if (bytes == null) {
                lastNote = "直链探测失败"
                return@withContext null
            }
            val partial = candidate.durationSec > 0 &&
                bytes < candidate.durationSec * AudioProbe.BYTES_PER_SEC * AudioProbe.COMPLETE_RATIO
            lastNote = if (partial) "只有试听片段" else "拿到完整版"
            candidate.copy(audioUrl = url, partial = partial)
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

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

/**
 * 酷我。补漏用的第二源。
 *
 * 新接口（`www.kuwo.cn` 下面那套 `api/www` 接口）现在被阿里云盾挡着，
 * `kw_token` / `csrf` 那套走不通；
 * 老接口反而还活着，而且压根不需要 csrf。
 */
class KuwoAudioSource(private val http: OkHttpClient) : AudioSource {

    override val id = "kuwo"
    override val label = "酷我"

    @Volatile
    override var lastNote: String = ""
        private set

    companion object {
        private const val SEARCH = "https://search.kuwo.cn"
        private const val ANTI = "https://antiserver.kuwo.cn"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override suspend fun search(title: String, artist: String, durationSec: Int): List<AudioCandidate> =
        withContext(Dispatchers.IO) {
            val merged = LinkedHashMap<String, AudioCandidate>()
            val keywords = listOf("$title $artist", title)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            for (kw in keywords) {
                for (c in rawSearch(kw)) if (!merged.containsKey(c.songId)) merged[c.songId] = c
            }
            merged.values.sortedByDescending { rank(it, title, artist, durationSec) }
        }

    /** 酷我老接口返回的是 Python dict 文本，不是 JSON，得用正则抠字段。 */
    private fun rawSearch(keyword: String): List<AudioCandidate> {
        val url = "$SEARCH/r.s?all=${enc(keyword)}&ft=music&itemset=web_2013&client=kt" +
            "&pn=0&rn=20&rformat=json&encoding=utf8"
        val text = get(url) ?: return emptyList()
        val out = mutableListOf<AudioCandidate>()
        for (chunk in text.split("},{")) {
            val rid = chunk.field("DC_TARGETID") ?: continue
            if (chunk.field("ONLINE") != "1") continue
            val formats = chunk.field("FORMATS").orEmpty()
            if (!formats.contains("MP3", ignoreCase = true)) continue
            val title = chunk.field("SONGNAME").orEmpty()
            val artist = chunk.field("ARTIST").orEmpty()
            if (title.isEmpty()) continue
            out.add(
                AudioCandidate(
                    sourceId = "kuwo:$rid",
                    sourceLabel = "酷我",
                    songId = rid,
                    title = title,
                    artist = artist,
                    album = "",
                    durationSec = chunk.field("DURATION")?.toIntOrNull() ?: 0,
                    variantTag = TrackMatcher.variantTag(title, artist)
                )
            )
        }
        return out
    }

    override suspend fun resolve(candidate: AudioCandidate): AudioCandidate? =
        withContext(Dispatchers.IO) {
            val url = "$ANTI/anti.s?type=convert_url&format=mp3&rid=${candidate.songId}" +
                "&br=128&response=url"
            val text = get(url)
            if (text == null) {
                lastNote = "拿不到直链"
                return@withContext null
            }
            val m = Regex("https?://[^\\s'\"<>]+\\.(mp3|aac|flac)", RegexOption.IGNORE_CASE)
                .find(text)
            if (m == null) {
                lastNote = "拿不到直链"
                return@withContext null
            }
            val audio = AudioProbe.https(m.value)
            val bytes = AudioProbe.headLength(http, audio, "$SEARCH/", UA)
            if (bytes == null) {
                lastNote = "直链探测失败"
                return@withContext null
            }
            val partial = candidate.durationSec > 0 &&
                bytes < candidate.durationSec * AudioProbe.BYTES_PER_SEC * AudioProbe.COMPLETE_RATIO
            lastNote = if (partial) "只有试听片段" else "拿到完整版"
            candidate.copy(audioUrl = audio, partial = partial)
        }

    private fun get(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "$SEARCH/")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

/** 从酷我那种 Python dict 文本里抠出一个字段。 */
private fun String.field(key: String): String? {
    val m = Regex("'" + key + "':'([^']*)'").find(this) ?: return null
    return unescapeKuwo(m.groupValues[1])
}

/** 酷我的文本里有几种转义，得先还原，不然歌名里会混进 "nbsp"。 */
private fun unescapeKuwo(s: String): String = s
    .replace("\\u0026", "&")
    .replace("\\\\", "\\")
    .replace("&nbsp;", " ")
    // 剩下的反斜杠是它自己加的分隔符（「大土班的魔仙堡\&洛天依\&逃跑计划」），
    // 显示给用户之前清掉
    .replace("\\", "")
    .trim()
