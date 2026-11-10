package com.cloudmusic.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudmusic.app.data.MusicHit
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.formatBytes

/**
 * 搜歌页局部强调色。
 *
 * 跟设置页用同一套紫，但只在这页生效 —— 全局主色仍是近黑/近白，
 * 曲库、播放页那些按钮不该跟着变。
 */
private val Accent = Color(0xFF1A1A1A)
private val BadgeGreen = Color(0xFF1E8E57)
private val BadgeGreenBg = Color(0xFFE6F6EC)

/** 在线搜歌并导入网盘：网盘里没有的歌，搜一首直接传进去。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImportScreen(
    providers: List<ProviderKind>,
    appVm: AppViewModel,
    onBack: () -> Unit,
    onOpenPlaylistImport: () -> Unit
) {
    val vm: ImportViewModel = viewModel()
    val visible = remember(vm.hits, vm.sourceFilter) { vm.visibleHits }
    // 待确认删除的条目。删的是网盘里的文件，删了就没了，所以先问一句。
    var pendingDelete by remember { mutableStateOf<MusicHit?>(null) }
    var showDirDialog by remember { mutableStateOf(false) }
    var dirDraft by remember(vm.dir) { mutableStateOf(vm.dir) }

    // 进来的时候重新读一遍「已导入」记录 ——
    // 曲库那边删了文件，这里的记录也得跟着变，不然点「播放」会播一个已经不存在的东西。
    LaunchedEffect(Unit) { vm.reloadImported() }

    // 离开搜歌页就收掉试听，别把临时单曲队列带到别的页面
    DisposableEffect(Unit) {
        onDispose { vm.stopPreview() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    "导入歌曲",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                // 实底：滚动时下面的白卡不会从标题后面透出来
                containerColor = MaterialTheme.colorScheme.background
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                TextButton(onClick = onOpenPlaylistImport) {
                    Text("导入歌单", color = Accent)
                }
            }
        )

        // 整个页面一起滚，底部留白让开迷你播放器 + 页签栏
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalScreenBottomPad.current)
        ) {
            // 搜索框吸顶：结果往下滚的时候还能随时改词重搜，不用滑回顶部
            stickyHeader(key = "search") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        "IMPORT MUSIC",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                    )
                    ImportSearchBar(
                        keyword = vm.keyword,
                        searching = vm.searching,
                        onKeywordChange = { vm.onKeywordChange(it) },
                        onSearchNow = { vm.searchNow() }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            vm.searching -> "正在搜…"
                            vm.hits.isNotEmpty() ->
                                "找到 ${vm.hits.size} 首" +
                                    if (vm.notes.isNotEmpty()) " · ${vm.notes.joinToString(" · ")}" else ""
                            vm.notes.isNotEmpty() -> vm.notes.joinToString(" · ")
                            else -> "输入后自动搜索"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            item(key = "form") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SaveTargetCard(
                        providers = providers,
                        selectedId = vm.providerId,
                        dir = vm.dir,
                        appVm = appVm,
                        onSelect = { vm.setProvider(it) },
                        onChangeDir = {
                            dirDraft = vm.dir
                            showDirDialog = true
                        }
                    )

                    if (vm.message.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            vm.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (vm.importedCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已导入 ${vm.importedCount} 首，返回曲库重新扫描就能看到",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }

            // 来源过滤：想找完整版就切到夸克网盘，不想看网盘的切回网易云
            if (vm.hits.isNotEmpty()) {
                item(key = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SourceFilter.entries.forEach { f ->
                            val selected = vm.sourceFilter == f
                            Text(
                                "${f.label} ${vm.countOf(f)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) Accent
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { vm.sourceFilter = f }
                                    .padding(vertical = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // 最近一次导入的各阶段耗时 —— 用来判断「慢」到底慢在哪一段
            vm.lastTimings?.let { t ->
                item(key = "timings") {
                    Text(
                        "上次导入：${t.summary()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            }

            if (vm.hits.isEmpty()) {
                item(key = "empty") {
                    EmptyState("搜一首歌，下载后直接传进你的网盘")
                }
            } else if (visible.isEmpty()) {
                item(key = "empty-filter") {
                    EmptyState("「${vm.sourceFilter.label}」这个源没搜到东西")
                }
            } else {
                items(visible, key = { it.id }) { hit ->
                    HitRow(
                        hit = hit,
                        state = vm.states[hit.id],
                        deleting = hit.id in vm.deletingIds,
                        previewing = vm.previewId == hit.id,
                        hint = vm.hintFor(hit.id),
                        onImport = { vm.import(hit) },
                        onPreview = { vm.preview(hit) },
                        onPlay = {
                            // 已经导进网盘了，直接播 —— 别再下一遍。
                            // 只出声、不跳播放页：用户还想留在这页继续挑歌。
                            if (!vm.playImported(hit)) vm.import(hit)
                        },
                        onDelete = { pendingDelete = hit }
                    )
                }
            }

            item(key = "footer") {
                Text(
                    "用自己的方式听歌",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                )
            }
        }

    }

    // 目录「更改」：数据层暂时没有列目录接口，退化成编辑路径而不是做一个假的选择器
    if (showDirDialog) {
        AlertDialog(
            onDismissRequest = { showDirDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("保存目录") },
            text = {
                Column {
                    Text(
                        "相对网盘根目录，留空就是根目录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = dirDraft,
                        onValueChange = { dirDraft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.changeDir(dirDraft.trim())
                    showDirDialog = false
                }) { Text("保存", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showDirDialog = false }) { Text("取消") }
            }
        )
    }

    // 删除是拿不回来的操作，先确认
    pendingDelete?.let { hit ->
        val st = vm.states[hit.id]
        val name = st?.track?.fileName ?: "${hit.title}.${hit.ext.ifBlank { "mp3" }}"
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("从网盘删除这首？") },
            text = {
                Text("《$name》会从网盘里删掉，删了就找不回来了。曲库里那一条也会一起消失。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    vm.deleteImported(hit)
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

// --------------------------------------------------------------- 顶部搜索区

/** 白色大搜索框 + 右侧紫色圆形「立即搜」按钮。 */
@Composable
private fun ImportSearchBar(
    keyword: String,
    searching: Boolean,
    onKeywordChange: (String) -> Unit,
    onSearchNow: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchNow() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (keyword.isEmpty()) {
                        Text(
                            "搜歌名或歌手，例如：稻香 周杰伦",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        )
        if (keyword.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "清空",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onKeywordChange("") }
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Accent)
                .clickable(onClick = onSearchNow),
            contentAlignment = Alignment.Center
        ) {
            if (searching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "立即搜索",
                    tint = Color.White,
                    modifier = Modifier.size(23.dp)
                )
            }
        }
    }
}

