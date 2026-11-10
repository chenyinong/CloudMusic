package com.cloudmusic.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** 扫描结果。 */
data class ScanOutcome(
    val tracks: List<Track> = emptyList(),
    /** 读取失败的目录 */
    val errors: List<String> = emptyList(),
    /** 扫到的所有文件的总大小（字节），用于显示网盘已用空间 */
    val usedBytes: Long = 0L,
)

/** 支持的网盘类型。 */
enum class ProviderKind(val id: String, val label: String, val shortLabel: String) {
    WEBDAV("webdav", "WebDAV / 坚果云", "坚果云"),
    ONEDRIVE("onedrive", "OneDrive", "OneDrive");

    companion object {
        fun of(id: String?): ProviderKind = entries.firstOrNull { it.id == id } ?: WEBDAV
    }
}

/** 上传结果。 */
sealed class UploadResult {
    data class Ok(val entryId: String) : UploadResult()
    data class Failed(val message: String) : UploadResult()
}

/** 删除网盘文件的结果。 */
sealed class DeleteResult {
    data object Ok : DeleteResult()
    data class Failed(val message: String) : DeleteResult()
}

/**
 * 网盘后端抽象。
 * 扫描、读歌词文件、播放地址都从这里出，WebDAV 和 OneDrive 各实现一份。
 */
interface CloudProvider {
    val kind: ProviderKind

    /** 递归扫描整棵树 */
    suspend fun scan(onProgress: (found: Int, currentPath: String) -> Unit): ScanResult

    /** 读取一个小文件的内容（歌词文件），失败返回 null */
    suspend fun fetchText(entryId: String): String?

    /** 播放地址 */
    fun playUrl(entryId: String): String

    /** 当前是否可用（已登录、令牌有效等） */
    fun isReady(): Boolean

    /**
     * 把一个本地文件上传到网盘。
     *
     * @param dirPath 相对网盘根目录的路径，如 "云音乐导入/周杰伦"，空表示根目录
     * @param fileName 网盘上的文件名（会自动补扩展名）
     * @param onProgress 上传进度回调（已传字节 / 总字节）
     */
    suspend fun upload(
        localFile: java.io.File,
        dirPath: String,
        fileName: String,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): UploadResult

    /**
     * 删掉网盘里的一个文件。
     *
     * 只删文件本身，不动目录 —— 目录是用户自己建的，不该顺手删掉。
     *
     * @param entryId WebDAV 的 href / OneDrive 的 item id
     */
    suspend fun delete(entryId: String): DeleteResult
}

class LibraryRepository(
    private val prefs: Prefs,
    private val db: LibraryDb,
) {

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun client(creds: DavCredentials = prefs.credentials): DavClient =
        DavClient(normalizeServerUrl(creds.serverUrl), creds, http)

    fun normalizeServerUrl(raw: String): HttpUrl = DavClient.normalizeServerUrl(raw)

    suspend fun scan(
        provider: CloudProvider,
        onProgress: (found: Int, currentPath: String) -> Unit
    ): ScanOutcome = withContext(Dispatchers.IO) {
        val result = provider.scan(onProgress)
        val tracks = result.audio.map { it.toTrack(provider.playUrl(it.href), provider.kind.id) }
        db.replaceAll(tracks, provider.kind.id)
        db.replaceLyricFiles(result.lyricFiles.map { it.toLyricFileRef(provider.kind.id) }, provider.kind.id)
        ScanOutcome(tracks, result.errors, result.usedBytes)
    }

    /** 全部网盘的曲目 */
    fun cachedTracks(): List<Track> = db.all()

    /**
     * 把一条曲目写进索引。
     *
     * 刚导入的歌不等整盘扫描就能进曲库，歌单页马上就能播。
     */
    fun upsertTrack(track: Track) = db.upsert(track)

    /** 删掉某个网盘的索引（断开连接时用） */
    fun forgetProvider(providerId: String) = db.deleteProvider(providerId)

    /**
     * 从索引里删掉一首。
     *
     * 网盘上的文件真被删了才调这个 —— 索引留着的话，曲库里会点得到一首播不了的歌。
     */
    fun deleteTrack(key: String) = db.deleteTrack(key)

    fun updateDuration(key: String, durationSec: Double) = db.updateDuration(key, durationSec)
}

fun DavEntry.toTrack(url: String, providerId: String = ProviderKind.WEBDAV.id): Track {
    val oneDrive = providerId == ProviderKind.ONEDRIVE.id
    val fileName = if (oneDrive) name else DavXmlParser.nameFromHref(href)
    val parsed = AudioTypes.parseTitle(fileName)
    val parent = if (oneDrive) parentPath
    else href.trimEnd('/').substringBeforeLast('/', "")
    val folderName = if (oneDrive) {
        // parentPath 形如 /drive/root:/Music/Album
        parent.substringAfterLast(':').substringAfterLast('/')
    } else {
        DavXmlParser.nameFromHref(parent)
    }
    // 服务器报的 MIME 常常是 application/octet-stream，用扩展名兜底，
    // 否则 ExoPlayer 认不出 flac 这类格式
    val mime = contentType.takeIf { it.startsWith("audio/", ignoreCase = true) }
        ?: AudioTypes.mimeOf(fileName)
    return Track(
        key = "$providerId:$href",
        id = href,
        url = url,
        fileName = fileName,
        title = parsed.second.ifBlank { fileName },
        artist = parsed.first,
        album = folderName,
        folder = parent,
        size = size,
        mime = mime,
        durationSec = this.durationSec,
        provider = providerId
    )
}

/** 把网盘里的歌词文件转成索引项：目录 + 文件名（不含扩展名） */
fun DavEntry.toLyricFileRef(providerId: String = ProviderKind.WEBDAV.id): LyricFileRef {
    val oneDrive = providerId == ProviderKind.ONEDRIVE.id
    val parent = if (oneDrive) parentPath
    else href.trimEnd('/').substringBeforeLast('/', "")
    val fileName = if (oneDrive) name else DavXmlParser.nameFromHref(href)
    val base = fileName.substringBeforeLast('.')
    return LyricFileRef(
        folder = decodePath(parent),
        base = decodePath(base),
        href = href
    )
}

private fun decodePath(s: String): String = try {
    URLDecoder.decode(s, "UTF-8")
} catch (e: Exception) {
    s
}
