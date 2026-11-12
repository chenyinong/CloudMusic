package com.cloudmusic.app.ui

import android.os.SystemClock
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.cloud.CloudTrack
import kotlinx.coroutines.delay

/**
 * 曲库筛选里的「云端」这一档。
 *
 * 网盘筛选用的是 provider id（webdav / onedrive），云端不是网盘，
 * 用一个不会跟它们撞上的特殊值。
 */
const val FILTER_CLOUD = "cloud"

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
        // 「云端」这一档不看网盘，云端的歌单独一栏渲染
        if (providerFilter == FILTER_CLOUD) emptyList()
        else {
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
    }

    // 「歌曲信息」弹窗要哪首
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    // 「设置封面」弹窗要哪首
    var coverTrack by remember { mutableStateOf<Track?>(null) }
    // 待确认删除的曲目。删的是网盘里的文件，删了就没了，先问一句。
    var pendingDelete by remember { mutableStateOf<Track?>(null) }

    // 云端收藏（网页版同步过来的），单独一栏显示
    val cloudFavorites by vm.cloudFavorites.collectAsState()

    // 进页面顺手拉一次云端收藏（网页端可能刚改过）
    LaunchedEffect(Unit) { vm.refreshCloudFavorites() }

    // 下拉刷新：重扫网盘 + 拉云端收藏。
    //
    // 指示器至少亮 600ms —— 刷新很快的话一闪而过，像没反应。
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            val start = SystemClock.elapsedRealtime()
            vm.refreshLibrary().join()
            val spent = SystemClock.elapsedRealtime() - start
            if (spent < MIN_SPIN_MS) delay(MIN_SPIN_MS - spent)
            refreshing = false
        }
    }

    val cloudFavs = remember(cloudFavorites, query) {
        if (query.isBlank()) cloudFavorites else cloudFavorites.filter {
            it.title.contains(query, true) ||
                it.artist.contains(query, true) ||
                it.album.contains(query, true)
        }
    }

    // 「云端」胶囊选中时，列表只剩云端收藏
    val cloudOnly = providerFilter == FILTER_CLOUD

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
        //
        // padding 要加在 RefreshBox 上（不是里面的 LazyColumn）：
        // 加在 LazyColumn 上的话，外层容器会顶到屏幕最上头，
        // 下拉时那个指示器正好落在顶栏底下，被顶栏挡住看不见。
        RefreshBox(
            refreshing = refreshing,
            onRefresh = { refreshing = true },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalScreenBottomPad.current)
        ) {
            item(key = "hero") {
                LibraryHero(
                    trackCount = tracks.size,
                    providerCount = providers.size,
                    artistCount = artistCount,
                    cloudCount = if (vm.cloudLoggedIn) cloudFavs.size else 0
                )
            }

            // 搜索栏 + 过滤胶囊：放在同一个吸顶块里，
            // 胶囊永远停在搜索栏下面，列表往下滑也能随时换筛选
            stickyHeader(key = "search") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    SearchField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = "搜索歌曲 / 歌手 / 专辑",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    ProviderFilterChips(
                        providers = providers,
                        providerFilter = providerFilter,
                        onProviderFilterChange = onProviderFilterChange,
                        cloudSelected = cloudOnly,
                        onCloudClick = {
                            if (vm.cloudLoggedIn) {
                                onProviderFilterChange(if (cloudOnly) null else FILTER_CLOUD)
                            } else {
                                vm.openCloudLogin()
                            }
                        }
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
                            if (cloudOnly) {
                                if (cloudFavs.isNotEmpty()) vm.playCloudQueue(cloudFavs)
                            } else if (filtered.isNotEmpty()) {
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
                        if (cloudOnly) "${cloudFavs.size} 首云端歌曲" else "${filtered.size} 首歌曲",
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

            // 过滤胶囊挪到上面的吸顶块里了，这里只留「播放全部 + 排序」
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

            // 选了「云端」这一档，网盘的歌就不列了 —— 只剩云端收藏
            if (!cloudOnly) {
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
                                when {
                                    tracks.isEmpty() && cloudFavs.isEmpty() ->
                                        "还没有歌曲，点右上角刷新扫描网盘"
                                    tracks.isEmpty() -> "网盘里还没有歌，下面是云端收藏"
                                    else -> "没有匹配的歌曲"
                                },
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
                            favorite = vm.isCloudFavorite(track),
                            onClick = {
                                // 点歌只出声，不跳页：想看播放页点底部迷你条
                                vm.player.play(filtered, filtered.indexOf(track))
                            },
                            onEnqueue = { vm.player.enqueue(track) },
                            onShowInfo = { infoTrack = track },
                            onSetCover = { coverTrack = track },
                            onRefreshCover = { vm.refreshCover(track) },
                            onCorrectTags = { vm.correctTrackTags(track) },
                            onToggleFavorite = { vm.toggleCloudFavorite(track) }
                        )
                    }
                }
            }

            // 云端收藏：网页版收藏同步过来的歌，跟网盘曲库分开一栏
            if (vm.cloudLoggedIn && (cloudOnly || cloudFavs.isNotEmpty())) {
                item(key = "cloud-header") {
                    CloudFavoritesHeader(count = cloudFavs.size, only = cloudOnly)
                }
                if (cloudFavs.isEmpty()) {
                    item(key = "cloud-empty") {
                        Text(
                            "云端还没有收藏，网页版收藏的歌会出现在这儿",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(cloudFavs, key = { "cloud-${it.key}" }) { t ->
                        CloudTrackRow(
                            track = t,
                            playing = vm.player.currentTrack?.key == "cloud:${t.key}",
                            onClick = { vm.playCloudTrack(t, cloudFavs) },
                            onRemove = { vm.removeCloudFavorite(t) }
                        )
                    }
                }
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
 * 按网盘 / 按云端筛选的小胶囊。
 *
 * 轻量小胶囊，不要 FilterChip 那种粗边框 —— 顶栏下面就这一行，
 * 粗边框会显得很吵。
 *
 * 现在它跟搜索栏一起吸顶，列表往下滑的时候胶囊一直停在搜索栏下面，
 * 换筛选不用先滚回中间。
 */
@Composable
private fun ProviderFilterChips(
    providers: List<ProviderKind>,
    providerFilter: String?,
    onProviderFilterChange: (String?) -> Unit,
    /** 「云端」这一档是不是选中着 */
    cloudSelected: Boolean,
    onCloudClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 6.dp),
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
        // 云端胶囊：跟网盘并排。没登录时点它去登录页
        Text(
            "云端",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (cloudSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (cloudSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (cloudSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable { onCloudClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
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
private fun LibraryHero(
    trackCount: Int,
    providerCount: Int,
    artistCount: Int,
    cloudCount: Int = 0,
) {
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
                if (cloudCount > 0) {
                    Spacer(Modifier.width(14.dp))
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.32f))
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "$cloudCount 首云端",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
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
    /** 这首歌是不是已经收藏到云端了（红心亮不亮） */
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
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
        // 行尾：小红心（收藏到云端）+ 竖三点
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onToggleFavorite != null) {
                IconButton(
                    onClick = { onToggleFavorite() },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (favorite) "取消云端收藏" else "收藏到云端",
                        tint = if (favorite) Color(0xFFE8546F)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
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
                if (onToggleFavorite != null) {
                    AppMenuItem(
                        text = if (favorite) "取消云端收藏" else "收藏到云端",
                        icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder
                    ) {
                        menuOpen = false
                        onToggleFavorite()
                    }
                }
                }
            }
        }
    }
}

/**
 * 「云端收藏」分区的标题。
 *
 * 跟网盘曲库分开一栏 —— 云端的歌没有本地文件，混在一起会让人以为
 * 点得进就一定播得出。
 */
@Composable
private fun CloudFavoritesHeader(count: Int, only: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
            "云端收藏",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (only) "$count 首 · 云端" else "$count 首 · 与网页版同步",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 云端收藏里的一行。
 *
 * 云端的歌没有本地文件，点它是在本机解析一条直链出来播，
 * 所以副标题后面标一下来源，别让人以为是网盘里的文件。
 */
@Composable
private fun CloudTrackRow(
    track: CloudTrack,
    playing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SongRow(
        coverSeed = track.album.ifBlank { track.title },
        coverTrack = track.toCoverTrack(),
        title = track.title,
        subtitle = buildString {
            append(track.artist.ifBlank { "未知歌手" })
            append(" · 云端")
        },
        playing = playing,
        badge = if (track.durationSec > 0) "${track.durationSec}s" else null,
        showDivider = false,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 云端收藏默认就是红的，点一下取消收藏
            IconButton(
                onClick = { onRemove() },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "取消云端收藏",
                    tint = Color(0xFFE8546F),
                    modifier = Modifier.size(18.dp)
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
                    AppMenuHeader(track.title, coverSeed = track.title, track = track.toCoverTrack())
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                    AppMenuItem(text = "播放", icon = Icons.Outlined.PlayArrow, highlight = true) {
                        menuOpen = false
                        onClick()
                    }
                    AppMenuItem(text = "取消云端收藏", icon = Icons.Outlined.FavoriteBorder) {
                        menuOpen = false
                        onRemove()
                    }
                }
            }
        }
    }
}

/** 云端歌曲借一张本地曲库身份去查封面（按歌名歌手检索）。 */
private fun CloudTrack.toCoverTrack(): Track = Track(
    key = "cloud-cover:$key",
    id = key,
    url = "",
    fileName = "",
    title = title,
    artist = artist,
    album = album,
    folder = "",
    size = 0L,
    mime = "audio/*",
    durationSec = durationSec.toDouble(),
    provider = "cloud",
)

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