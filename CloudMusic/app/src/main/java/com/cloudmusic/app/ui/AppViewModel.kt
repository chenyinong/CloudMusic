package com.cloudmusic.app.ui

import android.app.Application
import android.net.Uri
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudmusic.app.App
import com.cloudmusic.app.data.AudioTagsReader
import com.cloudmusic.app.data.AuthFailedException
import com.cloudmusic.app.data.CloudProvider
import com.cloudmusic.app.data.DavCredentials
import com.cloudmusic.app.data.DavProvider
import com.cloudmusic.app.data.DavQuota
import com.cloudmusic.app.data.DeleteResult
import com.cloudmusic.app.data.Lyrics
import com.cloudmusic.app.data.LyricsCandidate
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.WebDavException
import com.cloudmusic.app.data.cloud.CLOUD_TRACK_PREFIX
import com.cloudmusic.app.data.cloud.CloudPlaylistRef
import com.cloudmusic.app.data.cloud.CloudResolver
import com.cloudmusic.app.data.cloud.CloudTrack
import com.cloudmusic.app.data.onedrive.DeviceCodeInfo
import com.cloudmusic.app.data.onedrive.DriveQuota
import com.cloudmusic.app.data.onedrive.OneDriveAuth
import com.cloudmusic.app.data.onedrive.OneDriveAuthException
import com.cloudmusic.app.data.onedrive.Pkce
import com.cloudmusic.app.data.onedrive.TokenOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class Screen {
    LOGIN, LIBRARY, PLAYER, SETTINGS, IMPORT, PLAYLIST_IMPORT, PLAYLIST_LIST, PLAYLIST_DETAIL,
    QUARK_LOGIN, QUARK_DIAG, CLOUD_LOGIN, CLOUD_PLAYLIST_DETAIL
}

/** 夸克登录态实时校验的进度。 */
enum class QuarkCheckState { IDLE, CHECKING, VALID, INVALID }

/** 歌词面板的状态。 */
data class LyricsUiState(
    val loading: Boolean = false,
    val lyrics: Lyrics? = null,
    val source: String = "",
    val error: String? = null,
)

