package com.cloudmusic.app.data

import com.cloudmusic.app.data.onedrive.OneDriveAuth
import okhttp3.OkHttpClient

/**
 * 拉流时的鉴权头。
 *
 * 播放器和 DLNA 代理都要去网盘取音频，鉴权规则完全一样：
 * OneDrive 走 Bearer，用户自己的 WebDAV 走 Basic。
 * 抽成一份，两边不会走偏。
 */
object StreamAuth {

    private var cachedPrefs: Prefs? = null
    private var cachedAuth: OneDriveAuth? = null

    private fun oneDriveAuth(prefs: Prefs): OneDriveAuth {
        val p = cachedPrefs
        val a = cachedAuth
        if (p === prefs && a != null) return a
        val created = OneDriveAuth(prefs, OkHttpClient())
        cachedPrefs = prefs
        cachedAuth = created
        return created
    }

    /** 按目标 host 决定用哪种鉴权；不需要鉴权时返回 null */
    fun header(prefs: Prefs, host: String): String? {
        if (host.isBlank()) return null
        return when {
            host.equals("graph.microsoft.com", ignoreCase = true) -> bearer(prefs)
            prefs.webdavHost.isNotEmpty() &&
                host.equals(prefs.webdavHost, ignoreCase = true) ->
                prefs.credentials.authorization.takeIf { prefs.hasCredentials }
            else -> null
        }
    }

    /** OneDrive 的 access_token 过期时顺手刷新一下，不然长曲子播到一半会 401 */
    fun bearer(prefs: Prefs): String? {
        var token = prefs.oneDriveAccessToken
        if (token.isNotBlank() &&
            System.currentTimeMillis() >= prefs.oneDriveTokenExpiry - 60_000
        ) {
            val refreshed = oneDriveAuth(prefs).refreshNow(
                prefs.oneDriveClientId,
                prefs.oneDriveRefreshToken,
                prefs.oneDriveAuthority,
                prefs.oneDriveScopeMode,
            )
            if (refreshed != null) {
                prefs.saveOneDriveTokens(
                    refreshed.accessToken, refreshed.refreshToken, refreshed.expiresAt
                )
                token = refreshed.accessToken
            }
        }
        return token.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
    }
}
