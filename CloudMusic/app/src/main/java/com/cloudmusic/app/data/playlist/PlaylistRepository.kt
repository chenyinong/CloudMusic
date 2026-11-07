package com.cloudmusic.app.data.playlist

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 歌单引用：平台 + 歌单 id。 */
data class PlaylistRef(val platform: PlaylistPlatform, val playlistId: String)

/**
 * 从用户粘进来的东西里认出歌单。
 *
 * 分享链接实测有这些形态（都能认）：
 * - 网易云：`playlist?id=` / `#/playlist?id=` / `playlist/{id}` /
 *   `y.music.163.com/m/playlist?id=` / 纯数字
 * - QQ：`y.qq.com/n/ryqq/playlist/{id}` / `taoge.html?id=`（id 就是 disstid）/
 *   `n8/y/play/playlist.html?id=` /
 *   `c.y.qq.com/base/fcgi-bin/u?...&itemurl=<编码后的歌单链接>`
 *
 * 最后一种是从 QQ 转发到微信的分享文本长什么样，靠把 `itemurl` 解出来再认。
 * 纯数字有歧义（两家 id 都是数字），默认按网易云处理，界面上让用户能改。
 */
object PlaylistParser {

    private val NETEASE_ID = Regex("(?:playlist\\?id=|playlist/)(\\d+)")
    private val QQ_ID = Regex("(?:playlist/|taoge\\.html\\?id=|disstid=|[?&]id=)(\\d+)")
    private val ITEM_URL = Regex("itemurl=([^&\\s]+)", RegexOption.IGNORE_CASE)

    fun parse(input: String): PlaylistRef? {
        var s = input.trim()
        if (s.isEmpty()) return null

        // 转发链接里嵌着真正的歌单链接，解出来再认
        val nested = ITEM_URL.find(s)?.groupValues?.get(1)
        if (nested != null) {
            val decoded = decodeRepeatedly(nested)
            parse(decoded)?.let { return it }
            s = decoded
        }

        val lower = s.lowercase()
        if (lower.contains("qq.com")) {
            val m = QQ_ID.find(s) ?: return null
            return PlaylistRef(PlaylistPlatform.QQ, m.groupValues[1])
        }
        if (lower.contains("163.com")) {
            val m = NETEASE_ID.find(s) ?: return null
            return PlaylistRef(PlaylistPlatform.NETEASE, m.groupValues[1])
        }
        if (s.matches(Regex("\\d+"))) return PlaylistRef(PlaylistPlatform.NETEASE, s)
        return null
    }

    /** 分享链接可能被编码过好几次（`%253A` 是 `%3A` 的再编码）。 */
    private fun decodeRepeatedly(s: String): String {
        var out = s
        repeat(3) {
            val next = try {
                java.net.URLDecoder.decode(out, "UTF-8")
            } catch (e: Exception) {
                return out
            }
            if (next == out) return out
            out = next
        }
        return out
    }
}

/**
 * 拉歌单清单。
 *
 * 两个平台的接口都是公开的，但都挑人：
 * - 网易云简单，`/api/v6/playlist/detail` 拿 trackIds，再用 `/api/song/detail/` 批量补详情
 * - QQ 得用新版 H5 的 `musicu.fcg`，Referer 和 comm.cv 不对就直接 500003
 */
class PlaylistRepository(private val http: OkHttpClient) {

    companion object {
        private const val NETEASE = "https://music.163.com"
        private const val QQ_HOST = "https://u.y.qq.com"
        private const val UA_PC =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        private const val UA_QQ =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
        private const val QQ_REFERER = "https://i2.y.qq.com/n3/other/pages/details/playlist.html"
    }

    suspend fun fetch(ref: PlaylistRef): ImportedPlaylist = withContext(Dispatchers.IO) {
        when (ref.platform) {
            PlaylistPlatform.NETEASE -> fetchNetease(ref.playlistId)
            PlaylistPlatform.QQ -> fetchQQ(ref.playlistId)
        }
    }

