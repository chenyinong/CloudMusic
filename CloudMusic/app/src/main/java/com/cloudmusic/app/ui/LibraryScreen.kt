package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.Track

@OptIn(ExperimentalMaterial3Api::class)
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
    onOpenSettings: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    // 排序：0 默认（网盘顺序）、1 按标题、2 按歌手 —— 点操作行右侧图标循环切换
    var sortMode by remember { mutableStateOf(0) }
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
    // 待确认删除的曲目。删的是网盘里的文件，删了就没了，先问一句。
    var pendingDelete by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        // QQ 列表页是很淡的灰白底，不是纯白
        containerColor = Color(0xFFF5F6F8),
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
                    containerColor = Color.Transparent
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 搜索框独占一行。
            // 之前跟两个按钮挤在一行，OutlinedTextField 有 280dp 的最小宽度，
            // 窄屏上按钮会被推出屏幕外，所以改成上下两行。
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "搜索歌曲 / 歌手 / 专辑",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // QQ 收藏页那一行：绿圆播放 + 全部播放(N) + 右侧排序
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            if (filtered.isNotEmpty()) {
                                vm.player.play(filtered, 0)
                                onOpenPlayer()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "全部播放",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "全部播放(${filtered.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { sortMode = (sortMode + 1) % 3 }) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = when (sortMode) {
                            0 -> "排序：默认"
                            1 -> "排序：按标题"
                            else -> "排序：按歌手"
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 挂了多个网盘时可以按网盘筛（轻量小胶囊，不要 FilterChip 那种粗边框）
            if (providers.size > 1) {
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
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else Color(0xFFEBECEE)
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

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (tracks.isEmpty()) "还没有歌曲，点右上角刷新扫描网盘" else "没有匹配的歌曲",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(filtered, key = { _, t -> t.key }) { idx, track ->
                        TrackRow(
                            index = idx + 1,
                            track = track,
                            showProvider = providers.size > 1,
                            playing = vm.player.currentTrack?.key == track.key,
                            onClick = {
                                vm.player.play(filtered, filtered.indexOf(track))
                                onOpenPlayer()
                            },
                            onEnqueue = { vm.player.enqueue(track) },
                            onShowInfo = { infoTrack = track }
                        )
                    }
                    // 底部留白由 Root 统一处理（迷你播放器 + 导航栏）
                    item { Spacer(Modifier.height(16.dp)) }
                }
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
 * 曲库里的一行。
 *
 * 样式照 QQ 音乐收藏列表：序号 + 歌名 + 灰色歌手（带音质胶囊），
 * 右边「+」加入队列，再右边竖三点，没有分隔线。
 */
@Composable
private fun TrackRow(
    index: Int,
    track: Track,
    showProvider: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onEnqueue: () -> Unit,
    onShowInfo: () -> Unit,
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
        title = track.title,
        subtitle = sub,
        playing = playing,
        index = index,
        badge = formatBadge(track.fileName),
        showDivider = false,
        onClick = onClick
    ) {
        // QQ：右边先一个「加入队列」，再一个竖三点
        IconButton(onClick = onEnqueue, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "加入播放队列",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
        }
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
                AppMenuHeader(track.title)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
                AppMenuItem(
                    text = "播放",
                    icon = Icons.Filled.PlayArrow
                ) {
                    menuOpen = false
                    onClick()
                }
                AppMenuItem(
                    text = "加入播放队列",
                    icon = Icons.Filled.PlaylistAdd
                ) {
                    menuOpen = false
                    onEnqueue()
                }
                AppMenuItem(
                    text = "歌曲信息",
                    icon = Icons.Filled.Info
                ) {
                    menuOpen = false
                    onShowInfo()
                }
            }
        }
    }
}