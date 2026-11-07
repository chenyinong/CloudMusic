package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudmusic.app.data.ProviderKind

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
    // 投屏状态机：跟播放页是同一个实例（同一个 Activity 的 ViewModelStore），
    // 迷你播放器靠它知道「现在控制权在电视身上」
    val dlnaVm: DlnaViewModel = viewModel()

    // 播放页和登录页自己占满整屏，不画底部条 —— 内容区也就不能留底，
    // 否则底下会空出一块（外层是浅色底，深色的播放页下面就露出一条白）。
    val fullScreen = vm.screen == Screen.PLAYER || vm.screen == Screen.LOGIN

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
    // 底部条是浮在内容上面的，所以内容区要留出它的高度 ——
    // 这样每个页面都不用自己操心留白，滚动到底时最后一项正好压在条子上沿。
    // 还要加上系统手势条那块 inset（页签栏自己避开了它）。
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPad = when {
        fullScreen -> 0.dp
        conn.currentTrack != null -> 118.dp + navBottom
        else -> 58.dp + navBottom
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPad)
    ) {
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
            onOpenSettings = { vm.screen = Screen.SETTINGS },
                            // 从列表点歌是「想听」，停在唱片页
                            onOpenPlayer = {
                                openAtLyrics = false
                                vm.screen = Screen.PLAYER
                            }
                        )

        Screen.PLAYER -> PlayerScreen(
                        vm = vm,
                        startOnLyrics = openAtLyrics,
                        onBack = {
                            openAtLyrics = false
                            vm.screen = Screen.LIBRARY
                        },
                        onOpenSettings = { vm.screen = Screen.SETTINGS }
                    )

        Screen.IMPORT -> ImportScreen(
            providers = vm.connectedProviders,
            onBack = {
                vm.screen = Screen.LIBRARY
                vm.rescanAfterImport()
            },
            onOpenPlaylistImport = { vm.screen = Screen.PLAYLIST_IMPORT },
            // 点「已导入」那条直接开播，播完还能看着播放器
            onOpenPlayer = { vm.screen = Screen.PLAYER }
        )

        // 导入歌单：只拉清单存进「我的歌单」，一个字节的音频都不下
        Screen.PLAYLIST_IMPORT -> PlaylistImportScreen(
            onBack = { vm.screen = Screen.LIBRARY },
            onOpenPlaylist = { key ->
                vm.playlistKey = key
                vm.screen = Screen.PLAYLIST_DETAIL
            }
        )

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
            },
            onOpenPlayer = {
                                openAtLyrics = false
                                vm.screen = Screen.PLAYER
                            }
                        )

                    Screen.SETTINGS -> SettingsScreen(vm = vm, onBack = { vm.screen = Screen.LIBRARY })

        // 登录夸克网盘（下载链路的中转站）
        Screen.QUARK_LOGIN -> QuarkLoginScreen(
            onBack = { vm.screen = Screen.SETTINGS },
            onLoggedIn = { cookie, _ ->
                vm.saveQuarkCookie(cookie)
                vm.screen = Screen.QUARK_DIAG
            }
        )

        // 诊断台：一次性定性「登录态签出来的直链能不能下」
        Screen.QUARK_DIAG -> QuarkDiagScreen(
            loggedIn = vm.quarkLoggedIn,
            onBack = { vm.screen = Screen.SETTINGS },
            onOpenLogin = { vm.screen = Screen.QUARK_LOGIN }
        )
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
                        // 点迷你条 = 想看这句歌词，直接翻到歌词页
                        onOpenPlayer = {
                            openAtLyrics = true
                            vm.screen = Screen.PLAYER
                        },
                        onOpenQueue = { showMiniQueue = true }
                    )
                }
                BottomTabBar(
                    current = tabOf(vm.screen),
                    onSelect = { vm.screen = it }
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