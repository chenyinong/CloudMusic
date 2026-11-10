package com.cloudmusic.app.data

/**
 * 从音频文件头部抠出内嵌专辑封面。
 *
 * 只认最常见的两种容器：
 * - MP3：ID3v2.2 的 `PIC` / ID3v2.3、v2.4 的 `APIC` 帧
 * - FLAC：metadata block type 6 `PICTURE`
 *
 * 不引第三方库，纯字节解析。调用方只需要给前 1~2MB（HTTP Range），
 * 不用下载整首歌。M4A 的 `covr` atom 结构复杂，暂不处理，
 * 这类歌会自然回落到在线检索 / 占位图。
 */
object EmbeddedCover {

    /** 返回图片原始字节（jpeg/png/...），没有或格式不认识就返回 null。 */
    fun extract(bytes: ByteArray): ByteArray? =
        parseFlac(bytes) ?: parseId3v2(bytes)

    // ---------- FLAC ----------

    private fun parseFlac(bytes: ByteArray): ByteArray? {
        if (bytes.size < 8 || !bytes.match(0, "fLaC")) return null
        var p = 4
        while (p + 4 <= bytes.size) {
            val first = bytes[p].toInt() and 0xFF
            val type = first and 0x7F
            val last = (first and 0x80) != 0
            val len = be24(bytes, p + 1)
            val start = p + 4
            val end = minOf(bytes.size, start + len)
            if (type == 6 && end <= bytes.size && end - start > 36) {
                // PICTURE block：type(4) + mime(4+len) + desc(4+len) + 尺寸(16) + dataLen(4) + data
                var q = start + 4                       // 跳过 picture type
                val mimeLen = be32(bytes, q)
                if (mimeLen < 0 || mimeLen > end - q - 4) return null
                q += 4 + mimeLen
                if (q + 20 > end) return null
                val descLen = be32(bytes, q)
                if (descLen < 0 || descLen > end - q - 20) return null
                q += 4 + descLen + 16                   // desc + width/height/depth/colors
                if (q + 4 > end) return null
                val dataLen = be32(bytes, q)
                q += 4
                if (dataLen in 1..(end - q)) return bytes.copyOfRange(q, q + dataLen)
                return null
            }
            if (last || len <= 0) break
            p = end
        }
        return null
    }

    // ---------- ID3v2 ----------

    private fun parseId3v2(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10 || !bytes.match(0, "ID3")) return null
        val major = bytes[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val tagSize = synchsafe(bytes, 6)
        if (tagSize <= 0) return null
        val tagEnd = minOf(bytes.size, 10 + tagSize)

        var p = 10
        // 扩展头：v2.3 的长度不含自身 4 字节，v2.4 的长度含自身
        if (major >= 3 && (bytes[5].toInt() and 0x40) != 0 && p + 4 <= tagEnd) {
            val ext = if (major == 4) synchsafe(bytes, p) else be32(bytes, p)
            p += if (major == 4) ext else 4 + ext
        }

        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        val wanted = if (major == 2) "PIC" else "APIC"

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
            if (id == wanted && dataStart < dataEnd) {
                // frame 里 MIME / 描述长度受编码影响，不逐字段解析，
                // 直接从图片魔数切到帧尾（图片数据本来就到帧尾）
                findPicture(bytes, dataStart, dataEnd)?.let { return it }
            }
            p = dataEnd
        }
        return null
    }

    /** 在帧数据里找 JPEG/PNG/GIF/BMP 魔数，从魔数处截到帧尾。 */
    private fun findPicture(bytes: ByteArray, from: Int, to: Int): ByteArray? {
        for (i in from until to - 3) {
            val b = bytes[i].toInt() and 0xFF
            val isJpeg = b == 0xFF && (bytes[i + 1].toInt() and 0xFF) == 0xD8 &&
                (bytes[i + 2].toInt() and 0xFF) == 0xFF
            val isPng = b == 0x89 && bytes[i + 1] == 0x50.toByte() &&
                bytes[i + 2] == 0x4E.toByte() && bytes[i + 3] == 0x47.toByte()
            val isBmp = b == 'B'.code && bytes[i + 1] == 'M'.code.toByte()
            val isGif = bytes.match(i, "GIF8")
            if (isJpeg || isPng || isBmp || isGif) return bytes.copyOfRange(i, to)
        }
        return null
    }

    // ---------- 小工具 ----------

    private fun ByteArray.match(at: Int, s: String): Boolean {
        if (at + s.length > size) return false
        for (i in s.indices) if (this[at + i] != s[i].code.toByte()) return false
        return true
    }

    /** ID3 synchsafe integer：每个字节只用低 7 位 */
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
}
