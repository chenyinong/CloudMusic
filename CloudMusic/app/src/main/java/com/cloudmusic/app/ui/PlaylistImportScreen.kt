package com.cloudmusic.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudmusic.app.data.playlist.PlaylistPlatform
import com.cloudmusic.app.data.playlist.PlaylistTrack

/**
 * 导入歌单：给一个链接或 id，把整张歌单的**清单**拉下来存进「我的歌单」。
 *
 * 这一页一个字节的音频都不下 —— 几百首的歌单全导一遍既慢又费流量。
 * 存完进歌单列表，想听哪首点哪首，那时才去找音源、下载、传网盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistImportScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    val vm: PlaylistImportViewModel = viewModel()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("导入歌单") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            // 搜索框独占一行。
            // OutlinedTextField 有 280dp 的最小宽度，跟按钮挤一行时窄屏上按钮会被推出屏幕。
            OutlinedTextField(
                value = vm.input,
                onValueChange = { vm.input = it },
                label = { Text("歌单链接或 id") },
                placeholder = { Text("网易云 / QQ 音乐的歌单") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.load() },
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("拉取歌单")
                }
            }

            Spacer(Modifier.height(10.dp))
            // 纯数字 id 两家都是数字，让用户点一下选平台
            if (vm.input.trim().matches(Regex("\\d+"))) {
                Text(
                    "这是纯数字，帮我认一下是哪个平台的歌单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = vm.platformChoice == null || vm.platformChoice == PlaylistPlatform.NETEASE,
                        onClick = { vm.platformChoice = PlaylistPlatform.NETEASE },
                        label = { Text("网易云") }
                    )
                    FilterChip(
                        selected = vm.platformChoice == PlaylistPlatform.QQ,
                        onClick = { vm.platformChoice = PlaylistPlatform.QQ },
                        label = { Text("QQ音乐") }
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            if (vm.message.isNotBlank()) {
                Text(
                    vm.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val pl = vm.playlist
        if (pl == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "支持网易云和 QQ 音乐的歌单",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "只把歌单清单存下来，不下载歌曲",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "之后在「我的歌单」里点单曲再导入，想听哪首下哪首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        // 歌单预览：只列前若干首，让人确认拉对了没
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(pl.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "共 ${pl.tracks.size} 首 · ${pl.platform.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(pl.tracks.take(30), key = { _, t -> t.key }) { idx, t ->
                PlaylistPreviewRow(t, idx + 1)
            }
            if (pl.tracks.size > 30) {
                item {
                    Text(
                        "… 还有 ${pl.tracks.size - 30} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (vm.savedKey != null) {
                Button(
                    onClick = { vm.savedKey?.let(onOpenPlaylist) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("去看看这张歌单")
                }
            } else {
                Button(
                    onClick = { vm.save() },
                    enabled = !vm.saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (vm.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("存进我的歌单（不下载歌曲）")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "只存清单，一首歌都不下载。之后在「我的歌单」里点单曲再导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlaylistPreviewRow(track: PlaylistTrack, index: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$index",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(track.artist, formatSec(track.durationSec))
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 秒 -> mm:ss */
internal fun formatSec(sec: Int): String {
    if (sec <= 0) return "--:--"
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
