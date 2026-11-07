package com.cloudmusic.app.data.quark

import com.cloudmusic.app.data.AudioMagic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 夸克网盘的公开分享接口。
 *
 * 三段式拿直链，全程免登录、免提取码：
 * ```
 * POST /1/clouddrive/share/sharepage/token    {pwd_id, passcode}   → stoken
 * GET  /1/clouddrive/share/sharepage/detail   ?pwd_id&stoken      → 文件列表(fid)
 * POST /1/clouddrive/file/download            {fid_list:[fid]}     → download_url
 * ```
 *
 * 坑：stoken 里含 `+` 和 `=`，必须 URL 编码再拼进 query，
 * 否则服务端报「非法token」。
 *
 * 直链能不能下，取决于签发直链时用的身份 ——
 * 游客态签出来的会被 CDN 用 412 拦掉（实测），
 * 所以这里所有请求都带上用户自己的登录 cookie。
 */
class QuarkClient(
    private val http: OkHttpClient,
    /** 登录 cookie，登录前是空串 */
    private val cookieProvider: () -> String,
) {

    companion object {
        const val HOST = "https://drive-pc.quark.cn"
        private const val UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"

        /** 手机端 UA。有些 CDN 对手机 UA 的处理跟 PC 不一样。 */
        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        /** 分享链接里取分享码：`https://pan.quark.cn/s/cbe2f1cc45a9` → `cbe2f1cc45a9` */
        fun pwdIdOf(shareUrl: String): String? {
            val s = shareUrl.trim()
            if (s.isEmpty()) return null
            val m = Regex("pan\\.quark\\.cn/s/([A-Za-z0-9]+)").find(s)
            if (m != null) return m.groupValues[1]
            // 只给了分享码本身
            if (s.matches(Regex("[A-Za-z0-9]{8,20}"))) return s
            return null
        }
    }

    private val cookie: String get() = cookieProvider().trim()

    /** 已登录 */
    val loggedIn: Boolean get() = cookie.contains("__pus")

    // ------------------------------------------------------------ 分享解析

    /** 第一步：拿 stoken。 */
    suspend fun shareToken(pwdId: String, passcode: String = ""): QuarkToken =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("pwd_id", pwdId)
                .put("passcode", passcode)
                .toString()
            val json = postJson(
                "$HOST/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc",
                body
            )
            val data = json.optJSONObject("data")
                ?: throw QuarkException(quarkMessage(json, "取分享令牌失败"))
            QuarkToken(
                stoken = data.optString("stoken"),
                author = data.optJSONObject("author")?.optString("nickname").orEmpty(),
                total = data.optInt("total_files", 0),
            )
        }

    /** 第二步：列文件。 */
    suspend fun shareDetail(pwdId: String, stoken: String): List<QuarkShareFile> =
        withContext(Dispatchers.IO) {
            val url = "$HOST/1/clouddrive/share/sharepage/detail" +
                "?pr=ucpro&fr=pc&pwd_id=${enc(pwdId)}&stoken=${enc(stoken)}" +
                "&_fetch_share=1&_fetch_banner=1&_fetch_total=1&_page=1&_size=100"
            val json = getJson(url)
            val list = json.optJSONObject("data")?.optJSONArray("list")
                ?: throw QuarkException(quarkMessage(json, "读分享内容失败"))
            val out = mutableListOf<QuarkShareFile>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                out.add(
                    QuarkShareFile(
                        fid = o.optString("fid"),
                        fileName = o.optString("file_name"),
                        size = o.optLong("size"),
                        durationSec = o.optInt("duration"),
                        md5 = o.optString("md5"),
                        isDir = o.optInt("dir_fid") > 0 || o.optBoolean("dir"),
                    )
                )
            }
            out
        }

    /**
     * 第三步：拿下载直链。
     *
     * 登录 cookie 带上 —— 游客态签出来的直链会被 CDN 412 拦掉。
     */
    suspend fun downloadUrl(pwdId: String, stoken: String, fid: String): QuarkFile =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("fid_list", org.json.JSONArray().put(fid))
                .put("fids", org.json.JSONArray().put(fid))
                .put("pwd_id", pwdId)
                .put("stoken", stoken)
                .toString()
            val json = postJson("$HOST/1/clouddrive/file/download?pr=ucpro&fr=pc", body)
            val arr = json.optJSONArray("data")
            if (arr == null || arr.length() == 0) {
                throw QuarkException(quarkMessage(json, "这个文件拿不到下载地址"))
            }
            val o = arr.optJSONObject(0) ?: throw QuarkException("下载地址解析失败")
            QuarkFile(
                fid = o.optString("fid"),
                fileName = o.optString("file_name"),
                size = o.optLong("size"),
                durationSec = o.optInt("duration"),
                md5 = o.optString("md5"),
                downloadUrl = o.optString("download_url"),
            )
        }

    /**
     * 路径 B：把分享转存到自己盘。
     *
     * 游客态会返回 401 `require login [guest]`，必须带 cookie。
     * 转存会占夸克空间，也会消耗转存配额。
     */
    suspend fun saveToMyDrive(
        pwdId: String,
        stoken: String,
        fid: String,
        toPdirFid: String = "0",
    ): Boolean = withContext(Dispatchers.IO) {
        // 第一次转存要带一个防重放头，值从 stash 接口拿
        val stash = postJson(
            "$HOST/1/clouddrive/share/sharepage/params/stash?pr=ucpro&fr=pc",
            JSONObject().put(
                "save_as",
                JSONObject()
                    .put("pwd_id", pwdId)
                    .put("fid_list", org.json.JSONArray().put(fid))
                    .put("to_pdir_fid", toPdirFid)
                    .put("pwd_save_path", "")
            ).toString()
        )
        val stashId = stash.optJSONObject("data")?.optString("stash_id").orEmpty()

        val body = JSONObject()
            .put("fid_list", org.json.JSONArray().put(fid))
            .put("to_pdir_fid", toPdirFid)
            .put("pwd_id", pwdId)
            .put("stoken", stoken)
            .put("pwd_save_path", "")
            .put("stash_id", stashId)
            .put("scene", "link")
            .toString()
        val json = postJson(
            "$HOST/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc",
            body,
            extraHeaders = if (stashId.isBlank()) emptyMap() else mapOf("x-clouddrive-st" to stashId)
        )
        json.optInt("code", 0) == 0
    }

    // ------------------------------------------------------------ 直链探测

    /**
     * 试一下这个直链到底能不能下。
     *
     * 只读文件头，不下整个文件 —— 诊断「412 是不是还拦着」用这个。
     */
    suspend fun probe(
        url: String,
        readBytes: Int = 64 * 1024,
        withCookie: Boolean = true,
    ): QuarkProbe =
        withContext(Dispatchers.IO) {
            probeOnce(
                url,
                label = if (withCookie) "带 Cookie" else "不带 Cookie",
                range = "bytes=0-${readBytes - 1}",
                pcUa = true,
                readBytes = readBytes,
                withCookie = withCookie,
            )
        }

    /**
     * 用几种请求姿势各试一次，看哪种能真的拿到音频字节。
     *
     * 夸克 CDN 对「Range 请求」和「完整请求」的处理可能不一样，
     * 探测层用 Range、下载层用完整请求，两边对不上就会出现
     * 「探测说能下、下载完说不是音频」。这个方法一次把几种姿势全试出来。
     */
    suspend fun probeVariants(url: String): List<QuarkProbe> = withContext(Dispatchers.IO) {
        listOf(
            probeOnce(url, "Range+电脑UA", "bytes=0-65535", true, 64 * 1024),
            probeOnce(url, "完整+电脑UA", null, true, 64 * 1024),
            probeOnce(url, "完整+手机UA", null, false, 64 * 1024),
        )
    }

    /** 按指定姿势发一次请求，读前 readBytes 字节看看是什么。 */
    private suspend fun probeOnce(
        url: String,
        label: String,
        range: String?,
        pcUa: Boolean,
        readBytes: Int,
        withCookie: Boolean = true,
    ): QuarkProbe {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", if (pcUa) UA_PC else UA_MOBILE)
                .header("Referer", "https://pan.quark.cn/")
                .apply { if (range != null) header("Range", range) }
                // 直链是会话绑定的，必须带登录 Cookie，否则 CDN 直接 412
                .apply { if (withCookie && cookie.isNotBlank()) header("Cookie", cookie) }
                .build()
            http.newCall(request).execute().use { resp ->
                val body = resp.body
                val buf = body?.byteStream()?.use { input ->
                    val b = ByteArray(readBytes)
                    var n = 0
                    while (n < readBytes) {
                        val r = input.read(b, n, readBytes - n)
                        if (r <= 0) break
                        n += r
                    }
                    b.copyOf(n)
                } ?: ByteArray(0)
                QuarkProbe(
                    httpCode = resp.code,
                    contentType = resp.header("Content-Type").orEmpty(),
                    contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L,
                    magic = if (buf.size >= 4) {
                        AudioMagic.hexOf(buf.copyOfRange(0, 4))
                    } else {
                        ""
                    },
                    // 前 16 字节：FLAC / M4A 这类格式只凭 4 字节认不出来
                    head = AudioMagic.hexOf(buf),
                    readBytes = buf.size.toLong(),
                    message = when (resp.code) {
                        200, 206 -> "正常"
                        412 -> "被 CDN 拦了（权限不足）"
                        403 -> "直链签名或权限有问题"
                        401 -> "登录态失效"
                        else -> "HTTP ${resp.code}"
                    },
                    label = label,
                )
            }
        } catch (e: Exception) {
            QuarkProbe(
                httpCode = 0,
                contentType = "",
                contentLength = -1,
                magic = "",
                readBytes = 0,
                message = e.message ?: "请求异常",
                label = label,
            )
        }
    }

    // ------------------------------------------------------------ 工具

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA_PC)
            .header("Referer", "https://pan.quark.cn/")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw QuarkException("HTTP ${resp.code}：${text.take(160)}")
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw QuarkException("返回格式不对：${text.take(160)}")
            }
        }
    }

    private fun postJson(
        url: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA_PC)
            .header("Referer", "https://pan.quark.cn/")
            .header("Content-Type", "application/json")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .apply { for ((k, v) in extraHeaders) header(k, v) }
            .post(body.toRequestBody())
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw QuarkException("HTTP ${resp.code}：${text.take(160)}")
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw QuarkException("返回格式不对：${text.take(160)}")
            }
        }
    }

    /** 夸克的错误码翻译成人话。 */
    private fun quarkMessage(json: JSONObject, fallback: String): String {
        val code = json.optInt("code", 0)
        val msg = json.optString("message")
        return when {
            msg.contains("require login") || code == 31001 ->
                "夸克未登录或登录态过期了，去设置里重新登录"
            code == 14001 -> "参数不对：$msg"
            msg.contains("limit") || msg.contains("超过") || msg.contains("频繁") ->
                "夸克限流了（转存次数或频率超限），等一会儿再试"
            msg.contains("space") || msg.contains("空间") ->
                "夸克网盘空间不够"
            msg.contains("pwd_id is required") ->
                "分享链接认不出来了，重新解析一次"
            msg.isNotBlank() && msg != "ok" -> "$fallback（$msg）"
            else -> fallback
        }
    }

    /**
     * 转存 + 轮询 + 拿自己盘的直链，一步做完。
     *
     * 转存是异步任务：先 `save` 拿 `task_id`，再轮询 `/1/clouddrive/task`
     * 直到 `status` 变成完成，里面才有转存后的文件 fid。
     * 拿到 fid 后再走 `file/download` —— 这时是自己盘的文件，
     * 直链不再受分享权限限制（游客态签的直链会被 CDN 412 拦掉）。
     */
    suspend fun saveAndResolve(
        pwdId: String,
        stoken: String,
        fid: String,
        toPdirFid: String = "0",
        onStep: (String) -> Unit = {},
        /** 原始响应回显，定位夸克改了哪个字段时用 */
        onRaw: (String) -> Unit = {},
    ): QuarkFile = withContext(Dispatchers.IO) {
        // 1. 转存
        // stash 的 body 必须是 {save_as: {...}} —— pwd_id 得放在 save_as 里面，
        // 放顶层会报 "pwd_id is required in stash params"（夸克前端 U(e) 就是这么拼的）
        val saveAs = JSONObject()
            .put("pwd_id", pwdId)
            .put("fid_list", org.json.JSONArray().put(fid))
            .put("to_pdir_fid", toPdirFid)
            .put("pwd_save_path", "")
        val stash = postJson(
            "$HOST/1/clouddrive/share/sharepage/params/stash?pr=ucpro&fr=pc",
            JSONObject().put("save_as", saveAs).toString()
        )
        val stashId = stash.optJSONObject("data")?.optString("stash_id").orEmpty()
        if (stashId.isBlank()) {
            throw QuarkException(quarkMessage(stash, "拿转存凭证失败"))
        }
        val body = JSONObject()
            .put("fid_list", org.json.JSONArray().put(fid))
            .put("to_pdir_fid", toPdirFid)
            .put("pwd_id", pwdId)
            .put("stoken", stoken)
            .put("pwd_save_path", "")
            .put("stash_id", stashId)
            .put("scene", "link")
            .toString()
        val saved = postJson(
            "$HOST/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc",
            body,
            extraHeaders = if (stashId.isBlank()) emptyMap() else mapOf("x-clouddrive-st" to stashId)
        )
        if (saved.optInt("code", 0) != 0) {
            throw QuarkException(quarkMessage(saved, "转存失败"))
        }
        val taskId = saved.optJSONObject("data")?.optString("task_id").orEmpty()
        // tq_gap 在响应的顶层 metadata 里
        val gap = (saved.optJSONObject("metadata")?.optInt("tq_gap")
            ?: saved.optJSONObject("data")?.optJSONObject("metadata")?.optInt("tq_gap")
            ?: 1000).coerceAtLeast(300)
        onStep("转存任务已提交（${taskId.take(12)}…）")
        onRaw("save 响应：" + saved.toString().take(1800))

        // 转存后的文件信息在 **save 的响应**里，不在 task 的响应里
        // （夸克前端 G(e,t) 里是 te(r)，r 就是 save 的响应）
        var info = extractSavedFile(saved)

        // 2. 轮询任务
        var newFid = info?.first.orEmpty()
        var fileName = info?.second.orEmpty()
        var size = info?.third ?: 0L
        var duration = info?.fourth ?: 0
        var done = false
        var rawShown = false
        for (i in 0 until 90) {
            delay(gap.toLong())
            val task = pollTask(taskId, i)
            if (!rawShown) {
                rawShown = true
                onRaw("task 响应（第一次）：" + task.toString().take(1800))
            }
            val status = task.optJSONObject("data")?.opt("status")
            done = when (status) {
                null -> false
                is String -> status == "finish" || status == "success"
                is Int -> status == 2
                else -> false
            }
            if (newFid.isBlank()) {
                // task 响应里也翻一遍
                val fromTask = extractSavedFile(task)
                if (fromTask != null) {
                    newFid = fromTask.first
                    fileName = fromTask.second
                    size = fromTask.third
                    duration = fromTask.fourth
                }
            }
            if (done && !rawShown) {
                rawShown = true
                onRaw("task 响应（完成时）：" + task.toString().take(1800))
            }
            // 完成了就退出，别空转
            if (done) break
            if (i % 5 == 0) onStep("转存中…（第 ${i + 1} 次轮询）")
        }
        if (newFid.isBlank()) {
            // 兜底：去自己盘根目录翻最新的文件
            val fallback = findNewestInMyDrive(fileName)
            if (fallback != null) {
                newFid = fallback.first
                fileName = fallback.second
                size = fallback.third
                duration = fallback.fourth
                onStep("从自己盘里找到了转存结果：$fileName")
            }
        }
        if (newFid.isBlank()) {
            throw QuarkException(
                "转存${if (done) "完成了" else "超时"}，但返回里没找到文件信息。" +
                    "你的夸克里应该已经能看到这个文件了。"
            )
        }
        onStep("转存完成：$fileName")

        // 3. 自己盘的文件拿直链
        val dl = postJson("$HOST/1/clouddrive/file/download?pr=ucpro&fr=pc", JSONObject()
            .put("fids", org.json.JSONArray().put(newFid))
            .put("fid_list", org.json.JSONArray().put(newFid))
            .toString())
        val arr = dl.optJSONArray("data")
        if (arr == null || arr.length() == 0) {
            throw QuarkException(quarkMessage(dl, "自己盘的文件也拿不到下载地址"))
        }
        val o = arr.optJSONObject(0) ?: throw QuarkException("下载地址解析失败")
        onStep("直链拿到了")
        QuarkFile(
            fid = o.optString("fid", newFid),
            fileName = o.optString("file_name", fileName),
            size = o.optLong("size", size),
            durationSec = o.optInt("duration", duration),
            md5 = o.optString("md5"),
            downloadUrl = o.optString("download_url"),
        )
    }

    /** 轮询转存任务。夸克前端用的是 `taskId`（驼峰），但接口也可能认 `task_id`，两种都带上。 */
    private fun pollTask(taskId: String, retryIndex: Int): JSONObject {
        val get = try {
            getJson(
                "$HOST/1/clouddrive/task?pr=ucpro&fr=pc&task_id=${enc(taskId)}" +
                    "&taskId=${enc(taskId)}&retry_index=$retryIndex&interval=1000"
            )
        } catch (e: Exception) {
            JSONObject()
        }
        if (get.optJSONObject("data") != null) return get
        return get
    }

    /**
     * 从响应里把转存后的文件 fid 抠出来。
     *
     * 夸克真实结构（2026-11-07 实测）：
     * ```
     * save 响应: data.task_resp.data.save_as.save_as_top_fids = ["8156bd8cff7a4f89ac04431467cb4a6f"]
     * task 响应: data.save_as.save_as_top_fids                = ["8156bd8cff7a4f89ac04431467cb4a6f"]
     * ```
     * 是**字符串数组**，不是对象数组。
     *
     * 为了扛住夸克再改结构，这里不写死路径 ——
     * 递归找任何 key 里带 fid/top 的字符串数组，再不行就找 32 位 hex。
     */
    private fun extractSavedFile(resp: JSONObject): Quad? {
        val named = mutableListOf<String>()
        collectFidArray(resp, named)
        for (f in named) {
            if (f.length >= 16) return Quad(f, "", 0L, 0)
        }
        // 兜底：任意 32 位 hex（fid 就是 32 位 hex）
        val hexes = mutableListOf<String>()
        collectHex(resp, hexes)
        for (h in hexes) {
            if (h.length == 32) return Quad(h, "", 0L, 0)
        }
        return null
    }

    /** 递归找 key 含 fid/top、值是字符串数组的字段。 */
    private fun collectFidArray(o: Any?, out: MutableList<String>) {
        when (o) {
            is JSONObject -> {
                for (k in o.keys()) {
                    val v = o.opt(k)
                    if (v is JSONArray && (k.contains("fid", true) || k.contains("top", true))) {
                        for (i in 0 until v.length()) {
                            val s = v.optString(i)
                            if (s.isNotBlank()) out.add(s)
                        }
                    } else {
                        collectFidArray(v, out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until o.length()) collectFidArray(o.opt(i), out)
            }
        }
    }

    /** 递归找 32 位 hex 字符串。 */
    private fun collectHex(o: Any?, out: MutableList<String>) {
        when (o) {
            is JSONObject -> {
                for (k in o.keys()) {
                    val v = o.opt(k)
                    if (v is String && v.length == 32 && v.all { it.isDigit() || it in 'a'..'f' }) {
                        out.add(v)
                    } else {
                        collectHex(v, out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until o.length()) collectHex(o.opt(i), out)
            }
        }
    }

    /**
     * 兜底：转存结果实在抠不出来时，去自己盘根目录翻最新的文件。
     *
     * 转存是刚做完的，自己盘里最新那个就是它。
     */
    private fun findNewestInMyDrive(wantName: String): Quad? {
        val names = listOf(
            "$HOST/1/clouddrive/file/list?pr=ucpro&fr=pc&pdir_fid=0&_page=1&_size=50" +
                "&_sort=updated_at&_order=desc",
            "$HOST/1/clouddrive/file/current/list?pr=ucpro&fr=pc&pdir_fid=0",
        )
        for (u in names) {
            val json = try { getJson(u) } catch (e: Exception) { continue }
            val list = json.optJSONObject("data")?.optJSONArray("list")
                ?: json.optJSONArray("list")
                ?: continue
            var best: Quad? = null
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val fid = o.optString("fid")
                if (fid.isBlank()) continue
                val name = o.optString("file_name")
                val q = Quad(fid, name, o.optLong("size"), o.optInt("duration"))
                if (wantName.isNotBlank() && name == wantName) return q
                if (best == null) best = q
            }
            if (best != null) return best
        }
        return null
    }

    /** 删掉自己盘里的文件（清理中转用）。 */
    suspend fun deleteFile(fid: String, toPdirFid: String = "0"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val r = postJson(
                    "$HOST/1/clouddrive/file/delete?pr=ucpro&fr=pc",
                    JSONObject()
                        .put("fid_list", org.json.JSONArray().put(fid))
                        .put("to_pdir_fid", toPdirFid)
                        .toString()
                )
                r.optInt("code", 0) == 0
            } catch (e: Exception) {
                false
            }
        }

    /**
     * 看压缩包里有什么。
     *
     * 夸克支持在线解压，所以遇到 `.rar` / `.zip` 分享不用在手机上解 ——
     * 先列一下包里的文件，确认里面是不是有能直接用的音频。
     */
    suspend fun previewArchive(pwdId: String, stoken: String, fid: String): List<QuarkShareFile> =
        withContext(Dispatchers.IO) {
            try {
                val r = postJson(
                    "$HOST/1/clouddrive/archive/preview_tree?pr=ucpro&fr=pc",
                    JSONObject()
                        .put("fid_list", org.json.JSONArray().put(fid))
                        .put("pwd_id", pwdId)
                        .put("stoken", stoken)
                        .toString()
                )
                val out = mutableListOf<QuarkShareFile>()
                val list = r.optJSONObject("data")?.optJSONArray("list")
                    ?: r.optJSONObject("data")?.optJSONArray("tree")
                    ?: return@withContext out
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    out.add(
                        QuarkShareFile(
                            fid = o.optString("fid"),
                            fileName = o.optString("file_name"),
                            size = o.optLong("size"),
                            durationSec = o.optInt("duration"),
                            md5 = "",
                            isDir = o.optInt("dir_fid") > 0 || o.optBoolean("dir"),
                        )
                    )
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }

    /**
     * 把直链整个下到本地文件。
     *
     * @return 下载字节数；失败返回 -1
     */
    suspend fun downloadTo(url: String, dest: java.io.File): Long =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA_PC)
                    .header("Referer", "https://pan.quark.cn/")
                    .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext -1L
                    val body = resp.body ?: return@withContext -1L
                    dest.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.length()
                }
            } catch (e: Exception) {
                -1L
            }
        }

    /**
     * 账号昵称。登录态下才有，未登录返回空串。
     */
    suspend fun accountName(): String = withContext(Dispatchers.IO) {
        try {
            val json = getJson("$HOST/1/clouddrive/member?pr=ucpro&fr=pc")
            val data = json.optJSONObject("data")
            val member = data?.optJSONObject("member") ?: data
            member?.optString("nick").orEmpty().ifBlank {
                member?.optString("name").orEmpty()
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** 夸克接口出错。 */
class QuarkException(message: String) : Exception(message)
