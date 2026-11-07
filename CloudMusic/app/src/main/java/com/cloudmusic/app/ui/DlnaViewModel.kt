package com.cloudmusic.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudmusic.app.App
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.dlna.DlnaClient
import com.cloudmusic.app.data.dlna.DlnaDevice
import com.cloudmusic.app.data.dlna.DlnaServer
import com.cloudmusic.app.data.dlna.LanHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 投屏的状态管理。
 *
 * 两件事：
 * 1. **投到设备**：手机找到局域网里的电视/音箱，把当前这首歌推过去播；
 * 2. **让电视来播**：手机自己当渲染器，电视的 DLNA 播放器里能看到「云音乐」。
 *
 * 两种用法都要开着手机上的那个 HTTP 服务（电视要来这里拉音频流），
 * 所以推歌时会自动把它拉起来。
 */
class DlnaViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef = app as App
    private val client = DlnaClient(app)

    /** 搜到的渲染器 */
    var devices by mutableStateOf<List<DlnaDevice>>(emptyList())
        private set

    var discovering by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 正在往哪台设备投 */
    var casting by mutableStateOf<DlnaDevice?>(null)
        private set

    /** 手机是不是正在当渲染器 */
    var serverRunning by mutableStateOf(false)
        private set

    /** 手机在局域网里的地址 */
    var serverAddress by mutableStateOf("")
        private set

    /** SSDP 应答有没有起来（起不来电视就搜不到本机） */
    var ssdpAvailable by mutableStateOf(true)
        private set

    /** 电视那边开始播了（手机本地就该让位了） */
    var remotePlaying by mutableStateOf(false)
        private set

    /**
     * 投屏中，设备那边的播放状态。
     *
     * 跟 [remotePlaying] 是两码事：那个是「手机当渲染器」时电视来挑歌，
     * 这个是「把歌推到电视上」时电视放不放。
     */
    var castPlaying by mutableStateOf(false)
        private set

    /** 投屏时设备那边的播放进度（毫秒） */
    var castPositionMs by mutableStateOf(0L)
        private set

    /** 投屏时设备那边这首的总时长（毫秒） */
    var castDurationMs by mutableStateOf(0L)
        private set

    /** 投屏时设备在放的是哪首 —— 迷你播放器上要显示它 */
    var castTitle by mutableStateOf("")
        private set

    /** 投屏时设备在放的这首的歌手 */
    var castArtist by mutableStateOf("")
        private set

    private var server: DlnaServer? = null
    private var castPollJob: kotlinx.coroutines.Job? = null

    /** 最近一次拖动到的位置，等着验证设备到底动没动 */
    private var seekTarget = -1L
    private var seekTargetAt = 0L

    /** 刚换歌的时刻 —— 这段时间内设备的进度还没更新，不能拿它当基准 */
    private var castTrackStartedAt = 0L

    /**
     * 本地推算进度用的基准：设备报的位置，以及记下它的时刻。
     *
     * 中间的时间用本地时钟补上（`基准 + 经过的时间`），进度条和歌词才走得顺。
     *
     * 之所以提成字段而不是轮询协程里的局部变量：切歌、拖动都要能**显式清零**，
     * 不然设备上报的旧位置会把基准重新带偏（上一首的 1 分钟会跟着下一首一起显示）。
     */
    private var castBasePos = 0L
    private var castBaseAt = 0L

    /** 搜索局域网里的设备 */
    fun refresh() {
        if (discovering) return
        discovering = true
        error = null
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { client.discover(4000) }
            devices = list
            discovering = false
            if (list.isEmpty()) {
                error = "没搜到设备。请确认手机和电视连的是同一个 Wi-Fi，" +
                    "并且电视上的 DLNA / 媒体共享功能是开着的。"
            }
        }
    }

    /** 把当前这首歌推到设备上 */
    fun cast(device: DlnaDevice, track: Track?) {
        if (track == null) {
            error = "还没有在播放的歌曲，先放一首再投屏"
            return
        }
        viewModelScope.launch {
            error = null
            val s = withContext(Dispatchers.IO) { ensureServer() } ?: return@launch
            // 正常推歌是从头播，别留着上次的偏移
            s.setStartOffset(track.key, 0)
            val uri = s.mediaUri(track)
            val meta = s.didlFor(track)

            // 先自己确认手机这边的服务是通的。
            // 设备拿到地址后会先试着拉一下，拉不到就会拒绝播放，
            // 到时候光看设备的报错根本分不清是设备挑食还是手机够不着。
            val probe = withContext(Dispatchers.IO) {
                try {
                    LanHttp.request(uri, "GET", null, null, 5000, mapOf("Range" to "bytes=0-0"))
                } catch (e: Exception) {
                    null
                }
            }
            if (probe == null || (probe.code != 200 && probe.code != 206)) {
                error = "手机这边的投屏服务不通（${probe?.code ?: "连不上"}），" +
                    "设备够不着音频。请确认手机没被系统限制后台、Wi-Fi 正常。"
                return@launch
            }

            val ok = withContext(Dispatchers.IO) { client.push(device, uri, meta) }
            if (ok) {
                val now = System.currentTimeMillis()
                casting = device
                castPlaying = true
                castPositionMs = 0L
                castDurationMs = (track.durationSec * 1000).toLong()
                castTitle = track.title
                castArtist = track.artist
                castTrackStartedAt = now
                // 进度基准清零：这一首是从头开始的
                castBasePos = 0L
                castBaseAt = now
                startCastPolling()
            } else {
                // 把设备到底说了什么带出来，不然没法排查
                val why = client.lastError?.takeIf { it.isNotBlank() } ?: "设备没回应"
                error = "「${device.friendlyName}」播放失败：$why"
            }
        }
    }

    /** 停止投屏 */
    fun stopCasting() {
        val device = casting ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { client.stop(device) }
            casting = null
            castPlaying = false
            castPositionMs = 0L
            castTitle = ""
            castArtist = ""
            castBasePos = 0L
            castBaseAt = System.currentTimeMillis()
            castPollJob?.cancel()
            castPollJob = null
        }
    }

    // ---------------- 投屏时的遥控器 ----------------
    //
    // 投出去之后手机自己是暂停的，用户在手机上点播放/暂停/上一首/下一首，
    // 期望的都是**电视**那边有反应。所以这几个动作要转发给设备，
    // 而不是操作手机本地播放器。

    /** 手机上的播放 / 暂停 —— 转发给设备 */
    fun toggleCast() {
        val device = casting ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (castPlaying) client.pause(device) else client.play(device)
            }
            if (ok) {
                castPlaying = !castPlaying
                error = null
            } else {
                error = "「${device.friendlyName}」没回应控制指令：" +
                    (client.lastError ?: "设备无响应")
            }
        }
    }

    /** 拖动进度条 —— 投屏时转发给设备 */
    fun seekCast(positionMs: Long) {
        val device = casting ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { client.seek(device, positionMs) }
            if (ok) {
                // 乐观更新：先按用户拖到的地方显示，别等设备回报
                val now = System.currentTimeMillis()
                castPositionMs = positionMs
                // 基准也挪到新位置，不然下一轮轮询又会被旧位置带回去
                castBasePos = positionMs
                castBaseAt = now
                seekTarget = positionMs
                seekTargetAt = now
            } else {
                // 设备直接拒了 —— 换个带偏移的地址重新推，它只要从头播就行
                pushWithOffset(device, positionMs)
            }
        }
    }

    /**
     * 兜底：把歌**从头**重新推一遍，但地址里带上起始偏移。
     *
     * 不少电视/音箱的 DLNA 实现根本没实现 Seek 指令（发了也不动）。
     * 但「从头播这个 URL」是所有渲染器都支持的最基础能力，
     * 所以偏移放在我们自己的 HTTP 服务里，设备只管拉。
     */
    private fun pushWithOffset(device: DlnaDevice, positionMs: Long) {
        viewModelScope.launch {
            val s = ensureServer() ?: return@launch
            val track = appRef.player.currentTrack ?: return@launch
            // 偏移记在服务端，地址保持干净 —— 小爱音箱那类设备解析带 ? 的 URL 很粗
            s.setStartOffset(track.key, positionMs)
            val uri = s.mediaUri(track)
            val ok = withContext(Dispatchers.IO) { client.push(device, uri, s.didlFor(track, uri)) }
            if (ok) {
                val now = System.currentTimeMillis()
                castPositionMs = positionMs
                castPlaying = true
                // 基准挪到偏移处：这一首是「从偏移位置接着播」的
                castBasePos = positionMs
                castBaseAt = now
                seekTarget = -1
                castTrackStartedAt = now
                error = null
            } else {
                s.setStartOffset(track.key, 0)
                error = "「${device.friendlyName}」不支持拖动进度" +
                    "（它既不认 Seek 指令，也不肯重新拉流）"
            }
        }
    }

    /** 下一首：手机上换到下一首，再把新歌推给设备 */
    fun nextCast() {
        val device = casting ?: return
        val queue = appRef.player.queue
        val cur = appRef.player.currentTrack
        val idx = queue.indexOfFirst { it.key == cur?.key }
        val target = queue.getOrNull(idx + 1)
        if (target == null) {
            error = "已经是最后一首了"
            return
        }
        switchCastTrack(device, target)
    }

    /** 上一首 */
    fun previousCast() {
        val device = casting ?: return
        val queue = appRef.player.queue
        val cur = appRef.player.currentTrack
        val idx = queue.indexOfFirst { it.key == cur?.key }
        val target = queue.getOrNull(idx - 1)
        if (target == null) {
            error = "已经是第一首了"
            return
        }
        switchCastTrack(device, target)
    }

    /**
     * 换歌后重新推给设备。
     *
     * 设备那边只认「当前这首的地址」，所以切歌不是发 Next 指令，
     * 而是换一首再 SetAVTransportURI + Play 推一遍。
     */
    private fun switchCastTrack(device: DlnaDevice, track: Track) {
        viewModelScope.launch {
            // 手机上先切（队列、歌词这些才跟得上），但不让它出声
            appRef.player.jumpTo(track)
            appRef.player.controller?.pause()
            val s = ensureServer() ?: return@launch
            // 换歌是从头开始，别留着上次的偏移
            s.setStartOffset(track.key, 0)
            val ok = withContext(Dispatchers.IO) {
                client.push(device, s.mediaUri(track), s.didlFor(track))
            }
            if (ok) {
                val now = System.currentTimeMillis()
                castPlaying = true
                castPositionMs = 0L
                castDurationMs = (track.durationSec * 1000).toLong()
                castTitle = track.title
                castArtist = track.artist
                // 换歌后设备的进度还没清零（有的音箱 Stop 之后位置也不归零），
                // 基准必须归零，否则进度条会沿用上一首的进度。
                castBasePos = 0L
                castBaseAt = now
                castTrackStartedAt = now
                error = null
            } else {
                error = "切歌失败：" + (client.lastError ?: "设备没回应")
            }
        }
    }

    /**
     * 轮询设备的播放状态和进度，让进度条跟着电视走。
     *
     * 难点在于**设备报的位置不一定可信**：
     * - 有的音箱（小爱那类）压根不支持 GetPositionInfo，永远返回 0；
     * - 换歌之后设备那边报的还是上一首的位置（Stop 也不一定清零）。
     *
     * 所以这里不直接信设备报的数字，而是拿它跟**本地推算**比一比：
     * 差得离谱就当陈旧数据丢掉，继续用本地时钟走。
     * 这样不支持查进度的设备就自动退化成「本地计时」，进度条一样从 0 开始走。
     */
    private fun startCastPolling() {
        castPollJob?.cancel()
        castPollJob = viewModelScope.launch {
            // 有些音箱（小米那类）压根不支持 GetPositionInfo，永远返回 0。
            // 那就不能靠「位置有没有变」判断 seek 有没有生效 —— 会误判成没生效。
            var lastPos = -1L
            var sameCount = 0
            var staleCount = 0
            var positionSupported = true
            while (true) {
                val device = casting ?: break
                val now = System.currentTimeMillis()
                val pos = withContext(Dispatchers.IO) { client.positionMs(device) }
                val st = withContext(Dispatchers.IO) { client.transportState(device) }
                if (st != null) {
                    castPlaying = st.equals("PLAYING", ignoreCase = true)
                }

                // 本地推算出来的当前位置（基准 + 经过的时间）
                val drift = if (castPlaying) (now - castBaseAt).coerceAtLeast(0L) else 0L
                val local = castBasePos + drift

                // 刚拖过进度：两秒内别用设备的位置把它拉回去，等它到底动没动
                if (seekTarget >= 0) {
                    if (now - seekTargetAt < 2000) {
                        // 基准已经挪到新位置了，这两秒让它照常往前走，别冻住
                        castPositionMs = local
                        delay(300)
                        continue
                    }
                    val target = seekTarget
                    seekTarget = -1
                    // 设备不支持查进度的话，就没法验证它到底动没动 ——
                    // 这类设备多半也不认 Seek，直接用「带偏移重新推」这招更靠谱。
                    val moved = positionSupported && pos != null &&
                        kotlin.math.abs(pos - target) < 3000
                    if (moved) {
                        castBasePos = pos!!
                        castBaseAt = now
                    } else {
                        pushWithOffset(device, target)
                        delay(600)
                        continue
                    }
                }

                // 判断设备到底支不支持查进度：
                // 一直在播却老报同一个位置 = 它压根不更新位置
                if (pos != null && castPlaying) {
                    if (pos == lastPos) sameCount++ else sameCount = 0
                    if (sameCount >= 4) positionSupported = false
                    lastPos = pos
                }

                // 刚推完歌的这几秒，设备报的位置八成是上一首的，不能用
                val justPushed = castTrackStartedAt > 0 && now - castTrackStartedAt < 5000
                // 设备的位置跟本地推算差太远 —— 当成陈旧数据丢掉
                val trustworthy = pos != null && !justPushed &&
                    kotlin.math.abs(pos - local) < 15000

                if (trustworthy) {
                    staleCount = 0
                    castBasePos = pos!!
                    castBaseAt = now
                    castPositionMs = castBasePos
                } else {
                    if (pos != null && !justPushed) staleCount++
                    // 一直对不上，基本可以确定它不支持查进度
                    if (staleCount >= 6) positionSupported = false
                    castPositionMs = local
                }
                delay(600)
            }
        }
    }

    /** 开关「让电视来播」 */
    fun toggleServer() {
        if (serverRunning) {
            server?.stop()
            server = null
            serverRunning = false
            serverAddress = ""
            remotePlaying = false
            ssdpAvailable = true
            return
        }
        viewModelScope.launch {
            error = null
            withContext(Dispatchers.IO) { ensureServer() }
        }
    }

    fun clearError() {
        error = null
    }

    /**
     * 拉起手机这边的 HTTP 服务。
     *
     * 电视要来这里拉音频流（网盘的地址它自己拉不动：坚果云要 Basic 鉴权、OneDrive 要 Bearer），
     * 所以不管哪种用法这个服务都得开着。
     */
    private fun ensureServer(): DlnaServer? {
        server?.let { if (it.isRunning) return it }
        return try {
            val s = DlnaServer(
                context = appRef,
                prefs = appRef.prefs,
                http = appRef.repository.http,
                tracksProvider = { appRef.repository.cachedTracks() }
            )
            s.onStateChanged = { state -> remotePlaying = state == "PLAYING" }
            if (!s.start()) {
                error = "投屏服务起不来，请确认手机连着 Wi-Fi"
                return null
            }
            server = s
            serverRunning = true
            serverAddress = "http://${s.ip}:${s.port}"
            ssdpAvailable = s.ssdpAvailable
            s
        } catch (e: Exception) {
            error = "投屏服务起不来：${e.message ?: "未知原因"}"
            null
        }
    }

    override fun onCleared() {
        server?.stop()
        server = null
        super.onCleared()
    }
}
