package com.cloudmusic.app.data.onedrive

import android.util.Log
import com.cloudmusic.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 微软账号授权结果。 */
data class OneDriveTokens(
    val accessToken: String,
    val refreshToken: String,
    /** 过期时间（毫秒） */
    val expiresAt: Long,
)

/** 设备码授权第一步拿到的信息。 */
data class DeviceCodeInfo(
    /** 用户要输入的验证码，例如 ABCD-EFGH */
    val userCode: String,
    val deviceCode: String,
    /** 用户该打开的验证页面 */
    val verifyUrl: String,
    /** 轮询间隔（秒） */
    val interval: Int,
    /** 有效期（秒） */
    val expiresIn: Int,
    /**
     * 实际用的租户端点（common / consumers）。
     * 换 access_token 时必须用同一个端点，不然微软会拒绝。
     */
    val authority: String = AUTHORITY_COMMON,
    /**
     * 这个码是什么时候申请下来的（毫秒时间戳）。
     *
     * 微软给的有效期是 900 秒，过了就作废 —— 用户拿着过期码去输，
     * 网页上会报「该代码无法工作」。界面上靠它算倒计时。
     */
    val issuedAt: Long = 0L,
) {
    companion object {
        const val AUTHORITY_COMMON = "common"
        const val AUTHORITY_CONSUMERS = "consumers"
    }
}

/**
 * 申请的权限范围。
 *
 * 关键在于：微软的第一方应用（Office、Graph Explorer 这些）**不允许用户做 consent**，
 * 只能走「预授权」；而自己注册的应用正好相反，必须由用户点同意。
 * 所以内置的应用和自建的应用得用不同的 scope。
 */
enum class OneDriveScope(
    val mode: String,
    val scope: String,
    val label: String,
    val hint: String,
) {
    /** 用应用预授权的权限集合（.default），第一方应用只有这样才有机会过 */
    BUILTIN(
        "default",
        // offline_access 不能少：没有它微软不返回 refresh_token，
        // access_token 一小时后过期就没法自动续期，用户得重新登录一次。
        "https://graph.microsoft.com/.default offline_access",
        "内置默认",
        "用内置的微软应用 ID 时选这个",
    ),

    /** 自己注册的应用用这个，只要读文件的权限 */
    READONLY(
        "readonly",
        "offline_access Files.Read User.Read",
        "只读",
        "自己注册的应用选这个（最小权限，推荐）",
    ),

    /** 需要写权限时用（一般用不上） */
    READWRITE(
        "readwrite",
        "offline_access Files.ReadWrite User.Read",
        "读写",
        "只在自己注册的应用上确实需要写权限时用",
    );

    companion object {
        fun of(mode: String?): OneDriveScope =
            entries.firstOrNull { it.mode == mode } ?: BUILTIN
    }
}

/**
 * 微软返回的授权错误。
 * [message] 是给用户看的中文说明，[raw] 是微软的原始返回，方便排查。
 */
class OneDriveAuthException(
    val code: String,
    val raw: String,
    message: String,
) : Exception(message) {

    /**
     * 是不是「租户解析不出来」这类错误。
     * 这类错误换一个租户端点（common <-> consumers）有可能成功，值得重试。
     */
    val retryWithOtherAuthority: Boolean
        get() {
            val lower = raw.lowercase()
            return lower.contains("aadsts700016") ||
                lower.contains("aadsts7000215") ||
                lower.contains("aadsts50059") ||
                lower.contains("aadsts900023")
        }
}

/**
 * PKCE 的一对值。
 *
 * verifier 留在本机，换 token 时提交；challenge 发给微软。
 * 公共客户端没有 client_secret，靠这个保证「只有发起登录的那个 App 能换到 token」。
 */
class Pkce(val verifier: String, val challenge: String)

/** 轮询等授权的结果。 */
sealed class TokenOutcome {
    data class Success(val tokens: OneDriveTokens) : TokenOutcome()

    /** 验证码过期或已被作废，需要重新申请一个 */
    data object Expired : TokenOutcome()

    /** 微软明确拒绝了 */
    data class Failed(val error: OneDriveAuthException) : TokenOutcome()

    /** 等太久用户一直没操作 */
    data object Timeout : TokenOutcome()
}

/**
 * 微软账号授权，用 OAuth2 **设备码流程**。
 *
 * 选它是因为不需要注册重定向协议（不会和别的 App 抢回调），
 * 用户在手机上打开微软页面、输一下设备码就完成授权。
 */
