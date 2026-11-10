package com.cloudmusic.app.data

import android.util.Log

/** 音频文件内嵌的曲目信息。 */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/**
 * 从音频文件头部读内嵌的歌名/歌手/专辑。
 *
 * 支持：
 * - FLAC：metadata block type 4 `VORBIS_COMMENT`（TITLE / ARTIST / ALBUM）
 * - MP3：ID3v2.2 的 TT2/TP1/TAL，v2.3、v2.4 的 TIT2/TPE1/TALB
 * - M4A：moov/udta/meta/ilst 下的 ©nam / ©ART / ©alb
 *
 * 只读文件头 256KB 足够（标签和封面都在前面），配合 HTTP Range 不用下整首歌。
 * 内嵌标签比文件名靠谱：「乱舞春秋 - 周杰伦.flac」这种命名会被文件名解析搞反，
 * 标签里却是正的，所以扫描时拿它来纠正。
 */
object AudioTagsReader {

    fun read(bytes: ByteArray): AudioTags? {
        val magic = bytes.take(12).joinToString(" ") { "%02x".format(it) }
        Log.d("CMDIAG", "AudioTags.read bytes=${bytes.size} magic=$magic")
        val flac = readFlac(bytes)
        if (flac != null) {
            Log.d("CMDIAG", "AudioTags FLAC $flac")
            return flac
        }
        val id3 = readId3(bytes)
        if (id3 != null) {
            Log.d("CMDIAG", "AudioTags ID3 $id3")
            return id3
        }
        val mp4 = readMp4(bytes)
        if (mp4 != null) {
            Log.d("CMDIAG", "AudioTags MP4 $mp4")
            return mp4
        }
        Log.d("CMDIAG", "AudioTags NO TAGS FOUND")
        return null
    }

    /**
     * 用标签纠正曲目的歌名/歌手；不可信或没变化时返回 null。
     *
     * 标签里至少有一边要跟现有信息对得上才采用 —— 有些整轨/合辑文件会带一张
     * 完全不相干的标签，盲信反而会把曲库搞乱。
     */
    fun correct(track: Track, tags: AudioTags): Track? {
        val t = tags.title?.clean().orEmpty()
        val a = tags.artist?.clean().orEmpty()
        if (t.isEmpty() || a.isEmpty()) return null

        val curT = norm(track.title)
        val curA = norm(track.artist)
        val nt = norm(t)
        val na = norm(a)
        val plausible = listOf(nt, na).any { v ->
            v.length >= 2 && (
                v == curT || v == curA ||
                    (curT.length >= 2 && (v.contains(curT) || curT.contains(v))) ||
                    (curA.length >= 2 && (v.contains(curA) || curA.contains(v)))
                )
        }
        Log.d("CMDIAG", "AudioTags.correct plausible=$plausible cur='${track.title}'/'${track.artist}' tag='$t'/'$a'")
        if (!plausible) return null
        if (t == track.title && a == track.artist) return null

        return track.copy(
            title = t,
            artist = a,
            album = tags.album?.clean()?.takeIf { it.isNotEmpty() } ?: track.album
        )
    }

    /**
     * 读不到内嵌标签时的兜底：用整张曲库里「谁当过歌手」的频次判断。
     *
     * 例如「搁浅-周杰伦.flac」解析出 title=周杰伦 / artist=搁浅，
     * 而曲库里「周杰伦」在别的歌（周杰伦 - 晴天.mp3）里当过很多次 artist，
     * 「搁浅」从没当过 → 判定这两项反了，直接交换。
     */
    fun swapIfReversed(track: Track, artistFreq: Map<String, Int>): Track? {
        val t = track.title.trim()
        val a = track.artist.trim()
        if (t.isEmpty() || a.isEmpty()) return null
        val tAsArtist = artistFreq[t] ?: 0
        val aAsArtist = artistFreq[a] ?: 0
        return if (tAsArtist >= 2 && tAsArtist > aAsArtist) {
            track.copy(title = a, artist = t)
        } else null
    }

    // ---------- FLAC ----------

    private fun readFlac(bytes: ByteArray): AudioTags? {
        if (bytes.size < 8 || !bytes.match(0, "fLaC")) return null
        var p = 4
        while (p + 4 <= bytes.size) {
            val first = bytes[p].toInt() and 0xFF
            val type = first and 0x7F
            val last = (first and 0x80) != 0
            val len = be24(bytes, p + 1)
            val start = p + 4
            val end = minOf(bytes.size, start + len)
            if (type == 4 && end <= bytes.size && end - start >= 8) {
                var q = start
                val vendorLen = le32(bytes, q)
                if (vendorLen < 0 || vendorLen > end - q - 4) return null
                q += 4 + vendorLen
                if (q + 4 > end) return null
                val count = le32(bytes, q)
                q += 4
                if (count < 0 || count > 256) return null
                val map = mutableMapOf<String, String>()
                for (i in 0 until count) {
                    if (q + 4 > end) break
                    val l = le32(bytes, q)
                    q += 4
                    if (l < 0 || q + l > end) break
                    val entry = String(bytes, q, l, Charsets.UTF_8)
                    val eq = entry.indexOf('=')
                    if (eq > 0) {
                        map[entry.substring(0, eq).uppercase()] = entry.substring(eq + 1).clean()
                    }
                    q += l
                }
                return tagsFrom(map)
            }
            if (last || len <= 0) break
            p = end
        }
        return null
    }

