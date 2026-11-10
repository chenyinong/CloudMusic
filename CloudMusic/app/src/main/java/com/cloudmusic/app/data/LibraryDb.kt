package com.cloudmusic.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 缓存下来的一条歌词。 */
data class LyricsRow(val lrc: String, val source: String)

/** 网盘里的一个歌词文件，key = 目录 + 文件名（不含扩展名）。 */
data class LyricFileRef(val folder: String, val base: String, val href: String)

/** 网盘里的一个封面图片文件，索引口径与歌词文件一致。 */
data class CoverFileRef(val folder: String, val base: String, val href: String)

/**
 * 本地曲库索引。使用原生 SQLite，避免引入注解处理器。
 */
class LibraryDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "library.db", null, VERSION) {

    companion object {
        const val TABLE = "tracks"
        const val TABLE_LYRICS = "lyrics"
        const val TABLE_LYRIC_FILES = "lyric_files"

        /** 专辑封面直链缓存（key = 曲目 key，url = 在线图 URL 或 local: 本地文件） */
        const val TABLE_COVERS = "covers"

        /** 网盘里的封面图片文件索引（key = 目录 + 文件名） */
        const val TABLE_COVER_FILES = "cover_files"

        /** 存下来的歌单（只存清单，歌曲在网盘里） */
        const val TABLE_PLAYLISTS = "playlists"

        /** 歌单里的每一首歌 */
        const val TABLE_PLAYLIST_TRACKS = "playlist_tracks"

        const val VERSION = 8

        fun lyricKey(providerId: String, folder: String, base: String): String =
            "$providerId|${folder.lowercase()}|${base.lowercase()}"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                key TEXT PRIMARY KEY,
                id TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                fileName TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                artist TEXT NOT NULL DEFAULT '',
                album TEXT NOT NULL DEFAULT '',
                folder TEXT NOT NULL DEFAULT '',
                size INTEGER NOT NULL DEFAULT 0,
                mime TEXT NOT NULL DEFAULT '',
                duration REAL NOT NULL DEFAULT 0,
                provider TEXT NOT NULL DEFAULT 'webdav'
            )
            """.trimIndent()
        )
        createLyricsTables(db)
        createPlaylistTables(db)
        createCoverTable(db)
        createCoverFilesTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 升级不丢曲库索引，只补新表和新列
        if (oldVersion < 2) createLyricsTables(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN provider TEXT NOT NULL DEFAULT 'webdav'")
        }
        if (oldVersion < 4) {
            // 歌词文件索引加了 provider 列，这张表本来就是缓存，直接重建
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LYRIC_FILES")
            createLyricsTables(db)
        }
        if (oldVersion < 5) {
            // 主键改成 key（网盘类型 + id），两个网盘可以存同名文件
            db.execSQL(
                """
                CREATE TABLE ${TABLE}_new (
                    key TEXT PRIMARY KEY,
                    id TEXT NOT NULL DEFAULT '',
                    url TEXT NOT NULL DEFAULT '',
                    fileName TEXT NOT NULL DEFAULT '',
                    title TEXT NOT NULL DEFAULT '',
                    artist TEXT NOT NULL DEFAULT '',
                    album TEXT NOT NULL DEFAULT '',
                    folder TEXT NOT NULL DEFAULT '',
                    size INTEGER NOT NULL DEFAULT 0,
                    mime TEXT NOT NULL DEFAULT '',
                    duration REAL NOT NULL DEFAULT 0,
                    provider TEXT NOT NULL DEFAULT 'webdav'
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO ${TABLE}_new
                    (key, id, url, title, artist, album, folder, size, mime, duration, provider)
                SELECT provider || '|' || id, id, url, title, artist, album, folder,
                       size, mime, duration, provider
                FROM $TABLE
                """.trimIndent()
            )
            db.execSQL("DROP TABLE $TABLE")
            db.execSQL("ALTER TABLE ${TABLE}_new RENAME TO $TABLE")
        }
        if (oldVersion < 6) createPlaylistTables(db)
        if (oldVersion < 7) createCoverTable(db)
        if (oldVersion < 8) createCoverFilesTable(db)
    }

    /**
     * 歌单和它里面的曲目。
     *
     * 歌单只存「清单」，歌曲本体还是在网盘里 —— 用户导入一张几百首的歌单时，
     * 先把清单存下来，之后想听哪首再单独导那首。
     */
    private fun createPlaylistTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLISTS (
                key TEXT PRIMARY KEY,
                platform TEXT NOT NULL DEFAULT 'netease',
                playlistId TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL DEFAULT '',
                creator TEXT NOT NULL DEFAULT '',
                trackCount INTEGER NOT NULL DEFAULT 0,
                sourceUrl TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLIST_TRACKS (
                key TEXT PRIMARY KEY,
                playlistKey TEXT NOT NULL,
                trackKey TEXT NOT NULL DEFAULT '',
                position INTEGER NOT NULL DEFAULT 0,
                platform TEXT NOT NULL DEFAULT 'netease',
                platformId TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                artist TEXT NOT NULL DEFAULT '',
                album TEXT NOT NULL DEFAULT '',
                durationSec INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'none',
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pl_tracks ON $TABLE_PLAYLIST_TRACKS (playlistKey)")
    }

    private fun createLyricsTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LYRICS (
                trackId TEXT PRIMARY KEY,
                lrc TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT '',
                updatedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LYRIC_FILES (
                key TEXT PRIMARY KEY,
                provider TEXT NOT NULL DEFAULT 'webdav',
                folder TEXT NOT NULL DEFAULT '',
                base TEXT NOT NULL DEFAULT '',
                href TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    fun replaceAll(tracks: List<Track>, providerId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 只替换同一种网盘的索引，另一个网盘的曲库留着，切换时不用重扫
            db.delete(TABLE, "provider = ?", arrayOf(providerId))
            for (t in tracks) {
                val cv = ContentValues().apply {
                    put("key", t.key)
                    put("id", t.id)
                    put("url", t.url)
                    put("fileName", t.fileName)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album)
                    put("folder", t.folder)
                    put("size", t.size)
                    put("mime", t.mime)
                    put("duration", t.durationSec)
                    put("provider", t.provider)
                }
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 写入 / 覆盖一条曲目索引。
     *
     * 刚导入的歌不用等整盘扫描就能进曲库 ——
     * 上传接口已经返回了远端路径，播放地址当场就能拼出来。
     */
    fun upsert(track: Track) {
        val cv = ContentValues().apply {
            put("key", track.key)
            put("id", track.id)
            put("url", track.url)
            put("fileName", track.fileName)
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("folder", track.folder)
            put("size", track.size)
            put("mime", track.mime)
            put("duration", track.durationSec)
            put("provider", track.provider)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 全部网盘的曲目 */
    fun all(): List<Track> {
        val out = mutableListOf<Track>()
        readableDatabase.rawQuery(
            "SELECT key, id, url, fileName, title, artist, album, folder, size, mime, " +
                "duration, provider FROM $TABLE ORDER BY provider, album, title",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(c.toTrack())
        }
        return out
    }

    fun updateDuration(key: String, durationSec: Double) {
        val cv = ContentValues().apply { put("duration", durationSec) }
        writableDatabase.update(TABLE, cv, "key = ?", arrayOf(key))
    }

    /** 断开某个网盘时，把它的索引清掉 */
    fun deleteProvider(providerId: String) {
        writableDatabase.delete(TABLE, "provider = ?", arrayOf(providerId))
        writableDatabase.delete(TABLE_LYRIC_FILES, "provider = ?", arrayOf(providerId))
        writableDatabase.delete(TABLE_COVER_FILES, "provider = ?", arrayOf(providerId))
    }

    /**
     * 删掉一条索引。
     *
     * 网盘上的文件被删了，索引里也不能留着 —— 否则曲库里还点得到一首播不了的歌。
     */
    fun deleteTrack(key: String) {
        writableDatabase.delete(TABLE, "key = ?", arrayOf(key))
    }

    // ---------- 封面 ----------

    private fun createCoverFilesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_COVER_FILES (
                key TEXT PRIMARY KEY, provider TEXT NOT NULL DEFAULT 'webdav',
                folder TEXT NOT NULL DEFAULT '', base TEXT NOT NULL DEFAULT '',
                href TEXT NOT NULL DEFAULT '')
            """.trimIndent()
        )
    }

    /** 扫描后重建封面图片索引，播放时直接查表，不用再遍历网盘。 */
    fun replaceCoverFiles(files: List<CoverFileRef>, providerId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_COVER_FILES, "provider = ?", arrayOf(providerId))
            for (f in files) {
                val cv = ContentValues().apply {
                    put("key", lyricKey(providerId, f.folder, f.base))
                    put("provider", providerId)
                    put("folder", f.folder)
                    put("base", f.base)
                    put("href", f.href)
                }
                db.insertWithOnConflict(
                    TABLE_COVER_FILES, null, cv, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 找同目录下指定名字（不含扩展名）的封面图，返回网盘 href。 */
    fun coverFile(providerId: String, folder: String, base: String): String? {
        readableDatabase.rawQuery(
            "SELECT href FROM $TABLE_COVER_FILES WHERE key = ?",
            arrayOf(lyricKey(providerId, folder, base))
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0)
        }
    }

    /** 同目录下所有封面图的 href（找不到同名/通用名时的兜底）。 */
    fun coverFilesInFolder(providerId: String, folder: String): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT href FROM $TABLE_COVER_FILES WHERE provider = ? AND folder = ?",
            arrayOf(providerId, folder)
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun clearCover(trackId: String) {
        writableDatabase.delete(TABLE_COVERS, "trackId = ?", arrayOf(trackId))
    }

    private fun createCoverTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_COVERS (
                trackId TEXT PRIMARY KEY,
                url TEXT NOT NULL DEFAULT '',
                updatedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    fun saveCover(trackId: String, url: String) {
        val cv = ContentValues().apply {
            put("trackId", trackId)
            put("url", url)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE_COVERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun coverUrl(trackId: String): String? {
        readableDatabase.rawQuery(
            "SELECT url FROM $TABLE_COVERS WHERE trackId = ?",
            arrayOf(trackId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0)?.takeIf { it.isNotBlank() }
        }
    }

    // ---------- 歌词 ----------

    fun saveLyrics(trackId: String, lrc: String, source: String) {
        val cv = ContentValues().apply {
            put("trackId", trackId)
            put("lrc", lrc)
            put("source", source)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE_LYRICS, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun lyrics(trackId: String): LyricsRow? {
        readableDatabase.rawQuery(
            "SELECT lrc, source FROM $TABLE_LYRICS WHERE trackId = ?",
            arrayOf(trackId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return LyricsRow(c.getString(0) ?: "", c.getString(1) ?: "")
        }
    }

    /** 扫描后重建"歌词文件索引"，播放时直接查表，不用再发网络请求。 */
    fun replaceLyricFiles(files: List<LyricFileRef>, providerId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_LYRIC_FILES, "provider = ?", arrayOf(providerId))
            for (f in files) {
                val cv = ContentValues().apply {
                    put("key", lyricKey(providerId, f.folder, f.base))
                    put("provider", providerId)
                    put("folder", f.folder)
                    put("base", f.base)
                    put("href", f.href)
                }
                db.insertWithOnConflict(
                    TABLE_LYRIC_FILES, null, cv, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 找同目录下与歌曲同名的歌词文件 */
    fun lyricFile(providerId: String, folder: String, base: String): String? {
        readableDatabase.rawQuery(
            "SELECT href FROM $TABLE_LYRIC_FILES WHERE key = ?",
            arrayOf(lyricKey(providerId, folder, base))
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0)
        }
    }

    private fun Cursor.toTrack(): Track = Track(
        key = getString(0) ?: "",
        id = getString(1) ?: "",
        url = getString(2) ?: "",
        fileName = getString(3) ?: "",
        title = getString(4) ?: "",
        artist = getString(5) ?: "",
        album = getString(6) ?: "",
        folder = getString(7) ?: "",
        size = getLong(8),
        mime = getString(9) ?: "",
        durationSec = getDouble(10),
        provider = getString(11) ?: "webdav"
    )
}
