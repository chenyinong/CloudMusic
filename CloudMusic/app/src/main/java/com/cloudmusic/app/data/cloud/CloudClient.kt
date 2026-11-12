package com.cloudmusic.app.data.cloud

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端接口客户端。
 *
 * 跟网页版共用同一套 Cloudflare Workers 接口：
 * - `POST /api/auth/login` 换令牌
 * - `GET  /api/me` 账号信息
 * - `GET/POST/DELETE /api/favorites` 收藏
 * - `GET/POST/DELETE /api/playlists` 歌单
 * - `GET/POST/DELETE /api/settings/quark` 夸克 Cookie
 *
 * 令牌由调用方通过 [token] 提供（存在 Prefs 里），过期了服务端会返 401，
 * 这里翻译成一句人话。
 */
class CloudClient(
    private val http: OkHttpClient,
    /** 云端服务器地址，比如 https://xxx.workers.dev */
    private val baseUrl: () -> String,
    /** 登录令牌，空串表示还没登录 */
    private val token: () -> String,
) {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private fun root(): String {
        val raw = baseUrl().trim()
        if (raw.isEmpty()) throw CloudException("还没填云端服务器地址")
        val full = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        return full.trimEnd('/')
    }

    // ---------------- 账号 ----------------

    suspend fun login(username: String, password: String): CloudLogin =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("username", username.trim())
                put("password", password)
            }
            val json = send("POST", "/api/auth/login", body, auth = false)
            val t = json.optString("token").trim()
            if (t.isEmpty()) throw CloudException("服务端没返回令牌，登录失败")
            CloudLogin(t, json.optString("username").ifBlank { username.trim() })
        }

    suspend fun me(): CloudUser = withContext(Dispatchers.IO) {
        val json = send("GET", "/api/me", null)
        CloudUser(json.optLong("id"), json.optString("username"))
    }

    // ---------------- 收藏 ----------------

    suspend fun favorites(): List<CloudFavorite> = withContext(Dispatchers.IO) {
        val arr = sendArray("/api/favorites")
        val out = mutableListOf<CloudFavorite>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key").trim()
            if (key.isEmpty()) continue
            out.add(
                CloudFavorite(
                    key = key,
                    track = o.optJSONObject("track")?.let { CloudTrack.fromJson(it) },
                    deleted = o.optBoolean("deleted", false) || o.optInt("deleted", 0) == 1,
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                )
            )
        }
        out
    }

    suspend fun addFavorite(track: CloudTrack) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("key", track.key)
            put("track", track.toJson())
        }
        send("POST", "/api/favorites", body)
    }

    suspend fun removeFavorite(key: String) = withContext(Dispatchers.IO) {
        send("DELETE", "/api/favorites/${Uri.encode(key)}", null)
    }

    // ---------------- 歌单 ----------------

    suspend fun playlists(): List<CloudPlaylistRef> = withContext(Dispatchers.IO) {
        val arr = sendArray("/api/playlists")
        val out = mutableListOf<CloudPlaylistRef>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            if (id <= 0) continue
            out.add(
                CloudPlaylistRef(
                    id = id,
                    name = text(o, "name"),
                    platform = text(o, "platform"),
                    updatedAt = o.optLong("updated_at", o.optLong("updatedAt")),
                    trackCount = o.optInt("count", o.optInt("trackCount")),
                    cover = text(o, "cover"),
                )
            )
        }
        out
    }

    suspend fun playlist(id: Long): CloudPlaylistDetail = withContext(Dispatchers.IO) {
        val json = send("GET", "/api/playlists/$id", null)
        CloudPlaylistDetail(
            id = json.optLong("id", id),
            name = text(json, "name"),
            platform = text(json, "platform"),
            updatedAt = json.optLong("updated_at", json.optLong("updatedAt")),
            tracks = parseTracks(json.opt("tracks")),
        )
    }

    suspend fun createPlaylist(
        name: String,
        platform: String,
        tracks: List<CloudTrack>,
    ): Long = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (t in tracks) arr.put(t.toJson())
        val body = JSONObject().apply {
            put("name", name.trim().ifEmpty { "我的歌单" })
            put("platform", platform.trim().ifEmpty { "netease" })
            put("tracks", arr)
        }
        send("POST", "/api/playlists", body).optLong("id")
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        send("DELETE", "/api/playlists/$id", null)
    }

    // ---------------- 夸克 Cookie ----------------

    /** 云端存的夸克 Cookie。没有就返回 null。 */
    suspend fun quarkCookie(): String? = withContext(Dispatchers.IO) {
        val json = send("GET", "/api/settings/quark", null)
        if (!json.optBoolean("hasCookie", false)) return@withContext null
        json.optString("cookie").trim().takeIf { it.isNotEmpty() }
    }

    suspend fun saveQuarkCookie(cookie: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("cookie", cookie.trim()) }
        send("POST", "/api/settings/quark", body)
    }

    suspend fun deleteQuarkCookie() = withContext(Dispatchers.IO) {
        send("DELETE", "/api/settings/quark", null)
    }

    // ---------------- 底层 ----------------

    /**
     * 取字符串字段。
     *
     * `JSONObject.optString` 有个坑：字段是 JSON `null` 时它返回的是
     * **字符串 "null"**（不是空串）。云端歌单没有封面时 `cover` 就是 null，
     * 照单全收的话就会拿 "null" 当图片地址去下载，怎么都下不来。
     * 这里统一当成没有。
     */
    private fun text(o: JSONObject, name: String): String {
        if (!o.has(name) || o.isNull(name)) return ""
        val s = o.optString(name).trim()
        return if (s.isEmpty() || s == "null") "" else s
    }

    /** 有些接口直接返回数组，而不是包在对象里。 */
    private fun sendArray(path: String): JSONArray {
        val url = root() + path
        val builder = Request.Builder().url(url).get()
        val t = token()
        if (t.isNotBlank()) builder.header("Authorization", "Bearer $t")
        val resp = try {
            http.newCall(builder.build()).execute()
        } catch (e: Exception) {
            throw CloudException("连不上云端服务器：${e.message ?: "网络异常"}")
        }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw CloudException(friendly(r.code, text))
            return try {
                JSONArray(text)
            } catch (e: Exception) {
                throw CloudException("云端返回的数据格式不对")
            }
        }
    }

    private fun send(
        method: String,
        path: String,
        body: JSONObject?,
        auth: Boolean = true,
    ): JSONObject {
        val url = root() + path
        val builder = Request.Builder().url(url)
        if (auth) {
            val t = token()
            if (t.isNotBlank()) builder.header("Authorization", "Bearer $t")
        }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> {
                builder.header("Content-Type", "application/json")
                builder.post((body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA))
            }
        }
        val resp = try {
            http.newCall(builder.build()).execute()
        } catch (e: Exception) {
            throw CloudException("连不上云端服务器：${e.message ?: "网络异常"}")
        }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            if (!r.isSuccessful) throw CloudException(friendly(r.code, text, json))
            return json
        }
    }

    private fun friendly(code: Int, text: String, json: JSONObject? = null): String {
        val msg = json?.optString("error")?.trim().orEmpty()
            .ifEmpty { runCatching { JSONObject(text).optString("error") }.getOrNull()?.trim().orEmpty() }
        if (msg.isNotEmpty()) return msg
        return when (code) {
            401 -> "登录已失效，请重新登录"
            404 -> "云端没找到这个内容"
            in 500..599 -> "云端服务器出错了（$code）"
            else -> "请求失败（HTTP $code）"
        }
    }

    /**
     * 云端把曲目清单存成 JSON **字符串**（不是数组），这里两种都能吃下。
     */
    private fun parseTracks(raw: Any?): List<CloudTrack> = when (raw) {
        null -> emptyList()
        is JSONArray -> CloudTrack.listFromJson(raw)
        is String -> if (raw.isBlank()) emptyList() else try {
            CloudTrack.listFromJson(JSONArray(raw))
        } catch (e: Exception) {
            emptyList()
        }
        else -> emptyList()
    }
}
