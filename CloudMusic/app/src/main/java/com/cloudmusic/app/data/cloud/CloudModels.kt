package com.cloudmusic.app.data.cloud

import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端歌曲在本地曲库 key 上的前缀。
 *
 * 云端歌没有本地文件，塞进队列时用这个前缀跟网盘里的同名文件区分开。
 */
const val CLOUD_TRACK_PREFIX = "cloud:"

/**
 * 云端（Cloudflare Workers 版网页云音乐）里的一首歌。
 *
 * 字段跟网页版的 Track 一一对应 —— 两端共用同一套接口，
 * 所以同一首歌在网页上收藏的、在 App 里收藏的，是同一条数据。
 */
data class CloudTrack(
    /** 全局唯一键。网页版是 `netease:123` / `qq:xxx` / `quark:yyy` */
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    /** netease / qq */
    val platform: String,
    /** 平台内的歌曲 id，有它就不用再搜一遍 */
    val platformId: String,
    /** 专辑封面直链 */
    val coverUrl: String,
    /** 下歌吧（夸克网盘）的资源 id，有它就直接取直链，不用再搜 */
    val quarkId: String,
) {

    /** 存回云端时的 JSON。空字段不写，云端那边读起来更干净。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationSec", durationSec)
        put("platform", platform)
        if (platformId.isNotBlank()) put("platformId", platformId)
        if (coverUrl.isNotBlank()) put("coverUrl", coverUrl)
        if (quarkId.isNotBlank()) put("quarkId", quarkId)
    }

    companion object {
        /** 解析云端下发的 track 对象。歌名和 key 缺一不可，其余都可有可无。 */
        fun fromJson(o: JSONObject): CloudTrack? {
            val key = text(o, "key")
            val title = text(o, "title")
            if (key.isEmpty() || title.isEmpty()) return null
            return CloudTrack(
                key = key,
                title = title,
                artist = text(o, "artist"),
                album = text(o, "album"),
                durationSec = o.optInt("durationSec", 0),
                platform = text(o, "platform").ifEmpty { "netease" },
                platformId = text(o, "platformId"),
                coverUrl = text(o, "coverUrl"),
                quarkId = text(o, "quarkId"),
            )
        }

        /**
         * 取字符串字段。
         *
         * `optString` 在字段是 JSON `null` 时返回的是**字符串 "null"**，
         * 不是空串 —— 封面地址要是变成 "null"，就会拿它去下载，永远下不来。
         */
        private fun text(o: JSONObject, name: String): String {
            if (!o.has(name) || o.isNull(name)) return ""
            val s = o.optString(name).trim()
            return if (s.isEmpty() || s == "null") "" else s
        }

        fun listFromJson(arr: JSONArray?): List<CloudTrack> {
            if (arr == null) return emptyList()
            val out = mutableListOf<CloudTrack>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o -> fromJson(o)?.let(out::add) }
            }
            return out
        }
    }
}

/** 登录成功的结果。 */
data class CloudLogin(val token: String, val username: String)

/** 云端的一条收藏（小红心）。 */
data class CloudFavorite(
    val key: String,
    val track: CloudTrack?,
    /** 软删除墓碑：别的设备取消了收藏，本机也得跟着删 */
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 云端歌单列表里的一项（不含曲目明细）。 */
data class CloudPlaylistRef(
    val id: Long,
    val name: String,
    val platform: String,
    val updatedAt: Long,
    val trackCount: Int,
    val cover: String,
)

/** 云端一张歌单的完整内容。 */
data class CloudPlaylistDetail(
    val id: Long,
    val name: String,
    val platform: String,
    val updatedAt: Long,
    val tracks: List<CloudTrack>,
)

/** 云端账号信息。 */
data class CloudUser(val id: Long, val username: String)

/** 云端接口出错。消息直接给用户看。 */
class CloudException(message: String) : Exception(message)
