package com.cloudmusic.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.charset.Charset

class WebDavException(val code: Int, message: String) : Exception(message)

class AuthFailedException(
    message: String = "账号或应用密码不正确，请重新检查"
) : Exception(message)

/**
 * WebDAV 服务端报的空间用量。
 *
 * 坚果云、Nextcloud、群晖都会在 PROPFIND 的响应里给出
 * `quota-available-bytes`（剩余）和 `quota-used-bytes`（已用）。
 */
data class DavQuota(
    /** 剩余字节数 */
    val available: Long,
    /** 已用字节数 */
    val used: Long,
) {
    /** 总额度 = 已用 + 剩余 */
    val total: Long get() = available + used

    /** 已用百分比（0f~1f） */
    val usedRatio: Float
        get() = if (total <= 0L) 0f else (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    /** 剩余空间大概够放几首 30 MB 的歌 */
    val songsLeft: Long
        get() = if (available <= 0L) 0L else available / (30L * 1024 * 1024)
}

/** 一次扫描的结果。 */
data class ScanResult(
    val audio: List<DavEntry> = emptyList(),
    val lyricFiles: List<DavEntry> = emptyList(),
    /** 读取失败的目录，用于在界面上告诉用户"哪里没扫到" */
    val errors: List<String> = emptyList(),
    /**
     * 扫到的所有文件的总大小（字节）。
     *
     * 坚果云这类不提供配额查询的网盘，只能靠这个数字告诉用户「已经用了多少」，
     * 剩下的空间由套餐容量减出来。
     */
    val usedBytes: Long = 0L,
)

/**
 * 极简 WebDAV 客户端，仅依赖 OkHttp。
 * 兼容坚果云 / Nextcloud / 群晖 等标准 WebDAV 服务。
 */
class DavClient(
    val baseUrl: HttpUrl,
    private val creds: DavCredentials,
    private val http: OkHttpClient,
) {

    companion object {
        const val USER_AGENT = "CloudMusic/1.0 (Android)"

        private const val TAG = "CloudMusicDav"

        /** 坚果云单次 PROPFIND 最多返回 750 条，超出部分靠分页拿 */
        private const val MAX_PAGES_PER_DIR = 40
        private const val MAX_DIRS = 5000

        private val PROPFIND_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:getcontentlength/><D:getcontenttype/><D:getlastmodified/><D:resourcetype/></D:prop></D:propfind>
        """.trimIndent()

        private val propfindBody = PROPFIND_XML.toRequestBody(
            "application/xml; charset=utf-8".toMediaType()
        )

        /**
         * 查空间用量用的 PROPFIND。
         *
         * 坚果云/Nextcloud/群晖都会在响应里给出这两个属性，
         * 是 WebDAV 协议里查配额的标准办法（DAV: quota-available-bytes）。
         */
        private val quotaBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop><D:quota-available-bytes/><D:quota-used-bytes/></D:prop></D:propfind>
        """.trimIndent().toRequestBody(
            "application/xml; charset=utf-8".toMediaType()
        )

        /** <https://...>; rel="next" */
        private val LINK_NEXT = Regex(
            """<([^>]+)>\s*;\s*rel\s*=\s*"?next"?""",
            RegexOption.IGNORE_CASE
        )

        fun normalizeServerUrl(raw: String): HttpUrl {
            var s = raw.trim()
            if (s.isEmpty()) s = Prefs.DEFAULT_SERVER
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            if (!s.endsWith("/")) s = "$s/"
            return s.toHttpUrl()
        }
    }

    private val basePath: String = baseUrl.encodedPath.trimEnd('/')

    /** 网盘根目录的 href（已做过路径规范化） */
    val baseHref: String get() = basePath

    fun normalizeHref(href: String): String = DavXmlParser.normalize(href, basePath)

    fun urlFor(href: String): HttpUrl =
        baseUrl.newBuilder().encodedPath(normalizeHref(href)).build()

    /**
     * 列出某目录下一层内容。
     *
     * 坚果云的 WebDAV 单次请求最多返回 750 个条目，超出时会带上
     * `Link: <...>; rel="next"` 让客户端继续拉，这里跟着翻页，
     * 否则大目录里的文件会整片丢失（这正是"传了歌却搜不到"的元凶）。
     */
    suspend fun list(href: String): List<DavEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DavEntry>()
        var url: HttpUrl = urlFor(href)
        var pages = 0
        val seen = mutableSetOf<String>()

        while (pages < MAX_PAGES_PER_DIR) {
            if (!seen.add(url.toString())) break

            var next: HttpUrl? = null
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", propfindBody)
                .header("Authorization", creds.authorization)
                .header("Depth", "1")
                .header("User-Agent", USER_AGENT)
                .build()

            http.newCall(request).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> throw AuthFailedException()
                    resp.code == 207 || resp.code == 200 -> {
                        out.addAll(DavXmlParser.parse(resp.body?.string().orEmpty(), basePath))
                        next = nextPageUrl(resp.headers.values("Link"))
                    }
                    else -> throw WebDavException(resp.code, "服务器返回 ${resp.code}")
                }
            }

            pages++
            url = next ?: break
        }
        out
    }

    /**
     * 查空间用量（剩余 / 已用）。
     *
     * 有些服务器（部分 Nextcloud 部署、老版本群晖）不支持这两个属性，
     * 会返回 207 但不带 quota —— 这时返回 null，界面上就显示「未知」。
     */
    suspend fun quota(): DavQuota? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlFor(basePath))
            .method("PROPFIND", quotaBody)
            .header("Authorization", creds.authorization)
            .header("Depth", "0")
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) return@withContext null
                if (!resp.isSuccessful) return@withContext null
                DavXmlParser.parseQuota(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 建目录（已存在也算成功）。
     *
     * WebDAV 的 MKCOL 对已存在的目录会返回 405，所以 405 也当成功。
     */
    suspend fun ensureDir(href: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlFor(href))
            .method("MKCOL", null)
            .header("Authorization", creds.authorization)
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                resp.code == 201 || resp.code == 200 || resp.code == 405 ||
                    resp.code == 301 || resp.code == 409
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 上传文件（WebDAV PUT）。
     *
     * @param mime 服务器可能不认这个类型，但 PUT 时带上总没错
     */
    suspend fun putFile(
        href: String,
        file: File,
        mime: String,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() == 0L) return@withContext false
            val body = progressRequestBody(file, mime, onProgress)
            val request = Request.Builder()
                .url(urlFor(href))
                .put(body)
                .header("Authorization", creds.authorization)
                .header("User-Agent", USER_AGENT)
                .build()
            val t0 = System.nanoTime()
            try {
                http.newCall(request).execute().use { resp ->
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    Log.i(TAG, "PUT ${file.name} ${resp.code} ${ms}ms ${file.length()} 字节")
                    resp.code == 201 || resp.code == 204 || resp.code == 200
                }
            } catch (e: Exception) {
                Log.w(TAG, "PUT ${file.name} 失败：${e.javaClass.simpleName} ${e.message}")
                false
            }
        }

    /**
     * 0 字节 PUT —— 用来快速探某个路径能不能写。
     *
     * 为什么值得单独写一个：有些网盘（坚果云就是）是**收完整 body 才回报错**的，
     * 拿一首 8MB 的歌去试水，等于白白传了 8MB。
     * 0 字节的 body 一发完就能拿到结论，几百毫秒搞定。
     */
    suspend fun putEmpty(href: String): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(urlFor(href))
                .put(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
                .header("Authorization", creds.authorization)
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    resp.code == 201 || resp.code == 204 || resp.code == 200
                }
            } catch (e: Exception) {
                false
            }
        }

    /**
     * 删掉一个文件（WebDAV DELETE）。
     *
     * 404 也算成功 —— 文件已经不在了，结果一样。
     * 403 说明这个网盘不让删（有的只给读权限），要如实告诉用户。
     */
    suspend fun delete(href: String): DeleteResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlFor(href))
            .delete()
            .header("Authorization", creds.authorization)
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                when (resp.code) {
                    204, 200, 404 -> DeleteResult.Ok
                    401, 403 -> DeleteResult.Failed(
                        "删除失败：网盘拒绝了这次删除（HTTP ${resp.code}）。" +
                            "多半是应用密码只有读权限 —— 换成有写权限的那个再试"
                    )
                    else -> DeleteResult.Failed("删除失败：网盘返回 HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            DeleteResult.Failed("删除失败：连不上网盘（${e.javaClass.simpleName}）")
        }
    }

    /**
     * 拼出「某目录下的某个文件」的路径。
     *
     * 目录里的中文要按 URL 编码规则转义，否则坚果云会当成两个不同的目录。
     */
    fun childHref(dirHref: String, name: String): String {
        val base = dirHref.trimEnd('/')
        return "$base/${encodeSegment(name)}"
    }

    /** 逐级建目录，返回最终的目录 href */
    suspend fun ensurePath(dirPath: String): String {
        var href = basePath.ifBlank { "/" }
        if (dirPath.isBlank()) return href
        for (seg in dirPath.split('/', '　').map { it.trim() }.filter { it.isNotEmpty() }) {
            href = childHref(href, seg)
            ensureDir(href)
        }
        return href
    }

    /** 把一段路径按 URL 规则编码（斜杠不编码） */
    fun encodeSegment(name: String): String =
        java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /** 读取一个小文件的内容（如 .lrc 歌词），失败返回 null。 */
    suspend fun fetchText(href: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlFor(href))
            .header("Authorization", creds.authorization)
            .header("User-Agent", USER_AGENT)
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

    /**
     * 递归扫描整棵树，收集音频文件与歌词文件。
     * 读失败的目录不会中断扫描，但会记进 errors 里提示用户。
     */
    suspend fun scanAll(
        rootHref: String,
        onProgress: (found: Int, currentPath: String) -> Unit
    ): ScanResult = withContext(Dispatchers.IO) {
        val audio = mutableListOf<DavEntry>()
        val lyricFiles = mutableListOf<DavEntry>()
        val errors = mutableListOf<String>()
        // 占用空间的是所有文件，不只是能被播放器认出来的那些
        var usedBytes = 0L
        val queue = ArrayDeque<String>()
        queue.addLast(normalizeHref(rootHref))
        var visited = 0
        val seenDirs = mutableSetOf<String>()

        while (queue.isNotEmpty() && visited < MAX_DIRS) {
            val dir = queue.removeFirst()
            if (!seenDirs.add(dir.trimEnd('/').lowercase())) continue
            visited++

            val entries = try {
                list(dir)
            } catch (e: Exception) {
                errors.add("${dir.substringAfterLast('/').ifBlank { "/" }}：${e.message ?: "读取失败"}")
                onProgress(audio.size, dir)
                continue
            }
            onProgress(audio.size, dir)

            for (e in entries) {
                if (e.isDirectory) {
                    if (!samePath(e.href, dir)) queue.addLast(e.href)
                } else {
                    usedBytes += e.size
                    if (AudioTypes.isAudio(e)) audio.add(e)
                    else if (AudioTypes.isLyricFile(e)) lyricFiles.add(e)
                }
            }
        }
        ScanResult(audio, lyricFiles, errors, usedBytes)
    }

    /** 从 Link 响应头里解析下一页地址。 */
    private fun nextPageUrl(linkHeaders: List<String>): HttpUrl? {
        for (header in linkHeaders) {
            val m = LINK_NEXT.find(header) ?: continue
            val url = m.groupValues[1].trim().toHttpUrlOrNull() ?: continue
            return url
        }
        return null
    }

    private fun samePath(a: String, b: String): Boolean =
        a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)

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
