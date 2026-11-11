package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudmusic.app.data.Track

/**
 * 通用歌曲行，两种形态：
 * - 默认（网易云）：方形小封面 + 歌名 + 灰色歌手 + 细分隔线；
 * - 传了 [index]（QQ 收藏列表）：左边是**序号**不是封面，歌手后面跟灰色小胶囊，
 *   一般配 showDivider = false。
 *
 * @param coverSeed 封面的占位种子（一般用专辑名，没有就用歌名）
 * @param playing 正在放这首 —— 歌名/序号染主题色，封面右下角挂个播放角标
 * @param index 序号（从 1 开始）；null 表示走封面形态
 * @param badge 歌手后面的灰色小胶囊（比如 FLAC / MP3）
 * @param showDivider 是否画底部分隔线
 * @param trailing 右侧插槽，放操作按钮用
 * @param note 第三行的补充说明（比如导入失败的原因），有就显示
 * @param noteColor 补充说明的颜色；不传走 error 红，搜歌页的试听提示传中性灰
 */
@Composable
fun SongRow(
    coverSeed: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    note: String = "",
    noteColor: Color? = null,
    index: Int? = null,
    badge: String? = null,
    showDivider: Boolean = true,
    showCover: Boolean = true,
    /** 有曲目就查 iTunes 真封面，查不到自动回落到渐变占位画 */
    coverTrack: Track? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (index != null) {
                // QQ 风格：序号代替封面
                Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        "$index",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (playing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            } else if (showCover) {
                Box(Modifier.size(56.dp)) {
                    CoverThumb(
                        track = coverTrack,
                        seed = coverSeed,
                        size = 56.dp,
                        corner = 10.dp
                    )
                    if (playing) {
                        // 正在放的这首，封面右下角挂个标记
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(if (index != null) 8.dp else if (showCover) 14.dp else 2.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (playing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (subtitle.isNotBlank() || badge != null) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (badge != null) {
                            Spacer(Modifier.width(6.dp))
                            // 纯文字淡灰，和副标题同字号同色，不再用胶囊底
                            Text(
                                badge,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (note.isNotBlank()) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = noteColor ?: MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
        }
        if (showDivider) {
            // 分隔线从封面右边开始画
            HorizontalDivider(
                modifier = Modifier.padding(start = 86.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        }
    }
}
