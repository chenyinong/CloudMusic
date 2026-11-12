package com.cloudmusic.app.data.playlist

import android.content.ContentValues
import com.cloudmusic.app.data.LibraryDb

/** 存下来的歌单。只存清单，歌曲本体在网盘里。 */
data class SavedPlaylist(
    val key: String,
    val platform: PlaylistPlatform,
    val playlistId: String,
    val name: String,
    val creator: String,
    val trackCount: Int,
    val sourceUrl: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * 它对应的那张云端歌单 id（上传到云端之后才有）。
     *
     * 记这个是为了删得干净 —— 本机和云端是两条记录，
     * 删一边另一边还在的话，用户看着就像没删掉。
     */
    val cloudId: Long = 0L,
)

/** 歌单里的一首歌，连同它自己的导入状态。 */
data class SavedTrack(
    val key: String,
    val playlistKey: String,
    /** 平台内的歌曲标识，用来在重新拉歌单时认出「还是这一首」 */
    val trackKey: String,
    val position: Int,
    val platform: PlaylistPlatform,
    val platformId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    /** 见 [TrackState] */
    val state: String,
    val note: String,
) {
    /** 还原成「歌单里的一首曲目」，好让匹配和下载那套逻辑直接复用。 */
    fun toPlaylistTrack(): PlaylistTrack = PlaylistTrack(
        key = trackKey.ifBlank { key },
        title = title,
        artist = artist,
        album = album,
        durationSec = durationSec,
        platform = platform,
        platformId = platformId.ifBlank { null },
    )
}

/** 一首歌在网盘里的状态。 */
object TrackState {
    /** 还没导入 */
    const val NONE = "none"

    /** 已经导入网盘了 */
    const val IMPORTED = "imported"

    /** 试过了，没成功 */
    const val FAILED = "failed"
}

/**
 * 歌单的本地存储。
 *
 * 为什么要存：一张歌单可能几百首，用户不会一次全导。
 * 存下清单之后，App 里就有「我的歌单」，点开能看到整张单，
 * 想听哪首点哪首 —— 跟网易云音乐的用法一样。
 */
class PlaylistStore(private val db: LibraryDb) {

    companion object {
        fun keyOf(platform: PlaylistPlatform, playlistId: String): String =
            "${platform.id}|$playlistId"
    }

    /**
     * 存一张歌单。同一张单再存一次就是更新（曲目会按最新的清单重排，
     * 但已经导入过的歌会保留「已导入」标记）。
     *
     * @return 歌单的 key
     */
    fun save(playlist: ImportedPlaylist, sourceUrl: String = ""): String {
        val key = keyOf(playlist.platform, playlist.playlistId)
        val now = System.currentTimeMillis()

        // 老歌单里已经导入过的歌，重新拉的时候要把状态继承过来
        val oldStates = mutableMapOf<String, Pair<String, String>>()
        for (t in tracks(key)) {
            if (t.trackKey.isNotBlank()) oldStates[t.trackKey] = t.state to t.note
        }
        // 已经同步到云端的那张，重拉清单别把关联弄丢了
        val oldCloudId = playlist(key)?.cloudId ?: 0L

        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("key", key)
                put("platform", playlist.platform.id)
                put("playlistId", playlist.playlistId)
                put("name", playlist.name)
                put("creator", "")
                put("trackCount", playlist.tracks.size)
                put("sourceUrl", sourceUrl)
                put("createdAt", now)
                put("updatedAt", now)
                put("cloudId", oldCloudId)
            }
            writable.insertWithOnConflict(
                LibraryDb.TABLE_PLAYLISTS, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )

            // 清单变了就整体重写
            writable.delete(
                LibraryDb.TABLE_PLAYLIST_TRACKS,
                "playlistKey = ?",
                arrayOf(key)
            )
            for ((i, t) in playlist.tracks.withIndex()) {
                val tk = t.key
                val inherited = oldStates[tk]
                val cv2 = ContentValues().apply {
                    put("key", "$key|$i")
                    put("playlistKey", key)
                    put("trackKey", tk)
                    put("position", i)
                    put("platform", t.platform.id)
                    put("platformId", t.platformId.orEmpty())
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album)
                    put("durationSec", t.durationSec)
                    put("state", inherited?.first ?: TrackState.NONE)
                    put("note", inherited?.second.orEmpty())
                }
                writable.insertWithOnConflict(
                    LibraryDb.TABLE_PLAYLIST_TRACKS, null, cv2,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        return key
    }

