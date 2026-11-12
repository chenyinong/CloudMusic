package com.cloudmusic.app.data.cloud

import android.util.Log
import com.cloudmusic.app.data.Prefs
import com.cloudmusic.app.data.playlist.PlaylistPlatform
import com.cloudmusic.app.data.playlist.SavedPlaylist
import com.cloudmusic.app.data.playlist.SavedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 云端同步的编排。
 *
 * 双向：云端有的补进本地，本地有的补传云端；云端标记删除的（别的设备取消了收藏）
 * 本地也跟着删 —— 墓碑机制在云端那侧，这里只要认这个标记就行。
 */
class CloudSync(
    private val client: CloudClient,
    private val store: CloudStore,
    private val prefs: Prefs,
    /** 歌单封面：按「第一首歌的封面」这个口径算 */
    private val cover: CloudCover? = null,
) {

    companion object {
        private const val TAG = "CM/Cloud"

        /**
         * 刚删掉的歌单，短时间内不再让「拉列表」写回来。
         *
         * 删除和刷新列表可能并发：先发出去的拉取拿到的是**删除前**的快照，
         * 要是晚一步到，就会把刚删掉的歌单又塞回本地缓存 ——
         * 用户看着就像删除没生效。这个墓碑就是挡这一下的。
         */
        private const val DELETE_TOMBSTONE_MS = 60_000L
    }

    /** 刚删掉的歌单 id → 删除时刻。 */
    private val recentDeletes = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    // ---------------- 收藏 ----------------

    /**
     * 拉一次云端收藏，覆盖本地缓存。
     *
     * @return 有效（没被删）的收藏条数
     */
    suspend fun pullFavorites(): Int = withContext(Dispatchers.IO) {
        val list = client.favorites()
        val alive = mutableListOf<CloudTrack>()
        for (f in list) {
            if (f.deleted) {
                store.removeFavorite(f.key)
                continue
            }
            val t = f.track ?: continue
            alive.add(t)
            store.upsertFavorite(t, f.updatedAt)
        }
        // 云端没有的本地条目，也别留着 —— 那是上次同步失败留下的残渣
        val cloudKeys = list.map { it.key }.toSet()
        for (t in store.favorites()) {
            if (t.key !in cloudKeys) store.removeFavorite(t.key)
        }
        alive.size
    }

    /** 收藏一首歌（云端 + 本地）。 */
    suspend fun pushFavorite(track: CloudTrack) = withContext(Dispatchers.IO) {
        client.addFavorite(track)
        store.upsertFavorite(track)
    }

    /** 取消收藏（云端 + 本地）。 */
    suspend fun removeFavorite(key: String) = withContext(Dispatchers.IO) {
        client.removeFavorite(key)
        store.removeFavorite(key)
    }

    // ---------------- 歌单 ----------------

    /**
     * 拉云端歌单列表（只拉概要，明细点开时再拉）。
     *
     * 概要里没有封面时，顺手把明细拉下来自己算 —— 跟网页版一个口径：
     * **歌单封面 = 第一首歌的封面**。
     */
    suspend fun pullPlaylists(): Int = withContext(Dispatchers.IO) {
        val list = client.playlists()
        // 过期了的墓碑清掉
        val now = System.currentTimeMillis()
        val it0 = recentDeletes.entries.iterator()
        while (it0.hasNext()) {
            if (now - it0.next().value > DELETE_TOMBSTONE_MS) it0.remove()
        }
        val skip = recentDeletes.keys
        for (p in list) {
            // 刚删掉的，别让旧的拉取结果又写回来
            if (p.id in skip) continue
            if (p.cover.isBlank()) {
                // 云端没给封面 → 拉一次明细，自己按第一首歌算
                val detail = runCatching { client.playlist(p.id) }.getOrNull()
                if (detail != null) {
                    store.savePlaylist(detail, coverUrlOf(detail.tracks))
                    continue
                }
            }
            store.savePlaylistRef(p)
        }
        // 云端是权威：云端已经删掉的，本地也别留着。
        //
        // 不这么清的话会出这个幺蛾子 ——「删除」和「刷新列表」并发时，
        // 早先发出去的那次拉取会把歌单又写回本地，用户看着就像没删掉。
        val ids = list.map { it.id }.toSet() - skip
        for (old in store.playlists()) {
            if (old.id !in ids) store.deletePlaylist(old.id)
        }
        list.size
    }

    /**
     * 歌单封面：曲目里自带的优先，没有就按平台 id 去问平台要。
     */
    private suspend fun coverUrlOf(tracks: List<CloudTrack>): String {
        if (tracks.isEmpty()) return ""
        tracks.firstOrNull { it.coverUrl.isNotBlank() }?.let { return it.coverUrl }
        val c = cover ?: return ""
        return runCatching { c.coverOfPlaylist(tracks) }.getOrDefault("")
    }

    /** 拉一张云端歌单的明细。 */
    suspend fun pullPlaylistDetail(id: Long): CloudPlaylistDetail = withContext(Dispatchers.IO) {
        val detail = client.playlist(id)
        store.savePlaylist(detail)
        detail
    }

    /**
     * 把本机的一张歌单传到云端。
     *
     * 曲目字段跟网页版的 Track 是同一套，两端可以直接对着看。
     */
    suspend fun pushPlaylist(
        name: String,
        platform: String,
        tracks: List<CloudTrack>,
    ): Long = withContext(Dispatchers.IO) {
        val id = client.createPlaylist(name, platform, tracks)
        pullPlaylists()
        id
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        client.deletePlaylist(id)
        // 记个墓碑：短时间内任何「拉列表」都不许把这张单写回来
        recentDeletes[id] = System.currentTimeMillis()
        store.deletePlaylist(id)
    }

    /**
     * 把本机歌单（[SavedPlaylist] + 它的曲目）转成云端格式。
     *
     * key 用 `trackKey`（平台内的歌曲标识），跟网页版拉下来的歌单一致，
     * 这样同一张单在两端能认出是同一首。
     *
     * 顺手补上封面直链 —— 云端歌单封面取的是第一首歌的封面，
     * 不补的话上传过去的那张单在网页版也是没有封面的。
     */
    suspend fun toCloudTracks(saved: SavedPlaylist, tracks: List<SavedTrack>): List<CloudTrack> {
        val list = tracks.map { t ->
            CloudTrack(
                key = t.trackKey.ifBlank { "${saved.key}|${t.position}" },
                title = t.title,
                artist = t.artist,
                album = t.album,
                durationSec = t.durationSec,
                platform = t.platform.id,
                platformId = t.platformId,
                coverUrl = "",
                quarkId = "",
            )
        }
        val c = cover ?: return list
        return runCatching { c.fillCovers(list) }.getOrDefault(list)
    }

    /** 本机歌单的平台，网页版只认 netease / qq 这两个。 */
    fun cloudPlatformOf(p: PlaylistPlatform): String = p.id

    // ---------------- 夸克 Cookie ----------------

    /**
     * 云端存的夸克 Cookie。没有就返回 null。
     *
     * 纯读取，不动本机 —— 到底用不用由上层决定。
     */
    suspend fun pullQuarkCookie(): String? = withContext(Dispatchers.IO) {
        val cookie = client.quarkCookie() ?: return@withContext null
        cookie.takeIf { it.isNotBlank() }
    }

    /**
     * 从云端恢复夸克登录态。
     *
     * 本机已经登录的话**不覆盖** —— 本机这份是用户刚扫码换来的，
     * 比云端那份新，覆盖了反而会让正在用的直链失效。
     *
     * @return true = 真的从云端恢复了一份
     */
    suspend fun restoreQuarkCookie(): Boolean = withContext(Dispatchers.IO) {
        val cookie = client.quarkCookie() ?: return@withContext false
        if (cookie.isBlank()) return@withContext false
        if (prefs.quarkLoggedIn) {
            Log.d(TAG, "本机已登录夸克，保留本机 Cookie")
            return@withContext false
        }
        prefs.saveQuark(cookie, "")
        true
    }

    /** 把本机的夸克 Cookie 传到云端（网页版会自动取到）。 */
    suspend fun pushQuarkCookie(cookie: String) = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) return@withContext
        client.saveQuarkCookie(cookie)
    }

    suspend fun clearQuarkCookie() = withContext(Dispatchers.IO) {
        client.deleteQuarkCookie()
    }

    // ---------------- 全量同步 ----------------

    /** 一次同步的结果，给界面显示。 */
    data class SyncResult(
        val favorites: Int = 0,
        val playlists: Int = 0,
        val quarkRestored: Boolean = false,
        val message: String = "",
    )

    /**
     * 登录之后拉一次全量。
     *
     * 顺便把云端存的夸克 Cookie 取回来 —— 本机没登录夸克时直接用上，
     * 换设备就不用再扫码了。
     */
    suspend fun syncAll(): SyncResult = withContext(Dispatchers.IO) {
        val favCount = pullFavorites()
        val plCount = pullPlaylists()
        val restored = restoreQuarkCookie()
        SyncResult(
            favorites = favCount,
            playlists = plCount,
            quarkRestored = restored,
            message = "已同步：收藏 $favCount 首 · 歌单 $plCount 张" +
                if (restored) " · 已从云端恢复夸克登录态" else "",
        )
    }
}
