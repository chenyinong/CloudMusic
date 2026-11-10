package com.cloudmusic.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 专辑封面来源链（按「出图速度」排序，命中即止）：
 * 内存/SQLite 缓存 → 同专辑复用 → 网盘同目录封面图 → QQ 音乐 → iTunes US → 文件内嵌封面 → 占位图。
 *
 * - 同专辑复用：同一网盘 + 歌手 + 目录的歌共用一张严格命中的封面，一屏里同专辑的歌只查一次
 * - 同目录图：扫描时建 cover_files 索引，cover.jpg / folder.png / 与歌曲同名图
 * - QQ 音乐：复用歌词那套搜索接口，取 albummid 拼封面直链；中文曲库大、几百毫秒就返回
 * - iTunes：免费无 Key，官方限约 20 次/分钟；只在 QQ 全落空时兜底
 * - 内嵌封面：ID3 APIC / FLAC PICTURE，要下 2MB 音频头（OneDrive 还得先走一次 302），
 *   成本最高，所以放到最后 —— 在线检索能命中的歌完全不用付这笔开销
 * - 命中的直链/本地路径存进 SQLite，同一首歌终身只查一次；查过没有的记进本会话负缓存
 *
 * 匹配分两级：≥10 分（严格）直接采用；6~9 分且与歌手对得上，作为兜底候选，
 * 全部来源都试完仍无严格命中时才用它，避免张冠李戴又尽量少出现占位图。
 */
