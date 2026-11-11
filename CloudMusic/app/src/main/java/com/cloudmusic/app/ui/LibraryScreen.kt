package com.cloudmusic.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.Track

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    vm: AppViewModel,
    tracks: List<Track>,
    status: String,
    busy: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    providers: List<ProviderKind>,
    providerFilter: String?,
    onProviderFilterChange: (String?) -> Unit,
    onOpenSettings: () -> Unit
) {
    // 排序：0 默认（网盘顺序）、1 按标题、2 按歌手 —— 点操作行右侧胶囊循环切换
    var sortMode by remember { mutableStateOf(0) }
    // 头卡上的统计：歌手数按列表实时算，不额外查库
    val artistCount = remember(tracks) {
        tracks.map { it.artist.trim() }.filter { it.isNotBlank() }.distinct().size
    }
    val filtered = remember(tracks, query, providerFilter, sortMode) {
        val byProvider = if (providerFilter == null) tracks
        else tracks.filter { it.provider == providerFilter }
        val matched = if (query.isBlank()) byProvider else byProvider.filter {
            it.title.contains(query, true) ||
                it.artist.contains(query, true) ||
                it.album.contains(query, true)
        }
        when (sortMode) {
            1 -> matched.sortedBy { it.title.lowercase() }
            2 -> matched.sortedBy { it.artist.lowercase() }
            else -> matched
        }
    }

    // 「歌曲信息」弹窗要哪首
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    // 「设置封面」弹窗要哪首
    var coverTrack by remember { mutableStateOf<Track?>(null) }
    // 待确认删除的曲目。删的是网盘里的文件，删了就没了，先问一句。
    var pendingDelete by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        // 跟主题走：写死浅灰底的话，暗色模式会变成「浅底 + 白字」，整页看不清
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "我的音乐",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    // 实底：滚动时列表不会从标题后面透出来
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = { vm.rescan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        // 整页一个滚动列表：头卡随内容滑走，搜索框吸顶，列表占满剩余空间。
        // 这样第一屏有视觉重心，往下滚又不浪费可视区域。
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = LocalScreenBottomPad.current)
        ) {
            item(key = "hero") {
                LibraryHero(
                    trackCount = tracks.size,
                    providerCount = providers.size,
                    artistCount = artistCount
                )
            }

            stickyHeader(key = "search") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    SearchField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = "搜索歌曲 / 歌手 / 专辑",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item(key = "controls") {
                Column {
            // 播放全部 + 排序方式
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            // 播放全部只出声，不跳页：想看播放页点底部迷你条
                            if (filtered.isNotEmpty()) {
                                vm.player.play(filtered, 0)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "全部播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "播放全部",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${filtered.size} 首歌曲",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 带文字的排序胶囊：光一个图标看不出当前按什么排
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { sortMode = (sortMode + 1) % 3 }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = "切换排序",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        listOf("默认", "按标题", "按歌手")[sortMode],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 挂了多个网盘时可以按网盘筛（轻量小胶囊，不要 FilterChip 那种粗边框）
            if (providers.size > 1) {
                // 和上面「播放全部」拉开一点，两行控件别挤成一坨
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val options = listOf<Pair<String?, String>>(null to "全部") +
                        providers.map { it.id to it.shortLabel }
                    options.forEach { (id, label) ->
                        val selected = providerFilter == id
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { onProviderFilterChange(id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (status.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

                }
            }

            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (tracks.isEmpty()) "还没有歌曲，点右上角刷新扫描网盘" else "没有匹配的歌曲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                itemsIndexed(filtered, key = { _, t -> t.key }) { _, track ->
                    TrackRow(
                        track = track,
                        showProvider = providers.size > 1,
                        playing = vm.player.currentTrack?.key == track.key,
                        onClick = {
                            // 点歌只出声，不跳页：想看播放页点底部迷你条
                            vm.player.play(filtered, filtered.indexOf(track))
                        },
                        onEnqueue = { vm.player.enqueue(track) },
                        onShowInfo = { infoTrack = track },
                        onSetCover = { coverTrack = track },
                        onRefreshCover = { vm.refreshCover(track) },
                        onCorrectTags = { vm.correctTrackTags(track) }
                    )
                }
                // 底部留白由 Root 统一处理（迷你播放器 + 导航栏）
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    infoTrack?.let { t ->
        TrackInfoDialog(
            track = t,
            onDismiss = { infoTrack = null },
            onDelete = {
                infoTrack = null
                pendingDelete = t
            }
        )
    }

    // 手动指定封面：填 URL 或从相册选图；也能清掉缓存重新自动查找
    coverTrack?.let { t ->
        CoverEditDialog(
            track = t,
            vm = vm,
            onDismiss = { coverTrack = null }
        )
    }

    // 删除是拿不回来的操作，先确认
    pendingDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("从网盘删除这首？") },
            text = { Text("《${t.title}》会从网盘里删掉，删了就找不回来了。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    vm.deleteTrack(t)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 曲库页的概览头卡 —— 一打开就有视觉重心。
 *
 * 深灰渐变 + 大数字 + 极淡圆环装饰，不挂投影（浅灰底上投影会显脏）。
 * 颜色不跟主题主色走，深浅模式都保留这块「深色名片」的对比。
 */
@Composable
private fun LibraryHero(trackCount: Int, providerCount: Int, artistCount: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(136.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF23272E), Color(0xFF3D444E))))
    ) {
        // 装饰：两个半透明圆环 + 一个淡到几乎看不见的均衡器图标
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 30.dp, y = (-44).dp)
                .size(150.dp)
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 26.dp, y = 40.dp)
                .size(92.dp)
                .border(1.dp, Color.White.copy(alpha = 0.07f), CircleShape)
        )
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.08f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(92.dp)
                .padding(end = 8.dp)
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                "MY LIBRARY",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 1.4.sp,
                color = Color.White.copy(alpha = 0.62f)
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$trackCount",
                    fontSize = 42.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "首歌曲",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(bottom = 7.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${providerCount} 个网盘",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f)
                )
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.32f))
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "${artistCount} 位歌手",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f)
                )
            }
        }
    }
}

