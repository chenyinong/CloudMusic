package com.cloudmusic.app.data.playlist

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** liumingye 搜到的一条。 */
data class LiumingyeHit(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String,
    /** 可用音质，如「夸克MP3」「夸克FLAC」「百度云」 */
    val quality: List<String>,
)

/** 一个可下载来源。 */
data class LiumingyeDownload(
    /** 网盘分享链接 */
    val url: String,
    /** 音质说明 */
    val quality: String,
)

/** 详情。`lyrics` 是完整 LRC 文本。 */
data class LiumingyeDetail(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String,
    val lyrics: String,
    val downloads: List<LiumingyeDownload>,
) {
    /** 挑一个音质最高的（FLAC > WAV > MP3 > 其它） */
    fun best(): LiumingyeDownload? = downloads.maxByOrNull { qualityRank(it.quality) }

    private fun qualityRank(q: String): Int = when {
        q.contains("FLAC", true) -> 5
        q.contains("WAV", true) -> 4
        q.contains("MP3", true) -> 3
        q.contains("百度", true) -> 2
        else -> 1
    }
}

/**
 * liumingye.cn（下歌吧）的公开接口。
 *
 * 这站是网盘资源聚合站，好处是能搜到网易云下不到的曲子，
 * 而且详情里直接带**完整歌词**。
 *
 * 代价是它只给网盘分享链接，音频得自己从网盘拿 ——
 * 配合 [com.cloudmusic.app.data.quark.QuarkClient] 走夸克那条线。
 *
 * 接口随时会变（`/api/source/geturl` 就是这两天新加的），
 * 所以只能当兜底源，不能当主下载源。
 */
class LiumingyeSource(private val http: OkHttpClient) {

    companion object {
        private const val HOST = "https://so.liumingye.cn"
        private const val UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"
    }

    /** 搜索。限流 30 次/60 秒。 */
    suspend fun search(keyword: String): List<LiumingyeHit> = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@withContext emptyList()
        val json = try {
            getJson("$HOST/api/music/search?q=${enc(kw)}")
        } catch (e: Exception) {
            Log.w("CM/LiumingyeHttp", "关键词「$kw」搜索失败：${e.message}")
            throw e
        }
        val arr = json.optJSONArray("data") ?: return@withContext emptyList()
        Log.d("CM/LiumingyeHttp", "关键词「$kw」返回 ${arr.length()} 条")
        val out = mutableListOf<LiumingyeHit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            val qualities = mutableListOf<String>()
            o.optJSONArray("quality")?.let { q ->
                for (j in 0 until q.length()) {
                    q.optString(j).takeIf { it.isNotBlank() }?.let { qualities.add(it) }
                }
            }
            out.add(
                LiumingyeHit(
                    id = id,
                    title = title,
                    artist = o.optString("artist").trim(),
                    album = o.optString("album").trim(),
                    cover = o.optString("cover"),
                    quality = qualities,
                )
            )
        }
        out
    }

    /** 详情：歌词 + 各音质的分享链接。 */
    suspend fun detail(id: String): LiumingyeDetail = withContext(Dispatchers.IO) {
        val json = getJson("$HOST/api/music/${enc(id)}")
        val downloads = mutableListOf<LiumingyeDownload>()
        json.optJSONArray("downloads")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url").trim()
                if (u.isEmpty()) continue
                downloads.add(LiumingyeDownload(url = u, quality = o.optString("quality")))
            }
        }
        LiumingyeDetail(
            id = json.optString("id", id),
            title = json.optString("title").trim(),
            artist = json.optString("artist").trim(),
            album = json.optString("album").trim(),
            cover = json.optString("cover"),
            lyrics = json.optString("lyrics"),
            downloads = downloads,
        )
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA_PC)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$HOST/")
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w("CM/LiumingyeHttp", "HTTP ${resp.code} $url：${text.take(160)}")
                throw LiumingyeException("HTTP ${resp.code}：${text.take(160)}")
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw LiumingyeException("返回格式不对：${text.take(160)}")
            }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** liumingye 接口出错。 */
class LiumingyeException(message: String) : Exception(message)
