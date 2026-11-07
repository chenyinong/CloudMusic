package com.cloudmusic.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.playlist.SavedTrack

/**
 * 歌单详情：整张单摆在这儿，想听哪首点哪首。
 *
 * 这一页默认什么都不做 —— 不会替用户把几百首歌塞进网盘。
 * 只有点了某首的「导入」，才会去找音源、下载、传网盘。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistKey: String,
    providers: List<ProviderKind>,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    /** 点了「播放」之后跳到播放页 */
    onOpenPlayer: () -> Unit,
) {
    val vm: PlaylistDetailViewModel = viewModel()
    val context = LocalContext.current

    LaunchedEffect(playlistKey) { vm.load(playlistKey) }

    var showTarget by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    // 几百首的歌单每次重组都过滤一遍会白费力气，记住结果
    val list = remember(vm.tracks, vm.query) { vm.filtered() }

    // 整页一个列表：歌单头、搜索框、歌曲一起滚，
    // 歌单头滑出去了，顶栏才顶上歌单名
    val listState = rememberLazyListState()
    val headerGone by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= 1 }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    // 歌单头滑出视野之后，顶栏才顶上歌单名（跟网易云一个做法）
                    if (headerGone) {
                        Text(
                            vm.playlist?.name ?: "歌单",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除歌单")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 歌单头：跟着一块儿往上滑，滑走了顶栏顶上歌单名
            item(key = "header") {
                PlaylistHeader(vm) { showTarget = true }
            }

            // 搜索：几百首的歌单得能搜。滑到顶就吸住，别让它跟着跑掉
            stickyHeader(key = "search") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    SearchField(
                        value = vm.query,
                        onValueChange = { vm.query = it },
                        placeholder = "在这张歌单里找",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // 播放全部：跟网易云一样摆在列表最上面，跟着列表一起滑，滑到顶就停在那儿
            if (vm.playableTracks.isNotEmpty()) {
                item(key = "playAll") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.playAll()
                                onOpenPlayer()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "播放全部",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(7.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "播放全部",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${vm.playableTracks.size} 首已经导进网盘 · 按顺序连着放",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (vm.message.isNotBlank()) {
                item(key = "message") {
                    Text(
                        vm.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }

            if (list.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            if (vm.tracks.isEmpty()) "这张歌单里没有歌" else "没有匹配的歌",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(list, key = { it.key }) { t ->
                    DetailTrackRow(
                        index = t.position + 1,
                        track = t,
                        phase = vm.phases[t.key],
                        inLibrary = t.key in vm.inLibrary,
                        // 网盘里真能找到这个文件才给「播放」
                        playable = t.key in vm.libraryTracks,
                        // 注意别写成 `a == b`：两边都是 null 时会误判成「正在播放」
                        playing = vm.libraryTracks[t.key]?.key
                            ?.let { vm.player.currentTrack?.key == it } == true,
                        onImport = { vm.importTrack(t) },
                        onPlay = {
                            vm.playTrack(t)
                            onOpenPlayer()
                        },
                        onFallback = {
                            val url = vm.fallbackUrl(t)
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: Exception) {
                                // 没有浏览器就算了
                            }
                        }
                    )
                }
            }
        }
    }

    // 版本要用户点头 —— 静默把 DJ 版塞进网盘会把曲库毁了
    vm.pendingConfirm?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.cancelPending() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("确认一下") },
            text = { Text("${p.track.title} - ${p.track.artist}\n\n${p.reason}") },
            confirmButton = {
                TextButton(onClick = { vm.confirmPending() }) { Text("就下这个") }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelPending() }) { Text("算了") }
            }
        )
    }

    // 目标网盘和目录
    if (showTarget) {
        var dirText by remember(vm.dir) { mutableStateOf(vm.dir) }
        AlertDialog(
            onDismissRequest = { showTarget = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("歌曲存到哪") },
            text = {
                Column {
                    Text(
                        "导入到",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        providers.forEach { p ->
                            val selected = vm.providerId == p.id
                            Text(
                                p.shortLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { vm.setProvider(p) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dirText,
                        onValueChange = { dirText = it },
                        label = { Text("网盘根目录下的位置") },
                        supportingText = {
                            Text(
                                "留空就是根目录，实际会存到 ${ProviderKind.of(vm.providerId).shortLabel}" +
                                    " 的「${vm.targetDirWith(dirText)}」"
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.changeDir(dirText.trim())
                    showTarget = false
                }) { Text("好了") }
            },
            dismissButton = {
                TextButton(onClick = { showTarget = false }) { Text("取消") }
            }
        )
    }

    // 删歌单
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删掉这张歌单？") },
            text = {
                Text("只删 App 里的这张歌单清单，已经导进网盘的歌曲不会动。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    // 真删，别只是把对话框关了
                    vm.deletePlaylist(playlistKey) { onDeleted() }
                }) { Text("删掉") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("算了") }
            }
        )
    }
}

/**
 * 歌单头：封面、名字、统计、存到哪儿。
 *
 * 它是列表的第一个 item —— 往下滑的时候跟着一起走，
 * 滑出视野之后顶栏顶上歌单名（跟网易云一个做法）。
 */
@Composable
private fun PlaylistHeader(
    vm: PlaylistDetailViewModel,
    onEditTarget: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GradientCover(
            vm.playlist?.name.orEmpty(),
            size = 96.dp,
            corner = 16.dp
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                vm.playlist?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val importedCount = vm.tracks.count { it.key in vm.inLibrary }
            Text(
                "共 ${vm.tracks.size} 首 · ${vm.playlist?.platform?.label.orEmpty()}" +
                    if (importedCount > 0) " · 已导入 $importedCount 首" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            // 音源状态：网盘源（下歌吧）要登录夸克才用得上，
            // 不说清楚的话用户只会看到「只有试听片段」，根本不知道还有第三个源
            Text(
                if (vm.cloudReady) "音源：网易云 · 酷我 · 网盘（下歌吧）"
                else "音源：网易云 · 酷我 · 网盘源需先登录夸克",
                style = MaterialTheme.typography.labelSmall,
                color = if (vm.cloudReady) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "导入到 ${ProviderKind.of(vm.providerId).shortLabel} · ${vm.targetDir()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onEditTarget)
            )
            TextButton(
                onClick = onEditTarget,
                contentPadding = PaddingValues(
                    horizontal = 4.dp,
                    vertical = 0.dp
                )
            ) {
                Text("改网盘和目录", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DetailTrackRow(
    index: Int,
    track: SavedTrack,
    phase: TrackPhase?,
    inLibrary: Boolean,
    /** 网盘里能找到这个文件，可以播 */
    playable: Boolean,
    /** 正在放这首 */
    playing: Boolean,
    onImport: () -> Unit,
    onPlay: () -> Unit,
    onFallback: () -> Unit,
) {
    val busy = phase?.busy == true
    // 「已导入 · 下歌吧」这种带来源后缀的也要算已导入
    val done = inLibrary || phase?.text?.startsWith("已导入") == true ||
        phase?.text == "已在网盘"
    Row(
        Modifier
            .fillMaxWidth()
            // 整行都可点：已经进网盘的就直接播，还没导的就去找音源
            .clickable(enabled = !busy) {
                when {
                    playable -> onPlay()
                    !done -> onImport()
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$index",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) {
                    MaterialTheme.colorScheme.primary
                } else if (done) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                listOf(track.artist, formatSec(track.durationSec))
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (phase != null && phase.failed && phase.message.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        phase.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onFallback) {
                        Icon(
                            Icons.Filled.OpenInBrowser,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text("去别处找", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(phase?.text.orEmpty(), style = MaterialTheme.typography.labelMedium)
            }
            // 已经进网盘了，能播就给「播放」
            // 不用 Material 的 Button —— 默认 40dp 高、带三角图标，太占地方。
            // 自己画个小胶囊，去掉箭头只留文字。
            playable -> Box(
                Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onPlay)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (playing) "播放中" else "播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            done -> Text(
                phase?.text?.takeIf { it.isNotBlank() } ?: "已在网盘",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            else -> Box(
                Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onImport)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "导入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
