package com.cloudmusic.app.data

import android.util.Log

/** 把已有的 WebDAV 客户端包装成统一的网盘后端。 */
class DavProvider(
    private val client: DavClient,
    private val prefs: Prefs? = null,
) : CloudProvider {

    override val kind: ProviderKind = ProviderKind.WEBDAV

    override suspend fun scan(
        onProgress: (found: Int, currentPath: String) -> Unit
    ): ScanResult = client.scanAll(client.baseUrl.encodedPath, onProgress)

    override suspend fun fetchText(entryId: String): String? = client.fetchText(entryId)

    override fun playUrl(entryId: String): String = client.urlFor(entryId).toString()

    override fun isReady(): Boolean = true

    /**
     * 上传文件到网盘。
     *
     * 这里踩过一个大坑：**坚果云是收完整 body 才回报错的**。
     *
     * 早先的写法是「先 PUT 到根目录试水，失败再 PUT 到目标目录」。
     * 看起来只多试一次，可那次「试水」服务器照单全收 ——
     * 7.84MB 传完才回一句 404。于是每首歌都白白传了两遍，
     * 上传耗时直接翻倍（实测 29 秒里有 15 秒纯属白费）。
     *
     * 现在的做法：
     * - 目标目录不为空 → 直接传目录，一次到位，压根不试根目录；
     * - 目标目录为空（目标就是根目录）→ 用 **0 字节** 探一下根目录能不能写，
     *   几百毫秒就有结论，结论存进 Prefs，以后不用再试。
     */
    override suspend fun upload(
        localFile: java.io.File,
        dirPath: String,
        fileName: String,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
    ): UploadResult {
        val name = if (fileName.contains('.')) fileName else "$fileName.mp3"
        val mime = AudioTypes.mimeOf(name)
        val t0 = System.nanoTime()

        if (dirPath.isNotBlank()) {
            // 逐级建目录后再传。目录不存在会 MKCOL 建出来，不会白传文件。
            val dirHref = client.ensurePath(dirPath)
            val href = client.childHref(dirHref, name)
            return put(href, localFile, name, mime, dirPath, onProgress, t0)
        }

        // 目标就是根目录 —— 先花几百毫秒确认根目录能写。
        if (!rootWritable()) {
            return UploadResult.Failed(
                "上传失败：这个网盘的根目录不允许写文件。" +
                    "请在导入设置里给它指定一个目录（比如「云音乐导入」）。"
            )
        }
        val href = client.childHref(client.baseHref, name)
        return put(href, localFile, name, mime, "", onProgress, t0)
    }

    override suspend fun delete(entryId: String): DeleteResult = client.delete(entryId)

    /**
     * 根目录能不能直接放文件。
     *
     * 结果缓存在 Prefs 里：这是「这个网盘是什么脾气」，
     * 跟具体哪首歌没关系，测一次就该记一辈子。
     */
    private suspend fun rootWritable(): Boolean {
        prefs?.davRootWritable?.let { return it }
        // 0 字节 PUT —— 服务器收不到 body，几十毫秒就出结论
        val probe = client.childHref(client.baseHref, ".cm_probe_tmp")
        val ok = client.putEmpty(probe)
        if (ok) {
            // 探测文件别留在网盘上
            client.delete(probe)
        }
        Log.i(TAG, "根目录可写性探测：${if (ok) "可以" else "不行"}")
        prefs?.davRootWritable = ok
        return ok
    }

    /** 真正把文件 PUT 上去，顺带把速率记一笔。 */
    private suspend fun put(
        href: String,
        localFile: java.io.File,
        name: String,
        mime: String,
        dir: String,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
        t0: Long,
    ): UploadResult {
        val size = localFile.length()
        val ok = client.putFile(href, localFile, mime, onProgress)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val speed = if (ms > 0) size * 1000 / ms else 0L
        Log.i(
            TAG,
            "上传《$name》 $size 字节 ${ms}ms ${speed / 1024}KB/s " +
                "目录[${dir.ifBlank { "/" }}] ${if (ok) "成功" else "失败"}"
        )
        return if (ok) {
            UploadResult.Ok(href)
        } else {
            UploadResult.Failed(
                "上传失败：网盘拒绝了这个路径。可能是目录不存在、目录名含非法字符，" +
                    "或者这个网盘的 WebDAV 不允许写入（坚果云是允许的）"
            )
        }
    }

    companion object {
        private const val TAG = "CloudMusicUpload"
    }
}
