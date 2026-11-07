package com.cloudmusic.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
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

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("导入歌曲到网盘") },
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
        LazyColumn(Modifier.fillMaxSize()) {
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
                        OutlinedTextField(
                            value = vm.keyword,
                            onValueChange = { vm.onKeywordChange(it) },
                            label = { Text("歌名 + 歌手") },
                            placeholder = { Text("例如：稻香 周杰伦") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.searchNow() }),
                            trailingIcon = {
                                when {
                                    vm.searching -> CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    vm.keyword.isNotEmpty() -> IconButton(
                                        onClick = { vm.onKeywordChange("") },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "清空",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
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
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("导入到", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        providers.forEach { p ->
                            FilterChip(
                                selected = vm.providerId == p.id,
                                onClick = { vm.setProvider(p) },
                                label = { Text(p.shortLabel) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    var dirText by remember(vm.dir) { mutableStateOf(vm.dir) }
                    OutlinedTextField(
                        value = dirText,
                        onValueChange = { dirText = it },
                        label = { Text("保存到网盘的目录") },
                        supportingText = { Text("相对网盘根目录，留空就是根目录") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { vm.changeDir(dirText.trim()) }) {
                        Text("保存这个目录")
                    }

                    if (vm.message.isNotBlank()) {
                        Text(
                            vm.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (vm.importedCount > 0) {
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
                            FilterChip(
                                selected = vm.sourceFilter == f,
                                onClick = { vm.sourceFilter = f },
                                label = { Text("${f.label} ${vm.countOf(f)}") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
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
        note = if (st != null && st.failed) st.message else "",
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
        Column(horizontalAlignment = Alignment.End) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            when {
                st == null -> Button(onClick = onImport) { Text("导入") }
                st.done -> Column(horizontalAlignment = Alignment.End) {
                    Text(
                        // 「已导入」的条目点一下就是播放，标出来免得用户以为还要再导一次
                        if (playable) "已导入 · 播放" else st.phase,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // 导错了要能删掉 —— 删的是网盘里的文件本体
                    if (playable) {
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
                    st.phase,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(st.phase, style = MaterialTheme.typography.labelMedium)
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
