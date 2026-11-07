package com.cloudmusic.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.dlna.DlnaDevice

/**
 * 投屏面板。
 *
 * 上半部分「让电视来播」= 手机当渲染器，电视去挑歌；
 * 下半部分「投到设备」= 手机主动把正在放的这首歌推到电视上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DlnaSheet(
    vm: DlnaViewModel,
    currentTrack: Track?,
    onDismiss: () -> Unit,
) {
    // 打开就搜一次，省得用户还要点刷新
    LaunchedEffect(Unit) {
        if (vm.devices.isEmpty()) vm.refresh()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "投屏",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (vm.discovering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新搜索设备")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 让电视来播 ----------
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Tv, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "让电视来播",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (vm.serverRunning) {
                                "已开启 · ${vm.serverAddress}\n电视的 DLNA 播放器里就能看到「云音乐」"
                            } else {
                                "开启后，电视的 DLNA 播放器里就能看到「云音乐」，可以自己挑歌"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (vm.serverRunning && !vm.ssdpAvailable) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "设备发现端口被占，电视可能搜不到本机（关掉别的投屏 App 再试）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = vm.serverRunning,
                        onCheckedChange = { vm.toggleServer() }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------- 投到设备 ----------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "投到设备",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (vm.casting != null) {
                    Text(
                        "停止",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { vm.stopCasting() }
                            .padding(4.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            val casting = vm.casting
            if (casting != null) {
                Text(
                    "正在投到「${casting.friendlyName}」：${currentTrack?.title ?: "当前歌曲"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }

            if (vm.devices.isEmpty()) {
                Text(
                    if (vm.discovering) "正在搜索局域网里的设备…" else "还没有搜到设备",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                vm.devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        active = casting?.udn == device.udn,
                        onClick = { vm.cast(device, currentTrack) }
                    )
                }
            }

            vm.error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DlnaDevice,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            if (active) Icons.Filled.CastConnected else Icons.Filled.Cast,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.friendlyName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                device.location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (active) {
            Text(
                "播放中",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
