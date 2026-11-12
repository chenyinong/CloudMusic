package com.cloudmusic.app.data.cloud

import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.playlist.AudioCandidate
import com.cloudmusic.app.data.playlist.LiumingyeAudioSource
import com.cloudmusic.app.data.playlist.OnlineFailReason
import com.cloudmusic.app.data.playlist.OnlineResolution
import com.cloudmusic.app.data.playlist.PlaylistImportService
import com.cloudmusic.app.data.playlist.PlaylistPlatform
import com.cloudmusic.app.data.playlist.PlaylistTrack
import com.cloudmusic.app.data.playlist.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 云端歌曲的本地解析。
 *
 * 云端只存「歌是哪首」，不存可播的直链 —— 那是各家的时效性链接，
 * 存下来过两天就废了。所以歌到了本机再解析一遍：
 * - 有下歌吧 id 的，直接用它换夸克直链（最准，不会挑错文件）
 * - 其它情况按歌名歌手去网易云 / 酷我 / 下歌吧搜（跟歌单在线播放同一套逻辑）
 *
 * 用的是**本机**的夸克 Cookie，不依赖云端在线 —— 断网也能听已同步下来的歌。
 */
class CloudResolver(
    private val playlistImport: PlaylistImportService,
    private val quarkSource: LiumingyeAudioSource,
    /** 本机夸克是否已登录（网盘源没登录就等于没有） */
    private val quarkLoggedIn: () -> Boolean,
) {

    /**
     * 解析一首云端歌曲。
     *
     * @return 成功时带可播直链；失败时带一句能说清楚的原因
     */
    suspend fun resolve(t: CloudTrack): OnlineResolution = withContext(Dispatchers.IO) {
        // 有下歌吧 id：直接换直链，别再搜一遍挑同名文件
        if (t.quarkId.isNotBlank() && quarkLoggedIn()) {
            val hit = resolveByQuarkId(t)
            if (hit != null) return@withContext hit
        }
        // 别的走歌单那套在线解析
        playlistImport.resolveForOnlinePlay(t.toPlaylistTrack(), quarkLoggedIn())
    }

    /** 用下歌吧 id 直接换夸克直链。 */
    private suspend fun resolveByQuarkId(t: CloudTrack): OnlineResolution? {
        val candidate = AudioCandidate(
            sourceId = "liumingye:${t.quarkId}",
            sourceLabel = quarkSource.label,
            songId = t.quarkId,
            title = t.title,
            artist = t.artist,
            album = t.album,
            durationSec = t.durationSec,
            targetDurationSec = t.durationSec,
            variantTag = TrackMatcher.variantTag(t.title, t.artist),
        )
        val hit = try {
            quarkSource.resolve(candidate)
        } catch (e: Exception) {
            null
        } ?: return null
        if (hit.audioUrl.isBlank()) return null
        return OnlineResolution(hit, partialOnly = hit.partial)
    }

    /** 云端歌曲 → 歌单那套的曲目（好让匹配、下载逻辑直接复用）。 */
    fun CloudTrack.toPlaylistTrack(): PlaylistTrack = PlaylistTrack(
        key = key,
        title = title,
        artist = artist,
        album = album,
        durationSec = durationSec,
        platform = when (platform.lowercase()) {
            "qq" -> PlaylistPlatform.QQ
            else -> PlaylistPlatform.NETEASE
        },
        platformId = platformId.ifBlank { null },
    )

    /** 解析结果 → 一首能直接喂给播放器的歌。 */
    fun toTrack(t: CloudTrack, c: AudioCandidate): Track = Track(
        // 加上 cloud: 前缀，跟网盘里的同名文件区分开
        key = "$CLOUD_TRACK_PREFIX${t.key}",
        id = t.key,
        url = c.audioUrl,
        fileName = "${t.title}.${c.ext.ifBlank { "mp3" }}",
        title = t.title,
        artist = t.artist,
        album = t.album,
        folder = "",
        size = 0L,
        mime = "audio/*",
        durationSec = if (c.durationSec > 0) c.durationSec.toDouble() else t.durationSec.toDouble(),
        provider = PROVIDER_CLOUD,
        // 夸克直链是会话绑定的，Cookie 得跟着走
        extraHeaders = c.extraHeaders,
    )

    /** 占位曲：还没解析出直链时先进队列，轮到它之前再解析。 */
    fun placeholder(t: CloudTrack): Track = Track(
        key = "$CLOUD_TRACK_PREFIX${t.key}",
        id = t.key,
        url = "",
        fileName = "",
        title = t.title,
        artist = t.artist,
        album = t.album,
        folder = "",
        size = 0L,
        mime = "audio/*",
        durationSec = t.durationSec.toDouble(),
        provider = PROVIDER_CLOUD,
    )

    /**
     * 作废这一首的解析缓存。
     *
     * 直链过期（还绑着夸克 Cookie）时播放会直接失败，
     * 把缓存清掉，下一次解析就会重新搜源、重新签发直链。
     */
    fun invalidate(t: CloudTrack) {
        playlistImport.invalidateCached(t.title, t.artist)
    }

    companion object {
        /** 云端歌曲在 Track 里的来源标记。 */
        const val PROVIDER_CLOUD = "cloud"

        /** 解析失败时给用户看的话。 */
        fun failText(reason: OnlineFailReason?): String = when (reason) {
            OnlineFailReason.ARTIST_MISMATCH -> "音源里有同名版本，但歌手标的不一样，没能直接播"
            OnlineFailReason.NO_SAME_TITLE -> "几个音源都没搜到同名歌曲"
            OnlineFailReason.NO_DIRECT_URL -> "找到同名同歌手，但拿不到可播的直链"
            OnlineFailReason.SOURCE_ERROR -> "音源请求都失败了，检查一下网络"
            null -> "没能解析出可播的直链"
        }
    }
}
