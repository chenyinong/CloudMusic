package com.cloudmusic.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudmusic.app.App
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.playlist.SavedPlaylist
import com.cloudmusic.app.data.playlist.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「我的歌单」列表。 */
class PlaylistListViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef: App = app as App
    private val store = appRef.playlistStore

    var playlists by mutableStateOf<List<SavedPlaylist>>(emptyList())
        private set

    /** 每张歌单已经导入了几首 */
    var importedCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** 每张歌单借哪首歌的封面来当歌单封面 */
    var coverTracks by mutableStateOf<Map<String, Track>>(emptyMap())
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

    /** 重新读一遍本机歌单。返回 Job，调用方（比如下拉刷新）可以等它做完。 */
    fun refresh(): Job = viewModelScope.launch {
        loading = true
        val list = withContext(Dispatchers.IO) { store.playlists() }
        val counts = withContext(Dispatchers.IO) {
            list.associate { it.key to store.importedCount(it.key) }
        }
        val covers = withContext(Dispatchers.IO) { coverTracksFor(list) }
        playlists = list
        importedCounts = counts
        coverTracks = covers
        loading = false
    }

    /**
     * 歌单封面借用里面某一首歌的封面：优先取网盘里真实存在的文件，
     * 这样能直接命中曲库已有的封面缓存；一首都对不上就造一个只有
     * 歌名/歌手的 Track，让 CoverRepository 去在线检索。
     */
    private fun coverTracksFor(list: List<SavedPlaylist>): Map<String, Track> {
        val existing = appRef.repository.cachedTracks()
        val byFileName = existing.associateBy { it.fileName }
        val byTitle = existing.groupBy {
            TrackMatcher.norm(TrackMatcher.stripBrackets(it.title))
        }
        val out = mutableMapOf<String, Track>()
        for (pl in list) {
            val songs = store.tracks(pl.key).take(8)
            val first = songs.firstOrNull() ?: continue
            val matched = songs.firstNotNullOfOrNull { t ->
                t.note.takeIf { it.isNotBlank() }?.let { byFileName[it] }
                    ?: byTitle[TrackMatcher.norm(TrackMatcher.stripBrackets(t.title))]
                        ?.firstOrNull { TrackMatcher.artistHit(t.artist, it.artist) }
            }
            out[pl.key] = coverTrackFor(first, matched)
        }
        return out
    }

    /** 删掉一张歌单。网盘里已经导进去的歌不动。 */
    /**
     * 删一张本机歌单。
     *
     * **只删本机这一份。** 云端的那份是上传过去的副本，删了本机的不该
     * 把它也带走 —— 云端歌单是能分享给别的设备的，本机这份才是自己的原件。
     * 想连云端那份一起删，去「云端歌单」分组里删那张。
     */
    fun delete(key: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(key) }
            refresh()
            message = "歌单已删除，云端那份还在（要删去「云端歌单」里删）"
        }
    }
}
