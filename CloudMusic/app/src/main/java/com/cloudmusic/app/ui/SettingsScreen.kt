package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudmusic.app.BuildConfig
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.formatBytes

/**
 * 设置页。
 *
 * 版式照 QQ 音乐设置页来：**分组白卡片 + 卡片内一行一项**，
 * 每行都是「标题（+ 小字说明）+ 右侧控件」，字号只有三档（标题 15 / 正文 13 / 说明 11），
 * 不再把服务器地址、账号、进度条、提示语全堆在一段里。
 *
 * 视觉上再加一层：顶部深色渐变配额总览卡、分组右侧小字、网盘彩色图标块与
 * 「已连接」胶囊徽章。紫色强调色只在设置页局部用，不动全局的近黑主色。
 */
/** 设置页局部强调色，避免影响其它页面的按钮/选中态 */
private val Accent = Color(0xFF1A1A1A)
private val AccentDeep = Color(0xFF2E2A55)
private val AccentDarker = Color(0xFF1B1A33)
private val BadgeGreen = Color(0xFF1E8E57)
private val BadgeGreenBg = Color(0xFFE6F6EC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    var showAbout by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    // 用实底：滚动时卡片不会从标题后面透出来
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 顶部：账号与偏好总览 ────────────────────────
            Text(
                "ACCOUNT & PREFERENCES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
            QuotaOverviewCard(vm)

            // ── 已连接的网盘：一个网盘一张白卡 ──────────────
            SectionHeader(
                label = "网盘连接",
                icon = Icons.Filled.Cloud,
                trailing = "已连接 ${vm.connectedProviders.size} 个"
            )
            if (vm.connectedProviders.isEmpty()) {
                PlainCard {
                    Text(
                        "还没有连接任何网盘",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                vm.connectedProviders.forEach { p ->
                    PlainCard { ProviderBlock(vm = vm, provider = p) }
                }
            }
            Text(
                "两个网盘可以同时连着，曲库会合并在一起；应用密码和登录令牌只保存在本机。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // 添加另一个网盘：跳到它的登录页去
            if (vm.connectedProviders.size == 1) {
                val other = if (ProviderKind.ONEDRIVE in vm.connectedProviders) {
                    ProviderKind.WEBDAV
                } else {
                    ProviderKind.ONEDRIVE
                }
                PlainCard {
                    ActionRow(
                        text = "添加 ${other.label}",
                        icon = Icons.Filled.Add
                    ) {
                        vm.switchProvider(other)
                        vm.screen = Screen.LOGIN
                    }
                }
            }

            // ── 云同步：跟网页版云音乐共用账号 ────────────────
            SettingsCard(
                label = "云同步",
                icon = Icons.Filled.CloudSync,
                trailing = if (vm.cloudLoggedIn) "已登录" else "未登录"
            ) {
                if (!vm.cloudLoggedIn) {
                    Text(
                        "登录网页版云音乐的账号，「我的」页的收藏和「歌单」页的歌单就会两边同步；" +
                            "夸克登录态也能存到云端，换设备免扫码。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("登录云端账号", Accent) {
                            vm.cloudLoginReturn = Screen.SETTINGS
                            vm.screen = Screen.CLOUD_LOGIN
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandIcon(Icons.Filled.CloudSync, Color(0xFF2F7BE5))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                vm.cloudUser.ifBlank { "已登录" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                vm.cloudServer,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        StatusBadge("已连接")
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("立即同步", Accent) { vm.cloudSyncNow() }
                        SmallButton("上传夸克登录态", MaterialTheme.colorScheme.onSurfaceVariant) {
                            vm.pushQuarkToCloud()
                        }
                        SmallButton("退出登录", MaterialTheme.colorScheme.error) {
                            vm.cloudLogout()
                        }
                    }
                    if (vm.cloudMessage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            vm.cloudMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "云端只存「歌是哪首」，直链还在本机解析 —— 断网也能听已经同步下来的歌。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 外观与歌词 ─────────────────────────────────
            SettingsCard("外观与歌词", Icons.Filled.Palette) {                SwitchRow(
                    title = "跟随系统深色模式",
                    subtitle = "跟随系统切换明暗主题",
                    checked = vm.themeFollowSystem,
                    onChange = { vm.themeFollowSystem = it }
                )
                RowDivider()
                SwitchRow(
                    title = "在线歌词",
                    subtitle = "优先匹配在线歌词，也可读取本地 .lrc",
                    checked = vm.onlineLyrics,
                    onChange = { vm.onlineLyrics = it }
                )
            }

            // ── 夸克网盘：下载链路的中转站 ────────────────────
            // 进设置页每次都真实校验一次登录态，失效了当场亮红灯
            LaunchedEffect(vm.quarkLoggedIn) {
                if (vm.quarkLoggedIn) vm.checkQuarkLogin()
            }
            val quarkInvalid = vm.quarkCheckState == QuarkCheckState.INVALID
            SettingsCard(
                label = "下载中转",
                icon = Icons.Filled.CloudDownload,
                trailing = when {
                    !vm.quarkLoggedIn -> "未登录"
                    quarkInvalid -> "已失效"
                    else -> "已登录"
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandIcon(Icons.Filled.CloudDownload, Color(0xFF7A5AF8))
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "夸克网盘",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (vm.quarkLoggedIn) StatusBadge(
                        if (quarkInvalid) "已失效" else "已登录",
                        error = quarkInvalid
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (vm.quarkLoggedIn) {
                    Text(
                        vm.quarkAccount.ifBlank { "作为补充音源，搜索未命中的歌曲" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    // 登录有效期：夸克 cookie 一般一个多月就失效，别等导歌失败才发现
                    val days = vm.quarkLoginDays
                    Text(
                        if (days >= 30) {
                            "登录有效期：已登录 $days 天，已超过常见有效期（约 30 天）"
                        } else {
                            "登录有效期：已登录 $days 天，约 30 天需重新登录（剩 ${30 - days} 天左右）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when (vm.quarkCheckState) {
                        QuarkCheckState.CHECKING -> Text(
                            "正在验证登录是否有效…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        QuarkCheckState.VALID -> Text(
                            "✓ 实时验证：登录有效",
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent
                        )
                        QuarkCheckState.INVALID -> {
                            Text(
                                "✗ 实时验证：登录已失效，导歌会失败，请重新登录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            if (vm.quarkCheckReason.isNotBlank()) {
                                Text(
                                    vm.quarkCheckReason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        QuarkCheckState.IDLE -> {}
                    }
                    if (vm.quarkCookieStale && !quarkInvalid) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "登录状态可能过期了，导歌失败的话重新登录一次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("直链诊断", Accent) {
                            vm.screen = Screen.QUARK_DIAG
                        }
                        SmallButton("重新验证", MaterialTheme.colorScheme.onSurfaceVariant) {
                            vm.checkQuarkLogin()
                        }
                        SmallButton("退出登录", MaterialTheme.colorScheme.error) {
                            vm.clearQuark()
                        }
                    }
                } else {
                    Text(
                        "有些歌公开音源下不到，得从网盘资源站找、再经夸克转到坚果云。" +
                            "登录夸克只是让 App 能拿到能下载的直链，不会碰你的网盘内容。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("登录夸克", Accent) {
                            vm.screen = Screen.QUARK_LOGIN
                        }
                        SmallButton("直链诊断", MaterialTheme.colorScheme.onSurfaceVariant) {
                            vm.screen = Screen.QUARK_DIAG
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "登录凭据只存在本机。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 维护 ────────────────────────────────────────
            SettingsCard("维护", Icons.Filled.Settings) {
                ActionRow("重新扫描音乐库", icon = Icons.Filled.Cloud) { vm.rescan() }
                RowDivider()
                ActionRow("清理播放缓存", icon = Icons.Filled.MusicNote) { vm.clearCache() }
                RowDivider()
                ActionRow(
                    text = "关于与版本",
                    icon = Icons.Filled.Info,
                    trailing = "v${BuildConfig.VERSION_NAME}"
                ) { showAbout = true }
                RowDivider()
                ActionRow(
                    text = "退出 ${vm.providerKind.shortLabel}",
                    error = true
                ) {
                    vm.disconnectCurrentProvider()
                    // 断掉最后一个网盘时会被送到登录页，这时不能再退回曲库
                    if (vm.screen == Screen.SETTINGS) onBack()
                }
            }

            Text(
                "感谢你用自己的方式听歌",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

/** 分组标题：小图标 + 深色加粗标题，右侧可挂一句状态小字。 */
@Composable
private fun SectionHeader(
    label: String,
    icon: ImageVector,
    trailing: String? = null,
) {
    Row(
        Modifier.padding(start = 4.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 白卡本体：极淡描边 + 大圆角，不挂投影（浅灰底上投影会显脏）。 */
@Composable
private fun PlainCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        content = content
    )
}

/** 分组 = 标题 + 一张白卡。 */
@Composable
private fun SettingsCard(
    label: String,
    icon: ImageVector,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(label, icon, trailing)
        PlainCard(content = content)
    }
}

/** 顶部深色渐变总览卡：把两个网盘的已用 / 总量汇总到一处。 */
@Composable
private fun QuotaOverviewCard(vm: AppViewModel) {
    val dav = vm.davQuota
    val davUsed = dav?.used ?: vm.davUsedBytes
    val davTotal = dav?.total ?: vm.davCapacityBytes
    val od = vm.oneDriveQuota
    val used = davUsed + (od?.used ?: 0L)
    val total = davTotal + (od?.total ?: 0L)
    val ratio = if (total > 0L) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val free = (total - used).coerceAtLeast(0L)

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(AccentDeep, AccentDarker)))
            .padding(18.dp)
    ) {
        Column {
            Text(
                "云盘配额",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${vm.connectedProviders.size}",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "个网盘已连接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已使用 ${formatBytes(used)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "可用 ${formatBytes(free)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.height(7.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.16f))
            ) {
                if (ratio > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(ratio)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF9B8BFF))
                    )
                }
            }
        }
    }
}

/** 网盘 / 夸克的彩色圆角图标块。 */
@Composable
private fun BrandIcon(icon: ImageVector, bg: Color) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(21.dp)
        )
    }
}

/** 「已连接 / 已登录」绿色胶囊徽章；error=true 变红色「已失效」样式。 */
@Composable
private fun StatusBadge(text: String, error: Boolean = false) {
    val tint = if (error) MaterialTheme.colorScheme.error else BadgeGreen
    val bg = if (error) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else BadgeGreenBg
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(tint)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}

/** 关于与版本：原来单独一张卡，收进维护行点开的弹窗。 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("云音乐") },
        text = {
            Column {
                Text(
                    "云音乐 v${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.BUILD_TIME}）",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "支持坚果云、Nextcloud、群晖等所有标准 WebDAV 服务，也支持微软 OneDrive，" +
                        "两个网盘可以同时连着。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "本应用仅播放你自己的文件，不提供任何公共资源库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 卡片内的一条分隔线 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    )
}

/** 标题 + 说明 + 右侧开关 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        SoftSwitch(checked = checked, onCheckedChange = onChange, trackColor = Accent)
    }
}

/** 卡片内的一行操作（点击整行）：正文色 + 右侧箭头，彩色只留给危险操作 */
@Composable
private fun ActionRow(
    text: String,
    icon: ImageVector? = null,
    trailing: String? = null,
    error: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 小胶囊按钮：淡底 + 一圈更淡的描边，比纯色块克制 */
@Composable
private fun SmallButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(32.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.09f))
            .border(1.dp, color.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

/** 一个网盘：彩色图标块 + 账号 + 已连接徽章，下面是空间占用。 */
@Composable
private fun ProviderBlock(vm: AppViewModel, provider: ProviderKind) {
    val isDav = provider == ProviderKind.WEBDAV
    val account = if (isDav) {
        vm.accountDefault.ifBlank { vm.serverDefault }
    } else {
        vm.oneDriveAccount.ifBlank { "已连接" }
    }
    val brandColor = if (isDav) Accent else Color(0xFF2F7BE5)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandIcon(Icons.Filled.Cloud, brandColor)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isDav) "坚果云" else "OneDrive",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isDav) "WebDAV · $account" else account,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge("已连接")
        }
        Spacer(Modifier.height(13.dp))
        if (isDav) DavQuotaRow(vm) else OneDriveQuotaRow(vm)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (provider == vm.providerKind) {
                Text(
                    "当前使用中",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "断开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { vm.disconnect(provider) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 空间占用一条：进度条 + 已用/总量 + 刷新。
 *
 * 「刷新」用固定宽度的文字按钮，不再放进 weight 列里 —— 之前被压成竖排两个字。
 */
@Composable
private fun QuotaRow(
    used: Long,
    total: Long,
    ratio: Float,
    remaining: Long,
    songsLeft: Long,
    onRefresh: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已用 ${formatBytes(used)} / ${formatBytes(total)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "刷新",
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = ratio,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "剩余 ${formatBytes(remaining)} · 大约还能放 $songsLeft 首 30MB 的歌",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 坚果云的空间占用。
 *
 * 两套机制：
 * - 服务器直接给配额（群晖、Nextcloud 的 `quota-available-bytes`）→ 直接显示；
 * - 坚果云不给（PROPFIND 一律 404）→ 已用靠扫描实测，容量让用户按套餐选一下。
 */
@Composable
private fun DavQuotaRow(vm: AppViewModel) {
    val quota = vm.davQuota
    val capacity = vm.davCapacityBytes
    val context = LocalContext.current
    var showCapacity by remember { mutableStateOf(false) }

    val used = vm.davUsedBytes
    if (quota != null) {
        // 服务器直接报了配额，一行说完
        QuotaRow(
            used = quota.used,
            total = quota.total,
            ratio = quota.usedRatio,
            remaining = quota.available,
            songsLeft = quota.songsLeft,
            onRefresh = { vm.refreshDavQuota() }
        )
    } else {
        // 坚果云：已用靠扫描，容量靠用户选
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (used > 0L) "已用 ${formatBytes(used)}" else "已用（重新扫描后统计）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (capacity > 0L) "容量 ${formatBytes(capacity)}" else "设置容量",
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showCapacity = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (capacity > 0L) {
            Spacer(Modifier.height(6.dp))
            val remain = (capacity - used).coerceAtLeast(0L)
            QuotaRow(
                used = used,
                total = capacity,
                ratio = (used.toFloat() / capacity.toFloat()).coerceIn(0f, 1f),
                remaining = remain,
                songsLeft = remain / (30L * 1024 * 1024),
                onRefresh = { vm.rescan() }
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                "坚果云不开放配额查询，选一下套餐就能算出剩余空间",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "每月上传/下载流量额度不通过 WebDAV 公开，剩余额度请在官网查看",
            style = MaterialTheme.typography.labelSmall,
            color = Accent,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.jianguoyun.com/dav#/")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
    }

    if (showCapacity) {
        DavCapacityDialog(
            current = capacity,
            onPick = { vm.setDavCapacity(it) },
            onDismiss = { showCapacity = false }
        )
    }
}

/** 选坚果云套餐容量 */
@Composable
private fun DavCapacityDialog(
    current: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val gb = 1024L * 1024 * 1024
    val options = listOf(
        0L to "不知道 / 不显示剩余",
        1L * gb to "免费版 1 GB",
        3L * gb to "专业版 3 GB",
        30L * gb to "企业版 30 GB / 人",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        // M3 1.2 默认容器色是 surfaceContainerHigh（带主题色调，会泛绿），换成纯色
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("坚果云套餐容量") },
        text = {
            Column {
                Text(
                    "坚果云的 WebDAV 接口查不到配额，剩余空间只能按套餐容量减出来。",
                    style = MaterialTheme.typography.bodySmall
                )
                options.forEach { (bytes, label) ->
                    TextButton(
                        onClick = { onPick(bytes); onDismiss() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (bytes == current) "● $label" else "○ $label",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * OneDrive 的空间占用。
 *
 * 免费版只有 5 GB，Microsoft 365 个人版 1 TB —— 放歌的话很容易撞到上限，
 * 所以这里直接把「还剩多少、还能放几首」摆出来，不用再去开网页版看。
 */
@Composable
private fun OneDriveQuotaRow(vm: AppViewModel) {
    val quota = vm.oneDriveQuota
    if (quota == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (vm.oneDriveQuotaLoading) "空间：正在查询…" else "空间：未知",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (vm.oneDriveQuotaLoading) "查询中" else "查询",
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { vm.refreshOneDriveQuota() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        return
    }

    QuotaRow(
        used = quota.used,
        total = quota.total,
        ratio = quota.usedRatio,
        remaining = quota.remaining,
        songsLeft = quota.songsLeft,
        onRefresh = { vm.refreshOneDriveQuota() }
    )
    if (quota.deleted > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "回收站里还有 ${formatBytes(quota.deleted)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
