package com.cloudmusic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

/**
 * 网易云风格的进度条：一条细线 + 一个圆点，能拖。
 *
 * 用 Material 的 Slider 改不出这个味 —— 它的轨道太粗、拇指太大。
 */
@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // 不写死绿色：没传就跟着主题的中性主色走
    accent: Color = Color.Unspecified,
) {
    val accentColor = if (accent == Color.Unspecified) MaterialTheme.colorScheme.primary else accent
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(30.dp)
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val progress = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f

        // 拖动时用手指位置，不跟播放进度抢
        var dragging by remember { mutableStateOf<Float?>(null) }
        val shown = dragging ?: progress

        val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        val knobColor = MaterialTheme.colorScheme.onSurface

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(durationMs, widthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { off ->
                            dragging = (off.x / widthPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragging?.let { onSeek((it * durationMs).toLong()) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                        onHorizontalDrag = { change, _ ->
                            dragging = (change.position.x / widthPx).coerceIn(0f, 1f)
                        }
                    )
                }
                .pointerInput(durationMs, widthPx) {
                    detectTapGestures { off ->
                        val f = (off.x / widthPx).coerceIn(0f, 1f)
                        onSeek((f * durationMs).toLong())
                    }
                }
        ) {
            val cy = size.height / 2f
            val knob = 9.dp.toPx()
            val left = knob
            val right = size.width - knob
            val track = 2.dp.toPx()

            // 底轨
            drawLine(
                color = trackColor,
                start = Offset(left, cy),
                end = Offset(right, cy),
                strokeWidth = track,
                cap = StrokeCap.Round,
            )
            // 已播放
            val end = left + (right - left) * shown
            drawLine(
                color = accentColor,
                start = Offset(left, cy),
                end = Offset(end, cy),
                strokeWidth = track,
                cap = StrokeCap.Round,
            )
            // 圆点
            drawCircle(color = knobColor, radius = knob * 0.55f, center = Offset(end, cy))
        }
    }
}

/**
 * 网易云风格的主控制栏：循环 / 上一首 / 播放 / 下一首 / 播放列表。
 */
@Composable
fun PlayerControlBar(
    playing: Boolean,
    repeatMode: Int,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    onShowQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCycleRepeat, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = "循环模式",
                tint = if (repeatMode == Player.REPEAT_MODE_OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(22.dp)
            )
        }

        IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = "上一首",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(34.dp)
            )
        }

        // 播放 / 暂停：黑色大圆 + 白三角（QQ 音乐样式）
        Box(
            Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(70.dp)) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "下一首",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(34.dp)
            )
        }

        IconButton(onClick = onShowQueue, modifier = Modifier.size(42.dp)) {
            Icon(
                Icons.Filled.QueueMusic,
                contentDescription = "播放列表",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 网易云风格底部功能栏：投屏 / 歌词 / 信息 / 设置 / 更多。
 */
@Composable
fun PlayerBottomBar(
    onCast: () -> Unit,
    onLyrics: () -> Unit,
    onInfo: () -> Unit,
    onSettings: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 「更多」按钮旁边挂的菜单。
     *
     * 菜单必须挂在按钮自己身上（Popup 以所在位置为锚点），
     * 否则点底部的按钮、菜单却跑到顶栏那儿去了。
     */
    moreMenu: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomBarItem(Icons.Filled.Cast, "投屏", onCast)
        BottomBarItem(Icons.Filled.Lyrics, "歌词", onLyrics)
        BottomBarItem(Icons.Filled.Info, "信息", onInfo)
        BottomBarItem(Icons.Filled.Settings, "设置", onSettings)
        BottomBarItem(Icons.Filled.MoreHoriz, "更多", onMore, trailing = moreMenu)
    }
}

/** 底部功能栏的一格：图标 + 下面一行小字。 */
@Composable
private fun BottomBarItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(CircleShape)
            .padding(4.dp)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 菜单放在文字下面，这样 Popup 的锚点就在这一格的底部，菜单正好浮在按钮下方
        trailing?.invoke()
    }
}
