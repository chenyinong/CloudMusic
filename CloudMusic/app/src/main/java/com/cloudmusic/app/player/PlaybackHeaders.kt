package com.cloudmusic.app.player

/**
 * 播放时「按直链地址」找回它自己的请求头。
 *
 * 夸克直链是会话绑定的：URL 里的 `token=5-...` 必须跟**签发这条直链时**刷新出来的
 * `__puus` 对得上，用设置里那份旧 Cookie 会被 CDN 直接 412。
 *
 * 但 MediaItem 没法带自定义请求头（`tag` 又不随 bundle 过 MediaSession），
 * 所以建媒体队列时把 `Track.extraHeaders` 按 URL 记在这里，
 * 播放层（[StreamDataSourceFactory]）发请求前再取出来。
 *
 * MediaService 和 App 在同一个进程里，直接共享内存就够，不用落盘。
 */
object PlaybackHeaders {

    /** 队列一般就几十首，留一点余量，按最近使用淘汰。 */
    private const val MAX = 128

    private val map = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Map<String, String>>,
        ): Boolean = size > MAX
    }

    fun put(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        synchronized(map) { map[url] = headers }
    }

    fun of(url: String): Map<String, String> {
        if (url.isBlank()) return emptyMap()
        synchronized(map) { return map[url] ?: emptyMap() }
    }
}
