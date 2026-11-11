package com.cloudmusic.app.data.quark

import com.cloudmusic.app.data.AudioMagic

/** 夸克分享链接解出来的临时令牌。 */
data class QuarkToken(
    val stoken: String,
    /** 分享者的昵称 */
    val author: String,
    /** 分享里的文件总数 */
    val total: Int,
)

/** 分享里的一个文件。 */
data class QuarkShareFile(
    val fid: String,
    val fileName: String,
    val size: Long,
    /** 秒，夸克对音频会给出时长 */
    val durationSec: Int,
    val md5: String,
    val isDir: Boolean,
)

/** 已经能下载的文件信息。 */
data class QuarkFile(
    val fid: String,
    val fileName: String,
    val size: Long,
    val durationSec: Int,
    val md5: String,
    /** 带 auth_key 的直链，有时效 */
    val downloadUrl: String,
    /**
     * 这条直链配套的完整 Cookie。
     *
     * 夸克在签发直链的 `file/download` 响应里会 `Set-Cookie: __puus=<新值>`，
     * CDN 只认这个新值；用登录时存的旧 __puus 去 GET 直链会被 Tengine 412 拦掉。
     * 所以直链和它的 cookie 必须成对传下去（并发导入时各用各的，不能共用全局）。
     */
    val cookie: String = "",
)

/** 转存任务结果里的文件信息。 */
data class Quad(
    val first: String,
    val second: String,
    val third: Long,
    val fourth: Int,
)

/** 直链探测结果 —— 诊断页用。 */
data class QuarkProbe(
    val httpCode: Int,
    val contentType: String,
    val contentLength: Long,
    /** 文件头四个字节的十六进制，用来判断是不是真音频 */
    val magic: String,
    /** 文件头前 16 字节的十六进制，FLAC / M4A 这类要看到更后面才认得出 */
    val head: String = "",
    /** 实际读到的字节数 */
    val readBytes: Long,
    val message: String,
    /** 这次探测用的是哪种请求 */
    val label: String = "",
) {
    /** 跟下载层的校验用同一套规则，不然会出现「探测能下、下载说不是音频」。 */
    val looksLikeAudio: Boolean get() = AudioMagic.looksLikeAudio(head.ifBlank { magic })
}
