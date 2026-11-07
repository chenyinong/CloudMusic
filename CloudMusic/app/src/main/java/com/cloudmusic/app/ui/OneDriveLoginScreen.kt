package com.cloudmusic.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudmusic.app.data.onedrive.DeviceCodeInfo
import com.cloudmusic.app.data.onedrive.OneDriveAuth
import com.cloudmusic.app.data.onedrive.OneDriveScope
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OneDrive 登录页。
 *
 * 微软对个人版 OneDrive 早就取消了 WebDAV，所以只能走官方 Graph API，
 * 需要一个自己注册的应用 ID（客户端 ID）—— 这是微软对第三方应用的要求。
 */
@Composable
fun OneDriveLoginScreen(
    clientIdDefault: String,
    connected: Boolean,
    account: String,
    busy: Boolean,
    deviceCode: DeviceCodeInfo?,
    status: String,
    error: String?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    scopeMode: String,
    onScopeModeChange: (String) -> Unit,
    accountKind: String,
    onAccountKindChange: (String) -> Unit,
    onCheckClientId: (String) -> Unit,
    onRefreshCode: () -> Unit,
    onRequestCode: (String) -> Unit,
    onWebLogin: (String) -> Unit,
    onSwitchToWebDav: () -> Unit,
) {
    var clientId by remember { mutableStateOf(clientIdDefault) }
    var showHelp by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = OneDriveScope.of(scopeMode)

    // 验证码还剩多少秒 —— 必须实时算。
    //
    // 微软的设备码只有 900 秒，过期后网页上会直接报「该代码无法工作」。
    // 原来这里只写死一句「约 15 分钟内有效」，用户不知道还剩多少，
    // 慢悠悠输完码发现已经作废了。
    var leftSec by remember(deviceCode) { mutableStateOf(leftSecondsOf(deviceCode)) }
    LaunchedEffect(deviceCode) {
        val dc = deviceCode ?: return@LaunchedEffect
        while (true) {
            leftSec = leftSecondsOf(dc)
            if (leftSec <= 0) break
            delay(1000)
        }
    }

    /**
     * 验证页链接。
     *
     * 注意：**微软的验证页不认 `?user_code=`**（实测 www.microsoft.com/link 打开后
     * 输入框还是空的，不会自动填），所以别指望链接能省掉手输。
     * 能省掉手输的办法只有一个：把验证码放进剪贴板，用户打开页面后长按粘贴。
     *
     * 端点不同验证页也不同：
     * - consumers → www.microsoft.com/link → login.live.com（个人账号专用）
     * - common    → login.microsoft.com/device → deviceauth（通用，页面上可选个人帐户）
     */
    val verifyLink = remember(deviceCode) {
        val dc = deviceCode ?: return@remember ""
        val sep = if (dc.verifyUrl.contains("?")) "&" else "?"
        dc.verifyUrl + sep + "user_code=" + dc.userCode
    }

    // 用户点「复制验证码并打开登录页」时，如果手上的码已经放了一会儿，
    // 先换一个新的再自动打开 —— 15 分钟的有效期经不起来回折腾。
    var autoOpenPending by remember { mutableStateOf(false) }
    LaunchedEffect(deviceCode) {
        val dc = deviceCode ?: return@LaunchedEffect
        if (!autoOpenPending) return@LaunchedEffect
        autoOpenPending = false
        clipboard.setText(AnnotatedString(dc.userCode))
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verifyLink)))
        }
    }
    // 当前这个码是在哪个端点申请的 —— 验证页必须跟它配对。
    // consumers 的码拿到 common 页面上用，微软会直接报「该代码无法工作」；
    // 反过来 common 的码拿到个人页面上用，会报「帐户不存在」。
    val authorityLabel = remember(deviceCode) {
        when (deviceCode?.authority) {
            DeviceCodeInfo.AUTHORITY_CONSUMERS -> "个人账号端点（consumers）"
            DeviceCodeInfo.AUTHORITY_COMMON -> "单位账号端点（common）"
            else -> ""
        }
    }
    // 签发时间。用户手里可能同时躺着好几个验证码（以前截的图、以前复制的链接），
    // 网页上输错一个就报「代码已过期」—— 把签发时刻亮出来，一眼就能对上是哪个。
    val issuedClock = remember(deviceCode) {
        val at = deviceCode?.issuedAt ?: 0L
        if (at <= 0L) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        if (canGoBack) {
            TextButton(onClick = onBack) {
                Text("← 返回曲库")
            }
            Spacer(Modifier.height(4.dp))
        }

        Text("OneDrive", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "用微软账号连你的云盘",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        // 主推荐路径。
        //
        // 设备码那条路（下面「获取授权码」）有个绕不开的坑：验证码只有 15 分钟，
        // 而且这 15 分钟要把「输码 → 输账号 → 可能还要收邮箱验证码」全部走完。
        // 中途一慢，最后一步微软就回一句「你输入的代码已过期」，前面全白做。
        // 应用内网页走授权码流程，没有倒计时，登完自动回到 App。
        Button(
            onClick = { onWebLogin(clientId) },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("用应用内网页登录（推荐）", fontWeight = FontWeight.Bold)
        }
        Text(
            "在下面弹出的网页里正常登录即可：不用抄验证码，也没有 15 分钟限制。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 内置 ID 的死路，必须在用户点之前就讲清楚。
        // 实测微软会回 invalid_scope：内置的 Office 客户端没有把 Graph 列进
        // Required Resource Access，用户既不能替它加权限也不能替它同意。
        if (clientId.trim() == OneDriveAuth.BUILTIN_CLIENT_ID) {
            Spacer(Modifier.height(12.dp))
            Card(
                // Card 默认 elevation=1dp，会把主题色叠到卡面上（泛绿）。归零后用描边分层
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp, draggedElevation = 0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "内置的应用 ID 连不上 OneDrive",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "它是微软自家的客户端，权限里根本没开 Graph 文件访问，而你又没法替它点同意 —— " +
                            "微软会直接回 invalid_scope。必须换成你自己注册的应用 ID（免费，约 5 分钟）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = { showHelp = true }) { Text("展开注册教程") }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp, draggedElevation = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("不用注册任何东西", fontWeight = FontWeight.Bold)
                Text("怎么用", fontWeight = FontWeight.Bold)
                Text("1. 点「获取授权码」，会显示一个验证码")
                Text("2. 点「复制验证码并打开登录页」，在网页输入框里长按粘贴、登录")
                Text("3. 回到 App，授权完成后自动开始扫描")
                Spacer(Modifier.height(4.dp))
                Text(
                    "App 已经内置了一个微软官方的公共客户端，正常情况下你什么都不用配置就能授权。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "微软在 2025 年后关掉了「在个人账号下直接注册应用」的入口（旧的 portal.azure.com " +
                        "页面会提示「在目录外部创建应用程序的功能已被弃用」），所以大多数人都不需要再去注册。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "只读取文件列表和播放文件，不会修改、上传或删除你的任何内容。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("应用 ID（客户端 ID）") },
            supportingText = { Text("已内置微软官方公共客户端，一般不用改") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (clientId != OneDriveAuth.BUILTIN_CLIENT_ID) {
            TextButton(onClick = { clientId = OneDriveAuth.BUILTIN_CLIENT_ID }) {
                Text("恢复成内置的应用 ID")
            }
        }

        TextButton(onClick = { onCheckClientId(clientId) }) {
            Text("检查这个应用 ID 能不能用")
        }

        Spacer(Modifier.height(8.dp))

        Text("你的账号类型", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 轻胶囊，不用 FilterChip —— 它选中的底色是带色调的 secondaryContainer
            listOf("personal" to "个人账号", "work" to "单位账号").forEach { (kind, label) ->
                val selected = accountKind == kind
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onAccountKindChange(kind) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Text(
            if (accountKind == "work") {
                "公司 / 学校邮箱（xxx@company.com）会走通用的微软登录页。"
            } else {
                "outlook.com / hotmail.com 这类个人邮箱必须走个人账号专用验证页，" +
                    "否则微软会把邮箱当成企业账号，网页上会报「Microsoft 帐户不存在」。"
            },
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(8.dp))

        Text("请求的权限", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OneDriveScope.entries.forEach { s ->
                val selected = scopeMode == s.mode
                Text(
                    s.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onScopeModeChange(s.mode) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Text(scope.hint, style = MaterialTheme.typography.bodySmall)
        Text(
            if (scope == OneDriveScope.BUILTIN) {
                "「内置默认」用的是微软预授权的那套权限（.default），不需要你在网页上点同意，" +
                    "所以只对微软自家应用有效。"
            } else {
                "「只读 / 读写」是给你自己注册的应用用的，需要你在微软页面上点一次同意。"
            },
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(6.dp))

        TextButton(onClick = { showHelp = !showHelp }) {
            Text(if (showHelp) "收起教程 ▲" else "内置的用不了？自己注册一个（备用）▼")
        }

        if (showHelp) {
            TutorialCard()
        }

        Spacer(Modifier.height(12.dp))

        if (deviceCode != null) {
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp, draggedElevation = 0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("在微软页面输入这个验证码", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            deviceCode.userCode,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (leftSec > 0) {
                            "${if (issuedClock.isBlank()) "" else "$issuedClock 签发 · "}" +
                                "剩余 ${leftSec / 60}:${(leftSec % 60).toString().padStart(2, '0')} · 过期后这个码就不能用了"
                        } else {
                            "这个验证码已经过期了，点下面「换一个验证码」"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (leftSec in 1..60) MaterialTheme.colorScheme.error
                        else if (leftSec <= 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(10.dp))
                    // 主操作：一个按钮干完「拿最新的码 + 放进剪贴板 + 打开登录页」。
                    //
                    // 之所以要这么做：网页那边不认 ?user_code=，只能手输，
                    // 而手输很容易拖到 15 分钟有效期之后，最终表现就是
                    // 「你输入的代码已过期」。所以每次跳转前都保证手里的码是刚生成的，
                    // 并且顺手把码放进剪贴板，用户长按粘贴就行，不用背。
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (leftSec <= 180) {
                                autoOpenPending = true
                                onRequestCode(clientId)
                            } else {
                                clipboard.setText(AnnotatedString(deviceCode.userCode))
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(verifyLink)
                                        )
                                    )
                                }
                            }
                        }
                    ) {
                        Text(
                            if (leftSec <= 180) "换一个新码并打开登录页"
                            else "复制验证码并打开登录页",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(deviceCode.userCode))
                        }) { Text("只复制验证码") }
                        OutlinedButton(onClick = { onRefreshCode() }) { Text("换一个验证码") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "打开页面后长按输入框粘贴就行（码已经在剪贴板里）。" +
                            "这一页不会自动填码，手打容易打错、也容易拖到过期。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (authorityLabel.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "这个码来自$authorityLabel，只能在这个端点自己的验证页上用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "每次点「换一个验证码」都会让上一个立刻作废 —— " +
                            "网页上请始终输**这个**最新的码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "验证页地址：$verifyLink",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        enabled = leftSec > 0,
                        onClick = { clipboard.setText(AnnotatedString(verifyLink)) }
                    ) {
                        Text("复制验证页链接")
                    }
                    // 排查用：把这一次拿到的原始参数原样抄出来。
                    // 报「代码已过期」这类错时，光看界面分不清是码真过期还是端点不对，
                    // 有这行数据就能对着微软的返回逐项比对。
                    TextButton(onClick = {
                        clipboard.setText(
                            AnnotatedString(
                                buildString {
                                    append("user_code=").append(deviceCode.userCode).append('\n')
                                    append("authority=").append(deviceCode.authority).append('\n')
                                    append("verify_uri=").append(deviceCode.verifyUrl).append('\n')
                                    append("expires_in=").append(deviceCode.expiresIn).append('\n')
                                    append("interval=").append(deviceCode.interval).append('\n')
                                    append("issuedAt=").append(deviceCode.issuedAt)
                                    append(" (").append(issuedClock).append(")\n")
                                    append("leftSec=").append(leftSec).append('\n')
                                    append("status=").append(status).append('\n')
                                    append("error=").append(error ?: "-").append('\n')
                                    append("手机时间=")
                                    append(
                                        SimpleDateFormat(
                                            "yyyy-MM-dd HH:mm:ss",
                                            Locale.getDefault()
                                        ).format(Date())
                                    )
                                }
                            )
                        )
                    }) {
                        Text("复制诊断信息")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "网页上提示「该代码无法工作」= 这个码不属于当前这个验证页（或已经过期）。" +
                            "验证页和申请验证码的端点必须配对，别自己换成另一个微软验证页地址；" +
                            "回到上面点「换一个新码并打开登录页」重来一次即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "网页上提示「你输入的代码已过期」= 这个码从签发到现在已经超过 15 分钟了。" +
                            "设备码的有效期是硬性的、不能续，所以别拖：点「换一个新码并打开登录页」，" +
                            "跳到网页后立刻粘贴、立刻登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "提示「Microsoft 帐户不存在」= 你在个人账号验证页（login.live.com）上登了" +
                            "公司/学校邮箱，或者反过来。回 App 把「你的账号类型」换成对应的那一项，" +
                            "重新点「获取授权码」，再用新页面登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 一条走得通的路：直接换到另一个端点重新取码，别让用户在两个验证页之间瞎试。
                    val otherKind =
                        if (deviceCode.authority == DeviceCodeInfo.AUTHORITY_CONSUMERS) "work"
                        else "personal"
                    TextButton(
                        onClick = {
                            onAccountKindChange(otherKind)
                            onRequestCode(clientId)
                        },
                        enabled = !busy && clientId.isNotBlank()
                    ) {
                        Text(
                            if (otherKind == "work") "换成单位账号端点重新取码"
                            else "换成个人账号端点重新取码"
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }

        if (error != null) {
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp, draggedElevation = 0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            // 一步到底：申请码 → 码进剪贴板 → 直接打开登录页。
            // 用户中间不需要在 App 和浏览器之间来回切，也就没时间把码放到过期。
            onClick = {
                autoOpenPending = true
                onRequestCode(clientId)
            },
            enabled = !busy && clientId.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.size(10.dp))
                Text(if (deviceCode == null) "申请中…" else "等待授权…")
            } else {
                Text(
                    if (connected) "重新授权并打开登录页" else "一键获取验证码并登录",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (connected && account.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "已连接账号：$account",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onSwitchToWebDav, modifier = Modifier.fillMaxWidth()) {
            Text("改用 WebDAV / 坚果云")
        }
    }
}

/**
 * 注册应用的分步教程。
 *
 * 重点是第 0 步：微软从 2024 年 6 月起就不再允许个人账号「无目录」注册应用，
 * 必须先有自己的目录（租户）。但很多人其实已经有 Azure 默认目录了，
 * 所以第一步是「找一找」，找不到再新建。
 */
@Composable
private fun TutorialCard() {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp, draggedElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("第 0 步：先看看你有没有现成的目录", fontWeight = FontWeight.Bold)
            Text(
                "微软从 2024 年 6 月起就不允许个人账号「无目录」注册应用了，" +
                    "所以得先有一个目录（租户）。但你既然能打开 Azure 门户，" +
                    "很可能右上角的目录切换器里已经有一个了：",
                style = MaterialTheme.typography.bodySmall
            )
            LinkButton(
                "打开 Azure 门户，看右上角的目录切换器",
                "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"
            )
            Text(
                "点右上角那个写着「Microsoft Services」的图标（或「目录 + 订阅」），" +
                    "下拉里如果除了「Microsoft Services」还有别的目录（比如「Default Directory」），" +
                    "说明你已经有了，**切换过去**，后面所有操作都在那个目录里做。",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(6.dp))
            Text("确实没有目录？建一个（都免费）", fontWeight = FontWeight.Bold)
            LinkButton(
                "① 注册免费 Azure 账户（会自带一个默认目录，最省事）",
                "https://azure.microsoft.com/free"
            )
            Text(
                "要手机号验证。注册完就有自己的目录了。部分流程会要求绑卡，" +
                    "有用户反馈只完成前面几步（验证 + 勾协议 + 下一步）也能拿到目录。",
                style = MaterialTheme.typography.bodySmall
            )
            LinkButton(
                "② 加入 Microsoft 365 开发人员计划（沙箱目录）",
                "https://developer.microsoft.com/microsoft-365/dev-program"
            )
            Text(
                "给你一个 onmicrosoft.com 的沙箱目录。注意是沙箱，数据可能被清理，90 天一期。",
                style = MaterialTheme.typography.bodySmall
            )
            LinkButton(
                "③ 在 Entra 里创建租户",
                "https://entra.microsoft.com/#view/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/~/Overview"
            )
            Text(
                "右上角点「创建租户」，名字随便填，选「Microsoft Entra ID」。",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))
            Text("第 1 步：注册应用", fontWeight = FontWeight.Bold)
            LinkButton(
                "打开「应用注册」页面",
                "https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"
            )
            Text("点「新建注册」，按下面填：", style = MaterialTheme.typography.bodySmall)
            Text("· 名称：随便填，比如「云音乐」", style = MaterialTheme.typography.bodySmall)
            Text(
                "· 支持的账户类型：一定要选「任意组织目录中的账户 + 个人 Microsoft 账户」" +
                    "（或「仅个人 Microsoft 账户」），选错了个人账号登不进来。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "· 重定向 URI：点「添加平台」→「移动和桌面应用程序」→ 勾上 " +
                    "https://login.microsoftonline.com/common/oauth2/nativeclient 。" +
                    "这一项必须有，网页登录成功后微软要跳回这个地址。",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))
            Text("第 2 步：允许公共客户端流", fontWeight = FontWeight.Bold)
            Text(
                "应用页面左侧「身份验证」→ 拉到最下面的「高级设置」→" +
                    "「允许公共客户端流」选「是」，保存。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "不勾这一项，授权码和设备码都会被微软直接拒绝。",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))
            Text("第 3 步：加读取权限", fontWeight = FontWeight.Bold)
            Text(
                "左侧「API 权限」→「添加权限」→「Microsoft Graph」→「委托的权限」，" +
                    "勾上 Files.Read、offline_access、User.Read，然后点「添加权限」。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "个人账号不需要管理员同意 —— 你自己就是所有者，登录时点一次「接受」即可。",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))
            Text("第 4 步：复制客户端 ID", fontWeight = FontWeight.Bold)
            Text(
                "回到「概述」页面，复制「应用程序(客户端) ID」，粘贴到上面的输入框，" +
                    "把「请求的权限」切成「只读」，然后点「用应用内网页登录」。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "不需要客户端密钥。整个流程里唯一必须自己准备的就是这个 ID。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** 验证码还剩多少秒。没记下签发时间就按完整有效期算。 */
private fun leftSecondsOf(dc: DeviceCodeInfo?): Int {
    if (dc == null) return 0
    if (dc.issuedAt <= 0L) return dc.expiresIn
    val left = dc.issuedAt + dc.expiresIn * 1000L - System.currentTimeMillis()
    return (left / 1000L).coerceAtLeast(0L).toInt()
}

/** 可点击的链接按钮 */
@Composable
private fun LinkButton(text: String, url: String) {
    val uriHandler = LocalUriHandler.current
    TextButton(
        onClick = { runCatching { uriHandler.openUri(url) } },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