class OneDriveAuth(
    private val prefs: Prefs,
    private val http: OkHttpClient,
) {

    companion object {
        private const val TAG = "OneDriveAuth"

        const val DEFAULT_VERIFY_PAGE = "https://microsoft.com/devicelogin"

        /**
         * 内置的公共客户端 ID —— 微软 Office 官方应用（多租户 + 支持个人 Microsoft 账户）。
         *
         * 微软已经关掉了「在个人账号下直接注册应用」的入口，没有自己的目录就注册不了，
         * 所以先借用一个官方公共客户端，让用户点一下就能授权。
         * 万一哪天被微软限制，可以在登录页填自己注册的应用 ID 覆盖掉它。
         */
        const val BUILTIN_CLIENT_ID = "d3590ed6-52b3-4102-aeff-aad2292ab01c"

        /**
         * 依次尝试的租户端点。
         *
         * **consumers 必须排前面**：个人 Microsoft 账户（outlook.com / hotmail.com）只在
         * consumers 租户下，走 common 的话微软会把邮箱当成「工作或学校账号」去组织目录里查，
         * 登录页直接报「Microsoft 帐户不存在。请输入其他帐户」。
         *
         * 实测两者的验证页也不一样：
         * - consumers → https://www.microsoft.com/link（专供个人账号）
         * - common    → https://login.microsoft.com/device（通用页，会先问账号类型）
         */
        val AUTHORITIES = listOf(
            DeviceCodeInfo.AUTHORITY_CONSUMERS,
            DeviceCodeInfo.AUTHORITY_COMMON,
        )

        fun deviceCodeUrl(authority: String) =
            "https://login.microsoftonline.com/$authority/oauth2/v2.0/devicecode"

        fun tokenUrl(authority: String) =
            "https://login.microsoftonline.com/$authority/oauth2/v2.0/token"

        /**
         * 授权码流程用的回调地址。
         *
         * 设备码流程有个硬伤：验证码只有 15 分钟，而且这 15 分钟要覆盖**整个**登录过程
         * （输码 → 输账号 → 可能还要收邮件/短信验证码）。中途只要慢一点，
         * 微软就会在最后一步回一句「你输入的代码已过期」—— 白忙一遍。
         *
         * 授权码流程没这个问题：登录完浏览器直接跳到这个地址带上 code，
         * App 拿到 code 立刻换 token，中间没有倒计时。
         *
         * 这个地址是微软给「公共客户端 / 原生应用」预注册的，
         * 内置的 Office 客户端认它（实测授权页不再报 redirect_uri 不匹配）。
         */
        const val REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient"

        /** 生成 PKCE。公共客户端没有密钥，靠它防止授权码被截走。 */
        fun newPkce(): Pkce {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            val random = java.security.SecureRandom()
            val verifier = buildString {
                repeat(64) { append(chars[random.nextInt(chars.length)]) }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            val challenge = android.util.Base64.encodeToString(
                digest,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            return Pkce(verifier, challenge)
        }

        /**
         * 备用回调地址：MSAL 用的 `msal{clientId}://auth`。
         *
         * 自建应用在门户里加「移动和桌面应用程序」平台时，微软推荐的就是这个地址，
         * 所以很多人只登记了它、没登记 nativeclient。两条都试，成功率更高。
         */
        fun msalRedirectUri(clientId: String) = "msal${clientId.trim()}://auth"

        /** 拼授权页地址。登录成功后微软会跳到 redirectUri?code=... */
        fun authCodeUrl(
            clientId: String,
            authority: String,
            scopeMode: String,
            pkce: Pkce,
            redirectUri: String = REDIRECT_URI,
        ): String {
            val scope = scopeFor(scopeMode)
            return "https://login.microsoftonline.com/$authority/oauth2/v2.0/authorize" +
                "?client_id=" + java.net.URLEncoder.encode(clientId.trim(), "UTF-8") +
                "&response_type=code" +
                "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, "UTF-8") +
                "&response_mode=query" +
                "&scope=" + java.net.URLEncoder.encode(scope, "UTF-8") +
                "&code_challenge=" + java.net.URLEncoder.encode(pkce.challenge, "UTF-8") +
                "&code_challenge_method=S256" +
                "&prompt=select_account"
        }

        /** 只取 scope 字符串，不需要 Prefs —— 给拼 URL 用 */
        private fun scopeFor(scopeMode: String): String =
            com.cloudmusic.app.data.onedrive.OneDriveScope.of(scopeMode).scope
    }

    /** 第一步：申请设备码 */
    suspend fun requestDeviceCode(
        clientId: String,
        scopeMode: String = "",
        preferredAuthority: String = "",
    ): DeviceCodeInfo = withContext(Dispatchers.IO) {
        val scope = OneDriveScope.of(scopeMode.ifBlank { prefs.oneDriveScopeMode }).scope
        var lastError: OneDriveAuthException? = null

        for (authority in authorityOrder(preferredAuthority)) {
                val body = FormBody.Builder()
                    .add("client_id", clientId.trim())
                    .add("scope", scope)
                    .build()

                val response = post(deviceCodeUrl(authority), body)
                if (response == null) {
                    throw OneDriveAuthException(
                        "network",
                        "",
                        "连不上微软服务器，请检查网络后重试。"
                    )
                }
                val (code, json) = response
                if (code == 200) {
                    val deviceCode = json.optString("device_code")
                    val userCode = json.optString("user_code")
                    if (deviceCode.isEmpty() || userCode.isEmpty()) {
                        throw OneDriveAuthException(
                            "invalid_response",
                            json.toString(),
                            "微软返回的授权信息不完整，请重试。"
                        )
                    }
                    val info = DeviceCodeInfo(
                        userCode = userCode,
                        deviceCode = deviceCode,
                        verifyUrl = json.optString("verification_uri", DEFAULT_VERIFY_PAGE),
                        interval = json.optInt("interval", 5).coerceAtLeast(1),
                        expiresIn = json.optInt("expires_in", 900),
                        authority = authority,
                        issuedAt = System.currentTimeMillis(),
                    )
                    Log.i(
                        TAG,
                        "devicecode ok authority=$authority user_code=$userCode " +
                            "uri=${info.verifyUrl} expires_in=${info.expiresIn} " +
                            "interval=${info.interval} issuedAt=${info.issuedAt} " +
                            "device_code=${deviceCode.take(24)}…"
                    )
                    return@withContext info
                }

                val error = OneDriveAuthException(
                    json.optString("error", "http_$code"),
                    json.optString("error_description", json.toString()),
                    "",
                )
                Log.w(TAG, "devicecode fail authority=$authority http=$code raw=${error.raw.take(300)}")
                lastError = error
                // 只有「租户解析不出来」才值得换个端点再试，其它错误直接报出来
                if (!error.retryWithOtherAuthority) throw error
            }

            throw lastError ?: OneDriveAuthException(
                "unknown",
                "",
                "申请授权码失败，请重试。"
            )
        }

    /**
     * 第二步：轮询等用户授权。
     *
     * 网络抖动不算失败（继续等），只有微软明确拒绝才算失败。
     * 验证码过期单独返回 [TokenOutcome.Expired]，让上层能自动换一个新的。
     *
     * @param deadline 截止时间戳，超过就放弃
     */
    suspend fun awaitToken(
        clientId: String,
        deviceCode: String,
        intervalSec: Int,
        deadline: Long,
        authority: String = DeviceCodeInfo.AUTHORITY_COMMON,
        scopeMode: String = "",
    ): TokenOutcome = withContext(Dispatchers.IO) {
        val scope = OneDriveScope.of(scopeMode.ifBlank { prefs.oneDriveScopeMode }).scope
        var waitMs = intervalSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(waitMs)
            val body = FormBody.Builder()
                .add("client_id", clientId.trim())
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", deviceCode)
                .add("scope", scope)
                .build()

            val response = post(tokenUrl(authority), body)
            // 网络没通：不是微软的错，继续等
            if (response == null) continue
            val (code, json) = response

            if (code == 200) {
                val token = json.optString("access_token")
                if (token.isNotEmpty()) {
                    return@withContext TokenOutcome.Success(
                        OneDriveTokens(
                            accessToken = token,
                            refreshToken = json.optString("refresh_token"),
                            expiresAt = System.currentTimeMillis() +
                                json.optLong("expires_in", 3600) * 1000L
                        )
                    )
                }
                continue
            }

            val err = json.optString("error")
            val desc = json.optString("error_description", json.toString())
            Log.i(
                TAG,
                "poll authority=$authority http=$code error=${err.ifEmpty { "-" }} " +
                    "leftSec=${(deadline - System.currentTimeMillis()) / 1000} raw=${desc.take(160)}"
            )
            when {
                err.isEmpty() -> continue
                err == "slow_down" -> waitMs += 5000
                err == "authorization_pending" -> Unit
                // 验证码过期 / 已被作废（用户在网页上用过一次就作废了）
                err == "expired_token" || desc.contains("AADSTS70008") ||
                    desc.contains("has expired") || err == "bad_verification_code" ->
                    return@withContext TokenOutcome.Expired
                err == "access_denied" -> return@withContext TokenOutcome.Failed(
                    OneDriveAuthException(
                        "access_denied",
                        desc,
                        "你取消了授权，没有拿到访问权限。"
                    )
                )
                else -> return@withContext TokenOutcome.Failed(
                    OneDriveAuthException(err, desc, "")
                )
            }
        }
        TokenOutcome.Timeout
    }

    /**
     * 授权码流程的最后一步：把浏览器跳回来的 code 换成 token。
     *
     * 这一步是机器对机器，用户看不到，也不会因为「慢了几十秒」而失败 ——
     * 这正是它比设备码流程可靠的地方。
     */
    suspend fun exchangeCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        authority: String,
        scopeMode: String = "",
        redirectUri: String = REDIRECT_URI,
    ): OneDriveTokens = withContext(Dispatchers.IO) {
        val scope = OneDriveScope.of(scopeMode.ifBlank { prefs.oneDriveScopeMode }).scope
        prefs.logAuth(
            "换 token 开始 authority=$authority clientId=${clientId.trim()} " +
                "redirect=$redirectUri scope=$scope codeLen=${code.length} " +
                "verifierLen=${codeVerifier.length}"
        )
        val body = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", codeVerifier)
            .add("scope", scope)
            .build()

        val response = post(tokenUrl(authority), body)
            ?: throw OneDriveAuthException("network", "", "连不上微软服务器，请检查网络后重试。")
        val (http, json) = response
        val token = json.optString("access_token")
        if (http == 200 && token.isNotEmpty()) {
            Log.i(
                TAG,
                "authcode ok authority=$authority hasRefreshtoken=${json.optString("refresh_token").isNotEmpty()}"
            )
            return@withContext OneDriveTokens(
                accessToken = token,
                refreshToken = json.optString("refresh_token"),
                expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000L,
            )
        }

        val error = OneDriveAuthException(
            json.optString("error", "http_$http"),
            json.optString("error_description", json.toString()),
            "",
        )
        Log.w(TAG, "authcode fail http=$http raw=${error.raw.take(300)}")
        throw error
    }

    /** 某个账号类型该用哪个端点（授权码流程直接用它） */
    fun authorityFor(accountKind: String): String = authorityOrder(accountKind).first()

    /** 用 refresh_token 换新的 access_token */
    suspend fun refresh(
        clientId: String,
        refreshToken: String,
        authority: String = "",
        scopeMode: String = "",
    ): OneDriveTokens? = withContext(Dispatchers.IO) {
        refreshNow(clientId, refreshToken, authority, scopeMode)
    }

    /**
     * 同步版本的刷新，给播放器的 OkHttp 拦截器用
     * （拦截器跑在后台线程上，可以直接阻塞等待）。
     */
    fun refreshNow(
        clientId: String,
        refreshToken: String,
        authority: String = "",
        scopeMode: String = "",
    ): OneDriveTokens? {
        if (clientId.isBlank() || refreshToken.isBlank()) return null
        val scope = OneDriveScope.of(scopeMode.ifBlank { prefs.oneDriveScopeMode }).scope
        // 刷新必须用和当初授权一样的端点
        val auth = authority.ifBlank { prefs.oneDriveAuthority }
            .ifBlank { DeviceCodeInfo.AUTHORITY_CONSUMERS }
        val body = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("scope", scope)
            .build()
        return try {
            val response = post(tokenUrl(auth), body) ?: return null
            val (code, json) = response
            if (code != 200) return null
            val token = json.optString("access_token")
            if (token.isEmpty()) null
            else OneDriveTokens(
                accessToken = token,
                refreshToken = json.optString("refresh_token", refreshToken),
                expiresAt = System.currentTimeMillis() +
                    json.optLong("expires_in", 3600) * 1000L
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 端点尝试顺序：上次成功过的 → 用户指定的 → 另一个。
     *
     * 个人账号必须优先用 consumers，否则登录页会把 outlook.com 当成企业账号，
     * 报「Microsoft 帐户不存在」。
     *
     * @param accountKind personal / work，也接受直接传端点名
     */
    private fun authorityOrder(accountKind: String): List<String> {
        val remembered = prefs.oneDriveAuthority
        val wanted = when (accountKind) {
            "work", DeviceCodeInfo.AUTHORITY_COMMON -> DeviceCodeInfo.AUTHORITY_COMMON
            "personal", DeviceCodeInfo.AUTHORITY_CONSUMERS -> DeviceCodeInfo.AUTHORITY_CONSUMERS
            else -> if (prefs.oneDriveAccountKind == "work") {
                DeviceCodeInfo.AUTHORITY_COMMON
            } else {
                DeviceCodeInfo.AUTHORITY_CONSUMERS
            }
        }
        // 用户这次明确选的端点必须排第一位。
        // 以前是「上次成功的端点」优先，结果用户在界面上选了「个人账号」，
        // 却拿到一个 common 端点的码 —— 码和验证页对不上，
        // 网页上就报「该代码无法工作」/「帐户不存在」。
        return listOf(wanted, remembered)
            .filter { it in AUTHORITIES }
            .distinct()
            .let { it + AUTHORITIES.filter { a -> a !in it } }
    }

    /**
     * 快速检查一个应用 ID 能不能用：只申请一次设备码，看微软收不收。
     * 不用走完整个授权，几秒钟出结论，省得在浏览器里反复试错。
     */
    suspend fun checkClientId(clientId: String, scopeMode: String = ""): String =
        withContext(Dispatchers.IO) {
            try {
                val info = requestDeviceCode(clientId, scopeMode)
                "这个应用 ID 可以正常申请授权码（走的 ${info.authority} 端点）。\n\n" +
                    "接下来点「获取授权码」，在网页上完成授权即可。" +
                    "如果网页上仍然报错，多半是微软不让你给这个应用做用户同意授权" +
                    "（第一方应用的限制），那就得换一个自己注册的应用 ID。"
            } catch (e: OneDriveAuthException) {
                "这个应用 ID 用不了：\n\n${explain(e)}"
            } catch (e: Exception) {
                "检查失败：${e.message ?: "网络异常，请检查网络后重试"}"
            }
        }

    /** 把微软的原始报错翻译成能看懂的中文 */
    fun explain(error: OneDriveAuthException): String {
        val raw = error.raw
        val lower = raw.lowercase()
        val hint = when {
            lower.contains("first party application") ||
                lower.contains("not permitted to consent") ->
                "这个应用 ID 是**微软的第一方应用**（比如 Office、Graph Explorer 这些）。\n\n" +
                    "第一方应用不允许用户自己做授权同意，只能用微软给它预授权的权限，" +
                    "所以拿它访问个人 OneDrive 会被拒。\n\n" +
                    "两条出路：\n" +
                    "1. 把下面的「请求的权限」换成「**内置默认**」再试一次" +
                    "（.default 表示用微软预授权的那一套权限，不需要你点同意）；\n" +
                    "2. 还是不行的话，就自己注册一个应用（App 里有一键跳转的教程，" +
                    "大概 5 分钟），然后把「请求的权限」换成「**只读**」。"

            lower.contains("aadsts70002") ->
                "这个应用被注册成了「Web」应用，设备码授权不支持。\n\n" +
                    "解决：注册页 →「身份验证」→「添加平台」→「移动和桌面应用程序」，" +
                    "然后在同一个面板里勾上「允许公共客户端流」。"

            lower.contains("aadsts700016") || lower.contains("aadsts7000215") ->
                "微软在你的账号下找不到这个应用 ID。最常见的两个原因：\n\n" +
                    "1. 用的是旧的 portal.azure.com 页面——微软已经关掉了「在目录外创建应用」这条路，" +
                    "请改用 https://entra.microsoft.com 注册（先在 Identity → Overview 里 Create a tenant 建一个目录）。\n" +
                    "2. 注册时「支持的账户类型」没选包含「个人 Microsoft 账户」。\n\n" +
                    "另外也要确认你已经有自己的目录（租户）：" +
                    "entra.microsoft.com → Identity → Overview → Create a tenant，" +
                    "或者加入免费的 Microsoft 365 开发人员计划。"

            lower.contains("aadsts50059") || lower.contains("aadsts900023") ->
                "找不到这个应用对应的租户。\n\n" +
                    "如果应用注册时「支持的账户类型」选的是「仅个人 Microsoft 账户」，" +
                    "那它是靠 App 自动切换到 consumers 端点来授权的，直接点「获取授权码」重试即可；" +
                    "如果还是失败，请确认客户端 ID 复制完整、没有多余空格。"

            lower.contains("aadsts50020") ||
                lower.contains("account does not exist") ||
                lower.contains("帐户不存在") ->
                "微软说找不到这个账号。\n\n" +
                    "多半是登录页把你的邮箱当成「工作或学校账号」去组织目录里找了。" +
                    "把上面的「账号类型」切成「**个人账号**」再试一次，" +
                    "App 会换成专供个人账号的验证页面（microsoft.com/link），" +
                    "outlook.com / hotmail.com 就认得出来了。"

            lower.contains("aadsts50105") ->
                "这个账号被指派到了错误的租户。\n\n" +
                    "切到「**个人账号**」再试一次。"

            lower.contains("redirect_uri") || lower.contains("aadsts50011") ->
                "这个应用还没登记登录完成后要跳回的地址（重定向 URI），所以微软拒绝了这次登录。\n\n" +
                    "去 Azure 门户修：打开你的应用 → 左侧「身份验证」→「+ 添加平台」→" +
                    "选「移动和桌面应用程序」→ 在弹出的面板里勾上\n" +
                    "https://login.microsoftonline.com/common/oauth2/nativeclient\n" +
                    "（面板里其它地址一起勾上也没关系）→ 点底部「配置」。\n\n" +
                    "保存后回到这里重新点「用应用内网页登录」即可，不用改应用 ID。"

            lower.contains("invalid_scope") ->
                "这个应用没有访问 OneDrive 的权限。\n\n" +
                    "**内置的微软官方客户端是走不通的**（实测微软直接回：" +
                    "scope is not valid - it refers to a resource which is not listed in the " +
                    "client's Required Resource Access）。它是微软自家的应用，你既不能给它加权限，" +
                    "也不能替它点同意。\n\n" +
                    "所以必须换成你自己注册的应用 ID（免费，约 5 分钟，App 里有分步教程）：\n" +
                    "1. 点下面「内置的用不了？自己注册一个」，按第 0~4 步做完；\n" +
                    "2. 把拿到的「应用程序(客户端) ID」填到上面的输入框；\n" +
                    "3.「请求的权限」选「只读」；\n" +
                    "4. 点「用应用内网页登录」。"

            lower.contains("unauthorized_client") ->
                "这个应用不允许公共客户端流（设备码授权）。\n\n" +
                    "解决：注册页 →「身份验证」→ 勾上「允许公共客户端流」。"

            else ->
                "微软拒绝了这次授权请求。"
        }
        return buildString {
            append(hint)
            if (raw.isNotBlank()) {
                append("\n\n原始返回：")
                append(raw.take(400))
            }
        }
    }

    /**
     * POST 一下，返回 HTTP 状态码和 JSON。
     * 返回 null 表示网络层就失败了（没拿到任何响应）——这种情况不能当成微软的拒绝。
     */
    private fun post(url: String, body: FormBody): Pair<Int, JSONObject>? {
        val request = Request.Builder().url(url).post(body).build()
        val grant = (0 until body.size)
            .firstOrNull { body.name(it) == "grant_type" }
            ?.let { body.value(it) }
            .orEmpty()
        val startedAt = System.currentTimeMillis()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject()
                }
                prefs.logAuth(
                    "POST ${url.substringBefore("?")} grant=$grant http=${resp.code} " +
                        "costMs=${System.currentTimeMillis() - startedAt} " +
                        "resp=${redact(text).take(600)}"
                )
                resp.code to json
            }
        } catch (e: Exception) {
            prefs.logAuth(
                "POST ${url.substringBefore("?")} grant=$grant 网络异常 " +
                    "costMs=${System.currentTimeMillis() - startedAt} " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    /**
     * 把返回里的令牌抹掉再写日志。
     *
     * 这些日志写在应用私有目录里，但没必要留一份能直接拿去用的 access_token ——
     * 排查只要看长度和错误码就够了。
     */
    private fun redact(text: String): String = text
        .replace(Regex("\"(access_token|refresh_token|id_token)\"\\s*:\\s*\"[^\"]*\"")) {
            "\"${it.groupValues[1]}\":\"<${it.value.substringAfter("\":\"").dropLast(1).length}字符>\""
        }
}
