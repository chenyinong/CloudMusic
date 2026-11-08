package com.cloudmusic.app.data.playlist

import android.util.Log

/**
 * 各音源共用的搜索关键词构造规则。
 *
 * 实测下歌吧对长关键词会直接 HTTP 400：歌单里一首歌的
 * 「夏日底片（不可战胜的夏天） 逵逵爱唱歌（KuiKui） / 还是可以」
 * 整整 34 个字搜不动，而纯歌名「夏日底片」能精确命中。
 * QQ / 网易云歌单的歌手字段还常是「A / B / C」多人拼接，
 * 整串塞进关键词既没必要，也把兜底的那次搜索一起拖死。
 *
 * 所以统一：去括号副标题、歌手只取首位、总长设上限，
 * 并且**纯歌名排第一**。各源复用同一套，避免每家拼法不一样。
 */
internal object SearchKeywords {

    private const val TAG = "CM/Keywords"

    /** 实测 26 字还能返回 200、34 字就 400，留点余量。 */
    private const val MAX_LEN = 24

    private val BRACKET = Regex("[（(【\\[].*?[)）】\\]]")
    private val ARTIST_SEP = Regex(
        "\\s*/\\s*|\\s*&\\s*|\\s*、\\s*|\\s*[,，;；]\\s*|\\s+feat\\.?\\s*|\\s+ft\\.?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val SPACE = Regex("\\s+")

    /** 去括号副标题 + 压空白：「夏日底片（不可战胜的夏天）」→「夏日底片」。 */
    private fun clean(s: String): String =
        SPACE.replace(BRACKET.replace(s, " "), " ").trim()

    /** 多人歌手只取第一个，并去掉括号里的别名。 */
    private fun firstArtist(artist: String): String =
        ARTIST_SEP.split(artist).firstOrNull()?.let(::clean).orEmpty()

    /**
     * 按优先级给出去重后的关键词：纯歌名 → 纯歌名+首位歌手 → 原始歌名。
     *
     * 每一项都截到 [MAX_LEN] 以内；「歌名 + 歌手」超长就直接丢掉这个组合，
     * 而不是把歌名截半截拼出一个谁也搜不到的词。
     */
    fun forTrack(title: String, artist: String): List<String> {
        val main = clean(title)
        val raw = SPACE.replace(title, " ").trim()
        val singer = firstArtist(artist)
        val out = linkedSetOf<String>()

        fun add(s: String) {
            val k = SPACE.replace(s, " ").trim()
            if (k.isNotEmpty()) out.add(k.take(MAX_LEN))
        }

        add(main)
        if (singer.isNotEmpty() && main.length + 1 + singer.length <= MAX_LEN) {
            add("$main $singer")
        }
        // 括号副标题偶尔真的是歌名的一部分（「XX（电影版）」），再给一次原样机会
        if (raw.isNotEmpty() && raw != main && raw.length <= MAX_LEN) add(raw)

        val list = out.toList()
        Log.d(TAG, "「$title」/「$artist」→ $list")
        return list
    }

    /** 没音源时跳到聚合站自己搜，用的也是清洗过的词，别把整串歌手塞进 URL。 */
    fun fallback(title: String, artist: String): String =
        forTrack(title, artist).firstOrNull()
            ?: SPACE.replace(title, " ").trim().take(MAX_LEN)
}
