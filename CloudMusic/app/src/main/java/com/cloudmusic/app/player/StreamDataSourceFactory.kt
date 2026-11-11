package com.cloudmusic.app.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.cloudmusic.app.data.Prefs
import com.cloudmusic.app.data.StreamAuth
import com.cloudmusic.app.data.quark.QuarkClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 播放用的数据源。
 *
 * 用 OkHttp 而不是默认实现，是因为 OneDrive 的下载地址会 302 跳到预签名直链，
 * OkHttp 跳转时会保留 Range 头（拖进度条需要）并且自动去掉跨域的 Authorization。
 * 鉴权头按目标 host 决定（和 DLNA 代理共用同一套规则）：graph.microsoft.com 用 Bearer，
 * 用户自己的 WebDAV 用 Basic。
 */
class StreamDataSourceFactory(private val prefs: Prefs) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val builder = request.newBuilder()

                // 这条直链自己的请求头优先 —— 必须在下面「按 host 补默认值」之前写，
                // 否则会被旧 Cookie 盖掉。
                //
                // 夸克直链是会话绑定的：URL 里的 token 只认签发它时刷新出来的
                // `__puus`，拿设置里那份旧 Cookie 去请求会被 CDN 412。
                // 建队列时已经按 URL 存好了（见 PlaybackHeaders / PlayerConnection）。
                val extra = PlaybackHeaders.of(request.url.toString())
                for ((k, v) in extra) builder.header(k, v)
                fun hasHeader(name: String) =
                    extra.keys.any { it.equals(name, ignoreCase = true) }

                // 夸克直链还要求 PC UA + Referer，缺了同样 412。
                // 曲库里的歌都来自自己的网盘，只有「搜歌页试听」和「歌单在线播放」
                // 会请求夸克域名，所以按 host 判断就够。
                // OkHttp 跟随 302 时只自动剥离 Authorization，其它普通头会保留，
                // 所以重定向到 CDN 之后这几个头依然在。
                val quark = host.contains("quark.cn", ignoreCase = true)
                if (quark) {
                    if (!hasHeader("User-Agent")) builder.header("User-Agent", QuarkClient.UA_PC)
                    if (!hasHeader("Cookie") && prefs.quarkCookie.isNotBlank()) {
                        builder.header("Cookie", prefs.quarkCookie.trim())
                    }
                    if (!hasHeader("Referer")) builder.header("Referer", "https://pan.quark.cn/")
                } else if (!hasHeader("User-Agent")) {
                    builder.header("User-Agent", "CloudMusic/1.0 (Android)")
                }

                val authHeader = StreamAuth.header(prefs, host)
                if (authHeader != null) builder.header("Authorization", authHeader)

                chain.proceed(builder.build())
            }
            .build()

        return OkHttpDataSource.Factory(client).createDataSource()
    }
}
