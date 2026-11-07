package com.cloudmusic.app.data

import java.io.File

/**
 * 音频文件头的魔数判断。
 *
 * 这个判断有两处用：探测直链（能不能下）、校验下载结果（下的是不是音频）。
 * **两边必须用同一套规则** —— 不然就会出现「探测说能下、下载完却说不是音频」的鬼打墙。
 *
 * （之前就是踩了这个：探测层认 FLAC/WAV，下载层只认 MP3/Ogg，
 *  于是夸克下回来的 FLAC 被自己人拦下，提示「拿到的不是音频文件」。）
 */
object AudioMagic {

    /** 读多少字节来判断。MP4 容器要到第 8 字节才看得出 ftyp，16 字节够用。 */
    private const val HEAD_LEN = 16

    /** 字节数组转十六进制。Byte 是有符号的，必须屏蔽高位，否则 0xFF 会变成 "ffffffff"。 */
    fun hexOf(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 读文件头的前若干字节。 */
    fun readHead(file: File): ByteArray = try {
        file.inputStream().use { input ->
            val buf = ByteArray(HEAD_LEN)
            var n = 0
            while (n < HEAD_LEN) {
                val r = input.read(buf, n, HEAD_LEN - n)
                if (r <= 0) break
                n += r
            }
            buf.copyOf(n)
        }
    } catch (e: Exception) {
        ByteArray(0)
    }

    /** 判断这串文件头是不是音频。 */
    fun looksLikeAudio(head: ByteArray): Boolean = looksLikeAudio(hexOf(head))

    /**
     * 判断文件头是不是音频。
     *
     * @param hex 文件头的十六进制（至少 3 字节）
     */
    fun looksLikeAudio(hex: String): Boolean {
        if (hex.length < 6) return false
        return when {
            hex.startsWith("494433") -> true       // MP3（带 ID3 标签）
            hex.startsWith("fff") -> true          // MP3（裸帧 0xFF 0xEx）
            hex.startsWith("ff") -> true           // MP3（裸帧 0xFF 0xFx）
            hex.startsWith("4f676753") -> true     // Ogg（Vorbis / Opus）
            hex.startsWith("52494646") -> true     // WAV（RIFF....WAVE）
            hex.startsWith("664c6143") -> true     // FLAC（fLaC）
            hex.startsWith("46464952") -> true     // AIFF（FORM）
            hex.startsWith("4d41494c") -> true     // Monkey's Audio
            hex.startsWith("377abcaf") -> true     // WavPack
            hex.contains("66747970") -> true       // MP4 容器（M4A / AAC）
            else -> false
        }
    }

    /** 判断文件是不是音频。 */
    fun looksLikeAudio(file: File): Boolean = looksLikeAudio(readHead(file))
}
