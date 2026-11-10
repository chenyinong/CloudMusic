package com.cloudmusic.app.data

import android.content.Context
import android.util.Base64
import com.cloudmusic.app.data.onedrive.OneDriveAuth
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/**
 * WebDAV 凭据。password 一律使用网盘「应用密码」，而非账号登录密码。
 */
data class DavCredentials(
    val serverUrl: String,
    val account: String,
    val appPassword: String,
) {
    val authorization: String
        get() = "Basic " + Base64.encodeToString(
            "$account:$appPassword".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
}

/**
 * 轻量本地存储。保存服务器地址、账号与应用密码。
 */
class Prefs(context: Context) {

    companion object {
        const val DEFAULT_SERVER = "https://dav.jianguoyun.com/dav/"
        private const val KEY_SERVER = "server_url"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_PASSWORD = "app_password"
        private const val KEY_ONLINE_LYRICS = "online_lyrics"
        private const val KEY_PROVIDER = "provider_kind"
        private const val KEY_OD_CLIENT = "onedrive_client_id"
        private const val KEY_OD_TOKEN = "onedrive_access_token"
        private const val KEY_OD_REFRESH = "onedrive_refresh_token"
        private const val KEY_OD_EXPIRY = "onedrive_token_expiry"
        private const val KEY_OD_ACCOUNT = "onedrive_account"
        private const val KEY_OD_AUTHORITY = "onedrive_authority"
        private const val KEY_OD_SCOPE = "onedrive_scope_mode"
        private const val KEY_OD_ACCOUNT_KIND = "onedrive_account_kind"
        private const val KEY_IMPORT_PROVIDER = "import_provider"
        private const val KEY_IMPORT_DIR = "import_dir"
        private const val KEY_QUARK_COOKIE = "quark_cookie"
        private const val KEY_QUARK_ACCOUNT = "quark_account"
        private const val KEY_QUARK_LOGIN_AT = "quark_login_at"
        private const val KEY_DAV_CAPACITY = "dav_capacity_bytes"
        private const val KEY_DAV_USED = "dav_used_bytes"
        private const val KEY_IMPORTED = "imported_tracks"
        private const val KEY_DAV_ROOT_WRITABLE = "dav_root_writable"
        private const val KEY_THEME_FOLLOW = "theme_follow_system"
        private const val KEY_TAGS_FIX_V1 = "tags_fix_v1"
    }

    private val appContext = context.applicationContext

    private val sp = appContext
        .getSharedPreferences("cloud_music_prefs", Context.MODE_PRIVATE)

    /**
     * 把跟微软的 HTTP 往返记一份到应用私有目录。
     *
     * 为什么不只用 logcat：这台机器的 logcat 缓冲区几分钟就滚没了，
     * 等用户把网页登录点完，前面发的日志早就被系统刷掉了 —— 排查时啥也看不到。
     * 落文件之后可以随时 `run-as ... cat files/onedrive_auth.log` 复盘。
     */
    fun logAuth(message: String) {
        runCatching {
            val file = java.io.File(appContext.filesDir, "onedrive_auth.log")
            if (file.exists() && file.length() > 128 * 1024) file.delete()
            file.appendText(
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + "  " + message + "\n"
            )
        }
    }

    var serverUrl: String
        get() = sp.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        set(v) = sp.edit().putString(KEY_SERVER, v.trim()).apply()

    var account: String
        get() = sp.getString(KEY_ACCOUNT, null) ?: ""
        set(v) = sp.edit().putString(KEY_ACCOUNT, v.trim()).apply()

    var appPassword: String
        get() = sp.getString(KEY_PASSWORD, null) ?: ""
        set(v) = sp.edit().putString(KEY_PASSWORD, v.trim()).apply()

    val hasCredentials: Boolean
        get() = account.isNotBlank() && appPassword.isNotBlank()

    /** 是否允许联网抓在线歌词 */
    var onlineLyrics: Boolean
        get() = sp.getBoolean(KEY_ONLINE_LYRICS, true)
        set(v) = sp.edit().putBoolean(KEY_ONLINE_LYRICS, v).apply()

    /** 是否跟随系统深色模式。默认 false（固定浅色）。 */
    var themeFollowSystem: Boolean
        get() = sp.getBoolean(KEY_THEME_FOLLOW, false)
        set(v) = sp.edit().putBoolean(KEY_THEME_FOLLOW, v).apply()

    /** 历史曲库「歌名/歌手反了」的一次性纠正是否已跑过 */
    var tagsFixV1Done: Boolean
        get() = sp.getBoolean(KEY_TAGS_FIX_V1, false)
        set(v) = sp.edit().putBoolean(KEY_TAGS_FIX_V1, v).apply()

    val credentials: DavCredentials
        get() = DavCredentials(serverUrl, account, appPassword)

    fun saveCredentials(creds: DavCredentials) {
        serverUrl = creds.serverUrl
        account = creds.account
        appPassword = creds.appPassword
    }

    fun clearCredentials() {
        sp.edit()
            .remove(KEY_ACCOUNT)
            .remove(KEY_PASSWORD)
            .apply()
    }

    // ---------------- 网盘类型 ----------------

    /** 当前使用的网盘 */
    var providerKind: String
        get() = sp.getString(KEY_PROVIDER, ProviderKind.WEBDAV.id) ?: ProviderKind.WEBDAV.id
        set(v) = sp.edit().putString(KEY_PROVIDER, v).apply()

    /** WebDAV 服务器的 host，给播放时的鉴权用 */
    val webdavHost: String
        get() = try {
            serverUrl.toHttpUrl().host
        } catch (e: Exception) {
            ""
        }

    /**
     * WebDAV 根目录能不能直接放文件。
     *
     * 坚果云这类网盘的根目录往往不让放文件（PUT 回 404），
     * 得先 MKCOL 建一个子目录才行。
     *
     * 这个结论是「这个网盘是什么脾气」，跟哪首歌无关 ——
     * 测一次就该记下来，别每首歌都去试一次。
     * null = 还没试过。
     */
    var davRootWritable: Boolean?
        get() = if (sp.contains(KEY_DAV_ROOT_WRITABLE)) {
            sp.getBoolean(KEY_DAV_ROOT_WRITABLE, false)
        } else {
            null
        }
        set(v) {
            sp.edit().apply {
                if (v == null) remove(KEY_DAV_ROOT_WRITABLE)
                else putBoolean(KEY_DAV_ROOT_WRITABLE, v)
            }.apply()
        }

    // ---------------- OneDrive ----------------

    /** 自己注册的应用 ID（Azure 应用注册里的"应用程序(客户端) ID"） */
    var oneDriveClientId: String
        get() = sp.getString(KEY_OD_CLIENT, null)?.takeIf { it.isNotBlank() }
            ?: OneDriveAuth.BUILTIN_CLIENT_ID
        set(v) = sp.edit().putString(KEY_OD_CLIENT, v.trim()).apply()

    var oneDriveAccessToken: String
        get() = sp.getString(KEY_OD_TOKEN, null) ?: ""
        set(v) = sp.edit().putString(KEY_OD_TOKEN, v.trim()).apply()

    var oneDriveRefreshToken: String
        get() = sp.getString(KEY_OD_REFRESH, null) ?: ""
        set(v) = sp.edit().putString(KEY_OD_REFRESH, v.trim()).apply()

    /** access_token 过期时间（毫秒） */
    var oneDriveTokenExpiry: Long
        get() = sp.getLong(KEY_OD_EXPIRY, 0L)
        set(v) = sp.edit().putLong(KEY_OD_EXPIRY, v).apply()

    /** 已连接的账号昵称 */
    var oneDriveAccount: String
        get() = sp.getString(KEY_OD_ACCOUNT, null) ?: ""
        set(v) = sp.edit().putString(KEY_OD_ACCOUNT, v.trim()).apply()

    /**
     * 授权时实际用的租户端点（common / consumers）。
     * 刷新令牌时必须用同一个端点，不然微软会拒绝，所以要记下来。
     * 空串 = 还没成功授权过。
     */
    var oneDriveAuthority: String
        get() = sp.getString(KEY_OD_AUTHORITY, "") ?: ""
        set(v) = sp.edit().putString(KEY_OD_AUTHORITY, v).apply()

    /**
     * 申请的权限范围模式（default / readonly / readwrite）。
     * 换 access_token 时必须用同一个 scope，不然微软会拒绝。
     */
    var oneDriveScopeMode: String
        get() = sp.getString(KEY_OD_SCOPE, "default") ?: "default"
        set(v) = sp.edit().putString(KEY_OD_SCOPE, v).apply()

    /**
     * 账号类型：personal（outlook.com 等个人账号）/ work（工作或学校账号）。
     * 个人账号必须走 consumers 端点，否则微软会把邮箱当成企业账号，
     * 登录页报「Microsoft 帐户不存在」。
     */
    var oneDriveAccountKind: String
        get() = sp.getString(KEY_OD_ACCOUNT_KIND, "personal") ?: "personal"
        set(v) = sp.edit().putString(KEY_OD_ACCOUNT_KIND, v).apply()

    /** 在线搜歌导入：默认导入到哪个网盘 */
    var importProvider: String
        get() = sp.getString(KEY_IMPORT_PROVIDER, "webdav") ?: "webdav"
        set(v) = sp.edit().putString(KEY_IMPORT_PROVIDER, v).apply()

    /** 在线搜歌导入：存到网盘里的哪个目录（相对根目录） */
    var importDir: String
        get() = sp.getString(KEY_IMPORT_DIR, "云音乐导入") ?: "云音乐导入"
        set(v) = sp.edit().putString(KEY_IMPORT_DIR, v).apply()

    val oneDriveConnected: Boolean
        get() = oneDriveAccessToken.isNotBlank() && oneDriveClientId.isNotBlank()

    // ---------------- 夸克网盘（下载链路的中转站） ----------------

    /**
     * 夸克的登录 cookie。
     *
     * 登录态就这 6 个（从夸克网页端的登出逻辑里扒出来的）：
     * `__pus` `__puus` `__kp` `__kps` `__uid` `__ktd`。
     * 其中 `__pus` 是主令牌，其余是配套的设备/风控标识。
     */
    var quarkCookie: String
        get() = sp.getString(KEY_QUARK_COOKIE, "") ?: ""
        set(v) = sp.edit().putString(KEY_QUARK_COOKIE, v.trim()).apply()

    /** 登录时拿到的账号昵称，只是给界面显示用 */
    var quarkAccount: String
        get() = sp.getString(KEY_QUARK_ACCOUNT, "") ?: ""
        set(v) = sp.edit().putString(KEY_QUARK_ACCOUNT, v.trim()).apply()

    /** 登录时刻，用来提醒用户 cookie 是不是该换了 */
    var quarkLoginAt: Long
        get() = sp.getLong(KEY_QUARK_LOGIN_AT, 0L)
        set(v) = sp.edit().putLong(KEY_QUARK_LOGIN_AT, v).apply()

    val quarkLoggedIn: Boolean
        get() = quarkCookie.contains("__pus") && quarkCookie.isNotBlank()

    /** cookie 大概一个多月就失效，久了提示重新登录 */
    val quarkCookieStale: Boolean
        get() {
            if (!quarkLoggedIn) return true
            val days = (System.currentTimeMillis() - quarkLoginAt) / (24 * 3600 * 1000)
            return days > 30
        }

    fun saveQuark(cookie: String, account: String = "") {
        quarkCookie = cookie
        quarkAccount = account
        quarkLoginAt = System.currentTimeMillis()
    }

    fun clearQuark() {
        sp.edit()
            .remove(KEY_QUARK_COOKIE)
            .remove(KEY_QUARK_ACCOUNT)
            .remove(KEY_QUARK_LOGIN_AT)
            .apply()
    }

    /**
     * 坚果云套餐容量（字节）。0 = 不知道。
     *
     * 坚果云的 WebDAV 接口**不提供配额查询**（PROPFIND 的 quota 属性一律 404），
     * 官方也没开放用应用密码查配额的接口，所以总额度只能让用户按自己的套餐选一下，
     * 剩下的空间 = 套餐容量 - 扫描统计到的已用大小。
     */
    var davCapacityBytes: Long
        get() = sp.getLong(KEY_DAV_CAPACITY, 0L)
        set(v) = sp.edit().putLong(KEY_DAV_CAPACITY, v.coerceAtLeast(0L)).apply()

    /**
     * 已经导入到网盘的曲目（JSON）。
     *
     * 键是搜索结果的 id（比如 `quark:cmxxxx`），值是导入之后它在网盘里的位置。
     *
     * 存下来是为了**重启 App 后还认得出「这条已经导过了」** ——
     * 否则用户回到导入页看到的又是「导入」按钮，一点就重下一遍。
     */
    var importedTracksJson: String
        get() = sp.getString(KEY_IMPORTED, "") ?: ""
        set(v) = sp.edit().putString(KEY_IMPORTED, v).apply()

    /**
     * 把「已导入」记录里指向某个网盘文件的条目清掉。
     *
     * 同一个网盘文件可能被两条搜索结果各记了一次（文件名一样，上传时覆盖了同一个文件），
     * 删文件的时候得一起清 —— 留着的那条就是个「点得进、播不出」的死记录。
     *
     * @return 被清掉的搜索结果 id
     */
    fun removeImportedEntries(provider: String, entryId: String): List<String> {
        val raw = importedTracksJson
        if (raw.isBlank() || entryId.isBlank()) return emptyList()
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val removed = mutableListOf<String>()
        // 先把 key 收集齐再删 —— 一边遍历一边改 JSONObject 会抛异常
        val keys = mutableListOf<String>()
        val it = json.keys()
        while (it.hasNext()) keys.add(it.next())
        for (k in keys) {
            val o = json.optJSONObject(k) ?: continue
            if (o.optString("provider") == provider && o.optString("entryId") == entryId) {
                json.remove(k)
                removed.add(k)
            }
        }
        if (removed.isNotEmpty()) importedTracksJson = json.toString()
        return removed
    }

    /**
     * 上次扫描统计到的已用空间（字节）。
     *
     * 存下来是为了重启 App 后还能显示 —— 扫描一次可能要几十秒，
     * 没必要每次启动都重扫一遍。
     */
    var davUsedBytes: Long
        get() = sp.getLong(KEY_DAV_USED, 0L)
        set(v) = sp.edit().putLong(KEY_DAV_USED, v.coerceAtLeast(0L)).apply()

    fun saveOneDriveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        val editor = sp.edit()
            .putString(KEY_OD_TOKEN, accessToken.trim())
            .putLong(KEY_OD_EXPIRY, expiresAt)
        // 刷新时微软不一定再发 refresh_token。
        // 要是照单全收就把原来那个覆盖成空了 —— 之后就没法自动续期，
        // 用户用了一个小时就得重新登录一次。所以空的就别动它。
        val newRefresh = refreshToken.trim()
        if (newRefresh.isNotEmpty()) {
            editor.putString(KEY_OD_REFRESH, newRefresh)
        }
        editor.apply()
    }

    fun clearOneDrive() {
        sp.edit()
            .remove(KEY_OD_TOKEN)
            .remove(KEY_OD_REFRESH)
            .remove(KEY_OD_EXPIRY)
            .remove(KEY_OD_ACCOUNT)
            .remove(KEY_OD_AUTHORITY)
            .remove(KEY_OD_SCOPE)
            .apply()
    }
}
