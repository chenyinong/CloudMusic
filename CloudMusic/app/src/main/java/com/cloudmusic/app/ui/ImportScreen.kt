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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudmusic.app.data.MusicHit
import com.cloudmusic.app.data.ProviderKind

/** 在线搜歌并导入网盘：网盘里没有的歌，搜一首直接传进去。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImportScreen(
    providers: List<ProviderKind>,
    onBack: () -> Unit,
    onOpenPlaylistImport: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val vm: ImportViewModel = viewModel()
    val visible = remember(vm.hits, vm.sourceFilter) { vm.visibleHits }
    // 待确认删除的条目。删的是网盘里的文件，删了就没了，所以先问一句。
    var pendingDelete by remember { mutableStateOf<MusicHit?>(null) }

    // 进来的时候重新读一遍「已导入」记录 ——
    // 曲库那边删了文件，这里的记录也得跟着变，不然点「播放」会播一个已经不存在的东西。
    LaunchedEffect(Unit) { vm.reloadImported() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    "导入歌曲到网盘",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
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
                // 搜歌和导歌单是两条路，挨着放，用户不用先退回曲库
                TextButton(onClick = onOpenPlaylistImport) { Text("导歌单") }
            }
        )

        // 整个页面一起滚。
        //
        // 之前上半部分（搜索框、目录设置）是固定的，只有下面的结果列表能滑，
        // 用户想改个目录得先把列表滑到底 —— 屏幕上大半地方是空的。
        //
        // 所以整页做成一个大 LazyColumn：表单当第一个 item，结果接着往下排。
        // （不能用 Column + verticalScroll 包 LazyColumn，那样列表拿不到高度约束。）
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
                ) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        // 不用「搜索」按钮了：打字停一下就自动搜，
                        // 键盘上的搜索键也能立刻搜。
                        SearchField(
                            value = vm.keyword,
                            onValueChange = { vm.onKeywordChange(it) },
                            placeholder = "例如：稻香 周杰伦",
                            onImeSearch = { vm.searchNow() },
                            trailing = {
                                if (vm.searching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        // 搜到几条、各个音源什么情况，直接贴在搜索框底下 ——
                        // 以前这些字在下面，滑一下就看不见了
                        Text(
                            when {
                                vm.searching -> "正在搜…"
                                vm.notes.isNotEmpty() -> vm.notes.joinToString(" · ")
                                else -> "输入后自动搜索"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            item(key = "form") {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    var dirText by remember(vm.dir) { mutableStateOf(vm.dir) }
                    // 「导入到 + 保存目录」收进一张白卡，跟设置页一个结构
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(14.dp)
                    ) {
                        Text(
                            "导入到",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 轻量小胶囊，不要 FilterChip 那种粗边框
                            providers.forEach { p ->
                                val selected = vm.providerId == p.id
                                Text(
                                    p.shortLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    // 和曲库页筛选胶囊一致：选中实底黑 + 白字
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { vm.setProvider(p) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "保存到网盘的目录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        // 自绘输入框：浅灰底、无边框、不用浮动 label
                        BasicTextField(
                            value = dirText,
                            onValueChange = { dirText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 12.dp),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (dirText.isEmpty()) {
                                        Text(
                                            "相对网盘根目录，留空就是根目录",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "改完记得保存",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            SmallPill("保存目录", MaterialTheme.colorScheme.primary) {
                                vm.changeDir(dirText.trim())
                            }
                        }
                    }

                    if (vm.message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            vm.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (vm.importedCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已导入 ${vm.importedCount} 首，返回曲库重新扫描就能看到",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            // 来源过滤：想找完整版就切到夸克网盘，不想看网盘的切回网易云
            if (vm.hits.isNotEmpty()) {
                item(key = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SourceFilter.entries.forEach { f ->
                            val selected = vm.sourceFilter == f
                            Text(
                                "${f.label} ${vm.countOf(f)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                // 和「导入到」胶囊、曲库页筛选胶囊一致：选中实底黑 + 白字
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { vm.sourceFilter = f }
                                    .padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // 批量导入。
            //
            // 网盘那头有一段「等服务端处理」的固定等待（OneDrive 实测 13 秒上下，
            // 跟文件大小无关）。一首一首导，这段等待要付 N 遍；
            // 一起导，等待重叠起来，整批就快得多。
            if (vm.hits.isNotEmpty()) {
                item(key = "batch") {
                    val pending = vm.pendingOf(visible)
                    val running = vm.states.count {
                        it.value.phase.isNotBlank() && !it.value.done && !it.value.failed
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SmallPill(
                            if (pending > 0) "并发导入剩下的 $pending 首" else "这批都导完了",
                            MaterialTheme.colorScheme.primary
                        ) {
                            vm.importAll(visible)
                        }
                        if (running > 0) {
                            Text(
                                "进行中 $running 首",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            if (vm.hits.isEmpty()) {
                item(key = "empty") {
                    EmptyHint("搜一首歌，下载后直接传进你的网盘")
                }
            } else if (visible.isEmpty()) {
                item(key = "empty-filter") {
                    EmptyHint("「${vm.sourceFilter.label}」这个源没搜到东西")
                }
            } else {
                items(visible, key = { it.id }) { hit ->
                    HitRow(
                        hit = hit,
                        state = vm.states[hit.id],
                        deleting = hit.id in vm.deletingIds,
                        onImport = { vm.import(hit) },
                        onPlay = {
                            // 已经导进网盘了，直接播 —— 别再下一遍
                            if (!vm.playImported(hit)) vm.import(hit)
                            onOpenPlayer()
                        },
                        onDelete = { pendingDelete = hit }
                    )
                }
            }
            // 底部留白由 Root 统一处理（迷你播放器 + 导航栏）
            item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
        }
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

/** 结果为空时给一句话提示。 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 搜到的一条。
 *
 * 跟曲库的列表一个样式（网易云那种）：方形封面 + 大号歌名 + 灰色歌手，
 * 右边是来源和「导入」按钮。
 */
