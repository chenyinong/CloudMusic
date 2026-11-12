package com.cloudmusic.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.sp
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
    var showLyricsOffset by remember { mutableStateOf(false) }
    var showDlna by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    /**
     * 「更多」菜单从哪儿弹出来的。
     *
     * 顶栏和底部各有一个 ⋮，菜单要浮在被点的那个按钮下面，
     * 所以记住来源，用一个值天然保证两个菜单不会同时开着。
     */
    var moreFrom by remember { mutableStateOf(0) } // 0=没开 1=顶栏 2=底部

    // 菜单内容：顶栏和底部共用一份
    val moreMenuContent: @Composable () -> Unit = {
        AppMenuHeader(
            title.ifBlank { "正在播放" },
            subtitle = artist.ifBlank { null },
            coverSeed = if (title.isNotBlank()) title else "music",
            track = track
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
        AppMenuItem(
            text = "歌曲信息",
            icon = Icons.Outlined.Info
        ) {
            moreFrom = 0
            showInfo = true
        }
        AppMenuItem(
            text = "播放列表（${conn.queue.size}）",
            icon = Icons.Outlined.QueueMusic
        ) {
            moreFrom = 0
            showQueue = true
        }
        AppMenuItem(
            text = "投屏",
            icon = Icons.Outlined.Cast
        ) {
            moreFrom = 0
            showDlna = true
        }
        AppMenuItem(
            text = "定时关闭",
            icon = Icons.Outlined.Schedule
        ) {
            moreFrom = 0
            showSleep = true
        }
        AppMenuItem(
            text = "重新匹配歌词",
            icon = Icons.Outlined.Refresh
        ) {
            moreFrom = 0
            vm.refreshLyrics()
        }
        AppMenuItem(
            text = "手动搜索歌词",
            icon = Icons.Outlined.Search
        ) {
            moreFrom = 0
            showLyricsSearch = true
        }
        AppMenuItem(
            text = "歌词时间校准",
            icon = Icons.Outlined.Schedule
        ) {
            moreFrom = 0
            showLyricsOffset = true
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

    // 氛围背景：优先从封面取一个主色，封面还没查到（或黑白封面）时回落歌名调色板；
    // 换歌时颜色平滑过渡，不跳变
    val bgBase = vm.coverThemeColor ?: coverColors(title)[0]
    val bgTop by animateColorAsState(bgBase, tween(durationMillis = 600), label = "bgTop")

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
        // 氛围背景：封面页和歌词页统一用整页淡彩（顶部很淡、越往下越足）
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to bgTop.copy(alpha = 0.14f),
                    0.42f to bgTop.copy(alpha = 0.26f),
                    1f to bgTop.copy(alpha = 0.72f)
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
                // 封面左上角的来源角标：网盘直连/在线音源 + 音质
                val sourceLabel =
                    (if (track?.provider == "online") "在线音源" else "网盘直连") +
                        " · " + (formatBadge(track?.fileName) ?: "原音质")
                // 右下角队列序号（从 1 开始；不在队列里传 0 就不显示）
                val idx = conn.queue.indexOfFirst { it.key == track?.key }
                CoverPage(
                    title = title,
                    artist = artist,
                    coverUrl = vm.coverUrl,
                    sourceLabel = sourceLabel,
                    index = if (idx >= 0) idx + 1 else 0,
                    total = conn.queue.size,
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

        // ── 歌名 + 心形：放不下就跑马灯滚，不再折成两行撑高标题区 ─────
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title.ifBlank { "未在播放" },
                style = MaterialTheme.typography.titleLarge.copy(lineHeight = 28.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee()
            )
            Spacer(Modifier.width(14.dp))
            // 小红心：点一下就把这首歌收藏到云端，网页版同步可见。
            // 之前这是个纯装饰的图标，看着能点其实点了没反应。
            val favOn = track != null && vm.isCloudFavorite(track)
            IconButton(
                onClick = {
                    val t = track
                    if (t != null) vm.toggleCloudFavorite(t)
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    if (favOn) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (favOn) "取消云端收藏" else "收藏到云端",
                    tint = if (favOn) Color(0xFFE8546F)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        // 歌手 + 音质标签：原来那个裸「+」字符没有交互，只会让这行变吵，去掉
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
            formatBadge(track?.fileName)?.let { badge ->
                Spacer(Modifier.width(8.dp))
                // 不要胶囊底了：整页淡彩背景上那块灰底很突兀，跟歌手同字号淡灰就行
                Text(
                    badge,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 专辑名更淡、贴紧歌手，两行合成一个信息块
        track?.album?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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
                playMode = conn.playMode,
                onToggle = { if (casting != null) dlnaVm.toggleCast() else conn.toggle() },
                onPrevious = { if (casting != null) dlnaVm.previousCast() else conn.previous() },
                onNext = { if (casting != null) dlnaVm.nextCast() else conn.next() },
                onSelectMode = { conn.applyPlayMode(it) },
                onShowQueue = { showQueue = true },
                modifier = Modifier.fillMaxWidth()
            )

            if (conn.sleepMode != 0) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showSleep = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (conn.sleepMode == 1) "定时关闭 · ${formatTime((conn.sleepRemainingSec * 1000L).coerceAtLeast(0))}"
                        else "播完当前歌曲后关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "取消",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
            state = vm.lyricsSearchState,
            onSearch = { t, a -> vm.searchLyricsCandidates(t, a) },
            onSelect = { vm.selectLyricsCandidate(it) },
            onAutoMatch = { t, a ->
                showLyricsSearch = false
                vm.clearLyricsSearch()
                vm.manualSearchLyrics(t, a)
            },
            onDismiss = {
                showLyricsSearch = false
                vm.clearLyricsSearch()
            }
        )
    }

    if (showLyricsOffset) {
        LyricsOffsetDialog(
            offsetMs = vm.lyricsOffsetMs,
            onAdjust = { vm.adjustLyricsOffset(it) },
            onReset = { vm.resetLyricsOffset() },
            onDismiss = { showLyricsOffset = false }
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

    if (showSleep) {
        val sleepSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSleep = false },
            sheetState = sleepSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    "定时关闭",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    SleepSheetRow("$m 分钟后关闭") { conn.startSleep(m); showSleep = false }
                }
                SleepSheetRow("播完当前歌曲后关闭") { conn.startSleepAfterTrack(); showSleep = false }
                if (conn.sleepMode != 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    SleepSheetRow("取消定时", danger = true) { conn.cancelSleep(); showSleep = false }
                }
            }
        }
    }
}

@Composable
private fun SleepSheetRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