    /**
     * 认出歌单。
     *
     * 短链（t.cn、url.cn 之类）离线认不出来，得跟一次重定向拿到真正的地址。
     */
    suspend fun resolveRef(input: String): PlaylistRef? = withContext(Dispatchers.IO) {
        PlaylistParser.parse(input)?.let { return@withContext it }
        val finalUrl = followRedirect(input) ?: return@withContext null
        PlaylistParser.parse(finalUrl)
    }

    /** 手动跟重定向，最多 6 跳。 */
    private fun followRedirect(url: String): String? {
        var current = url
        for (i in 0 until 6) {
            val loc = try {
                val noRedirect = http.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
                val request = Request.Builder()
                    .url(current)
                    .header("User-Agent", UA_PC)
                    .build()
                noRedirect.newCall(request).execute().use { resp -> resp.header("Location") }
            } catch (e: Exception) {
                null
            } ?: return null
            if (loc.isNullOrBlank()) return current
            // App 禁明文流量，重定向到 http 的要升成 https
            current = if (loc.startsWith("http://")) "https://" + loc.substring(7) else loc
        }
        return current
    }

    // ---------------------------------------------------------------- 网易云

    private fun fetchNetease(id: String): ImportedPlaylist {
        val body = get("$NETEASE/api/v6/playlist/detail?id=$id&n=1000&s=0", "$NETEASE/", UA_PC)
            ?: throw PlaylistException("拉不到歌单，检查一下网络或 id 对不对")
        val obj = JSONObject(body).optJSONObject("playlist")
            ?: throw PlaylistException("歌单不存在，或者是私享/需要登录的歌单")
        val name = obj.optString("name").trim().ifBlank { "未命名歌单" }

        // trackIds 才是全量，tracks 字段只带前 N 首的完整信息
        val ids = mutableListOf<Long>()
        obj.optJSONArray("trackIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optLong("id")?.takeIf { it > 0 }?.let { ids.add(it) }
            }
        }
        if (ids.isEmpty()) throw PlaylistException("这张歌单里没有歌")

        val tracks = mutableListOf<PlaylistTrack>()
        var i = 0
        while (i < ids.size) {
            val end = minOf(i + 50, ids.size)
            val batch = ids.subList(i, end)
            val detail = get(
                "$NETEASE/api/song/detail/?ids=" + enc(JSONArray(batch).toString()),
                "$NETEASE/", UA_PC
            )
            if (detail != null) {
                val songs = JSONObject(detail).optJSONArray("songs")
                if (songs != null) {
                    for (j in 0 until songs.length()) {
                        val s = songs.optJSONObject(j) ?: continue
                        val sid = s.optLong("id").takeIf { it > 0 } ?: continue
                        val title = s.optString("name").trim()
                        if (title.isEmpty()) continue
                        val artists = mutableListOf<String>()
                        s.optJSONArray("artists")?.let { arr ->
                            for (k in 0 until arr.length()) {
                                arr.optJSONObject(k)?.optString("name")
                                    ?.takeIf { it.isNotBlank() }?.let { artists.add(it) }
                            }
                        }
                        tracks.add(
                            PlaylistTrack(
                                key = "netease:$sid",
                                title = title,
                                artist = artists.joinToString(" / "),
                                album = s.optJSONObject("album")?.optString("name").orEmpty(),
                                durationSec = (s.optLong("duration") / 1000).toInt(),
                                platform = PlaylistPlatform.NETEASE,
                                platformId = sid.toString()
                            )
                        )
                    }
                }
            }
            i = end
        }

