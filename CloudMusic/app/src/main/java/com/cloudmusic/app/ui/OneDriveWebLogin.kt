package com.cloudmusic.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 用应用内浏览器（WebView）走微软的授权码流程。
 *
 * 为什么不继续用设备码：设备码只有 15 分钟，而这 15 分钟要覆盖
 * 「输码 → 输账号 → 可能还要收邮箱验证码」整个过程。中途一旦多绕两步，
 * 微软会在最后一步甩一句「你输入的代码已过期」，前面全白做。
 *
 * 授权码流程没有这个倒计时：用户在网页里正常登录，登录完成的瞬间
 * 微软会跳到 [redirectUri] 并带上 code，这里拦下来直接交给上层换 token。
 */
/**
 * 判断这个地址是不是「登录成功后微软跳回来的那一个」。
 *
 * 除了我们指定的 nativeclient，MSA 有时会跳到 oauth20_desktop.srf —— 两种都得认，
 * 只认一种的话，登录其实成功了但 App 一直等不到 code，表现就像「没登录成功」。
 */
/**
 * 把登录过程写一份到应用私有目录。
 *
 * WebView 的回调有时会静默失败（页面跳过去了，但什么都没触发），
 * 而 logcat 不一定留得住，所以落个文件 —— 出问题可以直接：
 * `adb shell run-as com.cloudmusic.app cat files/onedrive_web.log`
 */
private fun webLog(context: android.content.Context, message: String) {
    runCatching {
        val file = java.io.File(context.filesDir, "onedrive_web.log")
        if (file.exists() && file.length() > 64 * 1024) file.delete()
        file.appendText(
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()) + "  " + message + "\n"
        )
    }
}

private fun isRedirect(target: String, redirectUri: String): Boolean =
    target.startsWith(redirectUri) ||
        target.startsWith("https://login.live.com/oauth20_desktop.srf") ||
        target.contains("/oauth2/nativeclient") ||
        target.startsWith("msal")

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OneDriveWebLoginDialog(
    url: String,
    redirectUri: String,
    /** 参数：授权码、实际跳回来的地址（换 token 时要原样带上） */
    onCode: (String, String) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    // 拦截只做一次：同一个 code 换两次 token 必失败
    var handled by remember { mutableStateOf(false) }
    // 当前网页地址。微软对嵌入式浏览器有时会拦，把地址亮出来一眼就能看出来卡在哪。
    var currentUrl by remember { mutableStateOf(url) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    /**
     * 兜底轮询。
     *
     * WebView 的回调在某些机型/某些跳转方式下不触发（尤其是 302 跳到自定义回调地址时），
     * 结果就是「网页明明登录成功了，App 却什么都没收到」。
     * 这里每秒看一眼 WebView 当前地址，只要地址里带了 code 就直接接住。
     */
    LaunchedEffect(Unit) {
        while (!handled) {
            kotlinx.coroutines.delay(700)
            val target = webViewRef.value?.url ?: continue
            if (target != currentUrl) currentUrl = target
            if (!isRedirect(target, redirectUri)) continue
            val uri = Uri.parse(target)
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrBlank() && !handled) {
                handled = true
                onCode(code, target.substringBefore("?"))
            } else if (handled.not() && uri.getQueryParameter("error") != null) {
                handled = true
                onError(uri.getQueryParameter("error_description") ?: "登录失败")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("登录 Microsoft 帐户", style = MaterialTheme.typography.titleMedium)
                        Text(
                            currentUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                Box(Modifier.weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).also { wv ->
                                // 带上 cookie：登录过一次之后，下次点登录不用再输账号密码
                                android.webkit.CookieManager.getInstance().apply {
                                    setAcceptCookie(true)
                                    setAcceptThirdPartyCookies(wv, true)
                                }
                            }.apply {
                                webViewRef.value = this
                                webLog(ctx, "打开授权页 url=${url.take(240)}")
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.setSupportMultipleWindows(false)
                                settings.userAgentString = settings.userAgentString
                                    ?.replace("; wv", "")
                                    ?.replace("Version/4.0 ", "") ?: ""

                                fun handle(target: String): Boolean {
                                    webLog(ctx, "url=${target.take(300)}")
                                    if (handled || !isRedirect(target, redirectUri)) return false
                                    val uri = Uri.parse(target)
                                    val code = uri.getQueryParameter("code")
                                    val problem = uri.getQueryParameter("error_description")
                                        ?: uri.getQueryParameter("error")
                                    handled = true
                                    if (!code.isNullOrBlank()) {
                                        webLog(ctx, "拦截到 code=${code.take(12)}…")
                                        onCode(code, target.substringBefore("?"))
                                    } else {
                                        webLog(ctx, "没有 code，问题=$problem")
                                        onError(problem ?: "微软没有返回授权码，请重试。")
                                    }
                                    return true
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean = handle(request.url.toString())

                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String?,
                                        favicon: Bitmap?,
                                    ) {
                                        loading = true
                                        if (url != null) {
                                            currentUrl = url
                                            handle(url)
                                        }
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        loading = false
                                        if (url != null) currentUrl = url
                                        webLog(ctx, "finished=${url?.take(300)}")
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: android.webkit.WebResourceError,
                                    ) {
                                        if (request.isForMainFrame) {
                                            currentUrl = "加载失败：${error.description} ${request.url}"
                                            webLog(
                                                ctx,
                                                "error=${error.errorCode} ${error.description} " +
                                                    request.url.toString().take(200)
                                            )
                                        }
                                    }
                                }
                                loadUrl(url)
                            }
                        }
                    )
                }
            }
        }
    }
}
