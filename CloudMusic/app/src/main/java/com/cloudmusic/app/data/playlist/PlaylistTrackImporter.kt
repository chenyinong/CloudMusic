package com.cloudmusic.app.data.playlist

import com.cloudmusic.app.data.AudioTypes
import com.cloudmusic.app.data.CloudProvider
import com.cloudmusic.app.data.DavXmlParser
import com.cloudmusic.app.data.DownloadResult
import com.cloudmusic.app.data.MusicHit
import com.cloudmusic.app.data.MusicSearchRepository
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.UploadResult
import java.io.File

/**
 * 把歌单里的**一首**歌下载下来传到网盘。
 *
 * 跟原来「整张单批量下」的流程分开，是因为用户要的是：
 * 先把歌单存下来，想听哪首再单独导哪首。
 */
class PlaylistTrackImporter(private val musicSearch: MusicSearchRepository) {

    sealed class Result {
        /**
         * 传完了。
         *
         * @param track 网盘里那个文件的曲目信息。
         *   上传接口会返回远端路径，据此能直接拼出播放地址 ——
         *   这样导入完马上就能播，不用等重新扫描整个网盘。
         */
        data class Done(val fileName: String, val track: Track? = null) : Result()

        /** 没成，消息直接给用户看 */
        data class Failed(val message: String) : Result()
    }

    /**
     * @param onPhase 阶段回调：准备中 / 下载中 / 上传中
     */
    suspend fun import(
        track: PlaylistTrack,
        candidate: AudioCandidate,
        provider: CloudProvider,
        dir: String,
        cacheDir: File,
        onPhase: (String) -> Unit,
    ): Result {
        onPhase("准备中")
        // 临时文件的扩展名也按实际格式来，系统解码器读时长更稳
        val tmp = File(cacheDir, "plist_${track.key.hashCode()}.${candidate.ext.ifBlank { "mp3" }}")
        val hit = MusicHit(
            id = candidate.songId,
            title = candidate.title,
            artist = candidate.artist,
            album = candidate.album,
            durationSec = candidate.durationSec,
            source = candidate.sourceLabel,
            directUrl = candidate.audioUrl,
            // 夸克那种会话绑定的直链，得带上 Cookie 才能下
            extraHeaders = candidate.extraHeaders,
        )
        // 文件名用歌单里的歌名歌手，跟「在线搜歌」保持一致，入库才拆得开。
        // 扩展名按实际格式来 —— 夸克给的是 FLAC 就存 .flac，别一律 .mp3
        val fileName = MusicSearchRepository.buildFileName(
            track.artist,
            track.title,
            candidate.ext,
        )

        onPhase("下载中")
        val dl = musicSearch.download(hit, tmp)
        if (dl is DownloadResult.Failed) {
            tmp.delete()
            return Result.Failed(dl.message)
        }
        if (dl is DownloadResult.Partial) {
            tmp.delete()
            return Result.Failed(
                "公开渠道只能拿到 ${dl.gotSec} 秒（完整 ${dl.wantSec} 秒），版权受限"
            )
        }
        val ok = dl as DownloadResult.Ok

        onPhase("上传中")
        val up = provider.upload(ok.file, dir, fileName)
        ok.file.delete()
        if (up is UploadResult.Failed) {
            return Result.Failed(up.message)
        }
        onPhase("已导入")
        val entryId = (up as UploadResult.Ok).entryId
        return Result.Done(fileName, buildTrack(provider, entryId, fileName, track, dir, ok.bytes))
    }

    /**
     * 用上传接口返回的远端路径拼出曲目信息。
     *
     * 跟扫描出来的 Track 保持同一套 key 规则（`provider:id`），
     * 这样它跟以后扫描出来的同一条能对上，播放队列不会重复。
     *
     * @param dir 导入时选的目标目录。OneDrive 的 entryId 是 item id、不含路径，
     *   没法从它解析出位置，所以直接用用户选的目录当 folder；
     *   WebDAV 的 entryId 本身就是路径，照旧从它解析。
     * @param size 真实文件大小（来自下载结果），否则信息页显示不出来。
     */
    private fun buildTrack(
        provider: CloudProvider,
        entryId: String,
        fileName: String,
        src: PlaylistTrack,
        dir: String,
        size: Long,
    ): Track {
        val oneDrive = provider.kind == ProviderKind.ONEDRIVE
        val name = if (oneDrive) fileName else DavXmlParser.nameFromHref(entryId)
        val parsed = AudioTypes.parseTitle(name)
        val parent = if (oneDrive) dir else entryId.trimEnd('/').substringBeforeLast('/', "")
        val folderName = if (oneDrive) {
            dir.substringAfterLast('/').ifBlank { provider.kind.shortLabel }
        } else {
            DavXmlParser.nameFromHref(parent)
        }
        return Track(
            key = "${provider.kind.id}:$entryId",
            id = entryId,
            url = provider.playUrl(entryId),
            fileName = name,
            title = src.title.ifBlank { parsed.second.ifBlank { name } },
            artist = src.artist.ifBlank { parsed.first },
            album = src.album.ifBlank { folderName },
            folder = parent,
            size = size,
            mime = AudioTypes.mimeOf(name),
            durationSec = src.durationSec.toDouble(),
            provider = provider.kind.id,
        )
    }

    companion object {
        /**
         * 歌单里的歌按歌单名建个子目录，不然几百首混在一起没法管。
         *
         * @param base 网盘上的根目录，留空就是根目录
         * @param playlistName 歌单名
         */
        fun targetDir(base: String, playlistName: String): String {
            val b = base.trim().trim('/')
            val raw = playlistName.trim()
            val safe = raw.replace(Regex("[\\/:*?\"<>|]"), "_").trim('/')
            if (b.isEmpty()) return safe
            if (safe.isEmpty()) return b
            return "$b/$safe"
        }
    }
}