    /** 所有歌单，按导入时间倒序（最近存的排前面）。 */
    fun playlists(): List<SavedPlaylist> {
        val out = mutableListOf<SavedPlaylist>()
        db.readableDatabase.rawQuery(
            "SELECT key, platform, playlistId, name, creator, trackCount, sourceUrl, " +
                "createdAt, updatedAt, cloudId FROM ${LibraryDb.TABLE_PLAYLISTS} ORDER BY updatedAt DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SavedPlaylist(
                        key = c.getString(0) ?: "",
                        platform = PlaylistPlatform.of(c.getString(1) ?: ""),
                        playlistId = c.getString(2) ?: "",
                        name = c.getString(3) ?: "",
                        creator = c.getString(4) ?: "",
                        trackCount = c.getInt(5),
                        sourceUrl = c.getString(6) ?: "",
                        createdAt = c.getLong(7),
                        updatedAt = c.getLong(8),
                        cloudId = c.getLong(9),
                    )
                )
            }
        }
        return out
    }

    fun playlist(key: String): SavedPlaylist? = playlists().firstOrNull { it.key == key }

    /** 记下这张本机歌单对应的云端歌单 id。 */
    fun setCloudId(key: String, cloudId: Long) {
        if (cloudId <= 0) return
        val cv = ContentValues().apply { put("cloudId", cloudId) }
        db.writableDatabase.update(LibraryDb.TABLE_PLAYLISTS, cv, "key = ?", arrayOf(key))
    }

    /** 找出对应某个云端歌单的本机歌单（没有就 null）。 */
    fun playlistByCloudId(cloudId: Long): SavedPlaylist? =
        if (cloudId <= 0) null else playlists().firstOrNull { it.cloudId == cloudId }

    /** 歌单里的歌，按原来的顺序。 */
    fun tracks(playlistKey: String): List<SavedTrack> {
        val out = mutableListOf<SavedTrack>()
        db.readableDatabase.rawQuery(
            "SELECT key, playlistKey, trackKey, position, platform, platformId, title, artist, " +
                "album, durationSec, state, note FROM ${LibraryDb.TABLE_PLAYLIST_TRACKS} " +
                "WHERE playlistKey = ? ORDER BY position",
            arrayOf(playlistKey)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SavedTrack(
                        key = c.getString(0) ?: "",
                        playlistKey = c.getString(1) ?: "",
                        trackKey = c.getString(2) ?: "",
                        position = c.getInt(3),
                        platform = PlaylistPlatform.of(c.getString(4) ?: ""),
                        platformId = c.getString(5) ?: "",
                        title = c.getString(6) ?: "",
                        artist = c.getString(7) ?: "",
                        album = c.getString(8) ?: "",
                        durationSec = c.getInt(9),
                        state = c.getString(10) ?: TrackState.NONE,
                        note = c.getString(11) ?: "",
                    )
                )
            }
        }
        return out
    }

    /** 更新一首歌的导入状态。 */
    fun updateTrackState(key: String, state: String, note: String = "") {
        val cv = ContentValues().apply {
            put("state", state)
            put("note", note)
        }
        db.writableDatabase.update(
            LibraryDb.TABLE_PLAYLIST_TRACKS, cv, "key = ?", arrayOf(key)
        )
    }

    /** 删掉一张歌单（网盘里的歌不动）。 */
    fun delete(key: String) {
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            writable.delete(LibraryDb.TABLE_PLAYLIST_TRACKS, "playlistKey = ?", arrayOf(key))
            writable.delete(LibraryDb.TABLE_PLAYLISTS, "key = ?", arrayOf(key))
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    /** 这张歌单里已经导入网盘的有几首。 */
    fun importedCount(playlistKey: String): Int {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${LibraryDb.TABLE_PLAYLIST_TRACKS} " +
                "WHERE playlistKey = ? AND state = ?",
            arrayOf(playlistKey, TrackState.IMPORTED)
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
