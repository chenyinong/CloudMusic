package com.cloudmusic.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections

/**
 * 专辑封面：iTunes Search API 按歌名 + 歌手检索。
 *
 * - 免费、无需 Key，官方限速约 20 次/分钟，超了会被封一小会儿
 * - 返回的 artworkUrl100 把「100x100bb」换成「600x600bb」就是高清图，不用另调接口
 * - 命中的直链存进 SQLite，同一首歌终身只查一次
 * - 查过没有的记进本会话负缓存，来回切歌也不会反复打接口
 *
 * 打分口径和歌词那套一致：歌名完全相同 10 分、互相包含 6 分；
 * 歌手完全相同 8 分、互相包含 5 分。不到 10 分宁缺毋滥，避免拿错专辑的封面。
 */
class CoverRepository(
    private val db: LibraryDb,
    private val http: OkHttpClient,
) {
    companion object {
        private const val SEARCH = "https://itunes.apple.com/search"

        /** 低于这个分数不敢用，宁可没有封面也不要张冠李戴 */
        private const val MATCH_THRESHOLD = 10

        private const val UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        /** 解码后的封面位图内存缓存（600x600 ARGB 约 1.4MB，按张数限 24 张） */
        private val bitmapCache = object : LruCache<String, Bitmap>(24) {
            override fun sizeOf(key: String, value: Bitmap): Int = 1
        }
    }

    /**
     * 本会话的 URL 缓存（值 "" = 查过但没有），列表滚动时每行重组也不会重复打库。
     * 同时兼作负缓存，下次启动还能重试。
     */
    private val urlCache = Collections.synchronizedMap(mutableMapOf<String, String>())

    /** 全局串行 + 最小间隔：iTunes 限约 20 次/分钟，滚动列表时按节奏排队查。 */
    private val searchMutex = Mutex()
    private var lastSearchAt = 0L

    /** 查封面：内存 → SQLite → iTunes（带限流）。失败返回 null，UI 画占位图。 */
    suspend fun load(track: Track): String? = withContext(Dispatchers.IO) {
        urlCache[track.key]?.let { return@withContext it.ifBlank { null } }
        db.coverUrl(track.key)?.let {
            urlCache[track.key] = it
            return@withContext it
        }

        val url = searchItunes(track.title.trim(), track.artist.trim())
        // 查过就记下来，没查到也别在同一次启动里反复打接口
        urlCache[track.key] = url ?: ""
        if (url != null) db.saveCover(track.key, url)
        url
    }

    /** 下载并解码封面位图，带内存缓存（600x600 的图来回切歌不用重新下）。 */
    suspend fun artwork(url: String): Bitmap? = withContext(Dispatchers.IO) {
        bitmapCache.get(url)?.let { return@withContext it }
        val bmp = try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        bitmapCache.put(url, bmp)
        bmp
    }

    private data class Hit(val title: String, val artist: String, val artwork: String)

    private suspend fun searchItunes(title: String, artist: String): String? {
        if (title.isBlank()) return null
        val wantTitle = normalize(title)
        val wantArtist = normalize(artist)

        // 「歌名 歌手」命中率高，搜不到再单搜歌名
        val keywords = LinkedHashSet<String>()
        if (artist.isNotBlank()) keywords.add("$title $artist")
        keywords.add(title)

        // 中文歌走 CN 店，欧美/冷门走 US 店，两店各试一轮
        for (kw in keywords) {
            for (country in listOf("CN", "US")) {
                throttleSearch()
                val best = hits(kw, country)
                    .map { it to score(normalize(it.title), normalize(it.artist), wantTitle, wantArtist) }
                    .filter { it.second >= MATCH_THRESHOLD }
                    .maxByOrNull { it.second }
                    ?: continue
                return best.first.artwork
            }
        }
        return null
    }

    /** 任意两次 iTunes 检索至少隔 3 秒（20 次/分钟），全局串行不并发闯限。 */
    private suspend fun throttleSearch() {
        searchMutex.withLock {
            val wait = 3000L - (System.currentTimeMillis() - lastSearchAt)
            if (wait > 0) delay(wait)
            lastSearchAt = System.currentTimeMillis()
        }
    }

    private fun hits(keyword: String, country: String): List<Hit> {
        val url =
            "$SEARCH?term=${enc(keyword)}&media=music&entity=song&limit=12&country=$country"
        val body = get(url) ?: return emptyList()
        return try {
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<Hit>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("trackName")
                val artwork = o.optString("artworkUrl100")
                if (name.isBlank() || artwork.isBlank()) continue
                // 100x100 缩略图换 600x600 高清，同一个 CDN 路径
                out.add(Hit(name, o.optString("artistName"), artwork.replace("100x100bb", "600x600bb")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 歌名 + 歌手打分，口径与歌词匹配一致。 */
    private fun score(
        name: String,
        artist: String,
        wantTitle: String,
        wantArtist: String,
    ): Int {
        if (wantTitle.isEmpty()) return 0
        val minLen = minOf(name.length, wantTitle.length)
        val titleScore = when {
            name == wantTitle -> 10
            (name.contains(wantTitle) || wantTitle.contains(name)) && minLen >= 2 -> 6
            else -> 0
        }
        if (titleScore == 0) return 0
        if (wantArtist.isEmpty()) {
            // 没有歌手信息时，歌名完全相同才敢用
            return if (titleScore == 10) 10 else 0
        }
        val artistScore = when {
            artist == wantArtist -> 8
            (artist.contains(wantArtist) || wantArtist.contains(artist)) &&
                minOf(artist.length, wantArtist.length) >= 2 -> 5
            else -> 0
        }
        return titleScore + artistScore
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 去掉空格与常见标点，便于比较歌名歌手。 */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }
}
