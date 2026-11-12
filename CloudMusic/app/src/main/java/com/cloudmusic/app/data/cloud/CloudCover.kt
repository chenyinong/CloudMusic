package com.cloudmusic.app.data.cloud

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端歌单的封面。
 *
 * 口径跟网页版一致：**歌单封面 = 第一首歌的封面**。
 *
 * 云端存的曲目里不一定带封面字段（安卓上传的就没有），
 * 这里按「平台 + 平台内歌曲 id」直接问平台要封面直链 ——
 * 比按歌名歌手搜索准得多（不会张冠李戴），也快得多（一次请求一批）。
 */
class CloudCover(private val http: OkHttpClient) {

    companion object {
        private const val NETEASE = "https://music.163.com"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        /** 一次最多查多少首歌的封面（网易云批量接口的上限） */
        private const val BATCH = 50

        /**
         * 一张歌单最多补多少首的封面。
         *
         * 歌单封面只认第一首，补前三首是留个余量（第一首万一查不到）。
         * 补得越多越慢 —— 上传要等这个，不能拖成几十秒。
         */
        private const val FILL_LIMIT = 3
    }

    /**
     * 一张歌单的封面。
     *
     * 先用曲目里自带的封面（网页版创建的歌单都带），
     * 没有再按平台 id 去问平台要。
     */
    suspend fun coverOfPlaylist(tracks: List<CloudTrack>): String = withContext(Dispatchers.IO) {
        tracks.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl?.let { return@withContext it }
        val first = tracks.firstOrNull() ?: return@withContext ""
        coverOfTrack(first)
    }

    /**
     * 给一批曲目补上封面直链。
     *
     * 只补「平台 + 平台内 id」齐全的那些（目前是网易云），
     * 补不上的留空 —— 网页版那边还有一层按歌名歌手搜的兜底。
     */
    suspend fun fillCovers(tracks: List<CloudTrack>): List<CloudTrack> =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext tracks
            val need = tracks.withIndex()
                .filter { (_, t) ->
                    t.coverUrl.isBlank() && t.platform == "netease" && t.platformId.isNotBlank()
                }
                .take(FILL_LIMIT)
            if (need.isEmpty()) return@withContext tracks

            val ids = need.map { it.value.platformId }
            val covers = neteaseCovers(ids)
            if (covers.isEmpty()) return@withContext tracks

            tracks.mapIndexed { i, t ->
                val c = covers[t.platformId]
                if (c.isNullOrBlank() || t.coverUrl.isNotBlank()) t else t.copy(coverUrl = c)
            }
        }

    /** 单首歌的封面直链，查不到就是空串。 */
    suspend fun coverOfTrack(t: CloudTrack): String = withContext(Dispatchers.IO) {
        if (t.coverUrl.isNotBlank()) return@withContext t.coverUrl
        if (t.platform != "netease" || t.platformId.isBlank()) return@withContext ""
        neteaseCovers(listOf(t.platformId))[t.platformId].orEmpty()
    }

    /**
     * 网易云批量取封面：`/api/song/detail/?ids=[id1,id2,...]`。
     *
     * 返回 songId → 封面直链。QQ 歌单的曲目没存 mid，这里管不了，
     * 留给网页版按歌名歌手搜的那条兜底。
     */
    private fun neteaseCovers(songIds: List<String>): Map<String, String> {
        if (songIds.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (chunk in songIds.chunked(BATCH)) {
            val ids = JSONArray().apply { for (id in chunk) put(id) }
            val text = try {
                val req = Request.Builder()
                    .url("$NETEASE/api/song/detail/?ids=" + Uri.encode(ids.toString()))
                    .header("User-Agent", UA)
                    .header("Referer", "$NETEASE/")
                    .get()
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use ""
                    r.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                ""
            }
            if (text.isBlank()) continue
            try {
                val songs = JSONObject(text).optJSONArray("songs") ?: continue
                for (i in 0 until songs.length()) {
                    val s = songs.optJSONObject(i) ?: continue
                    val sid = s.optLong("id").takeIf { it > 0 }?.toString() ?: continue
                    val pic = s.optJSONObject("album")?.optString("picUrl").orEmpty().trim()
                    if (pic.isNotEmpty()) out[sid] = pic.replaceFirst("http://", "https://")
                }
            } catch (e: Exception) {
                // 这一批解析不了就跳过，别把整次同步带崩
            }
        }
        return out
    }
}
