package com.cloudmusic.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudmusic.app.App
import com.cloudmusic.app.data.DavProvider
import com.cloudmusic.app.data.DownloadResult
import com.cloudmusic.app.data.MusicHit
import com.cloudmusic.app.data.MusicSearchRepository
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.UploadResult
import com.cloudmusic.app.data.quark.QuarkClient
import com.cloudmusic.app.data.quark.QuarkFile
import com.cloudmusic.app.data.quark.QuarkProbe
import com.cloudmusic.app.data.quark.QuarkShareFile
import com.cloudmusic.app.data.playlist.LiumingyeHit
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「夸克直链能不能拿到 / 能不能下」的诊断台。
 *
 * 这是决定后续走向的关键一环：游客态签出来的直链会被 CDN 用 412 拦掉，
 * 登录态签出来的能不能过，服务器上测不出来，只能在真机上验。
 */
class QuarkDiagViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef: App = app as App
    private val quark = appRef.quarkClient
    private val lium = appRef.liumingye

    var keyword by mutableStateOf("")
    var searching by mutableStateOf(false)
    var hits by mutableStateOf<List<LiumingyeHit>>(emptyList())

    var shareUrl by mutableStateOf("")
    var resolving by mutableStateOf(false)

    /** 解析过程的每一步，方便定位卡在哪 */
    var steps by mutableStateOf<List<String>>(emptyList())

    var file by mutableStateOf<QuarkFile?>(null)
    var probe by mutableStateOf<QuarkProbe?>(null)
    var probeNoCookie by mutableStateOf<QuarkProbe?>(null)

    /** 几种请求姿势各试一次的结果，用来定位「探测能下、下载被毙」这类怪事 */
    var variants by mutableStateOf<List<QuarkProbe>>(emptyList())
        private set

    var message by mutableStateOf("")

    /** 分享里的是不是压缩包 */
    var isArchive by mutableStateOf(false)
        private set

    /** 压缩包里的文件列表 */
    var archiveFiles by mutableStateOf<List<QuarkShareFile>>(emptyList())
        private set

    private var lastPwdId = ""
    private var lastStoken = ""
    private var lastFid = ""
    private var lastTitle = ""
    private var lastArtist = ""

    val loggedIn: Boolean get() = quark.loggedIn

    // ------------------------------------------------------------ liumingye 搜歌

    fun search() {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            message = "输入歌名，比如「稻香」"
            return
        }
        if (searching) return
        searching = true
        message = ""
        hits = emptyList()
        viewModelScope.launch {
            try {
                val list = lium.search(kw)
                hits = list
                message = if (list.isEmpty()) "没搜到" else "搜到 ${list.size} 条"
            } catch (e: Exception) {
                message = e.message ?: "搜索失败"
            } finally {
                searching = false
            }
        }
    }

    /** 点了某条搜索结果 → 把它的分享链接填进去并直接解析。 */
    fun pick(hit: LiumingyeHit) {
        viewModelScope.launch {
            try {
                val d = lium.detail(hit.id)
                val best = d.best()
                if (best == null) {
                    message = "这条没有可用的分享链接"
                    return@launch
                }
                shareUrl = best.url
                lastTitle = d.title
                lastArtist = d.artist
                message = "已选：${d.title} - ${d.artist}（${best.quality}）"
                if (d.lyrics.isNotBlank()) {
                    steps = steps + "歌词：拿到了 ${d.lyrics.length} 字"
                }
                resolve(best.url)
            } catch (e: Exception) {
                message = e.message ?: "取详情失败"
            }
        }
    }

    // ------------------------------------------------------------ 夸克解析

    fun resolve(url: String) {
        val target = url.trim()
        if (target.isEmpty()) {
            message = "先贴一个分享链接，或者从上面搜一首歌"
            return
        }
        if (resolving) return
        resolving = true
        file = null
        probe = null
        steps = emptyList()
        message = ""
        viewModelScope.launch {
            try {
                val pwdId = QuarkClient.pwdIdOf(target)
                if (pwdId == null) {
                    message = "看不出分享码，链接应该长这样：https://pan.quark.cn/s/xxxxxxxx"
                    return@launch
                }
                lastPwdId = pwdId
                steps = steps + "① 分享码 $pwdId"

                val token = quark.shareToken(pwdId)
                lastStoken = token.stoken
                steps = steps + "② stoken 拿到了（分享者：${token.author.ifBlank { "?" }}）"

                val files = quark.shareDetail(pwdId, token.stoken)
                if (files.isEmpty()) {
                    message = "分享里没有文件"
                    return@launch
                }
                val f = files.first { !it.isDir } ?: files.first()
                lastFid = f.fid
                steps = steps + "③ 文件 ${f.fileName} · ${f.size} 字节 · ${f.durationSec}s"

                // 压缩包？列一下包里有什么
                isArchive = f.fileName.endsWith(".rar", true) ||
                    f.fileName.endsWith(".zip", true) ||
                    f.fileName.endsWith(".7z", true)
                if (isArchive) {
                    val inner = quark.previewArchive(pwdId, token.stoken, f.fid)
                    archiveFiles = inner
                    val audioInside = inner.filter { isAudioName(it.fileName) }
                    steps = steps + "③b 压缩包里有 ${inner.size} 个文件" +
                        (if (audioInside.isEmpty()) "（没有音频）" else "，其中 ${audioInside.size} 个音频")
                }

                val d = quark.downloadUrl(pwdId, token.stoken, f.fid)
                file = d
                steps = steps + "④ 直链拿到了：" + d.downloadUrl.take(60) + "…"
                message = if (isArchive) {
                    "这是压缩包，可以转存后在线解压"
                } else {
                    "解析成功，可以试下载了"
                }
            } catch (e: Exception) {
                message = e.message ?: "解析失败"
                steps = steps + "✗ " + (e.message ?: "失败")
            } finally {
                resolving = false
            }
        }
    }

    /** 试下载：只读文件头，看能不能真的拿到音频。 */
    fun probeDownload() {
        val f = file ?: run {
            message = "先解析出直链"
            return
        }
        if (f.downloadUrl.isBlank()) {
            message = "没有直链"
            return
        }
        viewModelScope.launch {
            message = "正在试下载…"
            // 带 cookie 和不带 cookie 各试一次，对比一下 cookie 是不是关键
            val withC = quark.probe(f.downloadUrl, withCookie = true)
            val withoutC = quark.probe(f.downloadUrl, withCookie = false)
            probe = withC
            probeNoCookie = withoutC
            message = if (withC.looksLikeAudio) {
                "能下！读到 ${withC.readBytes} 字节，文件头 ${withC.magic}"
            } else if (withoutC.looksLikeAudio) {
                "不带 Cookie 反而能下 —— ${withoutC.readBytes} 字节，文件头 ${withoutC.magic}"
            } else {
                "下不到：带 Cookie ${withC.httpCode} / 不带 ${withoutC.httpCode}"
            }
            steps = steps + "⑤ 试下载（带 Cookie）→ HTTP ${withC.httpCode} · ${withC.contentType} · ${withC.readBytes} 字节 · ${withC.message}"
            steps = steps + "⑤ 试下载（不带 Cookie）→ HTTP ${withoutC.httpCode} · ${withoutC.contentType} · ${withoutC.readBytes} 字节 · ${withoutC.message}"
        }
    }

    /**
     * 用三种请求姿势各试一次。
     *
     * 夸克 CDN 对「Range 请求」和「完整请求」的处理可能不一样，
     * 探测层用 Range、下载层用完整请求，两边对不上就会出现
     * 「探测说能下、下载完说不是音频」。这里一次把三种姿势都试出来。
     */
    fun probeVariants() {
        val f = file ?: run {
            message = "先解析出直链"
            return
        }
        if (f.downloadUrl.isBlank()) {
            message = "没有直链"
            return
        }
        viewModelScope.launch {
            message = "正在用三种姿势各试一次…"
            val list = quark.probeVariants(f.downloadUrl)
            variants = list
            for (p in list) {
                steps = steps + "〔${p.label}〕HTTP ${p.httpCode} · ${p.contentType} · " +
                    "len=${p.contentLength} · 读了 ${p.readBytes} 字节 · " +
                    "文件头 ${p.head.take(16)} · ${if (p.looksLikeAudio) "✅是音频" else "❌不是音频"}"
            }
            val good = list.filter { it.looksLikeAudio }
            message = if (good.isEmpty()) {
                "三种姿势都拿不到音频字节"
            } else {
                "能下的姿势：" + good.joinToString("、") { it.label }
            }
        }
    }

    /**
     * 路径 B：转存到自己盘，然后拿自己盘的直链。
     *
     * 转存是异步任务（先拿 task_id 再轮询），完成后才能拿到直链。
     */
    fun saveToMyDrive() {
        if (lastPwdId.isBlank() || lastStoken.isBlank() || lastFid.isBlank()) {
            message = "先解析出文件"
            return
        }
        viewModelScope.launch {
            message = "正在转存…"
            try {
                val f = quark.saveAndResolve(
                    lastPwdId, lastStoken, lastFid,
                    onStep = { step -> steps = steps + "⑥ " + step },
                    onRaw = { raw -> steps = steps + "〔原始〕" + raw }
                )
                file = f
                steps = steps + "⑦ 自己盘的直链拿到了：" + f.downloadUrl.take(50) + "…"
                message = "转存成功，点「读一下文件头试试」验证直链"
            } catch (e: Exception) {
                message = e.message ?: "转存失败"
                steps = steps + "⑥ 转存 → 失败（${e.message ?: "未知"}）"
            }
        }
    }

    /**
     * 完整跑一遍：转存 → 拿直链 → 下载 → 传坚果云 → 删掉中转文件。
     *
     * 这是路径 B 的终点验证。
     */
    fun saveImportAndClean() {
        if (lastPwdId.isBlank() || lastStoken.isBlank() || lastFid.isBlank()) {
            message = "先解析出文件"
            return
        }
        viewModelScope.launch {
            message = "正在转存…"
            var savedFid = ""
            try {
                val f = quark.saveAndResolve(
                    lastPwdId, lastStoken, lastFid,
                    onStep = { step -> steps = steps + "⑥ " + step },
                    onRaw = { raw -> steps = steps + "〔原始〕" + raw }
                )
                savedFid = f.fid
                file = f
                steps = steps + "⑦ 自己盘直链拿到了"

                message = "正在下载…"
                val tmp = File(appRef.cacheDir, "quark_diag_${f.fid}.mp3")
                val hit = MusicHit(
                    id = f.fid,
                    title = lastTitle.ifBlank { f.fileName },
                    artist = lastArtist,
                    album = "",
                    durationSec = f.durationSec,
                    source = "夸克",
                    directUrl = f.downloadUrl,
                    extraHeaders = quarkHeaders()
                )
                when (val dl = appRef.musicSearch.download(hit, tmp)) {
                    is DownloadResult.Failed -> {
                        message = "下载失败：${dl.message}"
                        steps = steps + "⑧ 下载 → 失败（${dl.message}）"
                        return@launch
                    }
                    is DownloadResult.Partial -> {
                        message = "只下到 ${dl.gotSec} 秒（完整 ${dl.wantSec} 秒）"
                        steps = steps + "⑧ 下载 → 不完整"
                        return@launch
                    }
                    is DownloadResult.Ok -> {
                        steps = steps + "⑧ 下载 → 成功 ${dl.file.length()} 字节"
                        uploadToProvider(dl.file, f)
                    }
                }
            } catch (e: Exception) {
                message = e.message ?: "失败"
                steps = steps + "✗ " + (e.message ?: "失败")
            } finally {
                // 中转文件清掉，不占夸克空间
                if (savedFid.isNotBlank()) {
                    val ok = quark.deleteFile(savedFid)
                    steps = steps + "⑨ 清理中转文件 → " + if (ok) "已删除" else "没删掉（手动清一下）"
                }
            }
        }
    }

    private fun isAudioName(name: String): Boolean =
        name.endsWith(".mp3", true) || name.endsWith(".flac", true) ||
            name.endsWith(".wav", true) || name.endsWith(".m4a", true) ||
            name.endsWith(".aac", true) || name.endsWith(".ogg", true)

    /**
     * 夸克直链是**会话绑定**的：URL 里的 `token=5-...` 要跟登录 Cookie 对得上，
     * 光拿直链去 GET 会被 CDN 用 412 拦掉。所以下载时必须把 Cookie 带上。
     */
    private fun quarkHeaders(): Map<String, String> {
        val c = appRef.prefs.quarkCookie
        val h = mutableMapOf("Referer" to "https://pan.quark.cn/")
        if (c.isNotBlank()) h["Cookie"] = c
        return h
    }

    fun reset() {
        file = null
        probe = null
        steps = emptyList()
        message = ""
    }

    // ------------------------------------------------------------ 完整跑一遍

    /**
     * 完整下载 + 传到坚果云 —— 一次验证到终点。
     *
     * 下载用的是现成的 [com.cloudmusic.app.data.MusicSearchRepository.download]，
     * 它自带「文件头魔数 + 时长」校验，所以能真的确认下到的是完整音频。
     */
    fun fullImport() {
        val f = file ?: run {
            message = "先解析出直链"
            return
        }
        if (f.downloadUrl.isBlank()) {
            message = "没有直链"
            return
        }
        viewModelScope.launch {
            message = "正在下载…"
            steps = steps + "⑥ 下载 → 开始"
            val tmp = File(appRef.cacheDir, "quark_diag_${f.fid}.mp3")
            val hit = MusicHit(
                id = f.fid,
                title = lastTitle.ifBlank { f.fileName },
                artist = lastArtist,
                album = "",
                durationSec = f.durationSec,
                source = "夸克",
                directUrl = f.downloadUrl,
                extraHeaders = quarkHeaders()
            )
            when (val dl = appRef.musicSearch.download(hit, tmp)) {
                is DownloadResult.Failed -> {
                    message = "下载失败：${dl.message}"
                    steps = steps + "⑥ 下载 → 失败（${dl.message}）"
                }
                is DownloadResult.Partial -> {
                    message = "只下到 ${dl.gotSec} 秒，完整 ${dl.wantSec} 秒"
                    steps = steps + "⑥ 下载 → 不完整"
                }
                is DownloadResult.Ok -> {
                    steps = steps + "⑥ 下载 → 成功 ${dl.file.length()} 字节"
                    uploadToProvider(dl.file, f)
                }
            }
        }
    }

    private suspend fun uploadToProvider(downloaded: File, f: QuarkFile) {
        val prefs = appRef.prefs
        val kind = ProviderKind.of(prefs.importProvider)
        val provider = when (kind) {
            ProviderKind.WEBDAV ->
                if (prefs.hasCredentials) DavProvider(appRef.repository.client(), prefs) else null
            ProviderKind.ONEDRIVE ->
                if (prefs.oneDriveAccessToken.isNotBlank()) appRef.oneDriveProvider else null
        }
        if (provider == null) {
            message = "下载成功了，但${kind.shortLabel}还没连上，先去设置里连网盘"
            steps = steps + "⑦ 上传 → 没连网盘"
            return
        }
        message = "正在上传到${kind.shortLabel}…"
        val name = MusicSearchRepository.buildFileName(lastArtist, lastTitle.ifBlank { f.fileName })
        val up = provider.upload(downloaded, prefs.importDir, name)
        downloaded.delete()
        when (up) {
            is UploadResult.Failed -> {
                message = "上传失败：${up.message}"
                steps = steps + "⑦ 上传 → 失败（${up.message}）"
            }
            is UploadResult.Ok -> {
                message = "全通了！文件已经在${kind.shortLabel}的 ${prefs.importDir.ifBlank { "根目录" }}"
                steps = steps + "⑦ 上传 → 成功（$name）"
            }
        }
    }
}
