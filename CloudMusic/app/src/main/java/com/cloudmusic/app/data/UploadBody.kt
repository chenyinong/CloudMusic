package com.cloudmusic.app.data

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

/**
 * 带进度回调的文件上传体。
 *
 * OkHttp 自带的 `file.asRequestBody()` 不报进度 ——
 * 传一个 30MB 的 FLAC 上去，界面一动不动，
 * 用户只知道「很慢」，不知道传到哪儿了。
 */
fun progressRequestBody(
    file: File,
    mime: String,
    onProgress: ((sent: Long, total: Long) -> Unit)?,
): RequestBody = object : RequestBody() {
    override fun contentType(): MediaType? = mime.toMediaTypeOrNull()
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        var lastTick = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                sent += n
                if (onProgress != null) {
                    // 每传 5% 报一次，别太频繁把界面刷爆
                    val step = (total / 20).coerceAtLeast(64 * 1024L)
                    if (sent - lastTick >= step || sent >= total) {
                        lastTick = sent
                        onProgress(sent, total)
                    }
                }
            }
        }
    }
}
