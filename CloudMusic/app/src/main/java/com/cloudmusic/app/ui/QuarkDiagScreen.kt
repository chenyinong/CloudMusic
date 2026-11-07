package com.cloudmusic.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 夸克下载链路的诊断台。
 *
 * 目的只有一个：**一次性定性「登录态签出来的直链能不能下」**。
 * 能下就照着这条路把导入接通；下不到就退到「转存到自己盘」那条路。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuarkDiagScreen(
    loggedIn: Boolean,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val vm: QuarkDiagViewModel = viewModel()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("夸克直链诊断", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 登录状态
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (loggedIn) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (loggedIn) "夸克已登录" else "夸克未登录",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (loggedIn) {
                                "直链会用这个登录态来签，游客态签的会被 CDN 412 拦掉"
                            } else {
                                "没登录的话，签出来的直链下载会被拦（HTTP 412）"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!loggedIn) {
                        OutlinedButton(onClick = onOpenLogin) { Text("去登录") }
                    }
                }
            }

            // 第一步：搜一首歌
            Text("① 从 liumingye 搜一首歌", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vm.keyword,
                    onValueChange = { vm.keyword = it },
                    label = { Text("歌名") },
                    placeholder = { Text("例如 稻香") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.search() },
                    enabled = !vm.searching
                ) {
                    if (vm.searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("搜")
                    }
                }
            }
            if (vm.hits.isNotEmpty()) {
                vm.hits.take(8).forEach { h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.pick(h) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${h.title} - ${h.artist}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                h.quality.joinToString(" · ").ifBlank { "未知来源" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { vm.pick(h) }) { Text("用它") }
                    }
                }
            }

            // 第二步：贴分享链接
            Text("② 贴一个分享链接", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = vm.shareUrl,
                onValueChange = { vm.shareUrl = it },
                label = { Text("夸克分享链接") },
                placeholder = { Text("https://pan.quark.cn/s/xxxxxxxx") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.resolve(vm.shareUrl) },
                    enabled = !vm.resolving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (vm.resolving) "解析中…" else "解析直链")
                }
                OutlinedButton(
                    onClick = { vm.saveToMyDrive() },
                    enabled = !vm.resolving && vm.file != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("转存并继续")
                }
            }

            if (vm.message.isNotBlank()) {
                Text(
                    vm.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 步骤日志
            if (vm.steps.isNotEmpty()) {
                Text("过程", fontWeight = FontWeight.Bold)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        vm.steps.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // 第三步：试下载
            if (vm.file != null) {
                Text("③ 试下载", fontWeight = FontWeight.Bold)
                val f = vm.file!!
                Text(
                    "${f.fileName} · ${f.size} 字节 · ${f.durationSec}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (vm.isArchive) {
                    Spacer(Modifier.height(6.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "这是压缩包",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (vm.archiveFiles.isEmpty()) {
                                Text(
                                    "包里的内容没列出来（夸克可能不让预览这个包）",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                val items = vm.archiveFiles.take(12)
                                for (i in items.indices) {
                                    Text(
                                        "· ${items[i].fileName}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { vm.probeDownload() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("读一下文件头试试")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { vm.probeVariants() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("三种请求姿势对比")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { vm.fullImport() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("完整下载一次并传到坚果云")
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { vm.saveImportAndClean() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("转存 → 下载 → 传坚果云 → 清理（推荐）")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "分享直链会被 CDN 拦（412），得先转存到自己盘再下。" +
                        "夸克支持在线解压，遇到压缩包也能处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                vm.probe?.let { p ->
                    Spacer(Modifier.height(6.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (p.looksLikeAudio) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "带 Cookie：HTTP ${p.httpCode} · ${p.contentType}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "读到 ${p.readBytes} 字节 · 文件头 ${p.magic}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            vm.probeNoCookie?.let { q ->
                                Text(
                                    "不带 Cookie：HTTP ${q.httpCode} · ${q.contentType} · ${q.readBytes} 字节",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                p.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (p.looksLikeAudio) {
                                Text(
                                    "这条路通了 —— 带 Cookie 能下。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
