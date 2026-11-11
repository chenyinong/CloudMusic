package com.cloudmusic.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudmusic.app.data.Lyrics
import com.cloudmusic.app.data.LyricsCandidate
import java.util.Locale

/**
 * 歌词面板：随播放滚动、高亮当前行，点某一行可以跳过去。
 */
@Composable
fun LyricsPanel(
    lyrics: Lyrics?,
    loading: Boolean,
    message: String?,
    source: String,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onManualSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val index = remember(lyrics, positionMs) { lyrics?.indexAt(positionMs) ?: -1 }

    LaunchedEffect(index, lyrics) {
        if (index >= 0) {
            val info = listState.layoutInfo
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val offset = if (viewport > 0) -(viewport / 2) else 0
            runCatching { listState.animateScrollToItem(index, offset) }
        }
    }

    Box(modifier) {
        when {
            loading -> {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        "正在加载歌词…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            lyrics == null || lyrics.lines.isEmpty() -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        message ?: "暂无歌词",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "把同名 .lrc 文件放在歌曲同目录，或在设置里开启在线歌词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (onManualSearch != null) {
                        TextButton(
                            onClick = onManualSearch,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text("手动搜索歌词")
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 70.dp)
                ) {
                    itemsIndexed(lyrics.lines) { i, line ->
                        val active = i == index
                        LyricRow(
                            text = line.text,
                            active = active,
                            onClick = { onSeek(line.timeMs) }
                        )
                    }
                }
                if (source.isNotBlank()) {
                    Text(
                        source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricRow(text: String, active: Boolean, onClick: () -> Unit) {
    // 高亮切换用动画过渡，别硬跳
    val target = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val color by animateColorAsState(target, label = "lyricColor")
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        fontSize = if (active) 18.sp else 15.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.Center,
        lineHeight = if (active) 25.sp else 21.sp
    )
}

/**
 * 手动搜索歌词：先输歌名/歌手，再从结果列表里挑一首。
 *
 * 自动匹配会直接采用打分最高的一首，遇到版本不同 / 同名歌就容易挑错，
 * 所以这里把候选都列出来，用户点哪首就用哪首。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSearchDialog(
    initialTitle: String,
    initialArtist: String,
    state: LyricsSearchUiState,
    onSearch: (title: String, artist: String) -> Unit,
    onSelect: (LyricsCandidate) -> Unit,
    onAutoMatch: (title: String, artist: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var showList by remember { mutableStateOf(false) }

    LaunchedEffect(state.done) { if (state.done) onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (showList) "选择歌词" else "手动搜索歌词") },
        text = {
            Column {
                if (!showList) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("歌名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = artist,
                            onValueChange = { artist = it },
                            label = { Text("歌手（可留空）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "搜出来会先列出结果，点哪首就用哪首的歌词",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    when {
                        state.searching || state.applying -> Box(
                            Modifier.fillMaxWidth().height(140.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Text(
                                    if (state.applying) "正在载入歌词…" else "正在搜索…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }
                        }

                        state.candidates.isEmpty() -> Box(
                            Modifier.fillMaxWidth().height(140.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                state.error ?: "没搜到候选歌曲",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }

                        else -> Box(Modifier.height(300.dp)) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(state.candidates) { _, c ->
                                    LyricsCandidateRow(c) { onSelect(c) }
                                }
                            }
                        }
                    }
                    // 某一首没取到歌词时留在列表里，提示一下让用户换一首
                    val errorMessage = state.error
                    if (errorMessage != null && state.candidates.isNotEmpty() && !state.applying) {
                        Text(
                            errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!showList) {
                TextButton(onClick = {
                    showList = true
                    onSearch(title, artist)
                }) { Text("搜索") }
            } else {
                TextButton(onClick = { showList = false }) { Text("改关键词") }
            }
        },
        dismissButton = {
            if (!showList) {
                TextButton(onClick = { onAutoMatch(title, artist) }) { Text("自动选最佳") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun LyricsCandidateRow(c: LyricsCandidate, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                c.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (c.recommended) {
                Text(
                    "推荐",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        val sub = listOf(c.artist, c.source).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * 歌词时间校准。
 *
 * 现场版 / 不同音源的时间轴常常和在线 LRC 差几秒（《三人游 [Timeless Live 2009]》
 * 用的就是录音室版歌词），自动匹配改不了这个，交给用户按 0.5 秒微调。
 */
@Composable
fun LyricsOffsetDialog(
    offsetMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label = "%.1f".format(Locale.US, offsetMs / 1000.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("歌词时间校准") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "当前偏移：${if (offsetMs >= 0) "+" else ""}$label 秒",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "歌词比唱得早就点「延后」，比唱得晚就点「提前」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onAdjust(-500L) }) { Text("延后 0.5s") }
                    TextButton(onClick = { onAdjust(500L) }) { Text("提前 0.5s") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("重置") }
        }
    )
}
