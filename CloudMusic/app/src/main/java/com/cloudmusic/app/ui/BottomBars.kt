package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay

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

    // 磨砂玻璃：背后内容实时模糊（haze，API 31+ 完整效果；低版本退化成半透明），
    // 内侧 1dp 高光描边出玻璃边。深浅模式 HazeMaterials 按背景色自动配。
    val isDark = isSystemInDarkTheme()
    val glassStroke = if (isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.6f)
    Surface(
        // 浮岛：左右和底部留边距，四角都圆，盖在内容上面像一块悬浮的玻璃卡片
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 8.dp)
            .then(
                if (hazeState != null) {
                    Modifier.hazeChild(
                        state = hazeState,
                        shape = RoundedCornerShape(18.dp),
                        style = HazeMaterials.thin(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                    )
                } else {
                    Modifier
                }
            )
            .border(1.dp, glassStroke, RoundedCornerShape(18.dp)),
        color = Color.Transparent,
        // 色调叠加会发绿，这里只留投影
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
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
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GradientCover(
                    seed = player.currentTrack?.album?.takeIf { it.isNotBlank() } ?: title,
                    size = 42.dp,
                    corner = 21.dp,
                    // 封面也能点 —— 跟歌名区域一样进歌词页
                    modifier = Modifier
                        .rotate(angle)
                        .clickable(onClick = onOpenPlayer)
                )
                Spacer(Modifier.width(10.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenPlayer)
                ) {
                    Text(
                        title.ifBlank { "未知歌曲" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        artist.ifBlank { "未知歌手" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MiniIconButton(
                    icon = Icons.Filled.SkipPrevious,
                    description = "上一首",
                    enabled = canSwitch,
                    onClick = {
                        if (casting != null) dlna.previousCast() else player.previous()
                    }
                )
                MiniIconButton(
                    icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    description = if (playing) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        if (casting != null) dlna.toggleCast() else player.toggle()
                    }
                )
                MiniIconButton(
                    icon = Icons.Filled.SkipNext,
                    description = "下一首",
                    enabled = canSwitch,
                    onClick = {
                        if (casting != null) dlna.nextCast() else player.next()
                    }
                )
                MiniIconButton(
                    icon = Icons.Filled.QueueMusic,
                    description = "播放列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onOpenQueue
                )
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
    Screen.PLAYLIST_DETAIL, Screen.PLAYLIST_IMPORT -> Screen.PLAYLIST_LIST
    Screen.QUARK_LOGIN, Screen.QUARK_DIAG -> Screen.SETTINGS
    else -> screen
}

/**
 * 底部导航栏。
 *
 * 网易云那个样子：纯图标 + 小字，选中的那个染成主题色。
 * 没做成 Material3 的 NavigationBar —— 那个默认 80dp 高，
 * 加上上面的迷你播放器就把屏幕占掉太多。
 */
@Composable
fun BottomTabBar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        // 和列表页用同一个底色；tonalElevation 必须为 0 ——
        // M3 的 Surface 会把主题色按高度叠上去，一叠就发绿，跟页面不搭
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
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
