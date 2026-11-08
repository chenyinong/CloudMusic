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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CloudUpload
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
import androidx.compose.runtime.DisposableEffect
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
) {
    val vm: PlaylistDetailViewModel = viewModel()
    val context = LocalContext.current

    LaunchedEffect(playlistKey) { vm.load(playlistKey) }

    // 离开歌单详情页只收掉在线试听 UI，播放队列保持整张歌单不动 ——
    // 以前这里会回放「试听前的旧队列」，点 mini 条进播放页就跳到别的歌。
    DisposableEffect(playlistKey) {
        onDispose { vm.stopOnline() }
    }

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
            contentPadding = PaddingValues(bottom = LocalScreenBottomPad.current + 24.dp)
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
                                // 播放全部也只出声，不跳页：想看播放页点底部迷你条
                                vm.playAll()
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

            // 在线播放的提示（成功 / 没找到音源 / 拉流报错）现在挂在对应歌曲行里，
            // 不再放列表顶部 —— 往下滑几屏就看不见了。

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
                // 当前播放落在歌单的哪一项：高亮、停止按钮、状态提示都跟着它走
                val currentKey = vm.currentPlaylistKey
                items(list, key = { it.key }) { t ->
                    DetailTrackRow(
                        index = t.position + 1,
                        track = t,
                        phase = vm.phases[t.key],
                        inLibrary = t.key in vm.inLibrary,
                        // 网盘里真能找到这个文件才给「播放」
                        playable = t.key in vm.libraryTracks,
                        // 已下载 / 在线解析的歌统一按当前曲目算，播到下一首会自己挪
                        playing = currentKey == t.key,
                        onlinePlaying = currentKey == t.key && vm.currentIsOnline,
                        isPlaying = vm.player.playing,
                        onlineLoading = vm.onlineLoadingKey == t.key,
                        hint = vm.hintFor(t.key),
                        onOnlinePlay = { vm.playOnline(t) },
                        onImport = { vm.importTrack(t) },
                        onPlay = {
                            // 已存在的歌点整行：只用网盘文件出声，不跳播放页。
                            // 想看播放页点底部迷你条，那时 playerOrigin 会按当前页
                            // 记成 PLAYLIST_DETAIL，返回仍能回到这张歌单。
                            vm.playTrack(t)
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
    onlinePlaying: Boolean,
    /** 播放器当前是否在出声（决定右侧按钮显示暂停还是继续） */
    isPlaying: Boolean,
    onlineLoading: Boolean,
    /** 在线播放的行内提示：挂在这一行，跟着列表滚 */
    hint: OnlineHint?,
    onImport: () -> Unit,
    onOnlinePlay: () -> Unit,
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
            // 整行默认就是「播这首」：进网盘的播网盘文件，没导的走在线播放。
            // 导入只走右边的云图标，别让点一下歌名就悄悄开始下载上传。
            .clickable(enabled = !busy) {
                when {
                    playable -> onPlay()
                    // 已经在在线播这首了，点行不重复也不误停（停止走右边图标）
                    !onlinePlaying -> onOnlinePlay()
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // B 方案：正在播（含在线播）时序号位换成均衡器条，
        // 播放状态放在这里，就不用在右侧再塞一个播放按钮了。
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (playing || onlinePlaying) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(6.dp, 12.dp, 8.dp).forEach { h ->
                        Box(
                            Modifier
                                .width(2.5.dp)
                                .height(h)
                                .clip(RoundedCornerShape(1.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            } else {
                Text(
                    "$index",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing || onlinePlaying) {
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
            // 在线播放的状态/失败原因就显示在这首歌下面，跟着列表一起滚
            if (hint != null) {
                Text(
                    hint.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hint.error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
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
        // B 方案：一行最多一个淡圆图标。整行点击已经是「播放」，
        // 所以播放不需要按钮；导入是低频次操作，降成一个 30dp 线性图标。
        when {
            busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(phase?.text.orEmpty(), style = MaterialTheme.typography.labelMedium)
            }
            onlineLoading -> Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            onlinePlaying -> Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onOnlinePlay),
                contentAlignment = Alignment.Center
            ) {
                // 跟当前曲目走：正在放显示暂停、已暂停显示继续，点一下切换。
                // 以前是个「停止」方块，但停止后无法恢复，等于死锁。
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "继续",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
            // 未导入（或导入失败可重试）：云 + 上箭头 = 传到自己的网盘
            !done -> Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onImport),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = "导入到网盘",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
            // 已导入的行右侧留空：点行即播放，不需要任何按钮
        }
    }
}
