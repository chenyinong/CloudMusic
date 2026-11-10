package com.cloudmusic.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudmusic.app.data.playlist.SavedPlaylist

/**
 * 「我的歌单」列表，抄的是网易云音乐的用法：
 * 这里只列歌单，点进去才看到里面的歌，想听哪首点哪首。
 *
 * 每张歌单是一张圆角白卡：左边程序化封面（不用联网，也不用给歌单表加字段），
 * 右边歌单名、曲目信息、导入进度条，行尾 ⋮ 可以打开或删除歌单。
 *
 * 滚动方式跟歌单详情页一致：整页一个列表，
 * 头部（大标题、统计、导入按钮）跟着一起滑走，滑走后顶栏顶上标题；
 * 搜索框滑到顶就吸住。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistListScreen(
    onBack: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    val vm: PlaylistListViewModel = viewModel()

    LaunchedEffect(Unit) { vm.refresh() }

    val listState = rememberLazyListState()
    // 头部滑出视野之后，顶栏才顶上标题
    val headerGone by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= 1 }
    }

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                if (headerGone) {
                    Text(
                        "我的歌单",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
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
                IconButton(onClick = onOpenImport) {
                    Icon(Icons.Filled.Add, contentDescription = "导入歌单")
                }
            }
        )

        if (vm.playlists.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "还没有歌单",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "导入一张网易云或 QQ 音乐的歌单，只存清单不下载歌曲",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenImport) { Text("导入歌单") }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalScreenBottomPad.current + 24.dp)
            ) {
                // 头部：跟着一起滑走，滑走了顶栏顶上「我的歌单」
                item(key = "header") {
                    PlaylistListHeader(
                        count = vm.playlists.size,
                        totalTracks = vm.totalTracks,
                        totalImported = vm.totalImported,
                        onOpenImport = onOpenImport
                    )
                }

                // 找歌单：滑到顶就吸住
                stickyHeader(key = "search") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        SearchField(
                            value = vm.query,
                            onValueChange = { vm.query = it },
                            placeholder = "找歌单",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }

                if (vm.visiblePlaylists.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "没有匹配的歌单",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    items(vm.visiblePlaylists, key = { it.key }) { p ->
                        PlaylistCard(
                            playlist = p,
                            imported = vm.importedCounts[p.key] ?: 0,
                            onClick = { onOpenPlaylist(p.key) },
                            onDelete = { vm.delete(p.key) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 列表头：大标题 + 统计 + 导入按钮。
 *
 * 故意做成会滑走的那一块 —— 滑走之后顶栏顶上标题，
 * 屏幕上就只剩歌单列表，不会一直占着地方。
 */
@Composable
private fun PlaylistListHeader(
    count: Int,
    totalTracks: Int,
    totalImported: Int,
    onOpenImport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "我的歌单",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "共 $count 张歌单 · $totalTracks 首" +
                if (totalImported > 0) " · 已导入 $totalImported 首" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        // 小胶囊按钮，别用 Material 默认那个 40dp 高的 Button
        Box(
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onOpenImport)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "导入歌单",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * 一张歌单一张白卡。
 *
 * 封面直接复用曲库那套程序化插画 [GeneratedCover]：按歌单名生成，
 * 不用联网，也不用给歌单表加封面字段；以后拿到真实封面 URL 时，
 * 只要把这块换掉就行。
 */
@Composable
private fun PlaylistCard(
    playlist: SavedPlaylist,
    imported: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                GeneratedCover(seed = playlist.name, modifier = Modifier.fillMaxSize())
                Text(
                    playlist.platform.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }

            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val sub = buildString {
                    append("${playlist.trackCount} 首")
                    append(" · ${playlist.platform.label}")
                    if (imported > 0) append(" · 已导入 $imported 首")
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(7.dp))
                val ratio = if (playlist.trackCount > 0) {
                    (imported.toFloat() / playlist.trackCount).coerceIn(0f, 1f)
                } else 0f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (ratio > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(ratio)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                )
                        )
                    }
                }
            }

            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(30.dp)
                ) {
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
                    AppMenuHeader(playlist.name)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                    AppMenuItem(text = "打开歌单", icon = Icons.Filled.KeyboardArrowRight) {
                        menuOpen = false
                        onClick()
                    }
                    AppMenuItem(text = "删除歌单", icon = Icons.Filled.Delete) {
                        menuOpen = false
                        confirmDelete = true
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除这张歌单？") },
            text = { Text("只删「${playlist.name}」的清单，网盘里已经导入的歌不会动。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}
