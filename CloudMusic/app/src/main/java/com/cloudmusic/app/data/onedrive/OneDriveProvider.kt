package com.cloudmusic.app.data.onedrive

import com.cloudmusic.app.data.AudioTypes
import com.cloudmusic.app.data.CloudProvider
import com.cloudmusic.app.data.DavEntry
import com.cloudmusic.app.data.DeleteResult
import com.cloudmusic.app.data.Prefs
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.ScanResult
import com.cloudmusic.app.data.UploadResult

/** OneDrive 作为网盘后端的实现。 */
class OneDriveProvider(
    private val graph: GraphClient,
    private val prefs: Prefs,
) : CloudProvider {

    /**
     * 「目录路径 → OneDrive 文件夹 id」的缓存。
     *
     * ensureFolder 每次都要先 list 一遍父目录找同名文件夹 ——
     * 导入十首歌就是十次多余的 list（目录里文件多时更慢）。
     * 同一个目录查一次就够。
     */
    private val dirCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override val kind: ProviderKind = ProviderKind.ONEDRIVE

    override suspend fun scan(
        onProgress: (found: Int, currentPath: String) -> Unit
    ): ScanResult {
        val audio = mutableListOf<DavEntry>()
        val lyricFiles = mutableListOf<DavEntry>()
        val errors = mutableListOf<String>()
        // 队列里放 (文件夹 id，文件夹名字)，根目录用空 id
        val queue = ArrayDeque<Pair<String, String>>()
        queue.addLast("" to "")

        var visited = 0
        while (queue.isNotEmpty() && visited < 5000) {
            val (folderId, folderName) = queue.removeFirst()
            visited++

            val entries = try {
                graph.listChildren(if (folderId.isEmpty()) null else folderId)
            } catch (e: Exception) {
                errors.add("$folderName：${e.message ?: "读取失败"}")
                onProgress(audio.size, folderName)
                continue
            }
            onProgress(audio.size, folderName)

            for (e in entries) {
                if (e.isDirectory) {
                    queue.addLast(e.href to e.name)
                } else if (AudioTypes.isAudio(e)) {
                    audio.add(e)
                } else if (AudioTypes.isLyricFile(e)) {
                    lyricFiles.add(e)
                }
            }
        }
        return ScanResult(audio, lyricFiles, errors)
    }

    override suspend fun fetchText(entryId: String): String? = graph.downloadText(entryId)

    override fun playUrl(entryId: String): String = graph.playUrl(entryId)

    override fun isReady(): Boolean =
        prefs.oneDriveAccessToken.isNotBlank() && prefs.oneDriveClientId.isNotBlank()

    override suspend fun upload(
        localFile: java.io.File,
        dirPath: String,
        fileName: String,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
    ): UploadResult {
        val target = folderIdFor(dirPath)
            ?: return UploadResult.Failed("在 OneDrive 上创建目录失败")
        val name = if (fileName.contains('.')) fileName else "$fileName.mp3"
        // 失败原因（没写权限 / 空间不足 / 限流）由 GraphClient 翻译好带回来
        return graph.uploadFile(target, name, localFile, onProgress)
    }

    override suspend fun delete(entryId: String): DeleteResult = graph.delete(entryId)

    /** 目录路径 → OneDrive 文件夹 id。查过一次就记住，别每首歌都重新 list。 */
    private suspend fun folderIdFor(dirPath: String): String? {
        val key = dirPath.trim()
        dirCache[key]?.let { return it }
        var folderId: String? = null
        for (seg in key.split('/', '　').map { it.trim() }.filter { it.isNotEmpty() }) {
            folderId = graph.ensureFolder(folderId, seg) ?: return null
        }
        val id = if (folderId.isNullOrEmpty()) "root" else folderId
        dirCache[key] = id
        return id
    }
}
