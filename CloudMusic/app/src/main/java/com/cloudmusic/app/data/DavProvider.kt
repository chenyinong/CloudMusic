package com.cloudmusic.app.data

/** 把已有的 WebDAV 客户端包装成统一的网盘后端。 */
class DavProvider(private val client: DavClient) : CloudProvider {

    /**
     * 根目录能不能直接写。
     *
     * 不少网盘不允许 MKCOL，但允许直接 PUT；也有反过来的 —— 根目录写不进去。
     *
     * 关键在于**失败的那次 PUT 已经把整个文件传上去了**（服务端才拒绝），
     * 所以每首歌都先试一遍根目录的话，等于每首歌白传一遍完整文件。
     * 试过一次就知道行不行，后面别再试了。
     */
    @Volatile
    private var rootWritable: Boolean? = null

    override val kind: ProviderKind = ProviderKind.WEBDAV

    override suspend fun scan(
        onProgress: (found: Int, currentPath: String) -> Unit
    ): ScanResult = client.scanAll(client.baseUrl.encodedPath, onProgress)

    override suspend fun fetchText(entryId: String): String? = client.fetchText(entryId)

    override fun playUrl(entryId: String): String = client.urlFor(entryId).toString()

    override fun isReady(): Boolean = true

    override suspend fun upload(
        localFile: java.io.File,
        dirPath: String,
        fileName: String,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
    ): UploadResult {
        val name = if (fileName.contains('.')) fileName else "$fileName.mp3"
        val mime = AudioTypes.mimeOf(name)

        // 先不建目录直接传到根目录：不少网盘不允许 MKCOL，但允许直接 PUT。
        // 只在还不知道行不行的时候试 —— 试失败过一次就直奔目录那条路。
        if (rootWritable != false) {
            val direct = client.childHref(client.baseHref, name)
            if (client.putFile(direct, localFile, mime, onProgress)) {
                rootWritable = true
                return UploadResult.Ok(direct)
            }
            rootWritable = false
        }

        // 退一步：逐级建目录后再传
        val dirHref = client.ensurePath(dirPath)
        val href = client.childHref(dirHref, name)
        return if (client.putFile(href, localFile, mime, onProgress)) {
            UploadResult.Ok(href)
        } else {
            UploadResult.Failed(
                "上传失败：网盘拒绝了这个路径。可能是目录不存在、目录名含非法字符，" +
                    "或者这个网盘的 WebDAV 不允许写入（坚果云是允许的）"
            )
        }
    }

    override suspend fun delete(entryId: String): DeleteResult = client.delete(entryId)
}
