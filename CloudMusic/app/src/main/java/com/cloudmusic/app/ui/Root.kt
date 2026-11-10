package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudmusic.app.data.ProviderKind
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

/**
 * 列表页底部要预留的滚动余量（迷你条 + 页签栏的高度）。
 *
 * 曲库/搜索导入/歌单这些列表页是全高渲染的 —— 内容能滚到迷你条后面，
 * 毛玻璃才有东西可糊；滚动到底不被条挡住由各列表拿这个值做 contentPadding。
 * 手势条 inset 由内容区统一避让，这里不含它。
 */
val LocalScreenBottomPad = staticCompositionLocalOf { 0.dp }

@Composable
fun CloudMusicRoot(vm: AppViewModel) {
    val tracks by vm.tracks.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()

    LaunchedEffect(Unit) {
        vm.player.connect()
    }

    // 切歌后自动加载歌词，不用等用户点进播放页
    val currentTrack = vm.player.currentTrack
    val nextTrack = vm.player.nextTrack
    LaunchedEffect(currentTrack?.key, nextTrack?.key) {
        vm.loadLyrics(currentTrack)
        vm.prefetchLyrics(nextTrack)
        vm.loadCover(currentTrack)
    }

    val conn = vm.player
    var showMiniQueue by remember { mutableStateOf(false) }
    /**
     * 打开播放页时要不要直接停在歌词页。
     *
     * 从底部迷你播放器点进来 = 想看歌词；从列表里点某首歌 = 想听歌，
     * 停在唱片页。两个入口不一样，所以这个状态得单独记。
     */
    var openAtLyrics by remember { mutableStateOf(false) }
    /**
     * 播放页是从哪个页面进来的 —— 返回时得回到那儿。
     *
     * 之前返回写死回「我的」，从歌单里点歌播放再返回就直接跳回曲库了，
     * 用户明明在歌单里，回不回去歌单很别扭。
     */
    var playerOrigin by remember { mutableStateOf(Screen.LIBRARY) }

    // 播放页返回：回到进来之前的那一页
    val backFromPlayer: () -> Unit = {
        openAtLyrics = false
        vm.screen = playerOrigin
        // 在歌单里导了歌就顺手刷新一下曲库
        if (playerOrigin == Screen.PLAYLIST_DETAIL) vm.rescanAfterImport()
    }

    /**
     * 系统返回键 / 侧滑返回手势。
     *
     * 这些页面是 Compose 自己画的栈（不是 Activity 栈），不接的话
     * 系统返回会直接把 App 关掉，而不是回到上一页。
     */
    BackHandler(enabled = vm.screen != Screen.LIBRARY && vm.screen != Screen.LOGIN) {
        when (vm.screen) {
            Screen.PLAYER -> backFromPlayer()
            Screen.IMPORT -> {
                vm.screen = Screen.LIBRARY
                vm.rescanAfterImport()
            }
            Screen.PLAYLIST_IMPORT -> vm.screen = Screen.LIBRARY
            Screen.PLAYLIST_LIST -> vm.screen = Screen.LIBRARY
            Screen.PLAYLIST_DETAIL -> {
                vm.screen = Screen.PLAYLIST_LIST
                vm.rescanAfterImport()
            }
            Screen.SETTINGS -> vm.screen = Screen.LIBRARY
            Screen.QUARK_LOGIN -> vm.screen = Screen.SETTINGS
            Screen.QUARK_DIAG -> vm.screen = Screen.SETTINGS
            else -> {}
        }
    }
    // 投屏状态机：跟播放页是同一个实例（同一个 Activity 的 ViewModelStore），
    // 迷你播放器靠它知道「现在控制权在电视身上」
    val dlnaVm: DlnaViewModel = viewModel()

    // 播放页和登录页自己占满整屏，不画底部条 —— 内容区也就不能留底，
    // 否则底下会空出一块（外层是浅色底，深色的播放页下面就露出一条白）。
    val fullScreen = vm.screen == Screen.PLAYER || vm.screen == Screen.LOGIN

    // 毛玻璃模糊源：mini 播放器要模糊的就是这一块背后的内容
    val hazeState = remember { HazeState() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
    // 底部条浮在内容上面。列表页（曲库/搜索导入/歌单）现在全高渲染、
    // 自己用 LocalScreenBottomPad 留滚动余量 —— 内容能滚到迷你条后面，
    // 毛玻璃才有东西可糊；其余表单页仍由 formPad 统一避让。
    // 手势条 inset 由内容区避让一次，列表页的 contentPadding 不重复算。
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scrollPad = when {
        fullScreen -> 0.dp
        conn.currentTrack != null -> 126.dp
        else -> 58.dp
    }
    val formPad = scrollPad + navBottom
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = if (fullScreen) 0.dp else navBottom)
            .haze(hazeState)
    ) {
        CompositionLocalProvider(LocalScreenBottomPad provides scrollPad) {
    when (vm.screen) {
        Screen.LOGIN -> if (vm.loginTarget == ProviderKind.ONEDRIVE) {
            OneDriveLoginScreen(
                clientIdDefault = vm.oneDriveClientIdDefault,
                connected = vm.oneDriveConnected,
                account = vm.oneDriveAccount,
                busy = busy,
                deviceCode = vm.oneDriveCode,
                status = vm.oneDriveStatus,
                error = error,
                canGoBack = vm.connectedProviders.isNotEmpty(),
                onBack = { vm.screen = Screen.LIBRARY },
                scopeMode = vm.oneDriveScopeMode,
                onScopeModeChange = { vm.changeOneDriveScopeMode(it) },
                accountKind = vm.oneDriveAccountKind,
                onAccountKindChange = { vm.changeOneDriveAccountKind(it) },
                onCheckClientId = { vm.checkOneDriveClientId(it) },
                onRefreshCode = { vm.refreshOneDriveCode() },
                onRequestCode = { vm.startOneDriveAuth(it) },
                onWebLogin = { vm.startOneDriveWebLogin(it) },
                onSwitchToWebDav = { vm.switchProvider(ProviderKind.WEBDAV) }
            )
        } else {
            LoginScreen(
                serverDefault = vm.serverDefault,
                accountDefault = vm.accountDefault,
                busy = busy,
                error = error,
                canGoBack = vm.connectedProviders.isNotEmpty(),
                onBack = { vm.screen = Screen.LIBRARY },
                onConnect = { server, account, password ->
                    vm.connect(server, account, password)
                },
                onSwitchToOneDrive = { vm.switchProvider(ProviderKind.ONEDRIVE) }
            )
        }

        Screen.LIBRARY -> LibraryScreen(
            vm = vm,
            tracks = tracks,
            status = status,
            busy = busy,
            query = vm.query,
            onQueryChange = { vm.query = it },
            providers = vm.connectedProviders,
            providerFilter = vm.providerFilter,
            onProviderFilterChange = { vm.providerFilter = it },
            onOpenSettings = { vm.screen = Screen.SETTINGS }
                        )

        Screen.PLAYER -> PlayerScreen(
                        vm = vm,
                        startOnLyrics = openAtLyrics,
                        onBack = backFromPlayer,
                        onOpenSettings = { vm.screen = Screen.SETTINGS }
                    )

        Screen.IMPORT -> ImportScreen(
            providers = vm.connectedProviders,
            onBack = {
                vm.screen = Screen.LIBRARY
                vm.rescanAfterImport()
            },
            // 已导入的行点整行只出声、留在搜歌页；想看播放页点底部迷你条，
            // 那时 playerOrigin 会按当前页记成 IMPORT，返回仍能回到这里。
            onOpenPlaylistImport = { vm.screen = Screen.PLAYLIST_IMPORT }
        )

        // 导入歌单：预览页底部有操作按钮，走统一避让（不参与滚到条后面）
        Screen.PLAYLIST_IMPORT -> Box(Modifier.padding(bottom = formPad)) {
            PlaylistImportScreen(
                onBack = { vm.screen = Screen.LIBRARY },
                onOpenPlaylist = { key ->
                    vm.playlistKey = key
                    vm.screen = Screen.PLAYLIST_DETAIL
                }
            )
        }

        // 我的歌单：网易云那种列表，点进去才是歌单详情
        Screen.PLAYLIST_LIST -> PlaylistListScreen(
            onBack = { vm.screen = Screen.LIBRARY },
            onOpenImport = { vm.screen = Screen.PLAYLIST_IMPORT },
            onOpenPlaylist = { key ->
                vm.playlistKey = key
                vm.screen = Screen.PLAYLIST_DETAIL
            }
        )

        // 歌单详情：想听哪首点哪首，点「导入」才去找音源、下载、传网盘
        Screen.PLAYLIST_DETAIL -> PlaylistDetailScreen(
            playlistKey = vm.playlistKey,
            providers = vm.connectedProviders,
            onBack = {
                vm.screen = Screen.PLAYLIST_LIST
                vm.rescanAfterImport()
            },
            onDeleted = {
                vm.screen = Screen.PLAYLIST_LIST
            }
        )

                    Screen.SETTINGS -> Box(Modifier.padding(bottom = formPad)) {
                        SettingsScreen(vm = vm, onBack = { vm.screen = Screen.LIBRARY })
                    }

        // 登录夸克网盘（下载链路的中转站）
        Screen.QUARK_LOGIN -> Box(Modifier.padding(bottom = formPad)) {
            QuarkLoginScreen(
                onBack = { vm.screen = Screen.SETTINGS },
                onLoggedIn = { cookie, _ ->
                    vm.saveQuarkCookie(cookie)
                    vm.screen = Screen.QUARK_DIAG
                }
            )
        }

        // 诊断台：一次性定性「登录态签出来的直链能不能下」
        Screen.QUARK_DIAG -> Box(Modifier.padding(bottom = formPad)) {
            QuarkDiagScreen(
                loggedIn = vm.quarkLoggedIn,
                onBack = { vm.screen = Screen.SETTINGS },
                onOpenLogin = { vm.screen = Screen.QUARK_LOGIN }
            )
        }
        }
    }
    }

        // ── 底部：正在播放的悬浮条 + 导航栏 ──────────────
        // 网易云就这样：不管在哪个页面，底部永远挂着当前在放的那首。
        // 播放页自己占满整屏，不显示（跟网易云一致）。
        if (!fullScreen) {
            Column(Modifier.align(Alignment.BottomCenter)) {
                if (conn.currentTrack != null) {
                    MiniPlayerBar(
                        player = conn,
                        dlna = dlnaVm,
                        hazeState = hazeState,
                        // 点迷你条 = 想看这句歌词，直接翻到歌词页
                        onOpenPlayer = {
                            openAtLyrics = true
                            // 从哪儿点的迷你条，返回就回哪儿
                            playerOrigin = vm.screen
                            vm.screen = Screen.PLAYER
                        },
                        onOpenQueue = { showMiniQueue = true }
                    )
                }
                BottomTabBar(
                    current = tabOf(vm.screen),
                    onSelect = { vm.screen = it },
                    hazeState = hazeState
                )
            }
        }
    }

    if (showMiniQueue) {
        PlayQueueSheet(
            queue = conn.queue,
            currentKey = conn.currentTrack?.key,
            onPick = { conn.jumpTo(it) },
            onDismiss = { showMiniQueue = false }
        )
    }

    // 微软登录：应用内浏览器 + 授权码流程（没有设备码那 15 分钟倒计时）
    val webLoginUrl = vm.oneDriveWebLoginUrl
    if (webLoginUrl != null) {
        OneDriveWebLoginDialog(
            url = webLoginUrl,
            redirectUri = vm.oneDriveWebRedirectUri,
            onCode = { code, redirect -> vm.onOneDriveWebLoginCode(code, redirect) },
            onError = { vm.onOneDriveWebLoginError(it) },
            onDismiss = { vm.cancelOneDriveWebLogin() },
        )
    }
}