package com.cloudmusic.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /**
     * 读取文件字节（同目录封面图 / 音频头部）。
     * 默认返回 null —— 不支持二进制读取的网盘自动退化成占位图，不影响播放。
     */
    suspend fun fetchBytes(
        entryId: String,
        range: String? = null,
        maxBytes: Int = 512 * 1024,
    ): ByteArray? = null

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
        // 文件名解析只认「歌手 - 歌名」，遇到「歌名 - 歌手」就会反过来。
        // 有内嵌标签的话以标签为准纠正一遍，顺带把封面缓存清掉让它重查。
        val corrected = correctFromTags(tracks, provider)
        db.replaceAll(corrected, provider.kind.id)
        db.replaceLyricFiles(result.lyricFiles.map { it.toLyricFileRef(provider.kind.id) }, provider.kind.id)
        db.replaceCoverFiles(result.coverFiles.map { it.toCoverFileRef(provider.kind.id) }, provider.kind.id)
        ScanOutcome(corrected, result.errors, result.usedBytes)
    }

    /**
     * 用文件内嵌标签纠正曲库索引里的歌名/歌手。
     *
     * 只挑文件名带 '-' 的曲目：不带 '-' 的文件名整段就是歌名，不存在「反了」的问题，
     * 全盘几百首都去读 256KB 文件头会把同步拖得很慢。最多 4 个并发，
     * 读不到/标签不可信就原样保留，绝不影响正常入库。
     */
    private suspend fun correctFromTags(
        tracks: List<Track>,
        provider: CloudProvider,
    ): List<Track> = coroutineScope {
        val targets = tracks.filter { it.fileName.contains('-') && it.id.isNotBlank() }
        if (targets.isEmpty()) return@coroutineScope tracks

        val semaphore = Semaphore(4)
        // 曲库里谁当过歌手、当过几次 —— 用来判断「歌名-歌手」被解析反的情况
        val artistFreq = tracks.map { it.artist.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        val fixes = targets.map { t ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    // OneDrive 的 /content Range 不稳定，直接用曲库统计判断，别白等超时
                    val fixed = if (t.provider == ProviderKind.WEBDAV.id) {
                        val head = try {
                            provider.fetchBytes(t.id, "bytes=0-262143", 256 * 1024)
                        } catch (e: Exception) {
                            null
                        }
                        val tags = head?.let { AudioTagsReader.read(it) }
                        val byTags = tags?.let { AudioTagsReader.correct(t, it) }
                        byTags ?: AudioTagsReader.swapIfReversed(t, artistFreq)
                    } else {
                        AudioTagsReader.swapIfReversed(t, artistFreq)
                    } ?: return@withPermit null
                    t.key to fixed
                }
            }
        }.awaitAll().filterNotNull().toMap()

        if (fixes.isEmpty()) return@coroutineScope tracks
        // 歌名/歌手变了，之前按旧字段查的封面（或负缓存）都作废
        fixes.keys.forEach { db.clearCover(it) }
        tracks.map { fixes[it.key] ?: it }
    }

    /**
     * 历史索引的一次性纠正：老版本只按「歌手 - 歌名」拆文件名，
     * 碰到「搁浅-周杰伦.flac」会存成 title=周杰伦 / artist=搁浅。
     *
     * 这里只用整张曲库的歌手频次判定，不联网、不读音频头，几十条记录几毫秒跑完；
     * 修好的写回索引并清掉按旧字段查出来的封面缓存。
     * 幂等：交换后歌名不再出现在别人的 artist 里，下次启动不会再动它。
     */
    fun fixReversedTags(): List<Track> {
        val tracks = db.all()
        if (tracks.isEmpty()) return emptyList()
        val artistFreq = tracks.map { it.artist.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        val fixed = tracks.mapNotNull { t ->
            if (!t.fileName.contains('-')) return@mapNotNull null
            AudioTagsReader.swapIfReversed(t, artistFreq)
        }
        fixed.forEach {
            db.upsert(it)
            db.clearCover(it.key)
        }
        return fixed
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

/** 把网盘里的封面图片转成索引项：目录 + 文件名（不含扩展名）。 */
fun DavEntry.toCoverFileRef(providerId: String = ProviderKind.WEBDAV.id): CoverFileRef {
    val oneDrive = providerId == ProviderKind.ONEDRIVE.id
    val parent = if (oneDrive) parentPath
    else href.trimEnd('/').substringBeforeLast('/', "")
    val fileName = if (oneDrive) name else DavXmlParser.nameFromHref(href)
    return CoverFileRef(
        folder = decodePath(parent),
        base = decodePath(fileName.substringBeforeLast('.')),
        href = href
    )
}

private fun decodePath(s: String): String = try {
    URLDecoder.decode(s, "UTF-8")
} catch (e: Exception) {
    s
}
