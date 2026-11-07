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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cloudmusic.app.BuildConfig
import com.cloudmusic.app.data.ProviderKind
import com.cloudmusic.app.data.formatBytes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已连接的网盘", fontWeight = FontWeight.Bold)
                    val connected = vm.connectedProviders
                    if (connected.isEmpty()) {
                        Text("还没有连接任何网盘")
                    }
                    connected.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                when (p) {
                                    ProviderKind.WEBDAV -> {
                                        Text("· 坚果云 / WebDAV", fontWeight = FontWeight.Medium)
                                        Text("服务器：${vm.serverDefault}")
                                        Text("账号：${vm.accountDefault.ifBlank { "未登录" }}")
                                        DavQuotaRow(vm)
                                    }
                                    ProviderKind.ONEDRIVE -> {
                                        Text("· OneDrive", fontWeight = FontWeight.Medium)
                                        Text("账号：${vm.oneDriveAccount.ifBlank { "已连接" }}")
                                        OneDriveQuotaRow(vm)
                                    }
                                }
                                if (p == vm.providerKind) {
                                    Text(
                                        "当前使用中",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            TextButton(onClick = { vm.disconnect(p) }) {
                                Text("断开 ${p.shortLabel}", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Text(
                        "两个网盘可以同时连着，曲库会合并在一起，可以按网盘筛选。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "应用密码和登录令牌只保存在本机，不会上传。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // 添加另一个网盘：跳到它的登录页去
            if (vm.connectedProviders.isNotEmpty() && vm.connectedProviders.size < 2) {
                val other = if (ProviderKind.ONEDRIVE in vm.connectedProviders) {
                    ProviderKind.WEBDAV
                } else {
                    ProviderKind.ONEDRIVE
                }
                OutlinedButton(
                    onClick = {
                        vm.switchProvider(other)
                        vm.screen = Screen.LOGIN
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("添加 ${other.label}")
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("在线歌词", fontWeight = FontWeight.Bold)
                        Text(
                            "开启后按歌名 + 歌手去 QQ 音乐匹配歌词，QQ 没有的再去下歌吧找；关闭则只用网盘里同名的 .lrc 文件",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = vm.onlineLyrics,
                        onCheckedChange = { vm.onlineLyrics = it }
                    )
                }
            }

            // 夸克网盘：下载链路的中转站
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("夸克网盘（下载中转）", fontWeight = FontWeight.Bold)
                    if (vm.quarkLoggedIn) {
                        Text(
                            "已登录${if (vm.quarkAccount.isNotBlank()) "：${vm.quarkAccount}" else ""}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "「下歌吧」音源已就绪 —— 网易云、酷我都搜不到的歌会走这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (vm.quarkCookieStale) {
                            Text(
                                "登录状态可能过期了，导歌失败的话重新登录一次",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.screen = Screen.QUARK_DIAG }) {
                                Text("直链诊断")
                            }
                            TextButton(onClick = { vm.clearQuark() }) {
                                Text("退出", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Text(
                            "有些歌公开音源下不到，得从网盘资源站找、再经夸克转到坚果云。" +
                                "登录夸克只是让 App 能拿到能下载的直链，不会碰你的网盘内容。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.screen = Screen.QUARK_LOGIN }) {
                                Text("登录夸克")
                            }
                            OutlinedButton(onClick = { vm.screen = Screen.QUARK_DIAG }) {
                                Text("直链诊断")
                            }
                        }
                    }
                    Text(
                        "登录凭据只存在本机。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            OutlinedButton(onClick = { vm.rescan() }, modifier = Modifier.fillMaxWidth()) {
                Text("重新扫描音乐库")
            }
            OutlinedButton(onClick = { vm.clearCache() }, modifier = Modifier.fillMaxWidth()) {
                Text("清理播放缓存")
            }

            OutlinedButton(
                onClick = {
                    vm.disconnectCurrentProvider()
                    // 断掉最后一个网盘时会被送到登录页，这时不能再退回曲库
                    if (vm.screen == Screen.SETTINGS) onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "退出 ${vm.providerKind.shortLabel}",
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("关于", fontWeight = FontWeight.Bold)
                    Text("云音乐 v${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.BUILD_TIME}）")
                    Text("支持多网盘的个人音乐播放器")
                    Text(
                        "支持坚果云、Nextcloud、群晖等所有标准 WebDAV 服务，也支持微软 OneDrive，两个网盘可以同时连着。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "本应用仅播放你自己的文件，不提供任何公共资源库。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
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

    Column(Modifier.padding(top = 2.dp)) {
        if (quota != null) {
            // 服务器直接报了配额，一行说完
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "空间：已用 ${formatBytes(quota.used)} / ${formatBytes(quota.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.refreshDavQuota() }) { Text("刷新") }
            }
            LinearProgressIndicator(
                progress = quota.usedRatio,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "剩余 ${formatBytes(quota.available)}，大约还能放 ${quota.songsLeft} 首 30MB 的歌",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        // 坚果云：已用靠扫描，容量靠用户选
        val used = vm.davUsedBytes
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (used > 0L) "空间：已用 ${formatBytes(used)}" else "空间：已用（重新扫描后统计）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            // 这里用 Text + clickable，不用 Button：
            // 这一行处在 weight 列里，按钮会被压成零宽（只剩个空壳，文字看不见）。
            Text(
                if (capacity > 0L) "容量 ${formatBytes(capacity)}" else "容量 未设置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showCapacity = true }
            )
        }

        if (capacity > 0L) {
            val remain = (capacity - used).coerceAtLeast(0L)
            val nearlyFull = remain < 200L * 1024 * 1024
            LinearProgressIndicator(
                progress = (used.toFloat() / capacity.toFloat()).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "剩余 ${formatBytes(remain)}，大约还能放 ${remain / (30L * 1024 * 1024)} 首 30MB 的歌",
                style = MaterialTheme.typography.bodySmall,
                color = if (nearlyFull) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "坚果云不开放配额查询，选一下套餐就能算出剩余空间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            "每月上传/下载流量额度不通过 WebDAV 公开，剩余额度请在官网查看",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .clickable {
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

/** 在系统浏览器里打开一个网页 */


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
            TextButton(onClick = { vm.refreshOneDriveQuota() }) {
                Text(if (vm.oneDriveQuotaLoading) "查询中" else "查询")
            }
        }
        return
    }

    val nearlyFull = quota.remaining < 1024L * 1024 * 1024
    Column(Modifier.padding(top = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "空间：已用 ${formatBytes(quota.used)} / ${formatBytes(quota.total)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (nearlyFull) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "刷新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { vm.refreshOneDriveQuota() }
            )
        }
        LinearProgressIndicator(
            progress = quota.usedRatio,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "剩余 ${formatBytes(quota.remaining)}，大约还能放 ${quota.songsLeft} 首 30MB 的歌" +
                if (quota.deleted > 0) "（回收站里还有 ${formatBytes(quota.deleted)}）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (nearlyFull) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}