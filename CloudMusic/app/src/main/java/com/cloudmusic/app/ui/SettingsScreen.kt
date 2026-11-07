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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
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
            // ── 已连接的网盘 ────────────────────────────────
            SettingsCard("网盘连接", Icons.Filled.Cloud) {
                if (vm.connectedProviders.isEmpty()) {
                    Text(
                        "还没有连接任何网盘",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    vm.connectedProviders.forEachIndexed { i, p ->
                        if (i > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            )
                        }
                        ProviderBlock(vm = vm, provider = p)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "两个网盘可以同时连着，曲库会合并在一起，可以按网盘筛选。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "应用密码和登录令牌只保存在本机，不会上传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 外观 ───────────────────────────────────────
            SettingsCard("外观", Icons.Filled.Palette) {
                SwitchRow(
                    title = "跟随系统深色模式",
                    subtitle = "开启后随系统切换深色；关闭则固定浅色",
                    checked = vm.themeFollowSystem,
                    onChange = { vm.themeFollowSystem = it }
                )
            }

            // 添加另一个网盘：跳到它的登录页去
            if (vm.connectedProviders.size == 1) {
                val other = if (ProviderKind.ONEDRIVE in vm.connectedProviders) {
                    ProviderKind.WEBDAV
                } else {
                    ProviderKind.ONEDRIVE
                }
                SettingsCard("添加网盘", Icons.Filled.Add) {
                    ActionRow(
                        text = "添加 ${other.label}",
                        onClick = {
                            vm.switchProvider(other)
                            vm.screen = Screen.LOGIN
                        }
                    )
                }
            }

            // ── 歌词 ───────────────────────────────────────
            SettingsCard("歌词", Icons.Filled.MusicNote) {
                SwitchRow(
                    title = "在线歌词",
                    subtitle = "开启后按歌名 + 歌手去 QQ 音乐匹配歌词，QQ 没有的再去下歌吧找；" +
                        "关闭则只用网盘里同名的 .lrc 文件",
                    checked = vm.onlineLyrics,
                    onChange = { vm.onlineLyrics = it }
                )
            }

            // ── 夸克网盘：下载链路的中转站 ────────────────────
            SettingsCard("下载中转", Icons.Filled.CloudDownload) {
                Text(
                    "夸克网盘",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                if (vm.quarkLoggedIn) {
                    Text(
                        "已登录${if (vm.quarkAccount.isNotBlank()) "：${vm.quarkAccount}" else ""}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "「下歌吧」音源已就绪 —— 网易云、酷我都搜不到的歌会走这里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (vm.quarkCookieStale) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "登录状态可能过期了，导歌失败的话重新登录一次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("直链诊断", MaterialTheme.colorScheme.primary) {
                            vm.screen = Screen.QUARK_DIAG
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
                        SmallButton("登录夸克", MaterialTheme.colorScheme.primary) {
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

            // ── 维护操作 ─────────────────────────────────────
            SettingsCard("维护", Icons.Filled.Settings) {
                ActionRow("重新扫描音乐库") { vm.rescan() }
                RowDivider()
                ActionRow("清理播放缓存") { vm.clearCache() }
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

            // ── 关于 ────────────────────────────────────────
            SettingsCard("关于", Icons.Filled.Info) {
                Text(
                    "云音乐",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "云音乐 v${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.BUILD_TIME}）",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "支持坚果云、Nextcloud、群晖等所有标准 WebDAV 服务，也支持微软 OneDrive，" +
                        "两个网盘可以同时连着。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "本应用仅播放你自己的文件，不提供任何公共资源库。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 分组 = 卡片外一行小标题（带图标）+ 一张白卡。
 *
 * 高级感主要来自这层节奏：标题在卡片外面、字距拉开、图标收敛成一根细线，
 * 卡片只用「极淡描边 + 大圆角」立形，不挂投影（投影在浅灰底上会显脏）。
 */
@Composable
private fun SettingsCard(
    label: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 4.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        SoftSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/** 卡片内的一行操作（点击整行）：正文色 + 右侧箭头，彩色只留给危险操作 */
@Composable
private fun ActionRow(
    text: String,
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
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
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

/** 一个网盘的信息块：标题行 + 详情 + 空间占用 */
@Composable
private fun ProviderBlock(vm: AppViewModel, provider: ProviderKind) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (provider) {
                    ProviderKind.WEBDAV -> "坚果云 / WebDAV"
                    ProviderKind.ONEDRIVE -> "OneDrive"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (provider == vm.providerKind) {
                Text(
                    "当前使用中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
            }
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
        Spacer(Modifier.height(6.dp))
        when (provider) {
            ProviderKind.WEBDAV -> {
                InfoLine("服务器", vm.serverDefault)
                InfoLine("账号", vm.accountDefault.ifBlank { "未登录" })
                Spacer(Modifier.height(6.dp))
                DavQuotaRow(vm)
            }
            ProviderKind.ONEDRIVE -> {
                InfoLine("账号", vm.oneDriveAccount.ifBlank { "已连接" })
                Spacer(Modifier.height(6.dp))
                OneDriveQuotaRow(vm)
            }
        }
    }
}

/** 「标签  值」一行，值长了就省略，不换行 */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
                color = MaterialTheme.colorScheme.primary,
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
            color = MaterialTheme.colorScheme.primary,
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
                color = MaterialTheme.colorScheme.primary,
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
            color = MaterialTheme.colorScheme.primary,
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
                color = MaterialTheme.colorScheme.primary,
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
