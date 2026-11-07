package com.cloudmusic.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cloudmusic.app.data.quark.QuarkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 登录夸克网盘。
 *
 * 夸克没有能直接调用的登录接口（走 `uop.quark.cn` 的 CAS 统一登录 + 阿里云验证码），
 * 所以这里内嵌一个 WebView 让用户正常登录，登录完把 cookie 抓下来。
 *
 * 夸克的登录态就 6 个 cookie，抓到 `__pus` 就算成了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuarkLoginScreen(
    onBack: () -> Unit,
    onLoggedIn: (cookie: String, account: String) -> Unit,
) {
    var showManual by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(true) }

    // 轮询 cookie：登录成功后夸克会跳回 drive.quark.cn，这时 cookie 才种齐
    LaunchedEffect(Unit) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        var tries = 0
        while (tries < 300 && waiting) {   // 最多等 5 分钟
            delay(1000)
            tries++
            val c = try {
                harvestCookies(cm)
            } catch (e: Exception) {
                ""
            }
            if (c.contains("__pus")) {
                withContext(Dispatchers.Main) {
                    onLoggedIn(c, "")
                }
                break
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("登录夸克网盘") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "在下面登录夸克网盘（扫码或短信验证码都行），登录成功会自动识别。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "夸克只在这一步当中转文件用，不碰你的网盘内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showManual = true }) {
                    Text("登录不上？手动贴 Cookie")
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // **必须伪装成电脑浏览器**。
                    //
                    // 夸克看 UA 决定给什么：用手机 UA 访问 pan.quark.cn 会 302 到
                    // h5.sm.cn 的「下载夸克 App」推广页，根本不是登录页；
                    // 用桌面 UA 才会给真正的网页版。
                    //
                    // 网页版那边是响应式的（meta viewport=width=device-width），
                    // 手机屏幕上照样渲染成移动版布局。
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    // 进登录页先把夸克域的旧登录态清掉，免得还挂着上一个账号
                    // （退出登录只清了 App 存的 cookie，WebView 全局 cookie 没动）。
                    clearQuarkWebViewCookies()
                    webViewClient = object : WebViewClient() {
                        // 兜底：万一又被导到「下载 App」推广页，切回网页版
                        var retry = 0

                        override fun onPageFinished(view: WebView, url: String) {
                            if (url.contains("h5.sm.cn") || url.contains("mobile-entry")) {
                                if (retry < 2) {
                                    retry++
                                    view.loadUrl("https://pan.quark.cn/")
                                    return
                                }
                            }
                            // 不管停在哪个域上，只要会话 cookie 种齐了就算登录成功。
                            // 夸克的登录页在 h5.sm.cn 上，登录完才跳回 pan.quark.cn，
                            // 而且 cookie 可能种在好几个域下（有的挂在 .quark.cn 顶级域）。
                            val c = try {
                                harvestCookies(CookieManager.getInstance())
                            } catch (e: Exception) {
                                ""
                            }
                            if (c.contains("__pus")) {
                                onLoggedIn(c, "")
                            }
                        }
                    }
                    // drive.quark.cn 这个旧域名已经废了（直接 404），
                    // 现在的入口是 pan.quark.cn —— 桌面 UA 才会给网页版，
                    // 手机 UA 会被导到 h5.sm.cn 的「下载 App」推广页。
                    loadUrl("https://pan.quark.cn/")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }

    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("手动贴 Cookie") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "1. 用电脑浏览器打开 pan.quark.cn 并登录\n" +
                            "2. 按 F12 → Network → 随便点一个请求 → Request Headers 里复制整条 Cookie\n" +
                            "3. 粘到下面（要有 __pus 和 __puus）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        label = { Text("Cookie") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val c = manualText.trim()
                        if (c.contains("__pus")) {
                            showManual = false
                            onLoggedIn(c, "")
                        }
                    }
                ) { Text("就用这个") }
            },
            dismissButton = {
                TextButton(onClick = { showManual = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 把夸克散在好几个域上的 cookie 收拢成一条。
 *
 * `CookieManager.getCookie(url)` 只返回**那个 url 域下**的 cookie，
 * 而夸克的会话 cookie 可能在 pan.quark.cn、可能在 h5.sm.cn（登录页那个域），
 * 还可能挂在 `.quark.cn` 这样的顶级域上 —— 只查一个域经常一个都拿不到。
 *
 * 所以挨个域取一遍，按名字合并（同名以先取到的为准）。
 */
private fun harvestCookies(cm: CookieManager): String {
    val urls = arrayOf(
        "https://pan.quark.cn/",
        "https://drive-pc.quark.cn/",
        "https://quark.cn/",
        "https://h5.sm.cn/",
        "https://drive.quark.cn/"
    )
    val merged = LinkedHashMap<String, String>()
    for (u in urls) {
        val raw = try {
            cm.getCookie(u)
        } catch (e: Exception) {
            null
        } ?: continue
        for (part in raw.split(';')) {
            val kv = part.trim().split('=', limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                val name = kv[0].trim()
                if (!merged.containsKey(name)) merged[name] = kv[1].trim()
            }
        }
    }
    return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/**
 * 清掉夸克相关域的旧登录态。
 *
 * `clearQuark()` 只清了 App 自己存的 `SharedPreferences` cookie，
 * 但 WebView 的全局 cookie（[CookieManager]）没动 —— 退出登录再进登录页
 * 时，网页还认得老账号，用户就没法换号了。
 * 这里把夸克几个域的 cookie 逐个置过期清掉。
 */
private fun clearQuarkWebViewCookies() {
    val cm = CookieManager.getInstance()
    val domains = arrayOf(
        "https://pan.quark.cn/",
        "https://quark.cn/",
        "https://h5.sm.cn/",
        "https://drive.quark.cn/",
        "https://drive-pc.quark.cn/"
    )
    for (u in domains) {
        val raw = kotlin.runCatching { cm.getCookie(u) }.getOrNull() ?: continue
        for (part in raw.split(';')) {
            val name = part.trim().substringBefore('=').trim()
            if (name.isNotBlank()) {
                cm.setCookie(u, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            }
        }
    }
    cm.flush()
}
