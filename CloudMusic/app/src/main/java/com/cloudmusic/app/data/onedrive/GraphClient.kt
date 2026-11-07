package com.cloudmusic.app.data.onedrive

import com.cloudmusic.app.data.AudioTypes
import com.cloudmusic.app.data.DavEntry
import com.cloudmusic.app.data.DeleteResult
import com.cloudmusic.app.data.Prefs
import com.cloudmusic.app.data.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/**
 * OneDrive 的空间配额。
 *
 * @param total 总额度（字节）
 * @param used 已用（字节）
 * @param remaining 剩余（字节）
 * @param deleted 在回收站里的（字节）
 */
data class DriveQuota(
    val total: Long,
    val used: Long,
    val remaining: Long,
    val deleted: Long,
) {
    /** 已用百分比（0f~1f） */
    val usedRatio: Float
        get() = if (total <= 0L) 0f else (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    /** 剩余空间大概够放几首 30 MB 的歌 */
    val songsLeft: Long
        get() = if (remaining <= 0L) 0L else remaining / (30L * 1024 * 1024)
}

/**
 * Microsoft Graph 客户端（只用到 OneDrive 那部分接口）。
 *
 * 目录列举走 /me/drive/root/children 和 /me/drive/items/{id}/children，
 * 带 @odata.nextLink 自动翻页；播放走 /content（会 302 到预签名直链，支持 Range）。
 */
class GraphClient(
    private val auth: OneDriveAuth,
    private val prefs: Prefs,
    private val http: OkHttpClient,
) {

    /** 上传（简单上传 + 大文件分块会话） */
    private val uploader = OneDriveUploader(http, prefs) { accessToken() }


    companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val SELECT =
            "id,name,size,file,folder,parentReference,lastModifiedDateTime"
    }

    /** 拿到可用的 access token，过期就自动用 refresh_token 换新的 */
    suspend fun accessToken(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val cached = prefs.oneDriveAccessToken
        if (!force && cached.isNotBlank() && System.currentTimeMillis() < prefs.oneDriveTokenExpiry - 60_000) {
            return@withContext cached
        }
        val refreshed = auth.refresh(
            prefs.oneDriveClientId,
            prefs.oneDriveRefreshToken,
            prefs.oneDriveAuthority,
            prefs.oneDriveScopeMode,
        ) ?: return@withContext null
        prefs.saveOneDriveTokens(refreshed.accessToken, refreshed.refreshToken, refreshed.expiresAt)
        refreshed.accessToken
    }

    /** 列目录，folderId 为 null 表示根目录 */
    suspend fun listChildren(folderId: String?): List<DavEntry> {
        val out = mutableListOf<DavEntry>()
        var url: String? = if (folderId == null) {
            "$GRAPH/me/drive/root/children?\$select=$SELECT&\$top=200"
        } else {
            "$GRAPH/me/drive/items/$folderId/children?\$select=$SELECT&\$top=200"
        }

        var guard = 0
        while (url != null && guard < 200) {
            guard++
            val body = get(url) ?: break
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                break
            }
            val arr = json.optJSONArray("value") ?: break
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isEmpty() || name.isEmpty()) continue
                val isFolder = o.has("folder")
                val mime = o.optJSONObject("file")?.optString("mimeType").orEmpty()
                    .takeIf { it.startsWith("audio/", ignoreCase = true) }
                    ?: AudioTypes.mimeOf(name)
                out.add(
                    DavEntry(
                        href = id,
                        name = name,
                        isDirectory = isFolder,
                        size = o.optLong("size", 0L),
                        lastModified = 0L,
                        contentType = if (isFolder) "" else mime,
                        parentPath = o.optJSONObject("parentReference")
                            ?.optString("path").orEmpty()
                    )
                )
            }
            url = json.optString("@odata.nextLink").takeIf { it.isNotEmpty() }
        }
        return out
    }

    /**
     * 删掉一个文件。
     *
     * Graph 的 DELETE 成功返回 204；404 当成「已经不在了」，结果一样。
     * 失败时把状态码和 Graph 给的原因带回去 —— 用户要知道自己该换权限还是该重登。
     *
     * 遇到 401 就换一个新 token 再试一次：本地缓存的 token 有时会莫名其妙失效
     * （被换掉、过期判断跑偏），重新授权一次太折腾，能自己救回来就别麻烦用户。
     */
    suspend fun delete(itemId: String): DeleteResult = withContext(Dispatchers.IO) {
        var token = accessToken() ?: return@withContext DeleteResult.Failed(
            "删除失败：OneDrive 登录已过期，回登录页重新授权一次"
        )
        var code = deleteRequest(itemId, token)
        if (code == 401) {
            token = accessToken(force = true) ?: return@withContext DeleteResult.Failed(
                "删除失败：OneDrive 登录已过期，回登录页重新授权一次"
            )
            code = deleteRequest(itemId, token)
        }
        when (code) {
            204, 200, 404 -> DeleteResult.Ok
            401 -> DeleteResult.Failed("删除失败：OneDrive 登录已过期，回登录页重新授权一次")
            403 -> DeleteResult.Failed(
                "删除失败：这个 OneDrive 应用只有「读取」权限，删除需要写权限。" +
                    "回登录页把「请求的权限」换成「读写」，重新授权一次就行"
            )
            -1 -> DeleteResult.Failed("删除失败：连不上 OneDrive，检查一下网络")
            else -> DeleteResult.Failed("删除失败：OneDrive 返回 HTTP $code")
        }
    }

    /** 发一次删除请求，返回状态码；连不上返回 -1。 */
    private fun deleteRequest(itemId: String, token: String): Int {
        val request = Request.Builder()
            .url("$GRAPH/me/drive/items/$itemId")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (resp.code >= 400) {
                    val body = resp.body?.string().orEmpty()
                    val reason = runCatching {
                        JSONObject(body).optJSONObject("error")
                            ?.optString("message").orEmpty()
                    }.getOrDefault("")
                    prefs.logAuth("删除 OneDrive 文件失败 HTTP ${resp.code}: $reason")
                }
                resp.code
            }
        } catch (e: Exception) {
            prefs.logAuth("删除 OneDrive 文件失败：${e.javaClass.simpleName}: ${e.message}")
            -1
        }
    }

    /** 下载小文件内容（歌词文件），失败返回 null */
    suspend fun downloadText(itemId: String): String? = withContext(Dispatchers.IO) {        val token = accessToken() ?: return@withContext null
        val request = Request.Builder()
            .url("$GRAPH/me/drive/items/$itemId/content")
            .header("Authorization", "Bearer $token")
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                decodeText(bytes)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 播放地址：Graph 会 302 到带签名的直链，OkHttp 自动跟随且保留 Range */
    fun playUrl(itemId: String): String = "$GRAPH/me/drive/items/$itemId/content"

    /** 在某个目录下建文件夹（已存在就复用），返回新文件夹的 item id */
    suspend fun ensureFolder(parentId: String?, name: String): String? =
        withContext(Dispatchers.IO) {
            val token = accessToken() ?: return@withContext null
            // 已经存在就复用
            listChildren(parentId).firstOrNull { it.name == name && it.isDirectory }
                ?.let { return@withContext it.href }

            val body = JSONObject()
                .put("name", name)
                .put("folder", JSONObject())
                .put("@microsoft.graph.conflictBehavior", "fail")
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val url = if (parentId.isNullOrEmpty()) {
                "$GRAPH/me/drive/root/children"
            } else {
                "$GRAPH/me/drive/items/$parentId/children"
            }
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", "Bearer $token")
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    JSONObject(resp.body?.string().orEmpty())
                        .optString("id").takeIf { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * 上传文件到指定文件夹。
     *
     * 小文件走一次 PUT，大文件（>250 MB）自动切成分块上传会话。
     */
    suspend fun uploadFile(
        folderId: String,
        fileName: String,
        file: File,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): UploadResult = uploader.upload(folderId, fileName, file, onProgress)

    /**
     * 查空间配额。
     *
     * `/me/drive/quota` 一把就能拿到总额度、已用、剩余。
     * 免费版 5 GB、Microsoft 365 个人版 1 TB，个人账号下这个接口一直可用。
     */
    suspend fun quota(): DriveQuota? {
        val body = get("$GRAPH/me/drive/quota") ?: return null
        return try {
            val json = JSONObject(body)
            val total = json.optLong("total")
            val used = json.optLong("used")
            val remaining = json.optLong("remaining")
            val deleted = json.optLong("deleted")
            if (total <= 0L && remaining <= 0L) null
            else {
                prefs.logAuth(
                    "空间配额 total=$total used=$used remaining=$remaining deleted=$deleted"
                )
                DriveQuota(total, used, remaining, deleted)
            }
        } catch (e: Exception) {
            prefs.logAuth("解析 quota 返回失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 当前登录的账号昵称 */
    suspend fun accountName(): String? {
        // /me 要 User.Read。个人账号（MSA）拿到的回应里 mail 经常是空的，
        // 所以三个字段依次兜底；全都空就当成拿不到，别写个空字符串进 Prefs ——
        // 设置页会因此显示「账号：已连接」，看着像没登上。
        val body = get("$GRAPH/me?\$select=displayName,mail,userPrincipalName")
        if (body == null) {
            prefs.logAuth("拉取账号昵称失败：/me 没有返回 2xx（不影响播放，只是设置页显示不出邮箱）")
            return null
        }
        return try {
            val json = JSONObject(body)
            listOf("displayName", "mail", "userPrincipalName")
                .map { json.optString(it) }
                .firstOrNull { it.isNotBlank() }
        } catch (e: Exception) {
            prefs.logAuth("解析 /me 返回失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * GET 一个 Graph 地址。
     *
     * 一定要在 IO 线程上跑：`execute()` 是阻塞调用，而调用方常常在
     * viewModelScope（主线程）里 —— 不切线程会直接抛
     * `NetworkOnMainThreadException`，表现就是「账号昵称永远拉不到」。
     */
    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val token = accessToken() ?: throw IllegalStateException("OneDrive 授权已失效，请重新授权")
        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { resp ->
                if (resp.code == 401) {
                    throw IllegalStateException("OneDrive 授权已失效，请重新授权")
                }
                if (resp.code == 403) {
                    throw IllegalStateException(
                        "这个应用 ID 没有读取 OneDrive 的权限（403）。\n" +
                            "微软的第一方应用一般只预授权了部分权限。如果用的是内置 ID，" +
                            "请把登录页的「请求的权限」换一档再试；还是不行就得自己注册一个应用" +
                            "（App 里有一键跳转的教程，约 5 分钟）。"
                    )
                }
                if (!resp.isSuccessful) {
                    // 非 2xx 全都记下来：401/403 上面已经翻译过了，
                    // 剩下的大多是「权限没同意」或「路径写错」，原始返回最说明问题。
                    prefs.logAuth(
                        "GET ${url.substringBefore("?")} http=${resp.code} " +
                            "body=${resp.body?.string().orEmpty().take(300)}"
                    )
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            prefs.logAuth(
                "GET ${url.substringBefore("?")} 异常 ${e.javaClass.simpleName}: " +
                    "${e.message}（已按「拿不到」处理）"
            )
            null
        }
    }

    /** 歌词文件常见 UTF-8 与 GBK 两种编码，UTF-8 解出乱码时退回 GBK。 */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return try {
            String(bytes, Charset.forName("GBK"))
        } catch (e: Exception) {
            utf8
        }
    }
}
