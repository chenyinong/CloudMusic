package com.cloudmusic.app.ui

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudmusic.app.App
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.cloud.CloudPlaylistRef
import com.cloudmusic.app.data.playlist.SavedPlaylist
import kotlinx.coroutines.delay

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
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenCloudPlaylist: (Long) -> Unit,
) {
    val listVm: PlaylistListViewModel = viewModel()

    LaunchedEffect(Unit) {
        listVm.refresh()
        // 云端歌单可能有变化（网页端加过），进页面顺手拉一次
        vm.refreshCloudPlaylists()
    }

    // 云端歌单：单独一栏，不跟本机歌单混在一起
    val cloudPlaylists by vm.cloudPlaylists.collectAsState()
    val cloudVisible = remember(cloudPlaylists, listVm.query) {
        val q = listVm.query.trim()
        if (q.isEmpty()) cloudPlaylists
        else cloudPlaylists.filter { it.name.contains(q, ignoreCase = true) }
    }

    val listState = rememberLazyListState()
    // 头部滑出视野之后，顶栏才顶上标题
    val headerGone by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= 1 }
    }

    // 过滤：0 = 所有歌单，1 = 只看本地，2 = 只看云端
    var filterMode by remember { mutableStateOf(0) }
    val showLocal = filterMode != 2
    val showCloud = filterMode != 1

    // 下拉刷新：本机歌单重读一遍，云端歌单重新拉一次。
    //
    // 云端那边可能加了新歌单（网页端加的），停在这个页面上不动是看不见的，
    // 下拉一下就同步过来。
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            val start = SystemClock.elapsedRealtime()
            listVm.refresh().join()
            vm.refreshCloudPlaylists().join()
            // 指示器至少亮 600ms —— 一闪而过的话像没反应
            val spent = SystemClock.elapsedRealtime() - start
            if (spent < MIN_SPIN_MS) delay(MIN_SPIN_MS - spent)
            refreshing = false
        }
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

        if (listVm.playlists.isEmpty() && cloudPlaylists.isEmpty()) {
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
            RefreshBox(
                refreshing = refreshing,
                onRefresh = {
                    refreshing = true
                },
                modifier = Modifier.fillMaxSize()
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LocalScreenBottomPad.current + 24.dp)
            ) {
                // 头部：跟着一起滑走，滑走了顶栏顶上「我的歌单」
                item(key = "header") {
                    PlaylistListHeader(
                        count = listVm.playlists.size,
                        totalTracks = listVm.totalTracks,
                        totalImported = listVm.totalImported,
                        onOpenImport = onOpenImport
                    )
                }

                // 找歌单 + 过滤胶囊：一起吸顶，胶囊一直停在搜索栏下面
                stickyHeader(key = "search") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        SearchField(
                            value = listVm.query,
                            onValueChange = { listVm.query = it },
                            placeholder = "找歌单",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        PlaylistFilterChips(
                            mode = filterMode,
                            onModeChange = { filterMode = it }
                        )
                    }
                }

                // 上传中：给个进度提示，别让用户对着没反应的界面干等
                if (vm.cloudBusy) {
                    item(key = "busy") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "正在上传到云端…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (showLocal) {
                    if (listVm.visiblePlaylists.isEmpty()) {
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
                        items(listVm.visiblePlaylists, key = { it.key }) { p ->
                            PlaylistCard(
                                playlist = p,
                                imported = listVm.importedCounts[p.key] ?: 0,
                                coverTrack = listVm.coverTracks[p.key],
                                canUpload = vm.cloudLoggedIn,
                                uploaded = p.cloudId > 0,
                                onClick = { onOpenPlaylist(p.key) },
                                onDelete = {
                                    // 只删本机这一份，云端那份是副本，还在那儿
                                    listVm.delete(p.key)
                                },
                                onUpload = { vm.uploadLocalPlaylist(p.key) }
                            )
                        }
                    }
                }

                // 云端歌单：单独一栏，跟本机歌单不混着
                if (showCloud) {
                    if (vm.cloudLoggedIn) {
                        item(key = "cloud-header") {
                            CloudPlaylistHeader(count = cloudVisible.size)
                        }
                        if (cloudVisible.isEmpty()) {
                            item(key = "cloud-empty") {
                                Text(
                                    "云端还没有歌单",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        } else {
                            items(cloudVisible, key = { "cloud-pl-${it.id}" }) { p ->
                                CloudPlaylistCard(
                                    playlist = p,
                                    onClick = { onOpenCloudPlaylist(p.id) },
                                    onDelete = { vm.deleteCloudPlaylist(p.id) }
                                )
                            }
                        }
                    } else if (filterMode == 2) {
                        // 没登录还选了「云端歌单」，得告诉人家先去登录，不然一片空白
                        item(key = "cloud-empty") {
                            Text(
                                "登录云端账号后，这里才会显示云端歌单",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * 「云端歌单」分区的标题。
 */
@Composable
private fun CloudPlaylistHeader(count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "云端歌单",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count 张 · 与网页版同步",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 一张云端歌单卡片。
 *
 * 云端歌单的封面是接口直接给的图（歌单里第一首歌的封面），
 * 没有就回落到程序化占位画。
 */
@Composable
private fun CloudPlaylistCard(
    playlist: CloudPlaylistRef,
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
            CloudPlaylistCover(playlist = playlist)

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
                Text(
                    "${playlist.trackCount} 首 · 云端",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                    AppMenuItem(text = "打开歌单", icon = Icons.Outlined.KeyboardArrowRight, highlight = true) {
                        menuOpen = false
                        onClick()
                    }
                    AppMenuItem(text = "删除云端歌单", icon = Icons.Outlined.Delete) {
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
            title = { Text("删除这张云端歌单？") },
            text = { Text("「${playlist.name}」会从云端删掉，网页版也跟着没了。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 云端歌单封面：接口给了图就用图，没给就画占位画（不让它裂成一块白）。
 */
@Composable
private fun CloudPlaylistCover(playlist: CloudPlaylistRef) {
    var bitmap by remember(playlist.cover, playlist.name) { mutableStateOf<ImageBitmap?>(null) }
    // LocalContext 是 @Composable 的读法，得在协程外面取
    val app = LocalContext.current.applicationContext as? App
    LaunchedEffect(playlist.cover, app) {
        val url = playlist.cover
        if (url.isBlank() || app == null) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = app.coverRepository.artwork(url)?.asImageBitmap()
    }
    Box(
        Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(10.dp))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            GeneratedCover(seed = playlist.name, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 列表头：大标题 + 统计 + 导入按钮。
 *
 * 故意做成会滑走的那一块 —— 滑走之后顶栏顶上标题，
 * 屏幕上就只剩歌单列表，不会一直占着地方。
 *
 * 「导入歌单」的小胶囊挂在标题这一行的最右边，跟标题齐平，
 * 比自己占一行省地方。
 */
@Composable
private fun PlaylistListHeader(
    count: Int,
    totalTracks: Int,
    totalImported: Int,
    onOpenImport: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
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
        }
        Spacer(Modifier.width(12.dp))
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
 * 歌单页的过滤胶囊：所有歌单 / 本地歌单 / 云端歌单。
 *
 * 跟「找歌单」搜索框放在同一个吸顶块里，往下滑也随时能切。
 * 样式跟曲库页那排胶囊一致，都是轻量小胶囊，不要粗边框。
 */
@Composable
private fun PlaylistFilterChips(
    /** 0 = 所有，1 = 本地，2 = 云端 */
    mode: Int,
    onModeChange: (Int) -> Unit,
) {
    val options = listOf("所有歌单", "本地歌单", "云端歌单")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = mode == index
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
                    .clickable { onModeChange(index) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * 一张歌单一张白卡。
 *
 * 封面借用歌单里某首歌的封面（[CoverThumb] 会自己查缓存 / 在线检索），
 * 不用给歌单表加封面字段；实在找不到才回退到程序化占位画。
 */
@Composable
private fun PlaylistCard(
    playlist: SavedPlaylist,
    imported: Int,
    coverTrack: Track?,
    canUpload: Boolean,
    /** 这张是不是已经同步到云端了 */
    uploaded: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
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
            CoverThumb(
                track = coverTrack,
                seed = playlist.name,
                size = 60.dp,
                corner = 10.dp
            )

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
                    AppMenuHeader(playlist.name, coverSeed = playlist.name, track = coverTrack)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                    AppMenuItem(text = "打开歌单", icon = Icons.Outlined.KeyboardArrowRight, highlight = true) {
                        menuOpen = false
                        onClick()
                    }
                    if (canUpload) {
                        AppMenuItem(
                            text = if (uploaded) "重新上传到云端" else "上传到云端",
                            icon = Icons.Outlined.CloudUpload,
                            subtitle = if (uploaded) "云端已有一份，会替换掉" else "网页版也能看到这张单"
                        ) {
                            menuOpen = false
                            onUpload()
                        }
                    }
                    AppMenuItem(text = "删除歌单", icon = Icons.Outlined.Delete) {
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
