package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
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
    /**
     * 「更多」菜单从哪儿弹出来的。
     *
     * 顶栏和底部各有一个 ⋮，菜单要浮在被点的那个按钮下面，
     * 所以记住来源，用一个值天然保证两个菜单不会同时开着。
     */
    var moreFrom by remember { mutableStateOf(0) } // 0=没开 1=顶栏 2=底部

    // 菜单内容：顶栏和底部共用一份
    val moreMenuContent: @Composable () -> Unit = {
        AppMenuHeader(title.ifBlank { "正在播放" })
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
        AppMenuItem(
            text = "歌曲信息",
            icon = Icons.Filled.Info
        ) {
            moreFrom = 0
            showInfo = true
        }
        AppMenuItem(
            text = "播放列表（${conn.queue.size}）",
            icon = Icons.Filled.QueueMusic
        ) {
            moreFrom = 0
            showQueue = true
        }
        AppMenuItem(
            text = "投屏",
            icon = Icons.Filled.Cast
        ) {
            moreFrom = 0
            showDlna = true
        }
    }

    // 唱片页 / 歌词页，左右滑动切换（跟 QQ 音乐一样横向翻）
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

    val bgColors = coverColors(title)

    /**
     * 从左边缘往右滑 = 返回上一层（跟 QQ 音乐一样）。
     *
     * 只在唱片页启用：歌词页往右滑是「翻回唱片页」，那个动作归 Pager 管。
     * 起点必须在屏幕最左边那一小条里，中间的横向拖动留给翻页。
     */
    val density = LocalDensity.current
    val edgeWidth = with(density) { 26.dp.toPx() }
    val edgeBackGesture = Modifier.pointerInput(pagerState.currentPage) {
        if (pagerState.currentPage != 0) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x > edgeWidth) return@awaitEachGesture
            var dragging = false
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!dragging && dx > edgeWidth * 0.7f && abs(dy) < dx * 0.5f) {
                    dragging = true
                }
                if (dragging) {
                    change.consume()
                    onBack()
                    break
                }
            } while (change.pressed)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(edgeBackGesture)
    ) {
        // QQ 音乐：顶部近白、底部一抹封面淡彩晕染（克制，不整页彩）
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Transparent,
                        bgColors[0].copy(alpha = 0.45f)
                    )
                )
            )
        )
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
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
            }
            // QQ 音乐顶栏中间：唱片/歌词分页指示点（当前页是深色长条）
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(2) { index ->
                    val active = pagerState.currentPage == index
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (active) 14.dp else 6.dp, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            )
                    )
                }
            }
            Box {
                IconButton(onClick = { moreFrom = 1 }) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                AppDropdownMenu(
                    expanded = moreFrom == 1,
                    onDismissRequest = { moreFrom = 0 }
                ) {
                    moreMenuContent()
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 中间：唱片页 / 歌词页，左右滑动或点击切换 ──────────
        val pagerInteraction = remember { MutableInteractionSource() }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 点一下整页就翻面：唱片 ↔ 歌词（去掉点击涟漪那个灰框）
                .clickable(
                    interactionSource = pagerInteraction,
                    indication = null
                ) {
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

        // ── 歌名 + 心形（QQ 音乐：卡片下方，歌名左、心形右）─────
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title.ifBlank { "未在播放" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.FavoriteBorder,
                contentDescription = "喜欢",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        // 歌手 + 灰色胶囊标签（+ / 音质）
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                artist.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "+",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
            formatBadge(track?.fileName)?.let { badge ->
                Spacer(Modifier.width(6.dp))
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        // 下面一行灰字：专辑名（QQ 是「改编词：xxx」那种淡注）
        track?.album?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

            // 投屏时给个明显的提示：手机这时候只是遥控器，控制权在电视上
            if (casting != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFE64A4A).copy(alpha = 0.12f))
                        .clickable { dlnaVm.stopCasting() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Cast,
                        contentDescription = null,
                        tint = Color(0xFFC62828),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在控制「${casting.friendlyName}」· 点此停止",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFC62828)
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
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "知道了",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { dlnaVm.clearError() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── 功能栏（QQ 音乐：进度条上面那排）───────────────
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
                onMore = { moreFrom = 2 },
                modifier = Modifier.fillMaxWidth(),
                // 菜单挂在「更多」这一格自己身上，才会浮在按钮下面
                moreMenu = {
                    AppDropdownMenu(
                        expanded = moreFrom == 2,
                        onDismissRequest = { moreFrom = 0 }
                    ) {
                        moreMenuContent()
                    }
                }
            )

            Spacer(Modifier.height(10.dp))

            // ── 进度条 ────────────────────────────────────────
            PlayerSeekBar(
                positionMs = showPos,
                durationMs = showDur,
                onSeek = { if (casting != null) dlnaVm.seekCast(it) else conn.seekTo(it) },
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatTime(showPos),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatTime(showDur),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // 底部留白：QQ 音乐的控制栏离屏幕底还有一小段，别贴着
            Spacer(Modifier.height(44.dp))
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
