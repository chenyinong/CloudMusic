package com.cloudmusic.app.data

import java.util.Locale

/** 一行歌词。timeMs 为该行的时间点（毫秒）。 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 解析后的歌词。
 *
 * @param offsetMs LRC 里 [offset] 标签的值，正数表示歌词整体提前。
 */
data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val offsetMs: Long = 0L,
    val meta: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** 播放到 positionMs 时应高亮的行号；没有歌词或还没到第一句时返回 -1。 */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val t = positionMs + offsetMs
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

/**
 * LRC 解析器。
 *
 * 支持：
 * - `[mm:ss.xx]` / `[mm:ss]` / `[mm:ss.xxx]` / `[hh:mm:ss.xx]`
 * - 一行多个时间标签：`[00:01.00][00:05.00]同一句歌词`
 * - 元信息标签：`[ti:]` `[ar:]` `[al:]` `[by:]` `[offset:]`
 */
object LrcParser {

    /** 行首的一串时间标签 */
    private val LEADING_TIMES = Regex("""^((?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\])+)(.*)$""")

    private val ONE_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    private val META = Regex("""^\[([a-zA-Z#]+):(.*)]$""")

    fun parse(raw: String): Lyrics {
        if (raw.isBlank()) return Lyrics()

        val lines = mutableListOf<LyricLine>()
        val meta = mutableMapOf<String, String>()
        var offset = 0L

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) continue

            val metaMatch = META.find(line)
            if (metaMatch != null && ONE_TIME.find(line) == null) {
                val key = metaMatch.groupValues[1].lowercase(Locale.US)
                val value = metaMatch.groupValues[2].trim()
                if (key == "offset") {
                    offset = value.toLongOrNull() ?: 0L
                } else if (value.isNotEmpty()) {
                    meta[key] = value
                }
                continue
            }

            val matched = LEADING_TIMES.find(line) ?: continue
            val text = matched.groupValues[2].trim()
            if (text.isEmpty()) continue

            for (t in ONE_TIME.findAll(matched.groupValues[1])) {
                val min = t.groupValues[1].toLongOrNull() ?: continue
                val sec = t.groupValues[2].toLongOrNull() ?: continue
                val millis = fractionToMillis(t.groupValues[3])
                lines.add(LyricLine(min * 60_000L + sec * 1_000L + millis, text))
            }
        }

        if (lines.isEmpty()) return Lyrics(meta = meta, offsetMs = offset)
        return Lyrics(lines.sortedBy { it.timeMs }, offset, meta)
    }

    /** `.5`→500ms，`.60`→600ms，`.600`→600ms */
    private fun fractionToMillis(frac: String): Long {
        if (frac.isEmpty()) return 0L
        val n = frac.toLongOrNull() ?: return 0L
        return when (frac.length) {
            1 -> n * 100L
            2 -> n * 10L
            else -> n
        }
    }
}
