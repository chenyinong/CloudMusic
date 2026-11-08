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

                // 夸克直链是会话绑定的：必须带登录 Cookie + Referer + PC UA，
                // 否则 CDN 直接 412。曲库里的歌都来自自己的网盘，只有「搜歌页试听」
                // 和「歌单在线播放」会请求夸克域名，所以按 host 判断就够。
                // OkHttp 跟随 302 时只自动剥离 Authorization，其它普通头会保留，
                // 所以重定向到 CDN 之后这几个头依然在。
                val quark = host.contains("quark.cn", ignoreCase = true) &&
                    prefs.quarkCookie.isNotBlank()
                val builder = request.newBuilder()
                    .header(
                        "User-Agent",
                        if (quark) QuarkClient.UA_PC else "CloudMusic/1.0 (Android)"
                    )
                if (quark) {
                    builder.header("Cookie", prefs.quarkCookie.trim())
                    builder.header("Referer", "https://pan.quark.cn/")
                }

                val authHeader = StreamAuth.header(prefs, host)
                if (authHeader != null) builder.header("Authorization", authHeader)

                chain.proceed(builder.build())
            }
            .build()

        return OkHttpDataSource.Factory(client).createDataSource()
    }
}