/**
 * 曲库里的一行。
 *
 * 样式照 QQ 音乐收藏列表：序号 + 歌名 + 灰色歌手（带音质胶囊），
 * 右边「+」加入队列，再右边竖三点，没有分隔线。
 */
@Composable
private fun TrackRow(
    track: Track,
    showProvider: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onEnqueue: () -> Unit,
    onShowInfo: () -> Unit,
    onSetCover: () -> Unit,
    onRefreshCover: () -> Unit,
    onCorrectTags: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 第二行只放歌手；挂了多个网盘时补个来源标签
    val sub = buildString {
        append(track.artist.ifBlank { "未知歌手" })
        if (showProvider) {
            append(" · ")
            append(ProviderKind.of(track.provider).shortLabel)
        }
    }
    SongRow(
        coverSeed = track.album.ifBlank { track.title },
        coverTrack = track,
        title = track.title,
        subtitle = sub,
        playing = playing,
        badge = formatBadge(track.fileName),
        showDivider = false,
        onClick = onClick
    ) {
        // 行尾只留竖三点：加入队列收进菜单里，别占一列
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
            AppDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                AppMenuHeader(track.title, coverSeed = track.title, track = track)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
                AppMenuItem(
                    text = "播放",
                    icon = Icons.Outlined.PlayArrow,
                    highlight = true
                ) {
                    menuOpen = false
                    onClick()
                }
                AppMenuItem(
                    text = "加入播放队列",
                    icon = Icons.Outlined.PlaylistAdd
                ) {
                    menuOpen = false
                    onEnqueue()
                }
                AppMenuItem(
                    text = "歌曲信息",
                    icon = Icons.Outlined.Info
                ) {
                    menuOpen = false
                    onShowInfo()
                }
                AppMenuItem(
                    text = "设置封面",
                    icon = Icons.Outlined.Image
                ) {
                    menuOpen = false
                    onSetCover()
                }
                AppMenuItem(
                    text = "重新查找封面",
                    icon = Icons.Outlined.Refresh
                ) {
                    menuOpen = false
                    onRefreshCover()
                }
                AppMenuItem(
                    text = "用文件标签修正歌名/歌手",
                    icon = Icons.Outlined.GraphicEq,
                    subtitle = "修改歌曲名与歌手信息"
                ) {
                    menuOpen = false
                    onCorrectTags()
                }
            }
        }
    }
}

/** 手动指定封面的对话框：填 URL / 选本地图 / 重新自动查找。 */
@Composable
private fun CoverEditDialog(
    track: Track,
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    var urlText by remember { mutableStateOf("") }
    // 选完图直接保存并关掉弹窗；图片会被复制进 App 私有目录
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.setCoverImage(track, uri)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("设置封面") },
        text = {
            Column {
                Text(
                    "《${track.title}》",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("封面图片 URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { pickImage.launch("image/*") }) {
                    Text("从相册选一张图")
                }
                Text(
                    "手动指定的封面优先于自动查找，会一直留着，直到你点「重新查找封面」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (urlText.isNotBlank()) vm.setCoverUrl(track, urlText)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    vm.refreshCover(track)
                    onDismiss()
                }) { Text("重新查找") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}