// --------------------------------------------------------------- 保存到网盘

/** 白卡：两个网盘二选一 + 剩余空间 + 目录「更改」。 */
@Composable
private fun SaveTargetCard(
    providers: List<ProviderKind>,
    selectedId: String,
    dir: String,
    appVm: AppViewModel,
    onSelect: (ProviderKind) -> Unit,
    onChangeDir: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Cloud,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "保存到网盘",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "选择目标",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(14.dp))
        ProviderKind.entries.forEach { kind ->
            ProviderOption(
                kind = kind,
                connected = kind in providers,
                selected = selectedId == kind.id,
                freeText = freeSpaceText(appVm, kind),
                onClick = { onSelect(kind) }
            )
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onChangeDir)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "保存目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    dir.ifBlank { "网盘根目录" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "更改",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = Accent
            )
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(18.dp)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun ProviderOption(
    kind: ProviderKind,
    connected: Boolean,
    selected: Boolean,
    freeText: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.07f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) Accent.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (kind == ProviderKind.ONEDRIVE) Color(0xFF1A1A1A)
                    else Color(0xFF1A1A1A)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (kind == ProviderKind.ONEDRIVE) Icons.Filled.CloudDownload
                else Icons.Filled.Cloud,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                kind.shortLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (connected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (connected) freeText else "未连接 · 去设置里登录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (connected) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(BadgeGreenBg)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(BadgeGreen)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "已连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = BadgeGreen
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) Accent else Color.Transparent)
                .border(
                    1.dp,
                    if (selected) Accent
                    else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/** 网盘剩余空间；拿不到配额时给一句说明，不编数字。 */
private fun freeSpaceText(vm: AppViewModel, kind: ProviderKind): String {
    val free = when (kind) {
        ProviderKind.WEBDAV -> vm.davQuota?.available
            ?: (vm.davCapacityBytes - vm.davUsedBytes).takeIf { vm.davCapacityBytes > 0L }
        ProviderKind.ONEDRIVE -> vm.oneDriveQuota?.remaining
    }
    return if (free != null && free > 0L) "剩余 ${formatBytes(free)}" else "剩余空间未知"
}

// --------------------------------------------------------------- 空态

/** 圆形声波空态：没有图片资源，直接 Canvas 画。 */
@Composable
private fun SoundWaveIllustration() {
    Box(
        Modifier
            .size(148.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(Accent.copy(alpha = 0.18f), Accent.copy(alpha = 0.03f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(104.dp)) {
            val heights = floatArrayOf(
                0.24f, 0.42f, 0.66f, 0.88f, 0.56f, 0.78f, 0.46f, 0.30f
            )
            val step = size.width / heights.size
            val barW = step * 0.42f
            heights.forEachIndexed { i, h ->
                val barH = size.height * h
                drawRoundRect(
                    color = Accent.copy(alpha = 0.42f + 0.5f * h),
                    topLeft = Offset(
                        i * step + (step - barW) / 2f,
                        (size.height - barH) / 2f
                    ),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SoundWaveIllustration()
        Spacer(Modifier.height(18.dp))
        Text(
            "还没有歌曲",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/**
 * 搜到的一条。
 *
 * 跟曲库的列表一个样式：歌名 + 灰色歌手，右边是来源和「导入」按钮。
 * 试听走整行点击，下载只走右边的按钮。
 */
@Composable
private fun HitRow(
    hit: MusicHit,
    state: ImportState?,
    deleting: Boolean,
    /** 正在试听这首 —— 歌名染主题色；再点一次会停止 */
    previewing: Boolean,
    /** 试听提示：挂在这一行，跟着列表滚 */
    hint: PreviewHint?,
    onImport: () -> Unit,
    onPreview: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val st = state
    val playable = st?.done == true && st.track != null
    SongRow(
        coverSeed = hit.title,
        title = hit.title,
        subtitle = listOf(hit.artist, hit.album)
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        note = hint?.text.orEmpty(),
        noteColor = if (hint?.error == true) null
        else MaterialTheme.colorScheme.onSurfaceVariant,
        showCover = false,
        playing = previewing,
        onClick = {
            if (playable) onPlay() else onPreview()
        }
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            // 高度必须固定！状态切换时整行不能忽高忽低，不然下面所有行跟着跳。
            modifier = Modifier.height(52.dp)
        ) {
            Text(
                buildString {
                    if (hit.durationSec > 0) {
                        append(formatDuration(hit.durationSec))
                        append(" · ")
                    }
                    append(hit.source)
                    if (hit.liumingyeId != null) append(" · 网盘直链")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.height(30.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                when {
                    st == null -> Box(
                        Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Accent)
                            .clickable(onClick = onImport)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "导入",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                    st.done -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (playable) "已导入 · 播放" else st.phase,
                            style = MaterialTheme.typography.labelMedium,
                            color = Accent,
                            maxLines = 1
                        )
                        if (playable) {
                            Spacer(Modifier.width(2.dp))
                            IconButton(
                                onClick = onDelete,
                                enabled = !deleting,
                                modifier = Modifier.size(28.dp)
                            ) {
                                if (deleting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "从网盘删除",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    st.failed -> Text(
                        st.message.ifBlank { st.phase },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            st.phase,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** 秒 -> mm:ss */
private fun formatDuration(sec: Int): String {
    if (sec <= 0) return "--:--"
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
