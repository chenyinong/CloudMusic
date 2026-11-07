package com.cloudmusic.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudmusic.app.App
import com.cloudmusic.app.data.playlist.ImportedPlaylist
import com.cloudmusic.app.data.playlist.PlaylistPlatform
import com.cloudmusic.app.data.playlist.PlaylistRef
import kotlinx.coroutines.launch

/**
 * 「拉歌单 → 存进我的歌单」这条链路的状态。
 *
 * 这一步**不碰音频**：一张歌单可能几百首，全下下来既慢又费流量。
 * 这里只把清单存下来，用户进歌单里想听哪首再点哪首。
 *
 * 跟 [ImportViewModel] 一样用 Compose 的 mutableStateOf，没上 StateFlow。
 */
class PlaylistImportViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef: App = app as App

    var input by mutableStateOf("")
    var platform by mutableStateOf(PlaylistPlatform.NETEASE)

    /**
     * 纯数字 id 有歧义 —— 网易云和 QQ 的歌单 id 都是数字。
     * 用户点一下选平台，没选就按网易云处理。
     */
    var platformChoice by mutableStateOf<PlaylistPlatform?>(null)

    var loading by mutableStateOf(false)
    var saving by mutableStateOf(false)

    /** 刚拉下来的歌单清单（还没存） */
    var playlist by mutableStateOf<ImportedPlaylist?>(null)

    /** 用户粘进来的原始文本，重新保存时用得上 */
    var sourceUrl by mutableStateOf("")

    var message by mutableStateOf("")

    /** 存好了的歌单 key，非空表示可以跳到歌单详情 */
    var savedKey by mutableStateOf<String?>(null)
        private set

    // ------------------------------------------------------------ 拉歌单

    fun load() {
        val text = input.trim()
        if (text.isEmpty()) {
            message = "把歌单链接或者 id 粘进来"
            return
        }
        if (loading) return
        loading = true
        message = ""
        playlist = null
        savedKey = null
        sourceUrl = text
        viewModelScope.launch {
            try {
                var ref = appRef.playlistImport.resolveRef(text)
                if (ref == null) {
                    message = "看不出是哪个平台的歌单，贴个完整链接试试"
                    return@launch
                }
                // 纯数字 id 两家都是数字，按用户选的平台来
                val choice = platformChoice
                if (choice != null && text.matches(Regex("\\d+"))) {
                    ref = PlaylistRef(choice, ref.playlistId)
                }
                val pl = appRef.playlistImport.fetchPlaylist(ref)
                playlist = pl
                platform = pl.platform
                message = "「${pl.name}」共 ${pl.tracks.size} 首 · ${pl.platform.label}"
            } catch (e: Exception) {
                message = e.message ?: "拉歌单失败"
            } finally {
                loading = false
            }
        }
    }

    // ------------------------------------------------------------ 存歌单

    /**
     * 把清单存进「我的歌单」。
     *
     * 只存清单，一首歌都不下。存完用户可以进歌单里点单曲导入。
     */
    fun save() {
        val pl = playlist ?: return
        if (saving) return
        saving = true
        message = ""
        viewModelScope.launch {
            try {
                val key = appRef.playlistStore.save(pl, sourceUrl)
                savedKey = key
                message = "已存进「我的歌单」，想听哪首点哪首"
            } catch (e: Exception) {
                message = e.message ?: "保存失败"
            } finally {
                saving = false
            }
        }
    }
}
