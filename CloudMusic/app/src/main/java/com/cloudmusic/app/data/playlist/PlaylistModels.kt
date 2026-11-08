package com.cloudmusic.app.data.playlist

/** 歌单来源平台。 */
enum class PlaylistPlatform(val id: String, val label: String) {
    NETEASE("netease", "网易云"),
    QQ("qq", "QQ音乐");

    companion object {
        fun of(id: String): PlaylistPlatform = entries.firstOrNull { it.id == id } ?: NETEASE
    }
}

/** 歌单里的一首曲目（还没去找音源）。 */
data class PlaylistTrack(
    /** 平台内唯一标识，用来在 UI 上做 key */
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val platform: PlaylistPlatform,
    /** 网易云的歌曲 id —— 有它就能直接下载，不用再搜一遍 */
    val platformId: String? = null,
)

/** 拉下来的一张歌单。 */
data class ImportedPlaylist(
    val name: String,
    val platform: PlaylistPlatform,
    val playlistId: String,
    val tracks: List<PlaylistTrack>,
)

/**
 * 从某个下载源找到的一个候选音频。
 *
 * @param variantTag 衍生版本标记（现场版 / DJ版 / 伴奏 …），正片为 null
 * @param audioUrl 可直接下载的音频地址；刚搜出来时还没探测，是空串
 */
data class AudioCandidate(
    val sourceId: String,
    val sourceLabel: String,
    /** 该源内的歌曲标识：网易云是歌曲 id，酷我是 rid */
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val variantTag: String? = null,
    val audioUrl: String = "",
    /** 只下到了试听片段（平台版权限制） */
    val partial: Boolean = false,
    /**
     * 下载这个候选时要额外带的请求头。
     *
     * 夸克的直链是**会话绑定**的：URL 里那个 `token=5-...` 要跟登录 Cookie 对得上，
     * 光拿直链去 GET 会被 CDN 用 412 拦掉。所以走夸克那条路时必须把 Cookie 带上。
     */
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * 音频的真实扩展名（不带点），默认 mp3。
     *
     * 夸克那边给的是 FLAC / WAV，存成 .mp3 的话播放器会按扩展名解析而失败，
     * 所以得按实际格式落盘。
     */
    val ext: String = "mp3",
)

/** 一首曲目在导入流程中的状态。 */
enum class TrackStatus {
    /** 已经在网盘里了 */
    EXISTING,

    /** 歌名对上了但歌手对不上 —— 可能是同一首，也可能是同名歌 */
    EXISTING_FUZZY,

    /** 有音源且版本一致，可以直接下 */
    READY,

    /** 只能下到衍生版（DJ / 伴奏 / 纯音乐 / 现场版），要用户点头 */
    VARIANT,

    /** 只拿得到试听片段 */
    PARTIAL,

    /** 两个源都找不到 */
    NO_SOURCE,
}

/** 歌单里某首歌的完整处理方案。 */
data class TrackPlan(
    val track: PlaylistTrack,
    val status: TrackStatus,
    /** 选中的候选。READY / VARIANT / PARTIAL 时有值 */
    val candidate: AudioCandidate? = null,
    /** 其它备选版本，用户可以换一个下 */
    val alternatives: List<AudioCandidate> = emptyList(),
    /** 没有音源时给用户的兜底链接（去聚合站自己搜） */
    val fallbackUrl: String? = null,
    /**
     * 每个音源的结论，一行一个。
     *
     * 「没找到音源」这种话对用户没用 —— 到底哪个源没命中、是搜不到还是
     * 拿不到直链，得说清楚，用户才知道下一步该干嘛。
     */
    val notes: List<String> = emptyList(),
)

/** 拉歌单失败时抛这个，消息直接给用户看。 */
class PlaylistException(message: String) : Exception(message)

/**
 * 歌名 / 歌手的匹配规则。
 *
 * 口径卡得很死是有原因的：实测酷我搜「热爱 郑钧」会把「渐渐被你吸引」排在前面，
 * 只看歌名的话分分钟下错歌进网盘。
 */
object TrackMatcher {

    private val BRACKET = Regex("[（(【\\[].*?[)）】\\]]")