class CoverRepository(
    private val db: LibraryDb,
    private val http: OkHttpClient,
    /** 拿来构造 WebDAV 客户端，读同目录封面图/音频头部 */
    private val library: LibraryRepository,
    /** OneDrive 曲目走这个 provider 读字节 */
    private val oneDrive: CloudProvider? = null,
    /** 存本地封面图（同目录图、内嵌图、手动选的图） */
    private val filesDir: File,
) {
    companion object {
        /** 存在 App 私有目录里的封面，用 local: + 绝对路径 表示 */
        const val LOCAL_PREFIX = "local:"

        private const val ITUNES_SEARCH = "https://itunes.apple.com/search"
        private const val QQ_SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val QQ_REFERER = "https://y.qq.com/"
        private const val QQ_ARTWORK = "https://y.gtimg.cn/music/photo_new/T002R500x500M000%s.jpg"

        private const val MATCH_STRICT = 10
        private const val MATCH_LOOSE = 6

        private const val UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        const val TAG = "CMDIAG"

        /** 解码后的封面位图内存缓存（500~600px ARGB 约 1.4MB，按张数限 24 张） */
        private val bitmapCache = object : LruCache<String, Bitmap>(24) {
            override fun sizeOf(key: String, value: Bitmap): Int = 1
        }
    }

    /**
     * 本会话的 URL 缓存（值 "" = 查过但没有），列表滚动时每行重组也不会重复打库。
     */
    private val urlCache = Collections.synchronizedMap(mutableMapOf<String, String>())

    /** 内嵌封面查过没有的曲目，别每次 load 都去下 2MB 文件头 */
    private val embeddedTried = Collections.synchronizedSet(mutableSetOf<String>())

    /** 读音频头部最多两个并发，列表快速滚动时别一次拉十几个 2MB */
    private val headSemaphore = Semaphore(2)

    /**
     * 同专辑（网盘 + 歌手 + 目录）已经严格命中的封面。
     * 一张专辑十几首歌，没必要为每首各打一次搜索接口。
     */
    private val albumCovers = Collections.synchronizedMap(mutableMapOf<String, String>())

    /** 点过「重新查找封面」的曲目，不参与专辑复用，让它真去查一遍。 */
    private val reuseDisabled = Collections.synchronizedSet(mutableSetOf<String>())

    /** 同一首歌的并发查找共用一个结果（列表行 + 迷你条 + 播放页会同时问）。 */
    private val loadInflight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** 同一个封面 URL 的并发下载共用一个结果，避免一屏里十行重复下同一张图。 */
    private val artworkInflight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    /** 手动改封面 / 重新查找后自增，UI 靠它触发重组刷新缩略图。 */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    /** 检索/下载图片专用短超时：一个卡住的请求不能把整条队列堵死 */
    private val shortHttp: OkHttpClient by lazy {
        http.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** 全局串行 + 最小间隔：iTunes 限约 20 次/分钟，滚动列表时按节奏排队查。 */
    private val searchMutex = Mutex()
    private var lastItunesAt = 0L
    private var lastQqAt = 0L

    /** 查封面：命中缓存直接返回，否则按「出图速度」逐级找。失败返回 null，UI 画占位图。 */
    suspend fun load(track: Track): String? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "load START key=${track.key} title=${track.title} artist=${track.artist} provider=${track.provider} file=${track.fileName}")
        urlCache[track.key]?.let {
            Log.d(TAG, "load HIT-MEM '$it'")
            return@withContext it.ifBlank { null }
        }
        db.coverUrl(track.key)?.let { cached ->
            // local: 指向的本地图可能被清缓存删掉，失效就重找，别一直挂着空图
            if (!cached.startsWith(LOCAL_PREFIX) || File(cached.removePrefix(LOCAL_PREFIX)).exists()) {
                urlCache[track.key] = cached
                Log.d(TAG, "load HIT-DB $cached")
                return@withContext cached
            }
            db.clearCover(track.key)
        }

        // 同一首歌可能被列表行、迷你条、播放页同时问，只让第一个真去查
        val mine = CompletableDeferred<String?>()
        val owner = loadInflight.putIfAbsent(track.key, mine)
        if (owner != null) return@withContext owner.await()

        var url: String? = null
        try {
            url = resolve(track)
            // 查过就记下来，没查到也别在同一次启动里反复打接口
            urlCache[track.key] = url ?: ""
            if (url != null) db.saveCover(track.key, url)
        } catch (e: Exception) {
            Log.w(TAG, "load FAILED ${e.javaClass.simpleName}: ${e.message}")
            url = null
        } finally {
            mine.complete(url)
            loadInflight.remove(track.key)
        }
        Log.d(TAG, "load RESULT $url cost=${System.currentTimeMillis() - t0}ms")
        url
    }

    /** 缓存未命中时的来源链：从零成本到最贵，命中即止。 */
    private suspend fun resolve(track: Track): String? {
        val groupKey = albumKey(track)

        // 1) 纯内存：同专辑已有歌严格命中过，直接共用那张图
        if (groupKey != null && track.key !in reuseDisabled) {
            albumCovers[groupKey]?.let {
                Log.d(TAG, "load HIT-ALBUM $it")
                return it
            }
        }

        // 2) 网盘同目录封面图（有 cover_files 索引时才有意义）
        val local = findLocalCover(track)
        Log.d(TAG, "load localCover=$local")
        if (local != null) return local

        // 3) 在线检索：QQ 通常几百毫秒就严格命中，绝大多数歌到这步就结束
        val online = searchOnline(track)
        Log.d(TAG, "load online=${online?.url} strict=${online?.strict}")
        if (online?.strict == true) {
            if (groupKey != null) {
                albumCovers[groupKey] = online.url
                reuseDisabled.remove(track.key)
            }
            return online.url
        }

        // 4) 在线没严格命中时才下 2MB 音频头：真封面比「宽松匹配」的搜索结果靠谱
        val embedded = findEmbeddedCover(track)
        Log.d(TAG, "load embedded=$embedded")
        if (embedded != null) return embedded

        // 5) 实在没有，才退而用宽松候选
        return online?.url
    }

    /** 同专辑判定：网盘 + 歌手 + 目录名（扫描时把目录名存进了 album）。 */
    private fun albumKey(track: Track): String? {
        val artist = track.artist.trim()
        val album = track.album.trim().ifBlank { decodePath(track.folder).substringAfterLast('/') }
        if (artist.isBlank() || album.isBlank()) return null
        return "${track.provider}|${artist.lowercase()}|${album.lowercase()}"
    }

    /**
     * 下载并解码封面位图，带内存缓存（来回切歌不用重新下）。
     * 同一 URL 的并发请求共用一个下载结果 —— 一屏里同专辑的歌都指向同一张图，
     * 不合并的话会同时发十几个一样的请求，互相抢带宽。
     */
    suspend fun artwork(url: String): Bitmap? = withContext(Dispatchers.IO) {
        bitmapCache.get(url)?.let { return@withContext it }
        val mine = CompletableDeferred<Bitmap?>()
        val owner = artworkInflight.putIfAbsent(url, mine)
        if (owner != null) return@withContext owner.await()
        // 解码是阻塞调用，中间没有挂起点，所以走到最后一定能 complete，
        // 等待者不会因这里的异常或取消被永久挂住。
        val bmp = try {
            decodeArtwork(url)
        } catch (e: Exception) {
            null
        }
        if (bmp != null) bitmapCache.put(url, bmp)
        mine.complete(bmp)
        artworkInflight.remove(url)
        bmp
    }

    private fun decodeArtwork(url: String): Bitmap? {
        // 本地封面（同目录图 / 内嵌图 / 手动选的图）
        if (url.startsWith(LOCAL_PREFIX)) {
            val f = File(url.removePrefix(LOCAL_PREFIX))
            return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        }
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            shortHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 同目录封面：扫描时已在 cover_files 里建好索引，
     * 优先「与歌曲同名」，再常见的 cover/folder/front，最后该目录第一张图。
     * 命中后把图存进 App 私有目录，返回 local: 路径，之后终身免查。
     */
    private suspend fun findLocalCover(track: Track): String? {
        // 在线直链曲目不在网盘扫描索引里，别拿 WebDAV 客户端去拼一个假路径
        if (track.folder.isBlank() || !isCloudTrack(track)) return null
        val folder = decodePath(track.folder)
        val base = track.fileName.substringBeforeLast('.')
        val candidates = LinkedHashSet<String>()
        db.coverFile(track.provider, folder, base)?.let { candidates.add(it) }
        for (n in listOf("cover", "Cover", "folder", "Folder", "front", "album", "art", "封面")) {
            db.coverFile(track.provider, folder, n)?.let { candidates.add(it) }
        }
        db.coverFilesInFolder(track.provider, folder).take(3).forEach { candidates.add(it) }
        Log.d(TAG, "findLocalCover folder=$folder base=$base candidates=${candidates.size}")

        for (href in candidates) {
            val bytes = readFromProvider(track, href, null, 4 * 1024 * 1024) ?: continue
            if (!isImage(bytes)) continue
            return storeLocal(track.key, bytes)
        }
        return null
    }

    /**
     * 内嵌封面：只读音频文件头 2MB，ID3/FLAC 的封面都在这个范围内。
     * 这是最贵的一档（OneDrive 要先拿 302 再拉 2MB），只在在线检索全落空时才走；
     * 命中同样存进私有目录，之后终身免查；查过没有的记进 embeddedTried。
     */
    private suspend fun findEmbeddedCover(track: Track): String? {
        if (track.key in embeddedTried) return null
        // 在线直链曲目可能只有 url 没有网盘 id，这时用 url 发 Range
        if (track.id.isBlank() && !track.url.startsWith("http")) return null
        embeddedTried.add(track.key)
        val head = headSemaphore.withPermit { audioHead(track) }
        if (head == null) {
            Log.d(TAG, "findEmbeddedCover NO HEAD")
            return null
        }
        val pic = EmbeddedCover.extract(head)
        Log.d(TAG, "findEmbeddedCover picBytes=${pic?.size} headMagic=" +
            head.take(4).joinToString(" ") { "%02x".format(it) })
        if (pic == null) return null
        if (!isImage(pic)) {
            Log.d(TAG, "findEmbeddedCover pic not decodable")
            return null
        }
        return storeLocal(track.key, pic)
    }

    /** 读音频文件头部（内嵌封面/标签都在这里）；[maxBytes] 按需调小。 */
    suspend fun audioHead(track: Track, maxBytes: Int = 2 * 1024 * 1024): ByteArray? {
        val max = maxBytes.coerceIn(64 * 1024, 4 * 1024 * 1024)
        val range = "bytes=0-${max - 1}"
        Log.d(TAG, "audioHead provider=${track.provider} id=${track.id} url=${track.url} range=$range")
        val bytes = when (track.provider) {
            ProviderKind.ONEDRIVE.id -> oneDrive?.fetchBytes(track.id, range, max)
            ProviderKind.WEBDAV.id -> library.client().fetchBytes(track.id, range, max)
            else -> if (track.url.startsWith("http")) {
                fetchDirectRange(track.url, track.extraHeaders, range, max)
            } else null
        }
        Log.d(
            TAG,
            "audioHead RESULT bytes=${bytes?.size} head=" +
                (bytes?.take(16)?.joinToString(" ") { "%02x".format(it) } ?: "null")
        )
        return bytes
    }

    /** 在线直链的曲目直接对播放地址发 Range，防盗链头一并带上。 */
    private fun fetchDirectRange(
        url: String,
        headers: Map<String, String>,
        range: String,
        maxBytes: Int,
    ): ByteArray? {
        val builder = Request.Builder()
            .url(url)
            .header("Range", range)
            .header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return try {
            shortHttp.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                body.byteStream().use { s ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (total < maxBytes) {
                        val n = s.read(buf, 0, minOf(buf.size, maxBytes - total))
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    out.toByteArray().takeIf { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isCloudTrack(track: Track): Boolean =
        track.provider == ProviderKind.WEBDAV.id || track.provider == ProviderKind.ONEDRIVE.id

    /** 按曲目所属网盘读取文件字节（WebDAV 用 Basic，OneDrive 用 Bearer）。 */
    private suspend fun readFromProvider(
        track: Track,
        href: String,
        range: String?,
        maxBytes: Int,
    ): ByteArray? = if (track.provider == ProviderKind.ONEDRIVE.id) {
        oneDrive?.fetchBytes(href, range, maxBytes)
    } else {
        library.client().fetchBytes(href, range, maxBytes)
    }

    /** 用 BitmapFactory 探一下是不是真图片，避免把随便一个 .jpg 当封面。 */
    private fun isImage(bytes: ByteArray): Boolean = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.outWidth > 0 && opts.outHeight > 0
    } catch (e: Exception) {
        false
    }

    /** 把图片字节存进 App 私有目录，返回 local: 路径。 */
    fun storeLocal(trackKey: String, bytes: ByteArray): String? = try {
        val dir = File(filesDir, "covers").apply { mkdirs() }
        val f = File(dir, md5(trackKey) + ".img")
        f.writeBytes(bytes)
        LOCAL_PREFIX + f.absolutePath
    } catch (e: Exception) {
        null
    }

    /** 手动指定封面（URL 或 local: 路径），写进 DB 后优先于一切自动查找。 */
    fun setManualCover(trackKey: String, url: String) {
        db.saveCover(trackKey, url)
        urlCache[trackKey] = url
        _changes.value++
    }

    /** 手动选的本地图片：存进私有目录再写 DB。 */
    fun setManualCoverFromBytes(trackKey: String, bytes: ByteArray): String? {
        val local = storeLocal(trackKey, bytes) ?: return null
        setManualCover(trackKey, local)
        return local
    }

    /**
     * 扫描纠正了歌名/歌手后调用：只清内存缓存，DB 里没被改动的封面记录仍在，
     * 下次 load 会从 DB 秒恢复；被纠正过的那几首则重新走完整来源链。
     */
    fun invalidateAll() {
        urlCache.clear()
        embeddedTried.clear()
        albumCovers.clear()
        reuseDisabled.clear()
        _changes.value++
    }

    /** 重新自动查找：清 DB 记录和内存缓存，下次 load 会从头走一遍来源链。 */
    fun resetCover(trackKey: String) {
        db.clearCover(trackKey)
        urlCache.remove(trackKey)
        embeddedTried.remove(trackKey)
        // 别再拿同专辑的旧结果糊弄过去，这次要真去查一遍
        reuseDisabled.add(trackKey)
        _changes.value++
    }

    private fun md5(s: String): String = try {
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        s.hashCode().toString()
    }

    private fun decodePath(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private data class Hit(val title: String, val artist: String, val artwork: String)

    /** [strict] = 严格命中，可以放心复用给同专辑的其他歌；宽松候选不复用。 */
    private data class OnlineHit(val url: String, val strict: Boolean)

    /**
     * 先 QQ 后 iTunes US，每个关键词取最高分。
     * 严格命中立即返回；宽松命中先记着，所有来源都没严格命中时才用。
     */
    private suspend fun searchOnline(track: Track): OnlineHit? {
        val t0 = System.currentTimeMillis()
        val title = track.title.trim()
        val artist = track.artist.trim()
        if (title.isBlank()) return null
        val wantTitle = normalize(title)
        val wantArtist = normalize(artist)
        val variants = keywordVariants(title, artist)
        Log.d(TAG, "searchOnline want='$title' / '$artist' variants=$variants")

        var loose: Hit? = null
        var looseScore = 0

        // 返回「严格命中」，顺手把最好的宽松候选记进 loose
        fun consider(hits: List<Hit>): Hit? {
            var strict: Pair<Hit, Int>? = null
            for (h in hits) {
                val s = score(normalize(h.title), normalize(h.artist), wantTitle, wantArtist)
                if (s >= MATCH_STRICT) {
                    if (strict == null || s > strict.second) strict = h to s
                } else if (s >= MATCH_LOOSE && wantArtist.isNotEmpty() &&
                    artistScore(normalize(h.artist), wantArtist) > 0 && s > looseScore
                ) {
                    loose = h
                    looseScore = s
                }
            }
            Log.d(
                TAG,
                "consider hits=${hits.size} sample=" + hits.take(3).map { "${it.title}/${it.artist}/${
                    score(normalize(it.title), normalize(it.artist), wantTitle, wantArtist)
                }" } + " strict=${strict?.first?.title}/${strict?.first?.artist} score=${strict?.second} loose=$looseScore"
            )
            return strict?.first
        }

        // 1) QQ 音乐优先：国内响应快、中文曲库大，且没有 iTunes 那种严格限流。
        //    实测 iTunes 的 country=CN 对中文歌几乎全是空结果，白等两轮 3 秒，直接不查。
        //    300ms 的间隔足够温和，又不会让一屏十首歌排十几秒的队。
        for (kw in variants) {
            throttle(300L, isQq = true)
            Log.d(TAG, "qq kw=$kw")
            consider(qqHits(kw))?.let {
                Log.d(TAG, "searchOnline QQ HIT ${System.currentTimeMillis() - t0}ms")
                return OnlineHit(it.artwork, true)
            }
        }
        // 2) iTunes 美国店兜底：欧美/冷门、QQ 没收录的。限流严，只试前两个关键词。
        for (kw in variants.take(2)) {
            throttle(3000L, isQq = false)
            Log.d(TAG, "itunes US kw=$kw")
            consider(itunesHits(kw, "US"))?.let { return OnlineHit(it.artwork, true) }
        }
        Log.d(TAG, "searchOnline FINAL loose=${loose?.title}/${loose?.artist} score=$looseScore cost=${System.currentTimeMillis() - t0}ms")
        return loose?.let { OnlineHit(it.artwork, false) }
    }

    /**
     * 节流：只在算「自己的下一个可用时间点」时短暂持锁，等待放到锁外。
     * 这样 iTunes 等 3 秒时不会把 QQ 的请求一起堵住。
     */
    private suspend fun throttle(intervalMs: Long, isQq: Boolean) {
        val wait = searchMutex.withLock {
            val now = System.currentTimeMillis()
            val last = if (isQq) lastQqAt else lastItunesAt
            val w = (intervalMs - (now - last)).coerceAtLeast(0L)
            // 预留槽位：并发调用也按顺序排队，不会同时打出去
            if (isQq) lastQqAt = now + w else lastItunesAt = now + w
            w
        }
        if (wait > 0) delay(wait)
    }

    private fun itunesHits(keyword: String, country: String): List<Hit> {
        val url =
            "$ITUNES_SEARCH?term=${enc(keyword)}&media=music&entity=song&limit=12&country=$country"
        val body = get(url) ?: return emptyList()
        return try {
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<Hit>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("trackName")
                val artwork = o.optString("artworkUrl100")
                if (name.isBlank() || artwork.isBlank()) continue
                out.add(Hit(name, o.optString("artistName"), artwork.replace("100x100bb", "600x600bb")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** QQ 搜索结果里的 albummid 直接能拼出专辑封面直链。 */
    private fun qqHits(keyword: String): List<Hit> {
        val url = "$QQ_SEARCH?format=json&p=1&n=12&w=${enc(keyword)}&t=0&cr=1"
        val body = get(url, QQ_REFERER) ?: return emptyList()
        return try {
            val list = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("list")
                ?: return emptyList()
            val out = mutableListOf<Hit>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val name = o.optString("songname")
                val albumMid = o.optString("albummid")
                if (name.isBlank() || albumMid.isBlank()) continue
                val singers = mutableListOf<String>()
                o.optJSONArray("singer")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        singers.add(arr.optJSONObject(j)?.optString("name").orEmpty())
                    }
                }
                out.add(Hit(name, singers.joinToString(" "), QQ_ARTWORK.format(albumMid)))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 关键词变体：原始「歌名 歌手」优先，再试洗掉版本后缀的干净歌名。
     * 「我好想你 (苏打绿版)」「流沙 (Live)」这类括号尾巴最容易让精确匹配失败。
     */
    private fun keywordVariants(title: String, artist: String): List<String> {
        val clean = cleanTitle(title)
        val out = LinkedHashSet<String>()
        if (artist.isNotBlank()) {
            out.add("$title $artist")
            if (clean != title && clean.isNotBlank()) out.add("$clean $artist")
        }
        out.add(title)
        if (clean != title && clean.isNotBlank()) out.add(clean)
        return out.toList()
    }

    /** 去掉括号版本信息、Live/伴奏之类尾巴、以及开头的曲目序号。 */
    private fun cleanTitle(s: String): String {
        var out = s
        // 反复剥掉 (…)（…）[…]【…】
        repeat(3) {
            val next = Regex("""[（(【\[][^）)】\]]*[）)】\]]""").replace(out, " ")
            if (next == out) return@repeat
            out = next
        }
        out = Regex(
            """(live|remix|demo|acoustic|instrumental|karaoke|现场|重制|翻唱|伴奏|纯音乐|版).*$""",
            RegexOption.IGNORE_CASE
        ).replace(out, "")
        out = Regex("""^\s*\d{1,3}[\s.\-_]+""").replace(out, "")
        return out.replace(Regex("""\s+"""), " ").trim().trim('-', '_', '.')
    }

    /** 歌名 + 歌手打分，口径与歌词匹配一致。 */
    private fun score(
        name: String,
        artist: String,
        wantTitle: String,
        wantArtist: String,
    ): Int {
        if (wantTitle.isEmpty()) return 0
        val minLen = minOf(name.length, wantTitle.length)
        val titleScore = when {
            name == wantTitle -> 10
            (name.contains(wantTitle) || wantTitle.contains(name)) && minLen >= 2 -> 6
            else -> 0
        }
        if (titleScore == 0) return 0
        if (wantArtist.isEmpty()) {
            return if (titleScore == 10) 10 else 0
        }
        return titleScore + artistScore(artist, wantArtist)
    }

    private fun artistScore(artist: String, wantArtist: String): Int = when {
        artist == wantArtist -> 8
        (artist.contains(wantArtist) || wantArtist.contains(artist)) &&
            minOf(artist.length, wantArtist.length) >= 2 -> 5
        else -> 0
    }

    private fun get(url: String, referer: String? = null): String? {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        if (referer != null) builder.header("Referer", referer)
        Log.d(TAG, "GET $url")
        return try {
            shortHttp.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "GET FAIL ${resp.code} $url")
                    return null
                }
                val body = resp.body?.string()
                Log.d(TAG, "GET OK ${resp.code} len=${body?.length} $url")
                body
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET ERROR ${e.javaClass.simpleName}: ${e.message} $url")
            null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 去掉空格与常见标点，便于比较歌名歌手。 */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }
}
