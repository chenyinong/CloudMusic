package com.cloudmusic.app.ui

import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.playlist.SavedTrack

/**
 * 给歌单里的歌造一个能查封面的 [Track]。
 *
 * - 已经导入网盘：用曲库匹配到的真 Track，直接命中曲库里已有的封面缓存。
 * - 还没导入：手上只有歌名/歌手，用平台歌曲 id 造一个跨歌单稳定的 key；
 *   id/url/folder 全留空，CoverRepository 会跳过同目录图与 2MB 音频头，
 *   只走在线检索。查到的封面按这个 key 存进 covers 表，
 *   列表页、详情页、别的歌单遇到同一首都能复用同一张。
 */
fun coverTrackFor(t: SavedTrack, library: Track?): Track {
    if (library != null) return library
    val stableId = t.platformId.ifBlank {
        t.trackKey.ifBlank { "${t.title}|${t.artist}" }
    }
    return Track(
        key = "pl:${t.platform.id}:$stableId",
        id = "",
        url = "",
        fileName = "",
        title = t.title,
        artist = t.artist,
        album = t.album,
        folder = "",
        size = 0L,
        mime = "",
        provider = "online-pending",
    )
}
