package com.cloudmusic.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * mini 条和 TAB 栏共用的毛玻璃参数：清透白玻璃（深色模式换深玻璃）。
 * HazeMaterials.thin 是 Android 12 材质风格，自带深色 tint + 噪点，
 * 在浅色页上发灰发脏，所以手动调；两份样式必须一致，不然两条玻璃会一大一小两种白。
 */
@Composable
private fun frostedGlassStyle(): HazeStyle {
    return if (isSystemInDarkTheme()) {
        HazeStyle(
            backgroundColor = Color(0xFF121418),
            tints = listOf(HazeTint(Color(0xFF1C2026).copy(alpha = 0.38f))),
            blurRadius = 22.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(Color(0xFF1C2026).copy(alpha = 0.85f)),
        )
    } else {
        HazeStyle(
            backgroundColor = Color.White,
            tints = listOf(HazeTint(Color.White.copy(alpha = 0.32f))),
            blurRadius = 22.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(Color.White.copy(alpha = 0.8f)),
        )
    }
}

/**
 * 底部「正在播放」悬浮条 —— 网易云那个样式。
 *
 * 有歌在播的时候常驻在页面底部：左边一张会转的唱片封面，
 * 中间歌名 + 歌手，右边上一首 / 播放暂停 / 下一首 / 播放队列。点歌名区域进播放页。
 *
 * 进度是自己 tick 的 —— 读 `player.positionMs` 会让整个页面跟着每 400ms
 * 重组一遍，这里只读 controller 的实时位置，更新范围就剩这一条。
 *
 * @param dlna 投屏状态机。投屏时控制权在电视身上，这几个按钮转发给设备，
 *   显示的也是设备在放的那首 —— 不能让用户点「下一首」结果只有手机换歌了。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MiniPlayerBar(
    player: PlayerConnection,
    onOpenPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    dlna: DlnaViewModel? = null,
    /** 毛玻璃模糊源（Root 提供内容区的 HazeState）。null 时退化为普通白卡。 */
    hazeState: HazeState? = null,
) {
    val casting = dlna?.casting
    val title = if (casting != null) dlna.castTitle else player.currentTitle
    val artist = if (casting != null) dlna.castArtist else player.currentArtist
    val playing = if (casting != null) dlna.castPlaying else player.playing
    // 队列里只有一首歌的时候，切歌按钮就没意义
    val canSwitch = player.queue.size > 1

    // 点击进歌词页但不带涟漪：毛玻璃上按下会留一块灰蒙蒙的高亮，看着像阴影
    val noIndication = remember { MutableInteractionSource() }

    // 左右滑切歌：跟翻页一致，左滑下一首、右滑上一首。
    // 阈值放低一点，短促一划就能切；方向取手势中的最大位移，来回滑也不会相互抵消。
    // 一次手势只切一次，点击和竖滑都不受影响。
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 24.dp.toPx() }
    // 滑动时内容跟手轻微位移，松手弹回，让「这一划真的切了歌」有反馈
    val swipeOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val swipeModifier = Modifier.pointerInput(player, dlna, canSwitch, casting != null) {
        var travelled = 0f
        var peak = 0f
        var switched = false
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f; peak = 0f; switched = false },
            onHorizontalDrag = { _, dx ->
                if (canSwitch || casting != null) {
                    travelled += dx
                    if (abs(travelled) > abs(peak)) peak = travelled
                    // 阻尼 0.3 且封顶，别把整张卡片拖出屏幕
                    scope.launch {
                        swipeOffset.snapTo((travelled * 0.3f).coerceIn(-48f, 48f))
                    }
                    if (!switched && abs(peak) >= swipeThreshold) {
                        switched = true
                        if (peak < 0) {
                            // 左滑：下一首
                            if (casting != null) dlna.nextCast() else player.next()
                        } else {
                            // 右滑：上一首
                            if (casting != null) dlna.previousCast() else player.previous()
                        }
                    }
                }
            },
            onDragEnd = { scope.launch { swipeOffset.animateTo(0f) } },
            onDragCancel = { scope.launch { swipeOffset.animateTo(0f) } }
        )
    }

    // 封面旋转：网易云那个小唱片一直在转，暂停才停
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    // withFrameNanos 给的是纳秒
                    val dt = (now - last) / 1_000_000_000f
                    angle = (angle + dt * 30f) % 360f
                }
                last = now
            }
        }
    }

    // 底部那条细进度线。自己画，别用 LinearProgressIndicator ——
    // 那个组件在 Material3 里两个重载签名不一样，版本一换就编译不过。
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player.currentTrack?.key, playing, casting != null) {
        while (true) {
            progress = if (casting != null) {
                val d = dlna.castDurationMs
                if (d > 0) (dlna.castPositionMs.toFloat() / d).coerceIn(0f, 1f) else 0f
            } else {
                val c = player.controller
                val d = c?.duration ?: 0L
                val p = c?.currentPosition ?: 0L
                if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
            }
            delay(500)
        }
    }

    // 磨砂玻璃：背后内容实时模糊（haze，API 31+ 完整效果；低版本退化成半透明）
    val isDark = isSystemInDarkTheme()
    val glassStyle = frostedGlassStyle()
    // 浮岛形态：四角都圆，左右和底部留边距，盖在内容上面像一块悬浮的玻璃卡片；
    // 背后仍是 haze 毛玻璃实时模糊。
    val barShape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 8.dp)
            .then(swipeModifier)
            .then(
                if (hazeState != null) {
                    Modifier.hazeChild(
                        state = hazeState,
                        shape = barShape,
                        style = glassStyle,
                    )
                } else {
                    Modifier
                }
            ),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        shape = barShape,
    ) {
        Box {
            // 玻璃高光：顶部略亮往下渐隐，白玻璃的厚度感
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            if (isDark) listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
                            else listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.04f))
                        )
                    )
            )
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = if (isDark) 0.10f else 0.30f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .padding(start = 14.dp, end = 10.dp)
                        .graphicsLayer { translationX = swipeOffset.value },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverThumb(
                        track = player.currentTrack,
                        seed = player.currentTrack?.album?.takeIf { it.isNotBlank() } ?: title,
                        size = 40.dp,
                        corner = 20.dp,
                        // 封面也能点 —— 跟歌名区域一样进歌词页
                        modifier = Modifier
                            .rotate(angle)
                            .clickable(
                                interactionSource = noIndication,
                                indication = null,
                                onClick = onOpenPlayer
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        // 切歌时旧歌名淡出上移、新歌名淡入上移，不要硬生生跳字
                        AnimatedContent(
                            targetState = title to artist,
                            modifier = Modifier.fillMaxWidth(),
                            transitionSpec = {
                                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 }) togetherWith
                                    (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 })
                            },
                            label = "miniTitle"
                        ) { (t, a) ->
                            Text(
                                // 参考图是「歌名 - 歌手」单行粗体；放不下不省略号，
                                // 改跑马灯来回滚，歌手名也能看全
                                if (a.isBlank()) t.ifBlank { "未知歌曲" }
                                else "${t.ifBlank { "未知歌曲" }} - $a",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee()
                                    .clickable(
                                        interactionSource = noIndication,
                                        indication = null,
                                        onClick = onOpenPlayer
                                    ),
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // 播放/暂停：白底圆形按钮（参考图样式）
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color.White.copy(alpha = 0.12f) else Color.White)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                CircleShape
                            )
                            .clickable {
                                if (casting != null) dlna.toggleCast() else player.toggle()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = if (isDark) Color.White else Color(0xFF1B1B1B),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    MiniIconButton(
                        icon = Icons.Filled.SkipNext,
                        description = "下一首",
                        enabled = canSwitch,
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            if (casting != null) dlna.nextCast() else player.next()
                        }
                    )
                    MiniIconButton(
                        icon = Icons.Filled.QueueMusic,
                        description = "播放列表",
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = onOpenQueue
                    )
                }
            }
        }
    }
}

