package com.cloudmusic.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudmusic.app.App
import com.cloudmusic.app.data.playlist.SavedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「我的歌单」列表。 */
class PlaylistListViewModel(app: Application) : AndroidViewModel(app) {

    private val store = (app as App).playlistStore

    var playlists by mutableStateOf<List<SavedPlaylist>>(emptyList())
        private set

    /** 每张歌单已经导入了几首 */
    var importedCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    var loading by mutableStateOf(false)
        private set

    var message by mutableStateOf("")

    /** 搜歌单用的关键字 */
    var query by mutableStateOf("")

    /** 列表里要显示哪些（按歌单名过滤） */
    val visiblePlaylists: List<SavedPlaylist>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return playlists
            return playlists.filter { it.name.contains(q, ignoreCase = true) }
        }

    /** 所有歌单一共多少首 */
    val totalTracks: Int
        get() = playlists.sumOf { it.trackCount }

    /** 已经导进网盘的总首数 */
    val totalImported: Int
        get() = importedCounts.values.sum()

    fun refresh() {
        loading = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.playlists() }
            val counts = withContext(Dispatchers.IO) {
                list.associate { it.key to store.importedCount(it.key) }
            }
            playlists = list
            importedCounts = counts
            loading = false
        }
    }

    /** 删掉一张歌单。网盘里已经导进去的歌不动。 */
    fun delete(key: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(key) }
            refresh()
            message = "歌单已删除，网盘里的歌还在"
        }
    }
}
