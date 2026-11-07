package com.cloudmusic.app.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.cloudmusic.app.data.Prefs
import com.cloudmusic.app.data.StreamAuth
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
                    .header("User-Agent", "CloudMusic/1.0 (Android)")

                val authHeader = StreamAuth.header(prefs, host)
                if (authHeader != null) builder.header("Authorization", authHeader)

                chain.proceed(builder.build())
            }
            .build()

        return OkHttpDataSource.Factory(client).createDataSource()
    }
}