@Composable
private fun HitRow(
    hit: MusicHit,
    state: ImportState?,
    deleting: Boolean,
    onImport: () -> Unit,
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
        note = "",
        // QQ 的搜索结果不带封面
        showCover = false,
        onClick = {
            when {
                // 已经导进网盘了 —— 直接播，别再下一遍
                playable -> onPlay()
                // 正在导入中就别重复触发
                st == null || st.failed -> onImport()
                // 标着「已导入」但拿不到曲目（比如网盘断了）：重新导一次
                else -> onImport()
            }
        }
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            // 高度必须固定！
            //
            // 这一块会在「导入按钮 / 下载中 / 已导入 / 失败」之间切换，
            // 高度一变整行就变高变矮，下面所有行跟着上移下移 ——
            // 用户点完「导入」再点一下同一个位置，命中的就成了下一首。
            modifier = Modifier.height(52.dp)
        ) {
            Text(
                buildString {
                    // 网盘源的搜索结果不带时长，没登录夸克时更拿不到
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
            // 操作区也固定 30dp，状态切换只换内容不换高度。
            // 注意不能加 fillMaxWidth —— 它会在 Row 里先把整行宽度吃光，
            // 左边 weight(1f) 的歌名歌手就被压成 0 宽度（字直接消失）。
            Box(
                Modifier.height(30.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                when {
                    st == null -> Box(
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
                    st.done -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // 「已导入」的条目点一下就是播放，标出来免得用户以为还要再导一次
                            if (playable) "已导入 · 播放" else st.phase,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        // 导错了要能删掉 —— 删的是网盘里的文件本体
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
                        // 失败原因放右边这块固定区域，别塞到左边第三行 ——
                        // 左边多一行，整行高度就变了，列表会跟着跳
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
