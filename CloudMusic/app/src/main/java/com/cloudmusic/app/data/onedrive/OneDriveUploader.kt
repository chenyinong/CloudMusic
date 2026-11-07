package com.cloudmusic.app.data.onedrive

import com.cloudmusic.app.data.Prefs
import com.cloudmusic.app.data.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * OneDrive 上传。
 *
 * 微软有两条路：
 *
 * - **简单上传** `PUT /me/drive/items/{父目录}:/{文件名}:/content`
 *   一次请求传完，最省事，但**单文件上限 250 MB**，超了直接 413。
 * - **上传会话** `POST .../{文件名}:/createUploadSession`
 *   先拿到一个带签名的 uploadUrl，再把文件切成块一块块 PUT 上去，
 *   单文件上限 250 GB。代价是多几次请求。
 *
 * 所以：**小文件走简单上传，超过 250 MB 自动切会话**，省事又能传大文件。
 *
 * 分块大小的硬规定：每块必须是 **320 KiB 的整数倍**（最后一块可以是任意大小）。
 * 这里用 5 MiB = 16 × 320 KiB，正好卡在整数倍上。
 */
class OneDriveUploader(
    private val http: OkHttpClient,
    private val prefs: Prefs,
    private val tokenProvider: suspend () -> String?,
) {

    companion object {
        /** 简单上传的单文件上限 */
        const val SIMPLE_MAX = 250L * 1024 * 1024

        /** 分块大小：5 MiB，正好是 320 KiB 的整数倍 */
        const val CHUNK_SIZE = 5 * 1024 * 1024

        /** 微软规定的分块粒度 */
        const val CHUNK_MULTIPLE = 320 * 1024

        private const val GRAPH_ROOT = "${GraphClient.GRAPH}/me/drive/root"
        private const val GRAPH_ITEMS = "${GraphClient.GRAPH}/me/drive/items"
    }

    /**
     * 上传一个文件。
     *
     * @param folderId 目标文件夹 id；传 "root" 或空串表示根目录
     * @param fileName 云端的文件名（要带扩展名）
     */
    suspend fun upload(
        folderId: String,
        fileName: String,
        file: File,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
    ): UploadResult = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            return@withContext UploadResult.Failed("OneDrive 授权已失效，请重新授权")
        }
        val size = file.length()
        if (size <= 0L) {
            return@withContext UploadResult.Failed("文件是空的，传不上去")
        }
        if (size <= SIMPLE_MAX) {
            simpleUpload(token, folderId, fileName, file, onProgress)
        } else {
            prefs.logAuth("文件 ${file.name} 有 $size 字节，超过 250 MB，改用分块上传会话")
            sessionUpload(token, folderId, fileName, file, onProgress)
        }
    }

    // ------------------------------------------------------------ 简单上传

    private suspend fun simpleUpload(
        token: String,
        folderId: String,
        fileName: String,
        file: File,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
    ): UploadResult {
        val body = com.cloudmusic.app.data.progressRequestBody(
            file, "application/octet-stream", onProgress
        )
        val request = Request.Builder()
            .url(contentUrl(folderId, fileName))
            .put(body)
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    UploadResult.Failed(explain(resp.code, resp.body?.string().orEmpty()))
                } else {
                    val id = runCatching {
                        JSONObject(resp.body?.string().orEmpty()).optString("id")
                    }.getOrDefault("")
                    UploadResult.Ok(id.ifEmpty { fileName })
                }
            }
        } catch (e: Exception) {
            UploadResult.Failed("上传失败：${e.message ?: "网络异常"}")
        }
    }

    // ------------------------------------------------------------ 上传会话

    /**
     * 分块上传。
     *
     * 步骤：先 POST 建会话拿 uploadUrl，再按顺序一块块 PUT。
     * 分块**必须严格顺序**发，不能并发 —— 微软会直接判失败。
     */
    private suspend fun sessionUpload(
        token: String,
        folderId: String,
        fileName: String,
        file: File,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
    ): UploadResult {
        val uploadUrl = createSession(token, folderId, fileName)
            ?: return UploadResult.Failed("OneDrive 上没能创建上传会话，请重试")

        val total = file.length()
        var offset = 0L
        var lastBody = ""
        try {
            file.inputStream().use { input ->
                while (offset < total) {
                    val want = minOf(CHUNK_SIZE.toLong(), total - offset).toInt()
                    val buf = ByteArray(want)
                    var read = 0
                    while (read < want) {
                        val n = input.read(buf, read, want - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read <= 0) break

                    val respBody = putChunk(uploadUrl, buf, read, offset, total)
                    if (respBody == null) {
                        return UploadResult.Failed(
                            "分块上传中断在第 ${offset / CHUNK_SIZE + 1} 块（已传 $offset / $total 字节）"
                        )
                    }
                    // 最后一块的响应里带完整的 item（里面有云端的 id）
                    if (respBody.isNotEmpty()) lastBody = respBody
                    offset += read
                    onProgress?.invoke(offset, total)
                }
            }
        } catch (e: Exception) {
            return UploadResult.Failed("上传失败：${e.message ?: "网络异常"}")
        }

        if (offset < total) {
            return UploadResult.Failed("文件没传完就断了（$offset / $total 字节）")
        }
        // 云端的 item id 要留给播放用，拿不到就退回文件名
        val entryId = runCatching { JSONObject(lastBody).optString("id") }.getOrDefault("")
        return UploadResult.Ok(entryId.ifEmpty { fileName })
    }

    /** 建上传会话，返回带签名的 uploadUrl */
    private suspend fun createSession(
        token: String,
        folderId: String,
        fileName: String,
    ): String? {
        // 同名文件直接覆盖，保持和简单上传一样的行为
        val body = JSONObject()
            .put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace"))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(sessionUrl(folderId, fileName))
            .post(body)
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    prefs.logAuth("创建上传会话失败 http=${resp.code} body=${text.take(300)}")
                    null
                } else {
                    JSONObject(text).optString("uploadUrl").takeIf { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            prefs.logAuth("创建上传会话异常 ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * PUT 一块。网络抖了重试几次 —— 大文件传一小时，中间断一次就白传了。
     *
     * @return 成功时返回响应体（最后一块里带云端 item 的 id），失败返回 null
     */
    private suspend fun putChunk(
        uploadUrl: String,
        buf: ByteArray,
        length: Int,
        offset: Long,
        total: Long,
    ): String? {
        repeat(3) { attempt ->
            val body = buf.copyOf(length).toRequestBody(null)
            val request = Request.Builder()
                .url(uploadUrl)
                .put(body)
                .header("Content-Range", "bytes $offset-${offset + length - 1}/$total")
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    // 202 = 还要继续传下一块；200/201 = 最后一块，收工
                    if (resp.code == 202 || resp.isSuccessful) {
                        return resp.body?.string().orEmpty()
                    }
                    prefs.logAuth(
                        "分块上传失败 http=${resp.code} offset=$offset " +
                            "body=${resp.body?.string().orEmpty().take(200)}"
                    )
                    // 4xx 是请求本身有问题（比如块大小不对），重试没用
                    if (resp.code in 400..499) return null
                }
            } catch (e: Exception) {
                prefs.logAuth("分块上传异常 offset=$offset ${e.javaClass.simpleName}: ${e.message}")
            }
            delay(800L * (attempt + 1))
        }
        return null
    }

    // ------------------------------------------------------------ URL / 报错

    /** 文件名里的特殊字符要转义，否则 URL 直接坏掉 */
    private fun escape(name: String): String = name
        .replace("%", "%25")
        .replace(":", "%3A")
        .replace("/", "%2F")
        .replace("#", "%23")
        .replace("?", "%3F")
        .replace(" ", "%20")

    /**
     * 目标文件地址。
     *
     * Graph 的路径语法是 `{item-id}:/{文件名}:/content`：
     * - 根目录的 item-id 是 `root`，所以是 `root:/文件名:/content`（**root 后面那个冒号不能少**，
     *   少了会被当成「往一个叫 probe.txt 的文件夹里写」而报 400）；
     * - 普通文件夹用自己的 item id：`items/{id}:/{文件名}:/content`。
     */
    private fun contentUrl(folderId: String, fileName: String): String {
        val name = escape(fileName)
        return if (folderId.isBlank() || folderId == "root") {
            "$GRAPH_ROOT:/$name:/content"
        } else {
            "$GRAPH_ITEMS/$folderId:/$name:/content"
        }
    }

    private fun sessionUrl(folderId: String, fileName: String): String {
        val name = escape(fileName)
        return if (folderId.isBlank() || folderId == "root") {
            "$GRAPH_ROOT:/$name:/createUploadSession"
        } else {
            "$GRAPH_ITEMS/$folderId:/$name:/createUploadSession"
        }
    }

    /** 把微软的报错翻译成用户看得懂的中文 */
    private fun explain(code: Int, body: String): String = when (code) {
        403 -> "这个 OneDrive 应用只有「读取」权限，上传需要写权限。\n" +
            "回登录页把「请求的权限」换成「**读写**」，重新授权一次就行。"
        404 -> "OneDrive 上找不到这个目录，可能是目录刚被改名或删了。"
        413 -> "文件太大，OneDrive 单次简单上传的上限是 250 MB。"
        507 -> "OneDrive 空间不足，先清一清网盘里的东西。"
        429 -> "请求太频繁，被微软限流了，等一会儿再试。"
        in 500..599 -> "微软服务器出错了（HTTP $code），稍后重试。"
        else -> "上传失败（HTTP $code）。" + body.take(200)
    }
}
