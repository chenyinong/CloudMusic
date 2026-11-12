package com.cloudmusic.app

import android.app.Application
import com.cloudmusic.app.data.LibraryDb
import com.cloudmusic.app.data.LibraryRepository
import com.cloudmusic.app.data.CoverRepository
import com.cloudmusic.app.data.LyricsRepository
import com.cloudmusic.app.data.MusicSearchRepository
import com.cloudmusic.app.data.Prefs
import com.cloudmusic.app.data.onedrive.GraphClient
import com.cloudmusic.app.data.onedrive.OneDriveAuth
import com.cloudmusic.app.data.onedrive.OneDriveProvider
import com.cloudmusic.app.data.playlist.KuwoAudioSource
import com.cloudmusic.app.data.playlist.LiumingyeAudioSource
import com.cloudmusic.app.data.playlist.NeteaseAudioSource
import com.cloudmusic.app.data.playlist.LiumingyeSource
import com.cloudmusic.app.data.playlist.PlaylistImportService
import com.cloudmusic.app.data.playlist.PlaylistRepository
import com.cloudmusic.app.data.playlist.PlaylistStore
import com.cloudmusic.app.data.playlist.PlaylistTrackImporter
import com.cloudmusic.app.data.cloud.CloudClient
import com.cloudmusic.app.data.cloud.CloudCover
import com.cloudmusic.app.data.cloud.CloudResolver
import com.cloudmusic.app.data.cloud.CloudStore
import com.cloudmusic.app.data.cloud.CloudSync
import com.cloudmusic.app.data.quark.QuarkClient
import com.cloudmusic.app.ui.CloudPlaybackController
import com.cloudmusic.app.ui.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var db: LibraryDb
        private set
    lateinit var repository: LibraryRepository
        private set
    lateinit var lyricsRepository: LyricsRepository
        private set
    lateinit var coverRepository: CoverRepository
        private set
    lateinit var oneDriveAuth: OneDriveAuth
        private set
    lateinit var oneDriveGraph: GraphClient
        private set
    lateinit var oneDriveProvider: OneDriveProvider
        private set
    lateinit var musicSearch: MusicSearchRepository
        private set
    lateinit var playlistImport: PlaylistImportService
        private set

    /** 我的歌单（只存清单，歌曲在网盘里） */
    lateinit var playlistStore: PlaylistStore
        private set

    /** 单曲导入器：歌单详情页点单曲时用 */
    lateinit var playlistImporter: PlaylistTrackImporter
        private set

    /** 夸克网盘客户端（下载链路的中转站） */
    lateinit var quarkClient: QuarkClient
        private set

    /** liumingye 搜索源 */
    lateinit var liumingye: LiumingyeSource
        private set

    /** 网盘音源（下歌吧 → 夸克网盘）。云端歌曲解析也要用它。 */
    lateinit var quarkSource: LiumingyeAudioSource
        private set

    /** 云端（网页版云音乐）接口客户端 */
    lateinit var cloudClient: CloudClient
        private set

    /** 云端内容的本地缓存 */
    lateinit var cloudStore: CloudStore
        private set

    /** 云端双向同步 */
    lateinit var cloudSync: CloudSync
        private set

    /** 云端歌单封面（取第一首歌的封面，跟网页版一个口径） */
    lateinit var cloudCover: CloudCover
        private set

    /** 云端歌曲的本地音源解析 */
    lateinit var cloudResolver: CloudResolver
        private set

    /**
     * 播放器，全局只有一个。
     *
     * 放在 App 而不是某个 ViewModel 里 —— 歌单页、我的歌单页、曲库页
     * 都要能直接起播，换歌、续播才连得上。
     */
    val player by lazy { PlayerConnection(this) }

    /**
     * 云端歌曲的在线混播控制器。
     *
     * 挂在 App 上而不是某个 ViewModel 里 —— 「我的」页和云端歌单详情页
     * 都要用它起播，共用一个才不会互相把对方的队列顶掉。
     */
    val cloudPlayback: CloudPlaybackController by lazy {
        val c = CloudPlaybackController(cloudResolver, player)
        c.attach(cloudScope)
        c.setUnresolvedHandler { track -> c.resolvePicked(track) }
        c
    }

    /** 云端播放用常驻协程域，跟界面生命周期无关。 */
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = LibraryDb(this)
        repository = LibraryRepository(prefs, db)
        oneDriveAuth = OneDriveAuth(prefs, repository.http)
        oneDriveGraph = GraphClient(oneDriveAuth, prefs, repository.http)
        oneDriveProvider = OneDriveProvider(oneDriveGraph, prefs)
        liumingye = LiumingyeSource(repository.http)
        // 歌词的兜底源是下歌吧（QQ 音乐搜不到的冷门歌它有）
        lyricsRepository = LyricsRepository(prefs, db, repository.http, oneDriveProvider, liumingye)
        // 封面来源链：同目录图 → 内嵌图 → QQ 音乐 → 占位图
        coverRepository = CoverRepository(db, repository.http, repository, oneDriveProvider, filesDir)
        quarkClient = QuarkClient(repository.http) { prefs.quarkCookie }
        // 网盘音源（下歌吧 → 夸克网盘）。网易云很多歌只有 30 秒试听，
        // 完整版得从网盘拿，所以在线搜歌和歌单导入都用它兜底。
        val quarkSource = LiumingyeAudioSource(liumingye, quarkClient) { prefs.quarkCookie }
        this.quarkSource = quarkSource
        musicSearch = MusicSearchRepository(repository.http, quarkSource)
        playlistImport = PlaylistImportService(
            playlists = PlaylistRepository(repository.http),
            sources = listOf(
                NeteaseAudioSource(repository.http),
                KuwoAudioSource(repository.http),
                // 兜底源：网易云酷我都没有的，它能搜到。
                // 但要绕夸克一圈（分享链接 → 直链），请求多、限流严，所以排最后。
                quarkSource,
            )
        )
        playlistStore = PlaylistStore(db)
        playlistImporter = PlaylistTrackImporter(musicSearch)

        // 云端（网页版云音乐）这一套
        //
        // 云端接口也是 API，响应很快 —— 超时压到 15 秒。
        // 主 client 的 readTimeout 是 60 秒（下载音频要那么久），
        // 拿来调接口的话 CF 那边一抽风就能把界面干等一分钟。
        cloudClient = CloudClient(
            http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build(),
            baseUrl = { prefs.cloudServerUrl },
            token = { prefs.cloudToken },
        )
        cloudStore = CloudStore(db)
        // 封面查询单独一个 client：短超时。
        //
        // 主 client 的 readTimeout 是 60 秒（下载音频要那么久），
        // 拿来查封面的话，网易云那边一抽风就能把「上传到云端」卡住一分钟。
        cloudCover = CloudCover(OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build())
        cloudSync = CloudSync(cloudClient, cloudStore, prefs, cloudCover)
        cloudResolver = CloudResolver(
            playlistImport = playlistImport,
            quarkSource = quarkSource,
            quarkLoggedIn = { quarkClient.loggedIn },
        )
    }
}