package com.cloudmusic.app.data.cloud

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.cloudmusic.app.data.LibraryDb
import org.json.JSONArray

/**
 * 云端内容的本地缓存。
 *
 * 云端不可达时（没网、令牌过期）界面也得能显示上次同步下来的东西，
 * 所以收藏和歌单都先落一份本地表，登录状态下再跟云端对齐。
 */
class CloudStore(private val db: LibraryDb) {

    // ---------------- 收藏 ----------------

    /** 云端收藏（本地缓存），按更新时间倒序。 */
    fun favorites(): List<CloudTrack> {
        val out = mutableListOf<CloudTrack>()
        db.readableDatabase.rawQuery(
            "SELECT key, title, artist, album, durationSec, platform, platformId, coverUrl, " +
                "quarkId FROM ${LibraryDb.TABLE_CLOUD_FAVORITES} ORDER BY updatedAt DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CloudTrack(
                        key = c.getString(0) ?: "",
                        title = c.getString(1) ?: "",
                        artist = c.getString(2) ?: "",
                        album = c.getString(3) ?: "",
                        durationSec = c.getInt(4),
                        platform = c.getString(5) ?: "",
                        platformId = c.getString(6) ?: "",
                        coverUrl = c.getString(7) ?: "",
                        quarkId = c.getString(8) ?: "",
                    )
                )
            }
        }
        return out
    }

    fun favoriteKeys(): Set<String> = favorites().map { it.key }.toSet()

    fun hasFavorite(key: String): Boolean {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM ${LibraryDb.TABLE_CLOUD_FAVORITES} WHERE key = ?",
            arrayOf(key)
        ).use { c -> return c.moveToFirst() }
    }

    fun upsertFavorite(t: CloudTrack, updatedAt: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply {
            put("key", t.key)
            put("title", t.title)
            put("artist", t.artist)
            put("album", t.album)
            put("durationSec", t.durationSec)
            put("platform", t.platform)
            put("platformId", t.platformId)
            put("coverUrl", t.coverUrl)
            put("quarkId", t.quarkId)
            put("updatedAt", updatedAt)
        }
        db.writableDatabase.insertWithOnConflict(
            LibraryDb.TABLE_CLOUD_FAVORITES, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeFavorite(key: String) {
        db.writableDatabase.delete(
            LibraryDb.TABLE_CLOUD_FAVORITES, "key = ?", arrayOf(key)
        )
    }

    /** 用云端下发的收藏整体覆盖本地缓存（墓碑里的删掉）。 */
    fun replaceFavorites(items: List<CloudTrack>) {
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            writable.delete(LibraryDb.TABLE_CLOUD_FAVORITES, null, null)
            for (t in items) upsertFavorite(t)
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    // ---------------- 歌单 ----------------

    fun playlists(): List<CloudPlaylistRef> {
        val out = mutableListOf<CloudPlaylistRef>()
        db.readableDatabase.rawQuery(
            "SELECT id, name, platform, updatedAt, trackCount, cover FROM " +
                "${LibraryDb.TABLE_CLOUD_PLAYLISTS} ORDER BY updatedAt DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CloudPlaylistRef(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        platform = c.getString(2) ?: "",
                        updatedAt = c.getLong(3),
                        trackCount = c.getInt(4),
                        cover = c.getString(5) ?: "",
                    )
                )
            }
        }
        return out
    }

    /** 歌单详情（含曲目）。本地没有就返回 null。 */
    fun playlistDetail(id: Long): CloudPlaylistDetail? {
        db.readableDatabase.rawQuery(
            "SELECT id, name, platform, updatedAt, tracksJson FROM " +
                "${LibraryDb.TABLE_CLOUD_PLAYLISTS} WHERE id = ?",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return CloudPlaylistDetail(
                id = c.getLong(0),
                name = c.getString(1) ?: "",
                platform = c.getString(2) ?: "",
                updatedAt = c.getLong(3),
                tracks = parseTracks(c.getString(4).orEmpty()),
            )
        }
    }

    fun savePlaylist(detail: CloudPlaylistDetail, cover: String = "") {
        val cv = ContentValues().apply {
            put("id", detail.id)
            put("name", detail.name)
            put("platform", detail.platform)
            put("updatedAt", detail.updatedAt)
            put("trackCount", detail.tracks.size)
            // 封面：外部算好的优先，否则用曲目里自带的（第一张有封面的）
            put("cover", cover.ifBlank { coverOf(detail.tracks) })
            put("tracksJson", tracksJson(detail.tracks))
        }
        db.writableDatabase.insertWithOnConflict(
            LibraryDb.TABLE_CLOUD_PLAYLISTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** 只更新列表页要用的概要信息（不清曲目）。 */
    fun savePlaylistRef(ref: CloudPlaylistRef) {
        // 概要里没有曲目，别把已经拉下来的明细冲掉
        val keep = db.readableDatabase.rawQuery(
            "SELECT tracksJson FROM ${LibraryDb.TABLE_CLOUD_PLAYLISTS} WHERE id = ?",
            arrayOf(ref.id.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "[]" }
        val cv = ContentValues().apply {
            put("id", ref.id)
            put("name", ref.name)
            put("platform", ref.platform)
            put("updatedAt", ref.updatedAt)
            put("trackCount", ref.trackCount)
            // 概要没带封面就别把已经算出来的封面冲掉
            if (ref.cover.isNotBlank()) put("cover", ref.cover)
            put("tracksJson", keep)
        }
        db.writableDatabase.insertWithOnConflict(
            LibraryDb.TABLE_CLOUD_PLAYLISTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deletePlaylist(id: Long) {
        db.writableDatabase.delete(
            LibraryDb.TABLE_CLOUD_PLAYLISTS, "id = ?", arrayOf(id.toString())
        )
    }

    fun clearAll() {
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            writable.delete(LibraryDb.TABLE_CLOUD_FAVORITES, null, null)
            writable.delete(LibraryDb.TABLE_CLOUD_PLAYLISTS, null, null)
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    private fun tracksJson(tracks: List<CloudTrack>): String {
        val arr = JSONArray()
        for (t in tracks) arr.put(t.toJson())
        return arr.toString()
    }

    private fun parseTracks(raw: String): List<CloudTrack> {
        if (raw.isBlank()) return emptyList()
        return try {
            CloudTrack.listFromJson(JSONArray(raw))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 歌单封面取第一张有封面的歌，跟网页版一个口径。 */
    private fun coverOf(tracks: List<CloudTrack>): String =
        tracks.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
}
