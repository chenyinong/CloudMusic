package com.cloudmusic.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudmusic.app.App
import com.cloudmusic.app.data.Track

/**
 * 封面页：全出血封面卡片（iTunes 查到的真封面），
 * 查不到时用程序生成的装饰画顶上，风格跟背景取色一脉相承。
 * 左上=来源角标，左下=歌手，右下=队列序号。
 */
@Composable
fun CoverPage(
    title: String,
    artist: String,
    coverUrl: String?,
    sourceLabel: String,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // 尽量占满宽度；高度不够就按高度收成正方形
        val side = minOf(maxWidth, maxHeight - 24.dp).coerceAtLeast(200.dp)

        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            modifier = Modifier.size(side)
        ) {
            Box(Modifier.fillMaxSize()) {
                val bmp = rememberCoverBitmap(coverUrl)
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 没封面/还没下完：程序生成的装饰画，别让卡片空着
                    GeneratedCover(seed = title.ifBlank { "?" }, modifier = Modifier.fillMaxSize())
                }

                // 上下角文字的可读性底：顶部和底部各一层淡黑渐隐
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.20f),
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.28f),
                                )
                            )
                        )
                )

                // 左上：来源 + 音质
                Text(
                    sourceLabel,
                    fontSize = 11.sp,
                    letterSpacing = 2.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 20.dp, top = 18.dp)
                )
                // 左下：歌手（衬线 + 字距，像专辑封面的题字）
                Text(
                    (artist.ifBlank { title }).ifBlank { "未知歌手" },
                    fontSize = 19.sp,
                    letterSpacing = 3.sp,
                    fontFamily = FontFamily.Serif,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 20.dp, bottom = 18.dp, end = 90.dp)
                )
                // 右下：队列序号（不在队列里就不显示）
                if (index > 0 && total > 0) {
                    Text(
                        "%02d / %02d".format(index, total),
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 18.dp)
                    )
                }
            }
        }
    }
}

/** 下载封面位图。仓库里带 LruCache，来回切歌不重复下载。 */
@Composable
private fun rememberCoverBitmap(url: String?): ImageBitmap? {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val app = LocalContext.current.applicationContext as? App
    LaunchedEffect(url) {
        if (url.isNullOrBlank() || app == null) {
            bmp = null
            return@LaunchedEffect
        }
        app.coverRepository.artwork(url)?.let { bmp = it.asImageBitmap() }
    }
    return bmp
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
    val offsetMs = vm.lyricsOffsetMs
    val index = remember(lyrics, position, offsetMs) {
        lyrics?.indexAt(position + offsetMs) ?: -1
    }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            lyrics == null || lyrics.lines.isEmpty() -> Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    state.error ?: "暂无歌词",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "把同名 .lrc 放在歌曲同目录，或在设置里开启在线歌词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onManualSearch) {
                    Text("手动搜索歌词", color = MaterialTheme.colorScheme.primary)
                }
            }

            else -> LazyColumn(
                state = listState,
                // 首尾留出空白：第一行/最后一行也能滚到中间，而不是贴着边缘被切断
                contentPadding = PaddingValues(vertical = 96.dp),
                modifier = Modifier
                    .fillMaxSize()
                    // 上下渐隐：靠近边缘的歌词按 alpha 淡出，不再硬裁成半行字。
                    // 用 DstIn 只改透明度，后面那层氛围渐变背景能原样透出来。
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.12f to Color.Black,
                                0.88f to Color.Black,
                                1f to Color.Transparent
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
            ) {
                itemsIndexed(lyrics.lines) { i, line ->
                    val active = i == index
                    Text(
                        text = line.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSeek(
                                    (line.timeMs - (lyrics.offsetMs + offsetMs)).coerceAtLeast(0L)
                                )
                            }
                            .padding(vertical = 8.dp),
                        fontSize = if (active) 19.sp else 15.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 歌曲信息弹窗。
 *
 * 不套 Material 的 AlertDialog 了：它只有纯色底、样式改不动。
 * 自己用 Dialog 画，跟投屏面板同一套氛围渐变（跟着这首歌的封面色），
 * 排版也按 QQ 风格：居中标题 + 淡分隔线 + 两列信息 + 底部小胶囊按钮。
 */
@Composable
fun TrackInfoDialog(
    track: Track,
    onDismiss: () -> Unit,
    /** 从网盘删掉这首。传 null 就不显示这个按钮。 */
    onDelete: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .songAmbientBackground(track.title)
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Text(
                    "歌曲信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(Modifier.height(14.dp))

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                )

                Spacer(Modifier.height(8.dp))

                Column(Modifier.fillMaxWidth()) {
                    InfoRow("歌名", track.title)
                    InfoRow("歌手", track.artist)
                    InfoRow("专辑", track.album)
                    InfoRow("文件名", track.fileName)
                    InfoRow("格式", track.mime)
                    InfoRow("大小", "${track.size / 1024 / 1024} MB")
                    InfoRow("时长", formatTime((track.durationSec * 1000).toLong()))
                    InfoRow("位置", track.folder)
                }

                if (onDelete != null) {
                    Spacer(Modifier.height(14.dp))
                    SmallPill("从网盘删除", MaterialTheme.colorScheme.error, onDelete)
                }

                Spacer(Modifier.height(8.dp))

                // 底部按钮：靠右，绿底小胶囊
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SmallPill("知道了", MaterialTheme.colorScheme.primary, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
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
        // M3 1.2 默认是 surfaceContainerLow（带主题色调 → 泛绿），换成纯白
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                "播放列表 · ${queue.size} 首",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            t.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
