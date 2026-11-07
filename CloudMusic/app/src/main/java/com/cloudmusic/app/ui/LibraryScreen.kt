package com.cloudmusic.app.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val filtered = remember(tracks, query, providerFilter) {
        val byProvider = if (providerFilter == null) tracks
        else tracks.filter { it.provider == providerFilter }
        if (query.isBlank()) byProvider else byProvider.filter {
            it.title.contains(query, true) ||
                it.artist.contains(query, true) ||
                it.album.contains(query, true)
        }
    }

    // 「歌曲信息」弹窗要哪首
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    // 待确认删除的曲目。删的是网盘里的文件，删了就没了，先问一句。
    var pendingDelete by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的音乐", fontWeight = FontWeight.Bold) },
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
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("搜索歌曲 / 歌手 / 专辑") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 挂了多个网盘时可以按网盘筛
            if (providers.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = providerFilter == null,
                        onClick = { onProviderFilterChange(null) },
                        label = { Text("全部") }
                    )
                    providers.forEach { p ->
                        FilterChip(
                            selected = providerFilter == p.id,
                            onClick = { onProviderFilterChange(p.id) },
                            label = { Text(p.shortLabel) }
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
                    items(filtered, key = { it.key }) { track ->
                        TrackRow(
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
 * 样式照网易云：方形封面 + 大号歌名 + 灰色歌手，右边一个「⋮」。
 */
@Composable
private fun TrackRow(
    track: Track,
    showProvider: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onEnqueue: () -> Unit,
    onShowInfo: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 网易云那一行第二行只放歌手；挂了多个网盘时补个来源标签
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
        onClick = onClick
    ) {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("播放") },
                    onClick = {
                        menuOpen = false
                        onClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("加入播放队列") },
                    onClick = {
                        menuOpen = false
                        onEnqueue()
                    }
                )
                DropdownMenuItem(
                    text = { Text("歌曲信息") },
                    onClick = {
                        menuOpen = false
                        onShowInfo()
                    }
                )
            }
        }
    }
}