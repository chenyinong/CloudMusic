package com.cloudmusic.app.data

import java.util.Locale

data class DavEntry(
    /** WebDAV 路径，或 OneDrive 的 item id */
    val href: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val contentType: String,
    /** 所在目录路径，OneDrive 用（形如 /drive/root:/Music/Album） */
    val parentPath: String = "",
)

object AudioTypes {

    val EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus",
        "wav", "wma", "ape", "aiff", "aif", "alac", "dsf", "dff"
    )

    /** 歌词文件候选扩展名 */
    val LYRIC_EXTENSIONS = setOf("lrc", "txt")

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.US)
    }

    fun isAudio(entry: DavEntry): Boolean {
        if (entry.isDirectory) return false
        if (entry.contentType.startsWith("audio/", ignoreCase = true)) return true
        return extensionOf(entry.name) in EXTENSIONS
    }

    /** 是否可能是歌词文件（扫描时收集起来，播放时按同名匹配） */
    fun isLyricFile(entry: DavEntry): Boolean {
        if (entry.isDirectory) return false
        return extensionOf(entry.name) in LYRIC_EXTENSIONS
    }

    /**
     * 由扩展名推断 MIME。
     * 坚果云对 .flac 等冷门格式常返回 application/octet-stream，
     * 直接拿它喂播放器会让 ExoPlayer 认不出格式，这里补一张对照表。
     */
    fun mimeOf(name: String): String {
        val ext = extensionOf(name)
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "m4b" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/x-ape"
            "aiff", "aif" -> "audio/x-aiff"
            "alac" -> "audio/alac"
            "dsf" -> "audio/x-dsf"
            "dff" -> "audio/x-dff"
            else -> ""
        }
        return mime
    }

    /** 曲名前缀的音轨号，如 "01 - " / "03." / "12 " */
    private val TRACK_PREFIX = Regex("""^\s*\d{1,3}\s*(?:[-._、]\s*|\s+)""")

    fun parseTitle(name: String): Pair<String, String> {
        var base = name.substringBeforeLast('.', name).trim()
        // 先去掉音轨号，否则 "01 - 稻香" 会被当成"歌手=01"
        base = TRACK_PREFIX.replace(base, "")
        val idx = base.indexOf('-')
        if (idx > 0 && idx < base.length - 1) {
            val artist = base.substring(0, idx).trim()
            val title = base.substring(idx + 1).trim()
            // 前面是纯数字的话那不是歌手，整段当歌名
            if (artist.isNotEmpty() && title.isNotEmpty() && !artist.all { it.isDigit() }) {
                return artist to title
            }
        }
        return "" to base.trim()
    }
}
