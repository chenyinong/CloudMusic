package com.cloudmusic.app.ui

import android.app.Application
import android.net.Uri
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.WebDavException
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
    QUARK_LOGIN, QUARK_DIAG
}

/** 歌词面板的状态。 */
data class LyricsUiState(
    val loading: Boolean = false,
    val lyrics: Lyrics? = null,
    val source: String = "",
    val error: String? = null,
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

    private var lyricsJob: Job? = null

    /** 正在加载歌词的曲目，避免同一首重复发请求 */
    private var lyricsLoadingId: String? = null

    /** 正在预取的曲目 */
    private val prefetchingIds = mutableSetOf<String>()

    /** 每首歌的歌词结果，避免来回切歌时反复请求 */
    private val lyricsCache = mutableMapOf<String, LyricsUiState>()

    var coverUrl by mutableStateOf<String?>(null)
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
            return
        }
        // 已经拿到过（或确认没有），直接用
        coverCache[id]?.let {
            coverJob?.cancel()
            coverLoadingId = null
            coverUrl = it.ifBlank { null }
            return
        }
        // 正在查同一首，等着就行
        if (coverLoadingId == id && coverJob?.isActive == true) return

        coverJob?.cancel()
        coverLoadingId = id
        // 新歌还没查到封面：先清掉上一首的，别让旧封面挂在新歌上
        coverUrl = null
        coverJob = viewModelScope.launch {
            val url = appRef.coverRepository.load(track)
            coverCache[id] = url ?: ""
            // 查完还在听这首才上图，不然刚切走的歌把新歌的封面顶掉了
            if (player.currentTrack?.key == id) coverUrl = url
            coverLoadingId = null
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

    var quarkAccount by mutableStateOf(prefs.quarkAccount)
        private set

    /** 保存夸克登录态。cookie 来自 WebView 抓取。 */
    fun saveQuarkCookie(cookie: String) {
        prefs.saveQuark(cookie, "")
        quarkLoggedIn = prefs.quarkLoggedIn
        quarkAccount = prefs.quarkAccount
        viewModelScope.launch {
            // 顺便把账号昵称拉回来，界面上好看点
            val name = appRef.quarkClient.accountName()
            if (name.isNotBlank()) {
                prefs.quarkAccount = name
                quarkAccount = name
            }
        }
    }

    fun clearQuark() {
        prefs.clearQuark()
        quarkLoggedIn = false
        quarkAccount = ""
        // 退出即把 WebView 里的夸克登录态也清掉（否则下次进登录页还认得老账号，
        // 会立刻自动重新登录回 App，看着就像「没退出干净」）。
        val cm = CookieManager.getInstance()
        @Suppress("DEPRECATION")
        cm.removeAllCookies(android.webkit.ValueCallback<Boolean> { })
        cm.flush()
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
            return
        }
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

        lyricsJob?.cancel()
        lyricsLoadingId = id
        lyricsState = LyricsUiState(loading = true)
        lyricsJob = viewModelScope.launch {
            val result = appRef.lyricsRepository.load(track)
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
            val result = appRef.lyricsRepository.manualSearch(title, artist)
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