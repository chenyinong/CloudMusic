package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
 *
 * 样式跟设置页/菜单统一：浅灰底 + 白色圆角卡 + 极淡描边，没有厚重阴影。
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        // 只留下面自己画的那条细拖柄，避免和默认拖柄叠成两条
        dragHandle = null
    ) {
        // 跟播放页同款氛围底：顶部近白带一点色、越往下彩越足，过渡连续
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .songAmbientBackground(currentTrack?.title.orEmpty())
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 28.dp)
            ) {
            // 顶部小拖柄
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
            )

            // ── 标题：居中，右侧一个轻量的刷新按钮 ──────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            ) {
                Text(
                    "投屏",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center)
                )
                Box(Modifier.align(Alignment.CenterEnd)) {
                    if (vm.discovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = { vm.refresh() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "重新搜索设备",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            val casting = vm.casting
            // ── 正在投屏：独立状态卡 ──────────────────────────
            if (casting != null) {
                SheetCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIcon(Icons.Filled.CastConnected, active = true)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "正在投屏到",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                casting.friendlyName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        SoftPill("停止", MaterialTheme.colorScheme.error) {
                            vm.stopCasting()
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前歌曲 · ${currentTrack?.title ?: "未在播放"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── 让电视来播 ──────────────────────────────────
            SheetCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Icons.Filled.Tv, active = vm.serverRunning)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "让电视来播",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (vm.serverRunning) "已开启 · 电视可主动挑歌"
                            else "开启后电视可主动挑歌",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (vm.serverRunning && vm.serverAddress.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                vm.serverAddress,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (vm.serverRunning && !vm.ssdpAvailable) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "设备发现端口被占，电视可能搜不到本机（关掉别的投屏 App 再试）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    SoftSwitch(vm.serverRunning) { vm.toggleServer() }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── 可用设备 ────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "可用设备",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (vm.discovering) {
                    Text(
                        "搜索中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            SheetCard {
                if (vm.devices.isEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (vm.discovering) "正在搜索局域网里的设备…" else "还没有搜到设备",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!vm.discovering) {
                            Spacer(Modifier.height(10.dp))
                            SoftPill("重新搜索", MaterialTheme.colorScheme.primary) {
                                vm.refresh()
                            }
                        }
                    }
                } else {
                    vm.devices.forEachIndexed { i, device ->
                        if (i > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        }
                        DeviceRow(
                            device = device,
                            active = casting?.udn == device.udn,
                            onClick = { vm.cast(device, currentTrack) }
                        )
                    }
                }
            }

            vm.error?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            }
        }
    }
}

/**
 * 面板里的内容容器：**不要白底**。
 *
 * 面板本身已经是跟着歌曲变色的渐变底，再套一层白卡就成了
 * 「渐变里挖两个白框」，很突兀。这里只负责留白和排版，
 * 结构靠分组小标题 + 行间细线来分。
 */
@Composable
private fun SheetCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        content = content
    )
}

/**
 * 圆形小图标底。
 *
 * 底色用半透明而不是固定的中性灰 —— 面板底是跟着歌曲变的渐变，
 * 死灰的圆块会像贴上去的补丁，半透明就能跟底色融在一起。
 */
@Composable
private fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            },
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * 淡色小胶囊：12% 的彩色底 + 同色文字。
 * 投屏面板是渐变底，实心按钮太抢眼，这种"软胶囊"更贴合。
 */
@Composable
private fun SoftPill(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** http://192.168.1.5:9999/uuid/xxx -> 192.168.1.5:9999 */
private fun shortLocation(url: String): String =
    url.substringAfter("://", url).substringBefore("/")

@Composable
private fun DeviceRow(
    device: DlnaDevice,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundIcon(
            if (active) Icons.Filled.CastConnected else Icons.Filled.Cast,
            active = active
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.friendlyName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                shortLocation(device.location),
                style = MaterialTheme.typography.labelSmall,
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
        } else {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