/** 手动搜索歌词弹窗的状态：先列候选，用户点一首再取词。 */
data class LyricsSearchUiState(
    val searching: Boolean = false,
    val applying: Boolean = false,
    val candidates: List<LyricsCandidate> = emptyList(),
    val error: String? = null,
    /** 选中并加载成功，UI 收到后关闭弹窗 */
    val done: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef: App = app as App
    private val prefs = appRef.prefs
    private val repo = appRef.repository

    var screen by mutableStateOf(Screen.LIBRARY)
    /** 进入「导入歌单」之前的页面，返回时跳回去（搜歌页或歌单列表页） */
    var importReturnScreen: Screen = Screen.LIBRARY
    var query by mutableStateOf("")

    /** 主题：是否跟随系统深色模式（关闭 = 固定浅色） */
    private var _themeFollowSystem by mutableStateOf(prefs.themeFollowSystem)
    var themeFollowSystem: Boolean
        get() = _themeFollowSystem
        set(value) {
            _themeFollowSystem = value
            prefs.themeFollowSystem = value
        }

    /** 当前打开的歌单（歌单详情页用）。 */
    var playlistKey by mutableStateOf("")

    /** 当前打开的云端歌单 id（云端歌单详情页用）。 */
    var cloudPlaylistId by mutableStateOf(0L)

    /** 云端登录页返回时回哪一页（从「我的」页进的回我的，从设置进的回设置）。 */
    var cloudLoginReturn by mutableStateOf(Screen.SETTINGS)

    /** 曲库筛选：null = 全部网盘 */
    var providerFilter by mutableStateOf<String?>(null)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    var lyricsState by mutableStateOf(LyricsUiState())
        private set

    var lyricsSearchState by mutableStateOf(LyricsSearchUiState())
        private set

    private var lyricsSearchJob: Job? = null

    /** 当前歌曲的歌词时间偏移：正数 = 歌词提前，负数 = 歌词延后。按曲目持久化。 */
    var lyricsOffsetMs by mutableStateOf(0L)
        private set

    private var lyricsJob: Job? = null

    /** 正在加载歌词的曲目，避免同一首重复发请求 */
    private var lyricsLoadingId: String? = null

    /** 正在预取的曲目 */
    private val prefetchingIds = mutableSetOf<String>()

    /** 每首歌的歌词结果，避免来回切歌时反复请求 */
    private val lyricsCache = mutableMapOf<String, LyricsUiState>()

    var coverUrl by mutableStateOf<String?>(null)
        private set

    /** 当前歌曲封面提出来的主题色；封面还没查到（或黑白封面）时为 null，回落歌名调色板 */
    var coverThemeColor by mutableStateOf<Color?>(null)
        private set

    private var coverJob: Job? = null

    /** 正在查封面的曲目，避免同一首重复发请求 */
    private var coverLoadingId: String? = null

    /** 每首歌的封面结果（空串 = 查过没有），负缓存只放内存，下次启动还能重试 */
    private val coverCache = mutableMapOf<String, String>()

    /**
     * 加载封面：切歌时跟着歌词一起调。
     * 命中就秒换图，没查到也别让占位图闪来闪去。
     */
    fun loadCover(track: Track?) {
        val id = track?.key
        if (id == null) {
            coverJob?.cancel()
            coverLoadingId = null
            coverUrl = null
            coverThemeColor = null
            return
        }
        // 已经拿到过（或确认没有），直接用
        coverCache[id]?.let {
            coverJob?.cancel()
            coverLoadingId = null
            val url = it.ifBlank { null }
            coverUrl = url
            // 别挂着上一首的颜色，先回落，封面色马上从位图缓存里取回来
            coverThemeColor = null
            updateCoverTheme(track, url)
            return
        }
        // 正在查同一首，等着就行
        if (coverLoadingId == id && coverJob?.isActive == true) return

        coverJob?.cancel()
        coverLoadingId = id
        // 新歌还没查到封面：先清掉上一首的，别让旧封面挂在新歌上
        coverUrl = null
        coverThemeColor = null
        coverJob = viewModelScope.launch {
            val url = appRef.coverRepository.load(track)
            coverCache[id] = url ?: ""
            // 查完还在听这首才上图，不然刚切走的歌把新歌的封面顶掉了
            if (player.currentTrack?.key == id) {
                coverUrl = url
                updateCoverTheme(track, url)
            }
            coverLoadingId = null
        }
    }

    /** 从封面位图提取主题色（命中内存缓存就是纯计算），失败保持回落色。 */
    private fun updateCoverTheme(track: Track, url: String?) {
        viewModelScope.launch {
            val colors = withContext(Dispatchers.IO) {
                url?.let { u ->
                    appRef.coverRepository.artwork(u)?.let { extractCoverPalette(it) }
                }
            }
            // 算完还在听这首才生效，不然切走的歌把颜色顶回来
            if (player.currentTrack?.key == track.key) coverThemeColor = colors
        }
    }

    /** 手动填一个封面图片 URL。 */
    fun setCoverUrl(track: Track, url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) return
        appRef.coverRepository.setManualCover(track.key, trimmed)
        applyManualCover(track, trimmed)
    }

    /** 手动从相册选一张图：复制进 App 私有目录再入库，图库里删了也不影响。 */
    fun setCoverImage(track: Track, uri: Uri) {
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) {
                val bytes = try {
                    appRef.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                } ?: return@withContext null
                appRef.coverRepository.setManualCoverFromBytes(track.key, bytes)
            } ?: return@launch
            applyManualCover(track, url)
        }
    }

    /** 重新自动查找封面：清掉所有缓存再走一遍来源链。 */
    fun refreshCover(track: Track) {
        appRef.coverRepository.resetCover(track.key)
        coverCache.remove(track.key)
        coverJob?.cancel()
        coverLoadingId = null
        if (player.currentTrack?.key == track.key) coverUrl = null
        loadCover(track)
    }

    /** 单首：优先用内嵌标签纠正，读不到就按曲库歌手频次交换，顺带让封面重查。 */
    fun correctTrackTags(track: Track) {
        viewModelScope.launch {
            val fixed = withContext(Dispatchers.IO) {
                val freq = appRef.repository.cachedTracks()
                    .map { it.artist.trim() }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }
                    .eachCount()
                // OneDrive 的 Range 读取不稳，别在这儿白等超时
                val byTags = if (track.provider == ProviderKind.WEBDAV.id) {
                    val head = appRef.coverRepository.audioHead(track, 256 * 1024)
                    val tags = head?.let { AudioTagsReader.read(it) }
                    tags?.let { AudioTagsReader.correct(track, it) }
                } else null
                byTags ?: AudioTagsReader.swapIfReversed(track, freq)
            }
            android.util.Log.d("CMDIAG", "correctTrackTags fixed=$fixed file=${track.fileName}")
            if (fixed == null) {
                _status.value = "《${track.title}》没能识别出歌名/歌手顺序，保持原样"
                return@launch
            }
            withContext(Dispatchers.IO) {
                appRef.repository.upsertTrack(fixed)
                appRef.coverRepository.resetCover(track.key)
            }
            refreshTracks()
            _status.value = "已修正《${fixed.title}》· ${fixed.artist}，封面会重新查找"
        }
    }

    private fun applyManualCover(track: Track, url: String) {
        coverCache[track.key] = url
        if (player.currentTrack?.key == track.key) coverUrl = url
    }

    var loggedIn by mutableStateOf(false)
        private set

    /** 当前正在使用的网盘（最近一次登录成功的那个），断开操作以它为准 */
    var providerKind by mutableStateOf(ProviderKind.of(prefs.providerKind))
        private set

    /**
     * 登录页当前展示哪个网盘的登录表单。
     * 和 [providerKind] 分开：切换登录目标不应该改「当前在用哪个网盘」，
     * 否则设置页的「断开」按钮文案会和真实连接状态对不上。
     */
    var loginTarget by mutableStateOf(ProviderKind.of(prefs.providerKind))
        private set

    /** OneDrive 授权流程的状态 */
    var oneDriveCode by mutableStateOf<DeviceCodeInfo?>(null)
        private set

    var oneDriveStatus by mutableStateOf("")
        private set

    private var oneDriveJob: Job? = null

    /** 播放器是全局单例（挂在 App 上），任何页面都能起播。 */
    val player: PlayerConnection get() = appRef.player

    val serverDefault: String get() = prefs.serverUrl
    val accountDefault: String get() = prefs.account
    val oneDriveClientIdDefault: String get() = prefs.oneDriveClientId
    val oneDriveAccount: String get() = prefs.oneDriveAccount
    val oneDriveConnected: Boolean get() = prefs.oneDriveConnected

    /** 申请的权限范围模式（default / readonly / readwrite） */
    var oneDriveScopeMode by mutableStateOf(prefs.oneDriveScopeMode)
        private set

    /**
     * OneDrive 的空间配额。
     *
     * null = 还没拉到（或者没连 OneDrive）。
     * 设置页用它显示「已用 xx / 剩余 yy」，免得开着网页版才知道快满了。
     */
    var oneDriveQuota by mutableStateOf<DriveQuota?>(null)
        private set

    /** 正在拉配额（界面上转圈用） */
    var oneDriveQuotaLoading by mutableStateOf(false)
        private set

    /** 拉一次空间配额。设置页里点「刷新」也走这里。 */
    fun refreshOneDriveQuota() {
        if (prefs.oneDriveAccessToken.isBlank()) return
        oneDriveQuotaLoading = true
        viewModelScope.launch {
            val q = runCatching { appRef.oneDriveGraph.quota() }.getOrNull()
            if (q != null) oneDriveQuota = q
            oneDriveQuotaLoading = false
        }
    }

    /**
     * 坚果云（WebDAV）的空间用量。
     *
     * 走 WebDAV 的 `quota-available-bytes` / `quota-used-bytes` 属性，
     * 别的标准 WebDAV 服务器（Nextcloud、群晖）也支持。
     *
     * 坚果云自己**不支持**这两个属性（PROPFIND 一律回 404），
     * 这时退回到 [davUsedBytes]（扫描实测）+ [davCapacityBytes]（用户选的套餐）。
     */
    var davQuota by mutableStateOf<DavQuota?>(null)
        private set

    /** 扫描统计到的已用空间（字节）。坚果云没有配额接口，只能这么算。 */
    var davUsedBytes by mutableStateOf(prefs.davUsedBytes)
        private set

    /** 用户选的套餐容量（字节），0 = 未知 */
    var davCapacityBytes by mutableStateOf(prefs.davCapacityBytes)
        private set

    /** 设置坚果云套餐容量 */
    fun setDavCapacity(bytes: Long) {
        prefs.davCapacityBytes = bytes
        davCapacityBytes = bytes
    }

    /** 正在查坚果云的空间 */
    var davQuotaLoading by mutableStateOf(false)
        private set

    /** 查一次坚果云的空间用量。 */
    fun refreshDavQuota() {
        if (!prefs.hasCredentials) return
        davQuotaLoading = true
        viewModelScope.launch {
            val q = runCatching { repo.client().quota() }.getOrNull()
            if (q != null) davQuota = q
            davQuotaLoading = false
        }
    }

    // ---------------- 夸克网盘（下载链路的中转站） ----------------

    /**
     * 夸克是否已登录。
     *
     * 用可观察的 State 而不是普通 `get()` —— 否则点「退出登录」后
     * `clearQuark()` 确实清了 SharedPreferences，但界面不重组，
     * 看着就像「没生效」。重进设置页才刷新。
     */
    var quarkLoggedIn by mutableStateOf(prefs.quarkLoggedIn)
        private set

    /** 夸克登录态是不是该换了（cookie 一般一个多月就失效） */
    val quarkCookieStale: Boolean get() = prefs.quarkCookieStale

    /** 已登录天数，设置页展示有效期用 */
    val quarkLoginDays: Int
        get() {
            if (!quarkLoggedIn) return 0
            val at = prefs.quarkLoginAt
            if (at <= 0L) return 0
            return ((System.currentTimeMillis() - at) / (24 * 3600 * 1000L)).toInt()
        }

    var quarkAccount by mutableStateOf(prefs.quarkAccount)
        private set

    /** 实时校验夸克登录态的进度与上次校验时刻 */
    // 结果持久化：重启后进设置页哪怕撞上冷却，也能立刻显示上次的「已失效」，不会一片空白
    var quarkCheckState by mutableStateOf(
        if (prefs.quarkLoggedIn && prefs.quarkCheckAt > 0L) {
            if (prefs.quarkCheckOk) QuarkCheckState.VALID else QuarkCheckState.INVALID
        } else {
            QuarkCheckState.IDLE
        }
    )
        private set

    var quarkCheckedAt by mutableStateOf(prefs.quarkCheckAt)
        private set

    /** 上次校验失败的原因（网络异常 / 夸克返回的 code），给设置页排查用 */
    var quarkCheckReason by mutableStateOf("")
        private set

    /**
     * 校验夸克登录态：真的拿 cookie 请求一次才知道失效没有。
     * 每次进设置页、点「重新验证」都实打实请求一次 —— 之前带冷却，
     * 刚过期的 cookie 会因为在冷却期内被跳过，继续显示「有效」。
     */
    fun checkQuarkLogin() {
        if (!quarkLoggedIn) return
        if (quarkCheckState == QuarkCheckState.CHECKING) return

        quarkCheckState = QuarkCheckState.CHECKING
        viewModelScope.launch {
            val check = appRef.quarkClient.checkLogin()
            prefs.quarkCheckAt = System.currentTimeMillis()
            prefs.quarkCheckOk = check.ok
            quarkCheckedAt = prefs.quarkCheckAt
            quarkCheckReason = check.reason
            // 拿着失效的 cookie 也可能拉回昵称缓存，所以只有验证通过才刷新昵称
            if (check.ok && check.nickname.isNotBlank()) {
                prefs.quarkAccount = check.nickname
                quarkAccount = check.nickname
            }
            quarkCheckState = if (check.ok) QuarkCheckState.VALID else QuarkCheckState.INVALID
        }
    }

    /** 保存夸克登录态。cookie 来自 WebView 抓取。 */
    fun saveQuarkCookie(cookie: String) {
        prefs.saveQuark(cookie, "")
        quarkLoggedIn = prefs.quarkLoggedIn
        quarkAccount = prefs.quarkAccount
        // 新 cookie 立刻重新校验，别沿用上一份的「已失效」
        prefs.quarkCheckAt = 0L
        prefs.quarkCheckOk = false
        quarkCheckedAt = 0L
        quarkCheckReason = ""
        quarkCheckState = QuarkCheckState.IDLE
        viewModelScope.launch {
            // 顺便把账号昵称拉回来，界面上好看点
            val name = appRef.quarkClient.accountName()
            if (name.isNotBlank()) {
                prefs.quarkAccount = name
                quarkAccount = name
            }
        }
        // 扫码换来的新 cookie，顺手同步到云端 —— 网页版会自动取用，
        // 换设备时就不用再扫一遍了。
        if (prefs.cloudLoggedIn) {
            val cookie = prefs.quarkCookie
            viewModelScope.launch {
                runCatching { appRef.cloudSync.pushQuarkCookie(cookie) }
            }
        }
    }

    fun clearQuark() {
        prefs.clearQuark()
        quarkLoggedIn = false
        quarkAccount = ""
        quarkCheckState = QuarkCheckState.IDLE
        quarkCheckedAt = 0L
        quarkCheckReason = ""
        // 退出即把 WebView 里的夸克登录态也清掉（否则下次进登录页还认得老账号，
        // 会立刻自动重新登录回 App，看着就像「没退出干净」）。
        val cm = CookieManager.getInstance()
        @Suppress("DEPRECATION")
        cm.removeAllCookies(android.webkit.ValueCallback<Boolean> { })
        cm.flush()
    }

    // ---------------- 云端账号（网页版云音乐，Cloudflare Workers） ----------------

    /** 云端服务器地址。设置页里改。 */
    var cloudServer by mutableStateOf(prefs.cloudServerUrl)
        private set

    var cloudLoggedIn by mutableStateOf(prefs.cloudLoggedIn)
        private set

    var cloudUser by mutableStateOf(prefs.cloudUser)
        private set

    /** 云端操作进行中（登录 / 同步 / 上传）。 */
    var cloudBusy by mutableStateOf(false)
        private set

    /** 云端相关的状态提示，成功和失败都挂这儿。 */
    var cloudMessage by mutableStateOf("")
        private set

    /**
     * 云端操作的反馈。
     *
     * 除了设置页，曲库页的提示行也显示一下 —— 用户在「我的」页点收藏时
     * 不会待在设置页，光写在这儿等于没反馈。
     */
    private fun cloudSay(msg: String) {
        cloudMessage = msg
        _status.value = msg
    }

    private val _cloudFavorites = MutableStateFlow<List<CloudTrack>>(emptyList())
    val cloudFavorites = _cloudFavorites.asStateFlow()

    /** 已经收藏的 key（判断网盘歌曲那颗心亮不亮）。 */
    private val _cloudFavoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    val cloudFavoriteKeys = _cloudFavoriteKeys.asStateFlow()

    private val _cloudPlaylists = MutableStateFlow<List<CloudPlaylistRef>>(emptyList())
    val cloudPlaylists = _cloudPlaylists.asStateFlow()

    /** 云端歌曲的在线混播（我的页、云端歌单详情页共用，挂在 App 上）。 */
    val cloudPlayback: CloudPlaybackController get() = appRef.cloudPlayback

    /** 把本地缓存的云端内容读进界面。 */
    private fun refreshCloudCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val favs = appRef.cloudStore.favorites()
            _cloudFavorites.value = favs
            _cloudFavoriteKeys.value = favs.map { it.key }.toSet()
            _cloudPlaylists.value = appRef.cloudStore.playlists()
        }
    }

    /**
     * 登录云端账号。
     *
     * 登录成功立刻拉一次全量：收藏、歌单都同步下来，
     * 顺便看看云端有没有存夸克 Cookie（本机没登录的话直接恢复）。
     */
    fun cloudLogin(server: String, username: String, password: String) {
        if (cloudBusy) return
        cloudBusy = true
        _error.value = null
        cloudMessage = "正在连接云端…"
        viewModelScope.launch {
            try {
                prefs.cloudServerUrl = server
                val res = appRef.cloudClient.login(username, password)
                prefs.saveCloudLogin(res.token, res.username)
                cloudLoggedIn = true
                cloudUser = res.username
                cloudServer = prefs.cloudServerUrl
                val sync = appRef.cloudSync.syncAll()
                if (sync.quarkRestored) {
                    quarkLoggedIn = prefs.quarkLoggedIn
                    quarkAccount = prefs.quarkAccount
                }
                refreshCloudCache()
                cloudSay(sync.message.ifBlank { "已登录：${res.username}" })
                // 登录完直接落在云端这一档，别让人再点一次胶囊
                providerFilter = FILTER_CLOUD
                screen = Screen.LIBRARY
            } catch (e: Exception) {
                _error.value = e.message ?: "云端登录失败"
                cloudMessage = ""
            } finally {
                cloudBusy = false
            }
        }
    }

    /** 退出云端账号。本地缓存也清掉，免得看到别人的歌。 */
    fun cloudLogout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { appRef.cloudStore.clearAll() }
            prefs.clearCloudLogin()
            cloudLoggedIn = false
            cloudUser = ""
            // 退登了还停在「云端」这一档就只剩一片空白，退回全部
            if (providerFilter == FILTER_CLOUD) providerFilter = null
            cloudSay("已退出云端账号")
            refreshCloudCache()
        }
    }

    /** 去登录云端账号（「我的」页的云端胶囊、设置页都走这儿）。 */
    fun openCloudLogin() {
        cloudLoginReturn = Screen.LIBRARY
        screen = Screen.CLOUD_LOGIN
    }

    /**
     * 只拉一次云端收藏（进「我的」页时调）。
     *
     * 比全量同步轻 —— 歌单不动，只更新收藏。
     * 返回 Job，调用方（比如下拉刷新）可以等它做完。
     */
    fun refreshCloudFavorites(): Job = viewModelScope.launch {
        if (!prefs.cloudLoggedIn) return@launch
        runCatching { appRef.cloudSync.pullFavorites() }
            .onSuccess { refreshCloudCache() }
    }

    /** 只拉一次云端歌单列表（进「歌单」页时调）。返回 Job，调用方可以等它做完。 */
    fun refreshCloudPlaylists(): Job = viewModelScope.launch {
        if (!prefs.cloudLoggedIn) return@launch
        runCatching { appRef.cloudSync.pullPlaylists() }
            .onSuccess { refreshCloudCache() }
    }

    /**
     * 「我的」页下拉刷新：重新扫一遍网盘，再拉一次云端收藏。
     *
     * 跟 [rescan] 的两处不同：
     * - 不跳登录页（没登录网盘就只拉云端，下拉手势里跳页面太突兀）；
     * - 返回 Job，界面可以等它做完再收起下拉指示器。
     */
    fun refreshLibrary(): Job = viewModelScope.launch {
        if (loggedIn) {
            _busy.value = true
            _status.value = "正在扫描音乐库…"
            try {
                scanAllProviders()
            } catch (e: Exception) {
                _status.value = friendlyError(e)
            } finally {
                _busy.value = false
            }
        }
        refreshCloudFavorites().join()
    }

    /** 手动同步一次。 */
    fun cloudSyncNow() {        if (cloudBusy) return
        if (!prefs.cloudLoggedIn) {
            _error.value = "还没登录云端账号"
            return
        }
        cloudBusy = true
        cloudMessage = "正在同步…"
        viewModelScope.launch {
            try {
                val sync = appRef.cloudSync.syncAll()
                if (sync.quarkRestored) {
                    quarkLoggedIn = prefs.quarkLoggedIn
                    quarkAccount = prefs.quarkAccount
                }
                refreshCloudCache()
                cloudSay(sync.message)
            } catch (e: Exception) {
                _error.value = e.message ?: "同步失败"
                cloudMessage = ""
            } finally {
                cloudBusy = false
            }
        }
    }

    /**
     * 一首歌在云端收藏里的 key。
     *
     * 网盘歌曲用曲库 key（就是「网盘类型 + 网盘内 id」，天然不会跟网页版的
     * `netease:123` / `quark:xxx` 撞上）；云端歌曲多一层 `cloud:` 前缀，
     * 去掉前缀才是它在云端的收藏 key。
     */
    private fun cloudKeyOf(track: Track): String =
        if (track.key.startsWith(CLOUD_TRACK_PREFIX)) track.key.removePrefix(CLOUD_TRACK_PREFIX)
        else track.key

    /** 网盘歌曲 → 云端收藏条目。 */
    private fun cloudTrackOf(track: Track): CloudTrack = CloudTrack(
        key = cloudKeyOf(track),
        title = track.title,
        artist = track.artist,
        album = track.album,
        durationSec = track.durationSec.toInt(),
        platform = track.provider,
        platformId = "",
        coverUrl = "",
        quarkId = "",
    )

    /** 这首歌是不是已经收藏到云端了。 */
    fun isCloudFavorite(track: Track): Boolean = cloudKeyOf(track) in _cloudFavoriteKeys.value

    /**
     * 收藏 / 取消收藏一首歌（同步到云端）。
     *
     * 网盘歌曲和云端歌曲都走这儿 —— 云端歌曲的原始条目还在本地缓存里，
     * 重新收藏时用原来那份，别把 platform / platformId 弄丢了。
     */
    fun toggleCloudFavorite(track: Track) {
        if (!prefs.cloudLoggedIn) {
            _error.value = "还没登录云端账号，点「我的」页的云端胶囊登录一下"
            return
        }
        val key = cloudKeyOf(track)
        val already = key in _cloudFavoriteKeys.value
        viewModelScope.launch {
            try {
                if (already) {
                    appRef.cloudSync.removeFavorite(key)
                    cloudSay("已取消收藏《${track.title}》")
                } else {
                    val original = _cloudFavorites.value.firstOrNull { it.key == key }
                    appRef.cloudSync.pushFavorite(original ?: cloudTrackOf(track))
                    cloudSay("已收藏《${track.title}》，网页版同步可见")
                }
                refreshCloudCache()
            } catch (e: Exception) {
                _error.value = e.message ?: "操作失败"
            }
        }
    }

    /** 取消收藏一首云端收藏。 */
    fun removeCloudFavorite(t: CloudTrack) {
        viewModelScope.launch {
            try {
                appRef.cloudSync.removeFavorite(t.key)
                cloudSay("已取消收藏《${t.title}》")
                refreshCloudCache()
            } catch (e: Exception) {
                _error.value = e.message ?: "操作失败"
            }
        }
    }

    /**
     * 播一首云端收藏。
     *
     * 直链是本机解析出来的（复用歌单那套音源），不依赖云端在线。
     */
    /** 播放一整队云端歌（从第一首开始）。 */
    fun playCloudQueue(queue: List<CloudTrack>) {
        if (queue.isEmpty()) return
        playCloudTrack(queue.first(), queue)
    }

    fun playCloudTrack(t: CloudTrack, queue: List<CloudTrack>) {
        val items = if (queue.isEmpty()) listOf(t) else queue
        val idx = items.indexOfFirst { it.key == t.key }.coerceAtLeast(0)
        viewModelScope.launch {
            _status.value = "正在找《${t.title}》的可播直链…"
            val err = cloudPlayback.play(items, idx)
            _status.value = ""
            if (err != null) _error.value = err
        }
    }

    /**
     * 把本机的一张歌单传到云端。
     *
     * 曲目字段跟网页版的 Track 是同一套，两端能对着看。
     */
    fun uploadLocalPlaylist(playlistKey: String) {
        if (!prefs.cloudLoggedIn) {
            _error.value = "还没登录云端账号，去设置里登录一下"
            return
        }
        if (cloudBusy) return
        cloudBusy = true
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { appRef.playlistStore.playlist(playlistKey) }
                if (saved == null) {
                    _error.value = "找不到这张歌单"
                    return@launch
                }
                val tracks = withContext(Dispatchers.IO) { appRef.playlistStore.tracks(playlistKey) }
                _status.value = "正在上传《${saved.name}》（${tracks.size} 首）到云端…"
                val cloudTracks = appRef.cloudSync.toCloudTracks(saved, tracks)

                // 已经同步过的那张：先把云端旧的删掉再建新的。
                //
                // 不这么做的话，云端会堆一堆同名的歌单 —— 每点一次「上传」多一张。
                val oldCloudId = saved.cloudId
                if (oldCloudId > 0) {
                    runCatching { appRef.cloudSync.deletePlaylist(oldCloudId) }
                }

                val cloudId = appRef.cloudSync.pushPlaylist(
                    name = saved.name,
                    platform = appRef.cloudSync.cloudPlatformOf(saved.platform),
                    tracks = cloudTracks,
                )
                // 记下两边的对应关系 —— 以后删哪边，另一边跟着删
                withContext(Dispatchers.IO) { appRef.playlistStore.setCloudId(playlistKey, cloudId) }
                refreshCloudCache()
                cloudSay("《${saved.name}》已上传到云端（${cloudTracks.size} 首）")
            } catch (e: Exception) {
                _error.value = e.message ?: "上传失败"
            } finally {
                _status.value = ""
                cloudBusy = false
            }
        }
    }

    /** 删掉一张云端歌单。 */
    fun deleteCloudPlaylist(id: Long) {
        viewModelScope.launch {
            try {
                appRef.cloudSync.deletePlaylist(id)
                // 删完再以云端为准拉一次。
                //
                // 只清本地缓存是不够的 —— 进页面时那次「拉歌单列表」可能还在路上，
                // 它要是晚一步到，就会把刚删掉的歌单又写回本地，看着就像没删掉。
                runCatching { appRef.cloudSync.pullPlaylists() }
                refreshCloudCache()
                cloudSay("云端歌单已删除")
            } catch (e: Exception) {
                _error.value = e.message ?: "删除失败"
            }
        }
    }

    /** 本机歌单列表有变动时 +1，歌单页监听它来刷新。 */
    var playlistListVersion by mutableStateOf(0)
        private set

    /** 本机歌单删了或者改了，通知一下歌单页。 */
    fun playlistListChanged() {
        playlistListVersion++
    }


    /** 把本机的夸克 Cookie 传到云端（网页版会自动取到，换设备免扫码）。 */
    fun pushQuarkToCloud() {
        if (!prefs.cloudLoggedIn) {
            _error.value = "还没登录云端账号"
            return
        }
        if (!prefs.quarkLoggedIn) {
            _error.value = "本机还没登录夸克，登录后才会同步"
            return
        }
        viewModelScope.launch {
            try {
                appRef.cloudSync.pushQuarkCookie(prefs.quarkCookie)
                cloudSay("夸克登录态已同步到云端，网页版会自动取用")
            } catch (e: Exception) {
                _error.value = e.message ?: "同步失败"
            }
        }
    }

    fun changeOneDriveScopeMode(mode: String) {
        oneDriveScopeMode = mode
        prefs.oneDriveScopeMode = mode
    }

    /** 账号类型：personal（个人账号）/ work（工作或学校账号） */
    var oneDriveAccountKind by mutableStateOf(prefs.oneDriveAccountKind)
        private set

    fun changeOneDriveAccountKind(kind: String) {
        oneDriveAccountKind = kind
        prefs.oneDriveAccountKind = kind
    }

    // ---------------- 网页登录（授权码 + PKCE） ----------------

    /** 非 null 时弹出应用内浏览器。值是授权页地址。 */
    var oneDriveWebLoginUrl by mutableStateOf<String?>(null)
        private set

    private var oneDrivePkce: Pkce? = null
    private var oneDriveWebClientId: String = ""
    /** 这次网页登录实际用的回调地址（换 token 时要原样带上） */
    private var oneDriveWebRedirect: String = OneDriveAuth.REDIRECT_URI

    /** 给界面用：WebView 要按这个地址判断「登录完成了」 */
    val oneDriveWebRedirectUri: String get() = oneDriveWebRedirect
    /** 回调地址已经自动换过一次了吗（先 nativeclient，再 msal://auth） */
    private var oneDriveWebRetried: Boolean = false

    /**
     * 打开应用内浏览器登录。
     *
     * 这条路没有 15 分钟倒计时，用户慢慢登就行 —— 设备码流程就是死在那个倒计时上。
     */
    fun startOneDriveWebLogin(clientId: String) {
        val appId = clientId.trim().ifBlank { OneDriveAuth.BUILTIN_CLIENT_ID }
        oneDriveWebClientId = appId
        oneDriveWebRedirect = OneDriveAuth.REDIRECT_URI
        oneDriveWebRetried = false
        // 先落盘：万一这次没走完，下次进来输入框里还能看到自己填的 ID，不用重抄
        prefs.oneDriveClientId = appId
        openWebLogin()
    }

    private fun openWebLogin() {
        val pkce = OneDriveAuth.newPkce()
        val authority = appRef.oneDriveAuth.authorityFor(oneDriveAccountKind)
        oneDrivePkce = pkce
        _error.value = null
        oneDriveStatus = "请在下面的网页里登录 Microsoft 帐户"
        oneDriveWebLoginUrl = OneDriveAuth.authCodeUrl(
            clientId = oneDriveWebClientId,
            authority = authority,
            scopeMode = oneDriveScopeMode,
            pkce = pkce,
            redirectUri = oneDriveWebRedirect,
        )
    }

    fun cancelOneDriveWebLogin() {
        oneDriveWebLoginUrl = null
        oneDrivePkce = null
    }

    /**
     * 网页那边报了错。
     *
     * 最常见的一类是「重定向地址没登记在这个应用里」。自建应用在门户里加
     * 「移动和桌面应用程序」平台时，微软给的推荐地址是 `msal{clientId}://auth`，
     * 很多人只勾了那条 —— 这时自动换过去再试一次，比让用户回去改门户快得多。
     */
    fun onOneDriveWebLoginError(message: String) {
        oneDriveWebLoginUrl = null
        oneDrivePkce = null
        val redirectProblem = message.contains("redirect_uri", ignoreCase = true) ||
            message.contains("redirect URI", ignoreCase = true) ||
            message.contains("aadsts50011", ignoreCase = true)
        if (redirectProblem && !oneDriveWebRetried) {
            oneDriveWebRetried = true
            oneDriveWebRedirect = OneDriveAuth.msalRedirectUri(oneDriveWebClientId)
            oneDriveStatus = "换一个回调地址再试一次…"
            openWebLogin()
            return
        }
        oneDriveStatus = ""
        _error.value = message
    }

    /** 网页里登录完成后，微软把授权码跳回来了 */
    fun onOneDriveWebLoginCode(code: String, redirectUri: String? = null) {
        prefs.logAuth("网页回调拿到 code（${code.length} 字符），redirect=$redirectUri")
        val pkce = oneDrivePkce
        // 以前这里是 `?: return`，静默失败：网页明明拿到码了，App 却什么都不做、也不报错。
        // 现在至少让用户知道要重新点一次登录。
        if (pkce == null) {
            prefs.logAuth("换 token 放弃：没有 PKCE verifier（登录流程被重置过）")
            oneDriveStatus = ""
            _error.value = "这次登录的校验信息丢了（可能是页面被重新创建过），请重新点「用应用内网页登录」。"
            oneDriveWebLoginUrl = null
            return
        }
        // 换 token 时 redirect_uri 必须和发起登录时一字不差
        val usedRedirect = redirectUri ?: oneDriveWebRedirect
        val appId = oneDriveWebClientId
        val authority = appRef.oneDriveAuth.authorityFor(oneDriveAccountKind)
        oneDriveWebLoginUrl = null
        oneDrivePkce = null
        _busy.value = true
        oneDriveStatus = "正在换取访问令牌…"
        _error.value = null

        viewModelScope.launch {
            try {
                val tokens = appRef.oneDriveAuth.exchangeCode(
                    clientId = appId,
                    code = code,
                    codeVerifier = pkce.verifier,
                    authority = authority,
                    scopeMode = oneDriveScopeMode,
                    redirectUri = usedRedirect,
                )
                prefs.saveOneDriveTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
                prefs.oneDriveClientId = appId
                prefs.oneDriveAuthority = authority
                prefs.logAuth(
                    "换 token 成功：accessLen=${tokens.accessToken.length} " +
                        "有 refresh_token=${tokens.refreshToken.isNotEmpty()} " +
                        "expiresAt=${tokens.expiresAt}"
                )
                prefs.oneDriveAccount = runCatching {
                    appRef.oneDriveGraph.accountName()
                }.getOrNull().orEmpty()
                prefs.providerKind = ProviderKind.ONEDRIVE.id
                providerKind = ProviderKind.ONEDRIVE
                loginTarget = ProviderKind.ONEDRIVE
                providerFilter = null
                loggedIn = true
                oneDriveCode = null
                refreshOneDriveQuota()
                oneDriveStatus = "授权成功，正在扫描 OneDrive…"
                _status.value = "正在扫描 OneDrive…"
                try {
                    scanProvider(ProviderKind.ONEDRIVE)
                    screen = Screen.LIBRARY
                } catch (e: Exception) {
                    _error.value = friendlyError(e)
                }
            } catch (e: OneDriveAuthException) {
                prefs.logAuth("换 token 失败 code=${e.code} raw=${e.raw.take(400)}")
                oneDriveStatus = ""
                _error.value = appRef.oneDriveAuth.explain(e)
            } catch (e: Exception) {
                prefs.logAuth("换 token 异常 ${e.javaClass.simpleName}: ${e.message}")
                oneDriveStatus = ""
                _error.value = "登录失败：${e.message ?: "网络异常，请稍后重试"}"
            } finally {
                _busy.value = false
            }
        }
    }

    init {
        loggedIn = prefs.hasCredentials || prefs.oneDriveConnected

        // 修正历史遗留：记录的「当前网盘」如果其实没连上，就换成真正连着的那个
        val connected = connectedProviders
        if (connected.isNotEmpty() && providerKind !in connected) {
            providerKind = connected.first()
            prefs.providerKind = providerKind.id
        }
        loginTarget = providerKind

        _tracks.value = repo.cachedTracks()
        screen = if (loggedIn) Screen.LIBRARY else Screen.LOGIN

        // 历史索引里「歌名-歌手.flac」被老解析规则存反了，一次性纠正。
        // 只做一次、纯本地统计，不联网；修完让封面按正确歌名重新查。
        if (!prefs.tagsFixV1Done) {
            viewModelScope.launch(Dispatchers.IO) {
                val fixed = runCatching { repo.fixReversedTags() }.getOrDefault(emptyList())
                prefs.tagsFixV1Done = true
                if (fixed.isEmpty()) return@launch
                coverCache.clear()
                appRef.coverRepository.invalidateAll()
                refreshTracks()
                _status.value = "已自动修正 ${fixed.size} 首歌的歌名/歌手，封面会重新查找"
            }
        }

        // 上次登录时昵称没拉到（网络抖了一下，或者 /me 当时没成功），
        // 设置页就会一直显示「账号：已连接」，看不出是谁的盘。
        // 这里补一次，失败也无所谓 —— 纯展示用，不影响读写。
        if (prefs.oneDriveAccessToken.isNotBlank() && prefs.oneDriveAccount.isBlank()) {
            viewModelScope.launch {
                runCatching { appRef.oneDriveGraph.accountName() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { prefs.oneDriveAccount = it }
            }
        }

        // 空间配额：设置页要显示「还剩多少空间」，启动后顺手拉一次
        if (prefs.oneDriveConnected) refreshOneDriveQuota()
        if (prefs.hasCredentials) refreshDavQuota()

        // 云端账号：先把本地缓存显示出来，再后台静默同步一次
        if (prefs.cloudLoggedIn) {
            refreshCloudCache()
            viewModelScope.launch {
                runCatching { appRef.cloudSync.syncAll() }
                    .onSuccess { sync ->
                        if (sync.quarkRestored) {
                            quarkLoggedIn = prefs.quarkLoggedIn
                            quarkAccount = prefs.quarkAccount
                        }
                        refreshCloudCache()
                    }
            }
        }
    }

    /**
     * 切换登录页要显示哪个网盘（只是换个登录表单，不影响已连接的网盘）。
     */
    fun switchProvider(kind: ProviderKind) {
        loginTarget = kind
        _error.value = null
        oneDriveCode = null
        oneDriveStatus = ""
    }

    fun connect(server: String, account: String, password: String) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _status.value = "正在连接服务器…"
        viewModelScope.launch {
            try {
                val creds = DavCredentials(server, account, password)
                val client = repo.client(creds)
                client.list(client.baseUrl.encodedPath)
                prefs.saveCredentials(creds)
                loggedIn = true
                providerKind = ProviderKind.WEBDAV
                prefs.providerKind = ProviderKind.WEBDAV.id
                loginTarget = ProviderKind.WEBDAV
                providerFilter = null
                // 空间用量顺手查一下，设置页直接就能显示
                refreshDavQuota()
                _status.value = "连接成功，正在扫描音乐库…"
                scanProvider(ProviderKind.WEBDAV)
                screen = Screen.LIBRARY
            } catch (e: Exception) {
                _error.value = friendlyError(e)
                _status.value = ""
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------------- OneDrive ----------------

    /** 快速检查一个应用 ID 能不能用，不用走完整个授权流程 */
    fun checkOneDriveClientId(clientId: String) {
        if (_busy.value) return
        val appId = clientId.trim().ifBlank { OneDriveAuth.BUILTIN_CLIENT_ID }
        val scope = oneDriveScopeMode
        _status.value = "正在检查这个应用 ID…"
        _error.value = null
        viewModelScope.launch {
            _status.value = appRef.oneDriveAuth.checkClientId(appId, scope)
        }
    }

    /**
     * 申请设备码并开始等待用户授权。
     *
     * 验证码会**自动续期**：快到期时（或者微软说码已作废时）自动换一个新的，
     * 界面上会同步刷新成最新的验证码。不用用户手动回来点。
     */
    fun startOneDriveAuth(clientId: String) {
        if (_busy.value) return
        // 留空就用内置的微软官方公共客户端
        val appId = clientId.trim().ifBlank { OneDriveAuth.BUILTIN_CLIENT_ID }
        val scope = oneDriveScopeMode
        oneDriveJob?.cancel()
        prefs.oneDriveClientId = appId
        prefs.oneDriveScopeMode = scope
        oneDriveCode = null
        oneDriveStatus = "正在申请授权码…"
        _error.value = null
        _busy.value = true

        oneDriveJob = viewModelScope.launch {
            var renewed = false
            // 刚签发就判失效的次数。正常情况下一个码能活 15 分钟，
            // 只有「端点/应用 ID 根本不被接受」时才会出现「签发几秒后就说过期」，
            // 那时候无限自动换码只会让用户手里的码一直作废，界面上疯狂跳。
            var deadOnArrival = 0

            // 不管从哪条路出去（成功 / 失败 / 异常），忙碌状态都要归位。
            // 卡在 true 的话，用户再点「获取授权码」会被开头的 if (_busy) return 挡掉，
            // 表现就是「点了没反应」。
            try {
            while (true) {
                val info = try {
                    appRef.oneDriveAuth.requestDeviceCode(appId, scope, oneDriveAccountKind)
                } catch (e: OneDriveAuthException) {
                    oneDriveStatus = ""
                    oneDriveCode = null
                    _error.value = appRef.oneDriveAuth.explain(e)
                    _busy.value = false
                    return@launch
                } catch (e: Exception) {
                    oneDriveStatus = ""
                    oneDriveCode = null
                    _error.value = "申请授权码失败：${e.message ?: "网络异常，请检查网络后重试"}"
                    _busy.value = false
                    return@launch
                }

                oneDriveCode = info
                // 界面上显示的账号类型要和实际拿到码的端点一致。
                // 请求时可能因为首选端点不通而落到另一个端点上，
                // 不同步的话用户会拿着 common 的码去个人账号验证页上输。
                changeOneDriveAccountKind(
                    if (info.authority == DeviceCodeInfo.AUTHORITY_COMMON) "work" else "personal"
                )
                oneDriveStatus = if (renewed) {
                    "上一个验证码已作废，请输入下面这个新的"
                } else {
                    "请在浏览器打开微软页面，输入上面的验证码"
                }

                // 不提前换码：换了新的会让用户手里那个立刻作废，反而更容易失败。
                // 只在微软明确说码作废时（Expired）才换。
                val deadline = System.currentTimeMillis() + info.expiresIn * 1000L
                val renewAt = deadline

                when (val outcome = appRef.oneDriveAuth.awaitToken(
                    appId, info.deviceCode, info.interval, renewAt, info.authority, scope
                )) {
                    is TokenOutcome.Success -> {
                        val tokens = outcome.tokens
                        try {
                            prefs.saveOneDriveTokens(
                                tokens.accessToken, tokens.refreshToken, tokens.expiresAt
                            )
                            // 拉昵称只是好看，**拉不到不能算登录失败**。
                            // /me 要 User.Read 权限，而内置的第一方应用不一定预授权了这个；
                            // 这里一抛异常，下面「登录成功」那几行就全被跳过了 ——
                            // token 存下了、loggedIn 却没设，界面上看着就是「一直登不进去」。
                            prefs.oneDriveAccount = runCatching {
                                appRef.oneDriveGraph.accountName()
                            }.getOrNull().orEmpty()
                            prefs.oneDriveAuthority = info.authority
                            prefs.providerKind = ProviderKind.ONEDRIVE.id
                            providerKind = ProviderKind.ONEDRIVE
                            loginTarget = ProviderKind.ONEDRIVE
                            providerFilter = null
                            loggedIn = true
                            oneDriveCode = null
                            oneDriveStatus = "授权成功，正在扫描 OneDrive…"
                            _status.value = "正在扫描 OneDrive…"
                            try {
                                scanProvider(ProviderKind.ONEDRIVE)
                                screen = Screen.LIBRARY
                            } catch (e: Exception) {
                                _error.value = friendlyError(e)
                            }
                        } catch (e: Exception) {
                            _error.value = friendlyError(e)
                        } finally {
                            _busy.value = false
                        }
                        return@launch
                    }

                    is TokenOutcome.Expired -> {
                        // 刚签发就被微软判失效 —— 再换下去也是白换，只会让用户手里的码反复作废。
                        if (System.currentTimeMillis() - info.issuedAt < 60_000L) {
                            deadOnArrival++
                            if (deadOnArrival >= 3) {
                                oneDriveCode = null
                                oneDriveStatus = ""
                                _error.value =
                                    "验证码刚拿到就被微软判为失效，连续 3 次都是这样。\n\n" +
                                        "这个应用 ID 多半不允许设备码授权（微软的第一方应用经常这样限制）。" +
                                        "可以把「请求的权限」换成「只读 / 读写」，或者在上面填一个自己注册的应用 ID 再试。"
                                return@launch
                            }
                        }
                        renewed = true
                        _status.value = "验证码已失效，正在自动换一个…"
                        continue
                    }

                    is TokenOutcome.Timeout -> {
                        renewed = true
                        _status.value = "验证码快过期了，正在自动换一个…"
                        continue
                    }

                    is TokenOutcome.Failed -> {
                        oneDriveStatus = ""
                        oneDriveCode = null
                        _error.value = appRef.oneDriveAuth.explain(outcome.error)
                        return@launch
                    }
                }
            }
            } finally {
                // 任何出口都要把忙碌状态放掉
                _busy.value = false
            }
        }
    }

    /** 用户主动换一个验证码 */
    fun refreshOneDriveCode() {
        // 原来这里先 if (_busy) return、再 _busy = false —— 互相矛盾，
        // 走不到第二行，等于按钮是死的。正确做法是先掐掉在跑的那个流程。
        oneDriveJob?.cancel()
        oneDriveJob = null
        _busy.value = false
        startOneDriveAuth(prefs.oneDriveClientId)
    }

    /** 断开指定的网盘，另一个网盘不受影响 */
    fun disconnect(kind: ProviderKind) {
        when (kind) {
            ProviderKind.WEBDAV -> {
                prefs.clearCredentials()
                repo.forgetProvider(ProviderKind.WEBDAV.id)
            }
            ProviderKind.ONEDRIVE -> {
                oneDriveJob?.cancel()
                prefs.clearOneDrive()
                repo.forgetProvider(ProviderKind.ONEDRIVE.id)
                oneDriveCode = null
                oneDriveStatus = ""
            }
        }

        // 断掉「当前在用」的那个时，把当前网盘顺延到还剩下的那个
        val remain = connectedProviders
        if (providerKind == kind) {
            providerKind = remain.firstOrNull() ?: ProviderKind.WEBDAV
            prefs.providerKind = providerKind.id
        }
        if (remain.isEmpty()) {
            loginTarget = providerKind
        }

        // 筛选的是刚被断开的网盘时，清掉筛选
        if (providerFilter == kind.id) providerFilter = null

        refreshTracks()
        loggedIn = remain.isNotEmpty()
        if (!loggedIn) {
            _tracks.value = emptyList()
            query = ""
            providerFilter = null
            screen = Screen.LOGIN
        }
    }

    /** 断开当前正在使用的那个网盘 */
    fun disconnectCurrentProvider() {
        disconnect(providerKind)
    }

    fun rescan() {
        if (_busy.value) return
        if (!loggedIn) {
            screen = Screen.LOGIN
            return
        }
        _busy.value = true
        _error.value = null
        _status.value = "正在扫描音乐库…"
        viewModelScope.launch {
            try {
                scanAllProviders()
                screen = Screen.LIBRARY
            } catch (e: Exception) {
                _error.value = friendlyError(e)
            } finally {
                _busy.value = false
            }
        }
    }

    /** 当前网盘后端 */
    fun currentProvider(): CloudProvider = when (providerKind) {
        ProviderKind.ONEDRIVE -> appRef.oneDriveProvider
        ProviderKind.WEBDAV -> DavProvider(repo.client(), prefs)
    }

    /** 已经连上的网盘（可以同时有多个） */
    val connectedProviders: List<ProviderKind>
        get() = buildList {
            if (prefs.hasCredentials) add(ProviderKind.WEBDAV)
            if (prefs.oneDriveConnected) add(ProviderKind.ONEDRIVE)
        }

    private fun providerOf(kind: ProviderKind): CloudProvider? = when (kind) {
        ProviderKind.ONEDRIVE -> if (prefs.oneDriveConnected) appRef.oneDriveProvider else null
        ProviderKind.WEBDAV -> if (prefs.hasCredentials) DavProvider(repo.client(), prefs) else null
    }


    /** 扫描所有已连接的网盘，结果合成同一个曲库 */
    /** 从导入页返回时重新扫描一遍，让新导入的歌立刻进曲库 */
    fun rescanAfterImport() {
        viewModelScope.launch {
            try {
                scanAllProviders()
            } catch (e: Exception) {
                _status.value = friendlyError(e)
            }
        }
    }

    private suspend fun scanAllProviders() {
        val providers = connectedProviders
        if (providers.isEmpty()) {
            screen = Screen.LOGIN
            return
        }
        val errors = mutableListOf<String>()
        for (kind in providers) {
            val provider = providerOf(kind) ?: continue
            val outcome = repo.scan(provider) { n, path ->
                _status.value = "[${kind.label}] 已找到 $n 首 · 正在扫描 ${path.substringAfterLast('/')}"
            }
            if (kind == ProviderKind.WEBDAV) {
                davUsedBytes = outcome.usedBytes
                prefs.davUsedBytes = outcome.usedBytes
            }
            errors.addAll(outcome.errors.map { "${kind.label} · $it" })
        }
        // 扫描时可能用内嵌标签纠正了歌名/歌手，封面缓存跟着失效重查
        appRef.coverRepository.invalidateAll()
        refreshTracks()
        _status.value = if (errors.isEmpty()) {
            "扫描完成，共 ${_tracks.value.size} 首" + providerSuffix(providers)
        } else {
            "扫描完成，共 ${_tracks.value.size} 首 · ${errors.size} 个目录读取失败" +
                providerSuffix(providers)
        }
        errors.take(3).forEach { _status.value = "$_status.value\n$it" }
    }

    /** 只扫一个网盘（刚连上的那个），另一个网盘的曲库保持不动 */
    private suspend fun scanProvider(kind: ProviderKind) {
        val provider = providerOf(kind) ?: return
        val outcome = repo.scan(provider) { n, path ->
            _status.value = "[${kind.label}] 已找到 $n 首 · 正在扫描 ${path.substringAfterLast('/')}"
        }
        if (kind == ProviderKind.WEBDAV) {
            davUsedBytes = outcome.usedBytes
            prefs.davUsedBytes = outcome.usedBytes
        }
        appRef.coverRepository.invalidateAll()
        refreshTracks()
        val errors = outcome.errors
        _status.value = if (errors.isEmpty()) {
            "扫描完成，共 ${_tracks.value.size} 首" + providerSuffix(connectedProviders)
        } else {
            "扫描完成，共 ${_tracks.value.size} 首 · ${errors.size} 个目录读取失败"
        }
    }

    /** 从库里重新读全部网盘的曲目 */
    private fun refreshTracks() {
        _tracks.value = repo.cachedTracks()
    }

    /**
     * 从网盘删掉一首歌。
     *
     * 删的是**网盘里的文件本体**，索引里那一条也跟着清掉 ——
     * 不然曲库里还点得到一首播不了的歌。
     */
    fun deleteTrack(track: Track) {
        viewModelScope.launch {
            val kind = ProviderKind.of(track.provider)
            val provider = providerOf(kind)
            if (provider == null) {
                _error.value = "${kind.shortLabel} 还没连上，先去设置里连一下"
                return@launch
            }
            when (val r = provider.delete(track.id)) {
                is DeleteResult.Ok -> {
                    repo.deleteTrack(track.key)
                    // 导入页那边的「已导入」记录也得清 —— 文件都没了，
                    // 那边还显示「已导入 · 播放」的话，一点就播不出来
                    appRef.prefs.removeImportedEntries(track.provider, track.id)
                    // 正在播的就是它？先停掉
                    if (player.currentTrack?.key == track.key) player.controller?.stop()
                    refreshTracks()
                    _status.value = "已从${kind.shortLabel}删除《${track.title}》"
                }
                is DeleteResult.Failed -> _error.value = r.message
            }
        }
    }

    private fun providerSuffix(providers: List<ProviderKind>): String =
        if (providers.size > 1) "（${providers.joinToString(" + ") { it.label }}）" else ""


    /**
     * 加载歌词：先本地 .lrc，再在线歌词。
     * 切歌时自动调用，成功的结果会记住，失败的允许重试。
     */
    fun loadLyrics(track: Track?) {
        val id = track?.key
        if (id == null) {
            lyricsJob?.cancel()
            lyricsLoadingId = null
            lyricsState = LyricsUiState()
            lyricsOffsetMs = 0L
            return
        }
        // 换歌时把这首歌校准过的偏移取回来（单行查询，主线程读一次开销可忽略）
        lyricsOffsetMs = appRef.db.lyricOffset(id)
        // 已经拿到过歌词，直接用
        lyricsCache[id]?.let {
            lyricsJob?.cancel()
            lyricsLoadingId = null
            lyricsState = it
            return
        }
        // 正在加载同一首，别重复发请求
        if (lyricsLoadingId == id && lyricsJob?.isActive == true) return
        // 正在预取同一首，等预取的结果就行
        if (prefetchingIds.contains(id)) {
            lyricsState = LyricsUiState(loading = true)
            return
        }

        startLyricsLoad(track, forceRefresh = false)
    }

    /** 清掉当前歌曲的旧歌词（内存 + 数据库）重新匹配，用在「歌词不对」时。 */
    fun refreshLyrics() {
        val track = player.currentTrack ?: return
        lyricsCache.remove(track.key)
        prefetchingIds.remove(track.key)
        startLyricsLoad(track, forceRefresh = true)
    }

    private fun startLyricsLoad(track: Track, forceRefresh: Boolean) {
        val id = track.key
        lyricsJob?.cancel()
        lyricsLoadingId = id
        lyricsState = LyricsUiState(loading = true)
        lyricsJob = viewModelScope.launch {
            val result = appRef.lyricsRepository.load(track, forceRefresh)
            val state = LyricsUiState(
                loading = false,
                lyrics = result.lyrics,
                source = result.source,
                error = result.error,
            )
            // 只有真拿到歌词才记住，失败的留着下次重试
            if (state.lyrics?.isEmpty == false) {
                lyricsCache[id] = state
            }
            lyricsLoadingId = null
            lyricsState = state
        }
    }

    /** 校准当前歌曲的歌词时间，deltaMs 为正表示歌词往前挪（提前）。 */
    fun adjustLyricsOffset(deltaMs: Long) {
        val id = player.currentTrack?.key ?: return
        val next = (lyricsOffsetMs + deltaMs).coerceIn(-60_000L, 60_000L)
        lyricsOffsetMs = next
        viewModelScope.launch(Dispatchers.IO) {
            appRef.db.saveLyricOffset(id, next)
        }
    }

    fun resetLyricsOffset() = adjustLyricsOffset(-lyricsOffsetMs)

    /** 提前把下一首的歌词准备好，按下一首时就不用等。 */
    fun prefetchLyrics(track: Track?) {
        val id = track?.key ?: return
        if (lyricsCache.containsKey(id)) return
        if (lyricsLoadingId == id || prefetchingIds.contains(id)) return
        prefetchingIds.add(id)
        viewModelScope.launch {
            val result = appRef.lyricsRepository.load(track)
            prefetchingIds.remove(id)
            if (!result.lyrics.isEmpty) {
                val state = LyricsUiState(
                    loading = false,
                    lyrics = result.lyrics,
                    source = result.source,
                )
                lyricsCache[id] = state
                // 刚好切到这一首，直接显示出来
                if (player.currentTrack?.key == id) lyricsState = state
            }
        }
    }

    /** 手动指定歌名/歌手再搜一次歌词。 */
    fun manualSearchLyrics(title: String, artist: String) {
        lyricsJob?.cancel()
        lyricsLoadingId = null
        lyricsState = LyricsUiState(loading = true)
        lyricsJob = viewModelScope.launch {
            val result = appRef.lyricsRepository.manualSearch(title, artist, player.currentTrack)
            val state = LyricsUiState(
                loading = false,
                lyrics = result.lyrics,
                source = result.source,
                error = result.error,
            )
            // 手动搜到的结果也记住，回切过去不用再搜
            if (state.lyrics?.isEmpty == false) {
                player.currentTrack?.key?.let { lyricsCache[it] = state }
            }
            lyricsState = state
        }
    }

    /** 列出候选歌曲，不自动采用 —— 用户点哪首就用哪首的歌词。 */
    fun searchLyricsCandidates(title: String, artist: String) {
        lyricsSearchJob?.cancel()
        lyricsSearchState = LyricsSearchUiState(searching = true)
        lyricsSearchJob = viewModelScope.launch {
            val list = appRef.lyricsRepository.searchCandidates(title, artist)
            lyricsSearchState = LyricsSearchUiState(
                candidates = list,
                error = if (list.isEmpty()) "没搜到候选歌曲，换个歌名或歌手试试" else null,
            )
        }
    }

    /** 用户点中某一首：取它的歌词并覆盖当前曲目的缓存。失败则留在列表里让用户换一首。 */
    fun selectLyricsCandidate(candidate: LyricsCandidate) {
        lyricsSearchJob?.cancel()
        lyricsSearchState = lyricsSearchState.copy(applying = true, error = null)
        lyricsSearchJob = viewModelScope.launch {
            val result = appRef.lyricsRepository.fetchCandidate(candidate, player.currentTrack)
            if (result.error == null && !result.lyrics.isEmpty) {
                val state = LyricsUiState(lyrics = result.lyrics, source = result.source)
                player.currentTrack?.key?.let { lyricsCache[it] = state }
                // 别让正在跑的自动加载晚一步把结果覆盖回去
                lyricsJob?.cancel()
                lyricsLoadingId = null
                lyricsState = state
                lyricsSearchState = LyricsSearchUiState(done = true)
            } else {
                lyricsSearchState = lyricsSearchState.copy(
                    applying = false,
                    error = result.error ?: "这首歌没有可用歌词",
                )
            }
        }
    }

    /** 关闭搜索弹窗时清状态，下次打开是干净的输入框。 */
    fun clearLyricsSearch() {
        lyricsSearchJob?.cancel()
        lyricsSearchState = LyricsSearchUiState()
    }

    var onlineLyrics: Boolean
        get() = _onlineLyrics
        set(value) {
            _onlineLyrics = value
            prefs.onlineLyrics = value
        }

    private var _onlineLyrics by mutableStateOf(prefs.onlineLyrics)

    fun logout() {
        disconnectCurrentProvider()
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(appRef.cacheDir, "media").deleteRecursively()
            }
            _status.value = "缓存已清理"
        }
    }

    fun dismissError() {
        _error.value = null
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is AuthFailedException -> "账号或应用密码不正确，请重新生成应用密码后填写"
        is WebDavException -> "服务器返回错误 ${e.code}"
        is UnknownHostException -> "无法解析服务器地址，请检查地址是否填对"
        is SocketTimeoutException -> "连接超时，请检查网络或换个网盘试试"
        is IOException -> "网络错误：${e.message ?: "连接失败"}"
        else -> e.message ?: "发生未知错误"
    }
}