/** 迷你条上的小图标按钮。 */
@Composable
private fun MiniIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = if (enabled) tint else tint.copy(alpha = 0.35f),
        modifier = modifier
            .size(34.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp)
    )
}

/** 底部导航栏的一个页签。 */
data class BottomTab(
    val label: String,
    val icon: ImageVector,
    val screen: Screen,
)

/** 底部导航栏的页签（跟网易云那种一排图标一个文字）。 */
val BOTTOM_TABS = listOf(
    BottomTab("我的", Icons.Filled.MusicNote, Screen.LIBRARY),
    BottomTab("歌单", Icons.Filled.LibraryMusic, Screen.PLAYLIST_LIST),
    BottomTab("搜歌", Icons.Filled.Search, Screen.IMPORT),
    BottomTab("设置", Icons.Filled.Settings, Screen.SETTINGS),
)

/**
 * 二级页面归到它所属的那个页签。
 *
 * 不然在「歌单详情」里底部一排图标全都不亮，看着像没选中任何东西。
 */
fun tabOf(screen: Screen): Screen = when (screen) {
    Screen.PLAYLIST_DETAIL, Screen.PLAYLIST_IMPORT, Screen.CLOUD_PLAYLIST_DETAIL -> Screen.PLAYLIST_LIST
    Screen.QUARK_LOGIN, Screen.QUARK_DIAG, Screen.CLOUD_LOGIN -> Screen.SETTINGS
    else -> screen
}

/**
 * 底部导航栏。
 *
 * 网易云那个样子：纯图标 + 小字，选中的那个染成主题色。
 * 没做成 Material3 的 NavigationBar —— 那个默认 80dp 高，
 * 加上上面的迷你播放器就把屏幕占掉太多。
 *
 * 和 mini 条共用同一套毛玻璃：同样的四角圆角浮岛、同样的模糊和 tint、
 * 无投影无描边，两条玻璃看起来是一组。
 */
@Composable
fun BottomTabBar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    /** 毛玻璃模糊源（和 mini 条同一个 HazeState）。null 时退化为普通底色。 */
    hazeState: HazeState? = null,
) {
    val isDark = isSystemInDarkTheme()
    val barShape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 8.dp)
            .then(
                if (hazeState != null) {
                    Modifier.hazeChild(
                        state = hazeState,
                        shape = barShape,
                        style = frostedGlassStyle(),
                    )
                } else {
                    Modifier
                }
            ),
        color = if (hazeState != null) Color.Transparent else MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        shape = barShape,
    ) {
        Box {
            if (hazeState != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                if (isDark) listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)
                                else listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.04f))
                            )
                        )
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    // 先把系统手势条那块 inset 撑出来，再定内容高度 ——
                    // 不然图标就压在导航条上（顺序反了的话 inset 会被 height 吃掉）
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(54.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
            BOTTOM_TABS.forEach { tab ->
                val selected = current == tab.screen
                val tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                val interactionSource = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab
                        ) { onSelect(tab.screen) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier.size(21.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint
                    )
                }
            }
            }
        }
    }
}
