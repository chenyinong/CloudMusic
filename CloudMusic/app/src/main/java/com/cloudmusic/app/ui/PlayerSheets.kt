package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudmusic.app.data.Track

/** 唱片页：黑胶 + 唱臂，下面挂一条音质胶囊。 */
@Composable
fun DiscPage(
    title: String,
    rotating: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // 小屏别让唱片顶出去 —— 按可用高度缩
        val maxH = maxHeight
        val disc = minOf(
            (maxH - 56.dp).coerceIn(160.dp, 300.dp),
            (maxWidth - 40.dp).coerceIn(160.dp, 300.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VinylDisc(title = title, rotating = rotating, discSize = disc.value.toInt())
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "网盘直连 · 原音质",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/** 歌词页：跟网易云一样，当前行放大加粗居中。 */
@Composable
fun LyricsPage(
    vm: AppViewModel,
    position: Long,
    onSeek: (Long) -> Unit,
    onManualSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = vm.lyricsState
    val lyrics = state.lyrics
    val index = remember(lyrics, position) { lyrics?.indexAt(position) ?: -1 }
    val listState = rememberLazyListState()

    LaunchedEffect(index, lyrics) {
        if (index >= 0) {
            val info = listState.layoutInfo
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val offset = if (viewport > 0) -(viewport / 2) else 0
            runCatching { listState.animateScrollToItem(index, offset) }
        }
    }

    Box(modifier.padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        when {
            state.loading -> Text(
                "正在加载歌词…",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f)
            )

            lyrics == null || lyrics.lines.isEmpty() -> Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    state.error ?: "暂无歌词",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "把同名 .lrc 放在歌曲同目录，或在设置里开启在线歌词",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onManualSearch) {
                    Text("手动搜索歌词", color = Color.White.copy(alpha = 0.8f))
                }
            }

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lyrics.lines) { i, line ->
                    val active = i == index
                    Text(
                        text = line.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeek(line.timeMs) }
                            .padding(vertical = 8.dp),
                        fontSize = if (active) 19.sp else 15.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Color.White else Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** 歌曲信息弹窗。 */
@Composable
fun TrackInfoDialog(
    track: Track,
    onDismiss: () -> Unit,
    /** 从网盘删掉这首。传 null 就不显示这个按钮。 */
    onDelete: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌曲信息") },
        text = {
            Column {
                InfoRow("歌名", track.title)
                InfoRow("歌手", track.artist)
                InfoRow("专辑", track.album)
                InfoRow("文件名", track.fileName)
                InfoRow("格式", track.mime)
                InfoRow("大小", "${track.size / 1024 / 1024} MB")
                InfoRow("时长", formatTime((track.durationSec * 1000).toLong()))
                InfoRow("位置", track.folder)
                if (onDelete != null) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onDelete) {
                        Text("从网盘删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 播放队列：跟网易云那个「播放列表」一样。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayQueueSheet(
    queue: List<Track>,
    currentKey: String?,
    onPick: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF2A2A2A),
        contentColor = Color.White,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                "播放列表 · ${queue.size} 首",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                items(queue) { t ->
                    val active = t.key == currentKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(t) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            t.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) Color(0xFFE64A4A) else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            t.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(96.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 从文件名猜个格式徽章，网易云那个位置放 VIP 标，我们放 FLAC / WAV。 */
fun formatBadge(fileName: String?): String? {
    val name = fileName ?: return null
    return when (name.substringAfterLast('.', "").uppercase()) {
        "FLAC" -> "FLAC"
        "WAV" -> "WAV"
        "M4A" -> "M4A"
        "AAC" -> "AAC"
        "OGG" -> "OGG"
        else -> null
    }
}

/** 毫秒 → mm:ss */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = (ms / 1000).toInt()
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