    /**
     * 衍生版本关键词 → 给用户看的标签。
     *
     * 顺序有讲究，先匹配更具体的（「伴奏」要排在「钢琴版」前面，
     * 因为「钢琴版伴奏」两个词都沾）。
     */
    private val VARIANT_TAGS: List<Pair<Regex, String>> = listOf(
        Regex("伴奏") to "伴奏",
        Regex("纯音乐|instrumental", RegexOption.IGNORE_CASE) to "纯音乐",
        Regex("吉他版|贝斯版|鼓版|钢琴版|钢琴曲|\\bpiano\\b", RegexOption.IGNORE_CASE) to "器乐版",
        Regex("\\blive\\b|演唱会|现场", RegexOption.IGNORE_CASE) to "现场版",
        Regex("\\bdj\\b", RegexOption.IGNORE_CASE) to "DJ版",
        Regex("\\bremix\\b", RegexOption.IGNORE_CASE) to "Remix",
        Regex("\\bdemo\\b", RegexOption.IGNORE_CASE) to "Demo",
        Regex("acoustic", RegexOption.IGNORE_CASE) to "不插电版",
        Regex("超燃版|超燃", RegexOption.IGNORE_CASE) to "超燃版",
        Regex("合唱版|合唱", RegexOption.IGNORE_CASE) to "合唱版",
        Regex("电视剧版|影视版|电影版|动画版|游戏版", RegexOption.IGNORE_CASE) to "影视版",
        Regex("翻唱|\\bcover\\b", RegexOption.IGNORE_CASE) to "翻唱",
        Regex("铃声|rington", RegexOption.IGNORE_CASE) to "铃声",
    )

    /** 归一化：小写 + 只保留字母数字。跟 MusicImport 的规则一致。 */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) if (c.isLetterOrDigit()) sb.append(c)
        return sb.toString()
    }

    /** 去掉括号里的补充说明：「稻香 (钢琴版) [原唱: 周杰伦]」→「稻香」 */
    fun stripBrackets(s: String): String = BRACKET.replace(s, " ").trim()

    /** 这一版是不是衍生版本。是的话返回给用户看的标签，正片返回 null。 */
    fun variantTag(title: String, artist: String = ""): String? {
        val raw = "$title $artist"
        for ((re, tag) in VARIANT_TAGS) if (re.containsMatchIn(raw)) return tag
        return null
    }

    /** 是不是同一首歌：去掉括号补充之后歌名完全一致。 */
    fun sameTitle(a: String, b: String): Boolean {
        val na = norm(stripBrackets(a))
        val nb = norm(stripBrackets(b))
        return na.isNotEmpty() && na == nb
    }

    /** 候选的歌手里有没有命中目标歌手。 */
    fun artistHit(target: String, candidate: String): Boolean {
        val n = norm(target)
        if (n.isEmpty()) return true
        val c = norm(candidate)
        if (c.isEmpty()) return false
        // norm 只保留字母数字，会一并去掉「& / 、」等分隔符，所以候选若是
        // 多人拼接（如「大土班的魔仙堡&洛天依&逃跑计划」），归一化后就变成
        // 一串；目标歌手若是其中一人，上面的 contains 已经能命中，不必再按
        // 分隔符拆开逐个比（拆的是已归一化的串，永远只是单段，毫无意义）。
        if (c.contains(n)) return true
        return false
    }

    /** 时长差在这个范围内算同一首。 */
    fun durationClose(a: Int, b: Int, tolerance: Int = 5): Boolean {
        if (a <= 0 || b <= 0) return false
        return kotlin.math.abs(a - b) <= tolerance
    }

    /**
     * 候选明显比目标短 —— 那它其实是片段，不是「另一个版本」。
     *
     * 酷我搜「飞得更高 汪峰」会给一个 235 秒的现场版，原版 397 秒，
     * 这种要算片段，不能当成「衍生版完整文件」让用户去下。
     */
    fun tooShort(candidateSec: Int, targetSec: Int): Boolean {
        if (candidateSec <= 0 || targetSec <= 0) return false
        return candidateSec < targetSec * 0.6
    }
}