    // ---------- ID3v2 ----------

    private fun readId3(bytes: ByteArray): AudioTags? {
        if (bytes.size < 10 || !bytes.match(0, "ID3")) return null
        val major = bytes[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val tagSize = synchsafe(bytes, 6)
        if (tagSize <= 0) return null
        val tagEnd = minOf(bytes.size, 10 + tagSize)

        var p = 10
        if (major >= 3 && (bytes[5].toInt() and 0x40) != 0 && p + 4 <= tagEnd) {
            val ext = if (major == 4) synchsafe(bytes, p) else be32(bytes, p)
            p += if (major == 4) ext else 4 + ext
        }

        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        val map = mutableMapOf<String, String>()
        while (p + headerLen <= tagEnd) {
            val id = String(bytes, p, idLen, Charsets.ISO_8859_1)
            val frameSize = when (major) {
                2 -> be24(bytes, p + idLen)
                4 -> synchsafe(bytes, p + idLen)
                else -> be32(bytes, p + idLen)
            }
            if (frameSize <= 0) break
            val dataStart = p + headerLen
            val dataEnd = minOf(tagEnd, dataStart + frameSize)
            val key = when (id) {
                "TIT2", "TT2" -> "TITLE"
                "TPE1", "TP1" -> "ARTIST"
                "TALB", "TAL" -> "ALBUM"
                else -> null
            }
            if (key != null && dataStart < dataEnd) {
                val text = decodeId3Text(bytes.copyOfRange(dataStart, dataEnd))
                if (text.isNotEmpty()) map[key] = text
            }
            p = dataEnd
        }
        return tagsFrom(map)
    }

    /** ID3 文本帧：首字节是编码方式，1 = 带 BOM 的 UTF-16，3 = UTF-8。 */
    private fun decodeId3Text(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val enc = data[0].toInt() and 0xFF
        val payload = data.copyOfRange(1, data.size)
        if (payload.isEmpty()) return ""
        val charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> if (payload.size >= 2 && payload[0] == 0xFF.toByte() && payload[1] == 0xFE.toByte()) {
                Charsets.UTF_16LE
            } else Charsets.UTF_16BE
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return try {
            String(payload, charset).clean()
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- M4A / MP4 ----------

    private fun readMp4(bytes: ByteArray): AudioTags? {
        val ilst = bytes.indexOf("ilst") ?: return null
        val map = mutableMapOf<String, String>()
        val end = minOf(bytes.size, ilst + 4 + 128 * 1024)
        var p = ilst + 4
        while (p + 8 <= end) {
            val size = be32(bytes, p)
            if (size < 8 || p + size > bytes.size) break
            val type = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
            val key = when (type) {
                "©nam" -> "TITLE"
                "©ART" -> "ARTIST"
                "©alb" -> "ALBUM"
                else -> null
            }
            if (key != null) {
                var q = p + 8
                val atomEnd = p + size
                while (q + 8 <= atomEnd) {
                    val dSize = be32(bytes, q)
                    val dType = String(bytes, q + 4, 4, Charsets.ISO_8859_1)
                    if (dSize >= 16 && dType == "data" && q + dSize <= bytes.size) {
                        val text = String(
                            bytes.copyOfRange(q + 16, q + dSize), Charsets.UTF_8
                        ).clean()
                        if (text.isNotEmpty()) map[key] = text
                        break
                    }
                    if (dSize < 8) break
                    q += dSize
                }
            }
            p += size
        }
        return tagsFrom(map)
    }

    // ---------- 小工具 ----------

    private fun tagsFrom(map: Map<String, String>): AudioTags? {
        val tags = AudioTags(
            title = map["TITLE"]?.takeIf { it.isNotEmpty() },
            artist = map["ARTIST"]?.takeIf { it.isNotEmpty() },
            album = map["ALBUM"]?.takeIf { it.isNotEmpty() },
        )
        // 至少要能拿到歌名或歌手，否则没有纠正价值
        return if (tags.title == null && tags.artist == null) null else tags
    }

    private fun String.clean(): String = trim('\u0000', '\uFEFF', ' ', '\t', '\r', '\n')

    private fun norm(s: String): String = buildString {
        for (c in s.lowercase()) if (c.isLetterOrDigit()) append(c)
    }

    private fun ByteArray.match(at: Int, s: String): Boolean {
        if (at + s.length > size) return false
        for (i in s.indices) if (this[at + i] != s[i].code.toByte()) return false
        return true
    }

    private fun ByteArray.indexOf(s: String): Int? {
        val needle = s.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }

    private fun synchsafe(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return 0
        return ((b[at].toInt() and 0x7F) shl 21) or
            ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or
            (b[at + 3].toInt() and 0x7F)
    }

    private fun be24(b: ByteArray, at: Int): Int {
        if (at + 3 > b.size) return 0
        return ((b[at].toInt() and 0xFF) shl 16) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            (b[at + 2].toInt() and 0xFF)
    }

    private fun be32(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return 0
        return ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)
    }

    /** Vorbis Comment 里的长度是小端 */
    private fun le32(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return 0
        return (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}
