package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    /**
     * 进来就直接停在歌词页。
     *
     * 从底部那个迷你播放器点进来时用 —— 用户在列表里看到歌，
     * 想的大概是「看看这句歌词是什么」，不是再看一遍唱片。
     */
    startOnLyrics: Boolean = false,
) {
    val conn = vm.player
    val title = conn.currentTitle
    val artist = conn.currentArtist
    val duration = conn.durationMs
    val position = conn.positionMs
    val track = conn.currentTrack

    val dlnaVm: DlnaViewModel = viewModel()
    val scope = rememberCoroutineScope()

    var showLyricsSearch by remember { mutableStateOf(false) }
    var showDlna by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    // 唱片页 / 歌词页，上下滑动切换（网易云就是这么翻的）
    val pagerState = rememberPagerState(
        initialPage = if (startOnLyrics) 1 else 0,
        pageCount = { 2 }
    )
    val onLyricsPage = pagerState.currentPage == 1

    // 从迷你播放器点进来时，直接翻到歌词页
    LaunchedEffect(startOnLyrics) {
        if (startOnLyrics) pagerState.scrollToPage(1)
    }

    LaunchedEffect(Unit) {
        while (true) {
            conn.tick()
            delay(400)
        }
    }

    // 只有滑到歌词页才去取词，省流量也省请求
    LaunchedEffect(onLyricsPage, conn.currentTrack?.key) {
        if (onLyricsPage) vm.loadLyrics(conn.currentTrack)
    }

    // 投出去之后手机就别自己出声了，避免和电视撞在一起
    LaunchedEffect(dlnaVm.casting, dlnaVm.remotePlaying) {
        if (dlnaVm.casting != null || dlnaVm.remotePlaying) {
            conn.controller?.pause()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF232323))))
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            // 底部功能栏别压在系统手势条上
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 投屏中（手机只是遥控器，控制权在电视）
        val casting = dlnaVm.casting
        // 投屏时进度、歌词都要跟电视走 —— 手机自己是暂停的，用它的位置不动
        val showPos = if (casting != null) dlnaVm.castPositionMs else position
        val showDur = if (casting != null) {
            if (dlnaVm.castDurationMs > 0) dlnaVm.castDurationMs else duration
        } else duration
        Spacer(Modifier.height(4.dp))

        // ── 顶栏：收起 + 标题 + 更多 ──────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = "收起",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题留白 —— 网易云那个「正在播放」跟状态栏叠一起很难看，干脆不要
                track?.album?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box {
                IconButton(onClick = { showMore = true }) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = "更多",
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                }
                DropdownMenu(
                    expanded = showMore,
                    onDismissRequest = { showMore = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("歌曲信息") },
                        onClick = {
                            showMore = false
                            showInfo = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("播放列表（${conn.queue.size}）") },
                        onClick = {
                            showMore = false
                            showQueue = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("投屏") },
                        onClick = {
                            showMore = false
                            showDlna = true
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 中间：唱片页 / 歌词页，上下滑动或点击切换 ──────────
        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 点一下整页就翻面：唱片 ↔ 歌词
                .clickable {
                    scope.launch {
                        if (pagerState.currentPage == 0) pagerState.animateScrollToPage(1)
                        else pagerState.animateScrollToPage(0)
                    }
                }
        ) { page ->
            if (page == 0) {
                DiscPage(
                    title = title,
                    // 投屏时手机自己是暂停的，唱片跟着电视转才不别扭
                    rotating = if (casting != null) dlnaVm.castPlaying else conn.playing,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LyricsPage(
                    vm = vm,
                    position = showPos,
                    // 投屏时点歌词要 seek 电视，不是手机
                    onSeek = { if (casting != null) dlnaVm.seekCast(it) else conn.seekTo(it) },
                    onManualSearch = { showLyricsSearch = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

            // ── 歌名 / 歌手 ───────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.ifBlank { "未在播放" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // 格式徽章，放网易云那个 VIP 标的位置
                formatBadge(track?.fileName)?.let { badge ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE8C86A),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (artist.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(16.dp))

            // 投屏时给个明显的提示：手机这时候只是遥控器，控制权在电视上
            if (casting != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFE64A4A).copy(alpha = 0.16f))
                        .clickable { dlnaVm.stopCasting() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Cast,
                        contentDescription = null,
                        tint = Color(0xFFFFCDD2),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在控制「${casting.friendlyName}」· 点此停止",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFCDD2)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // 投屏出问题时得说出来，不然用户只看到「拖了没反应」
            dlnaVm.error?.let { msg ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF3A3A3A))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFCDD2),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "知道了",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clickable { dlnaVm.clearError() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── 进度条 ────────────────────────────────────────
            PlayerSeekBar(
                positionMs = showPos,
                durationMs = showDur,
                onSeek = { if (casting != null) dlnaVm.seekCast(it) else conn.seekTo(it) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatTime(showPos),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Text(
                    formatTime(showDur),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── 主控制栏 ──────────────────────────────────────
            // 投屏时按钮全部转发给设备 —— 手机自己是暂停的，
            // 用户点播放期望的是电视出声
            PlayerControlBar(
                playing = if (casting != null) dlnaVm.castPlaying else conn.playing,
                repeatMode = conn.repeatMode,
                onToggle = { if (casting != null) dlnaVm.toggleCast() else conn.toggle() },
                onPrevious = { if (casting != null) dlnaVm.previousCast() else conn.previous() },
                onNext = { if (casting != null) dlnaVm.nextCast() else conn.next() },
                onCycleRepeat = { conn.cycleRepeatMode() },
                onShowQueue = { showQueue = true },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            // ── 底部功能栏 ────────────────────────────────────
            PlayerBottomBar(
                onCast = { showDlna = true },
                onLyrics = {
                    scope.launch {
                        if (onLyricsPage) pagerState.animateScrollToPage(0)
                        else pagerState.animateScrollToPage(1)
                    }
                },
                onInfo = { showInfo = true },
                onSettings = { onOpenSettings() },
                onMore = { showMore = true },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDlna) {
        DlnaSheet(
            vm = dlnaVm,
            currentTrack = conn.currentTrack,
            onDismiss = { showDlna = false }
        )
    }

    if (showLyricsSearch) {
        LyricsSearchDialog(
            initialTitle = title,
            initialArtist = artist,
            onDismiss = { showLyricsSearch = false },
            onConfirm = { t, a ->
                showLyricsSearch = false
                vm.manualSearchLyrics(t, a)
            }
        )
    }

    if (showInfo && track != null) {
        TrackInfoDialog(track = track, onDismiss = { showInfo = false })
    }

    if (showQueue) {
        PlayQueueSheet(
            queue = conn.queue,
            currentKey = track?.key,
            onPick = { conn.jumpTo(it) },
            onDismiss = { showQueue = false }
        )
    }
}
