package com.cloudmusic.app.data

/**
 * 服务端协议测试用的空壳。
 *
 * 只保留 DlnaServer 真正用到的那几个成员，真实实现（Prefs/Track/StreamAuth）
 * 依赖 Android 的 SharedPreferences，在 JVM 上跑不起来，这里只做替身。
 */

data class Track(
    val key: String,
    val id: String,
    val url: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val size: Long,
    val mime: String,
    val durationSec: Double = 0.0,
    val provider: String = "webdav",
)

class Prefs {
    val webdavHost: String = ""
    val hasCredentials: Boolean = false
}

object StreamAuth {
    /** 测试里不需要真的鉴权，直接返回 null（走匿名访问） */
    fun header(prefs: Prefs, host: String): String? = null
}