        return ImportedPlaylist(name, PlaylistPlatform.NETEASE, id, tracks)
    }

    // ------------------------------------------------------------------ QQ

    private fun fetchQQ(disstid: String): ImportedPlaylist {
        val numericId = disstid.toLongOrNull()
            ?: throw PlaylistException("歌单 id 不合法")

        val tracks = mutableListOf<PlaylistTrack>()
        var name = ""
        var total = 0
        var begin = 0
        var guard = 0

        val seen = mutableSetOf<String>()
        while (guard++ < 200) {
            val param = JSONObject()
                .put("disstid", numericId)
                .put("enc_host_uin", "")
                .put("tag", 1)
                .put("userinfo", 1)
                .put("song_begin", begin)
                .put("song_num", 300)
                .put("orderlist", 1)
            val body = JSONObject()
                .put("comm", JSONObject().put("cv", "10020000"))
                .put(
                    "req_0", JSONObject()
                        .put("module", "music.srfDissInfo.aiDissInfo")
                        .put("method", "uniform_get_Dissinfo")
                        .put("param", param)
                )
                .toString()

            val text = get("$QQ_HOST/cgi-bin/musicu.fcg?data=" + enc(body), QQ_REFERER, UA_QQ)
                ?: throw PlaylistException("连不上 QQ 音乐服务器")
            val req = JSONObject(text).optJSONObject("req_0")
                ?: throw PlaylistException("QQ 音乐返回格式变了，这个歌单先拉不了")
            if (req.optInt("code") != 0) {
                throw PlaylistException(qqErrorMessage(req.optInt("code")))
            }
            val data = req.optJSONObject("data") ?: break
            if (name.isBlank()) {
                name = data.optJSONObject("dirinfo")?.optString("title").orEmpty()
                total = data.optInt("total_song_num", 0)
            }
            val list = data.optJSONArray("songlist") ?: break
            if (list.length() == 0) break

            val before = tracks.size
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val mid = s.optString("songmid").ifBlank { s.optString("mid") }
                val title = s.optString("title").trim()
                    .ifBlank { s.optString("title_hilight").trim() }
                if (title.isEmpty()) continue
                val singers = mutableListOf<String>()
                s.optJSONArray("singer")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        arr.optJSONObject(j)?.optString("name")
                            ?.takeIf { it.isNotBlank() }?.let { singers.add(it) }
                    }
                }
                val key = if (mid.isBlank()) "qq:$begin:$i" else "qq:$mid"
                // 同一首歌可能因分页边界被重复返回，去重一下
                if (!seen.add(key)) continue
                tracks.add(
                    PlaylistTrack(
                        key = key,
                        title = title,
                        artist = singers.joinToString(" / "),
                        album = s.optJSONObject("album")?.optString("name").orEmpty(),
                        durationSec = s.optInt("interval", 0),
                        platform = PlaylistPlatform.QQ,
                        platformId = null
                    )
                )
            }

            Log.i(
                "CloudMusicPlaylist",
                "QQ 歌单 $disstid：第 ${guard} 页拉到 ${list.length()} 首，累计 ${tracks.size}" +
                    if (total > 0) "（服务端称共 $total 首）" else ""
            )

            begin += list.length()
            // 不再用 hasmore / total_song_num 提前收尾 —— 实测这俩字段不准时会
            // 只拉到一页（30 首）就停。只认两种真正的「到底」信号：
            // 1) 本页一首歌都没返回（上面已 break）；2) 本页没带来任何新歌（在打转）。
            if (tracks.size == before) break
        }

        if (tracks.isEmpty()) throw PlaylistException("这张歌单里没拉到歌，可能需要登录")
        return ImportedPlaylist(name.ifBlank { "未命名歌单" }, PlaylistPlatform.QQ, disstid, tracks)
    }

    // ------------------------------------------------------------------ 工具

    private fun get(url: String, referer: String, ua: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** QQ 的错误码翻译成人话。 */
    private fun qqErrorMessage(code: Int): String = when (code) {
        80120 -> "这个歌单不存在或者已经下架了"
        10006 -> "歌单 id 不合法"
        500003 -> "QQ 音乐拒绝了这次请求，稍后再试试"
        0 -> "拉不到歌单"
        else -> "QQ 音乐接口报错（$code），这个歌单可能需要登录或者已经失效"
    }
}
