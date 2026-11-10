package com.cloudmusic.app.data.dlna

import android.content.Context
import android.util.Log
import com.cloudmusic.app.data.Prefs
import com.cloudmusic.app.data.StreamAuth
import com.cloudmusic.app.data.Track
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLNA 媒体渲染器（DMR）服务端。
 *
 * 干三件事：
 * 1. SSDP 应答 M-SEARCH，让电视/音箱能在列表里看到「云音乐」；
 * 2. 起一个局域网页面，提供设备描述、SOAP 控制；
 * 3. 把网盘里的音频**代理**出去 —— 渲染器直接拉网盘的 URL 是拉不动的
 *    （坚果云要 Basic 鉴权、OneDrive 要 Bearer），所以由手机转发，
 *    并且透传 Range（拖进度条要用）。
 *
 * 同时它也是「推歌到电视」时给电视提供音频流的地址。
 */
class DlnaServer(
    private val context: Context,
    private val prefs: Prefs,
    private val http: OkHttpClient,
    private val tracksProvider: () -> List<Track>,
) {

    companion object {
        private const val TAG = "DlnaServer"
        const val FRIENDLY_NAME = "云音乐"
        const val SERVICE_AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val SERVICE_RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
        const val SERVICE_CDS = "urn:schemas-upnp-org:service:ContentDirectory:1"
        const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

        private const val CR: Byte = 13
        private const val LF: Byte = 10

        /** DIDL-Lite 的根元素，命名空间声明都在这儿，少一个渲染器就报「前缀未绑定」 */
        const val DIDL_OPEN =
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">"
    }

    private val running = AtomicBoolean(false)
    private val ssdpReady = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var ssdp: SsdpResponder? = null
    /** 当前正在投屏的歌（含在线歌），serveMedia 在曲库里查不到时兜底用 */
    private var castTrack: Track? = null
    private var pool: ExecutorService? = null

    /** HTTP 端口 */
    var port: Int = 0
        private set

    /** 局域网 IP */
    var ip: String = ""
        private set

    /** 设备 UUID */
    val uuid: String = "uuid:" + UUID.randomUUID().toString()

    /** 播放状态变化时回调（参数是 UPnP 的 TransportState） */
    @Volatile
    var onStateChanged: ((String) -> Unit)? = null

    @Volatile
    private var transportState: String = "STOPPED"

    @Volatile
    private var currentUri: String = ""

    @Volatile
    private var currentMeta: String = ""

    @Volatile
    private var nextUri: String = ""

    @Volatile
    private var nextMeta: String = ""

    @Volatile
    private var volume: Int = 30

    @Volatile
    private var muted: Boolean = false

    /** 暂停/停止时的播放位置（毫秒） */
    @Volatile
    private var seekBaseMs: Long = 0

    /** 开始播放时的墙上时钟 */
    @Volatile
    private var playStartedAt: Long = 0

    /** 订阅了事件的设备 */
    private val subscribers = ConcurrentHashMap<String, Subscriber>()

    /** 常规浏览器 UA：部分 CDN（如 QQ 音乐）会拦截非常规 UA 直接 403 */
    private val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    /** 探测到的上游文件大小缓存（在线歌等 size 未知时，用于毫秒→字节换算） */
    private val sizeCache = ConcurrentHashMap<String, Long>()
    /** 每首歌上次代理发给设备的字节数（无时长时用于字节级断点续播） */
    private val bytesSent = ConcurrentHashMap<String, Long>()
    /** 字节级起始偏移（无时长断点用），优先于毫秒偏移 */
    private val startOffsetBytes = ConcurrentHashMap<String, Long>()

    /** 每首歌这次要从哪儿开始播（ms），见 [setStartOffset] */
    private val startOffsets = ConcurrentHashMap<String, Long>()

    private class Subscriber(val callback: String, val sid: String, val service: String)

    val isRunning: Boolean get() = running.get()

    /** SSDP 应答有没有起来（1900 端口被别的 App 占了就起不来，电视就搜不到本机） */
    val ssdpAvailable: Boolean get() = ssdpReady.get()

    // ---------------- 生命周期 ----------------

    /**
     * 优先用固定端口，避免每次重启都换端口、导致音箱缓存的旧地址失效连不上；
     * 端口被占用（上次的 TIME_WAIT 等）就顺延尝试几个，都失败再退回随机端口。
     */
    private fun bindPort(): ServerSocket {
        val candidates = listOf(38000, 38001, 38002, 38003)
        for (p in candidates) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
                Log.d(TAG, "DLNA 服务绑定固定端口 $p")
                return s
            } catch (e: Exception) {
                Log.w(TAG, "端口 $p 占用，尝试下一个：${e.message}")
            }
        }
        val s = ServerSocket(0)
        s.reuseAddress = true
        Log.w(TAG, "固定端口均不可用，改用随机端口 ${s.localPort}")
        return s
    }

    fun start(): Boolean {
        if (running.get()) return true
        val lan = findLanIp()
        if (lan.isBlank()) {
            Log.w(TAG, "找不到局域网 IP，DLNA 服务端起不来")
            return false
        }
        return try {
            ip = lan
            val ss = bindPort()
            serverSocket = ss
            port = ss.localPort
            val p = Executors.newFixedThreadPool(8)
            pool = p
            val latch = CountDownLatch(1)
            val responder = SsdpResponder(context, { locationUrl() }, { uuid }) { ok ->
                ssdpReady.set(ok)
                latch.countDown()
            }
            ssdp = responder
            running.set(true)
            responder.start()
            // 等一下 SSDP 端口到底绑上没，别骗用户说"已开启"
            latch.await(1500, TimeUnit.MILLISECONDS)
            p.execute { acceptLoop(ss) }
            Log.i(TAG, "DLNA 服务端已启动：${locationUrl()}，SSDP=${ssdpReady.get()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DLNA 服务端启动失败", e)
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        ssdp?.stop()
        ssdp = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
        subscribers.clear()
        transportState = "STOPPED"
    }

    /** 电视/音箱来播放时，手机本地要不要暂停 */
    fun isRemotePlaying(): Boolean = transportState == "PLAYING"

    /** 局域网里别人能访问到的地址 */
    fun locationUrl(): String = "http://$ip:$port/description.xml"

    /**
     * 某首歌在局域网里的播放地址（推给电视用）。
     *
     * 刻意**不带任何查询参数** —— 小爱音箱那类设备对带 `?` 的 URL 解析得很粗，
     * 宁可地址干净点。
     *
     * 起始偏移不写在 URL 里，而是记在 [setStartOffset] 里，
     * 服务端自己知道该从哪儿开始给。
     */
    fun mediaUri(track: Track, startMs: Long = 0L): String {
        val base = "http://$ip:$port/media/" + URLEncoder.encode(track.key, "UTF-8")
        return if (startMs > 0) "$base?start=$startMs" else base
    }

    /**
     * 记下这首歌这次要从哪儿开始播（毫秒）。
     *
     * 有些设备（不少智能音箱）根本没实现 DLNA 的 Seek 指令，
     * 发了也不动。那就换个起点重新推一遍 ——
     * 设备只要「从头播这个 URL」就行，这是所有渲染器都支持的最基础能力。
     */
    fun setStartOffset(key: String, offsetMs: Long) {
        if (offsetMs > 0) startOffsets[key] = offsetMs else startOffsets.remove(key)
    }

    /** 记录当前投屏的歌（在线歌不在网盘曲库里，serveMedia 要兜底查它） */
    fun setCastTrack(t: Track?) {
        castTrack = t
    }

    /** 字节级起始偏移（无时长断点用）：设了就优先于毫秒偏移 */
    fun setStartOffsetBytes(key: String, bytes: Long) {
        if (bytes > 0) startOffsetBytes[key] = bytes else startOffsetBytes.remove(key)
    }
    fun startOffsetBytesOf(key: String): Long = startOffsetBytes[key] ?: 0L
    /** 上次代理发给设备的字节数，无时长时当作断点起点 */
    fun bytesSentFor(key: String): Long = bytesSent[key] ?: 0L

    /** 这首歌当前的起始偏移（毫秒），0 表示从头播 */
    fun startOffsetOf(key: String): Long = startOffsets[key] ?: 0L

    /**
     * 某首歌的 DIDL-Lite 元数据（推给电视用）。
     *
     * 必须是完整的 DIDL-Lite 根元素：里面的 dc:title / upnp:class 都依赖根元素上
     * 那几个 xmlns 声明，少一个渲染器就会直接报「前缀未绑定」。
     */
    fun didlFor(track: Track, uri: String = mediaUri(track)): String =
        DIDL_OPEN + didlItem(track, uri) + "</DIDL-Lite>"

    // ---------------- HTTP 骨架 ----------------

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val socket = ss.accept()
                val p = pool
                if (p == null) {
                    socket.close()
                } else {
                    p.execute { handleConnection(socket) }
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept 异常：${e.message}")
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30000
                val input = s.getInputStream()
                val head = readHead(input) ?: return
                val (requestLine, headers) = head

                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]
                val path = rawPath.substringBefore('?')

                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBuf = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(bodyBuf, read, len - read)
                    if (n <= 0) break
                    read += n
                }
                val body = String(bodyBuf, 0, read, Charsets.UTF_8)

                route(s, method, path, headers, body)
            } catch (e: Exception) {
                Log.w(TAG, "处理请求出错：${e.message}")
            }
        }
    }

    private fun readHead(input: InputStream): Pair<String, Map<String, String>>? {
        val head = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            val size = head.size()
            if (size >= 4) {
                val arr = head.toByteArray()
                val n = size - 4
                if (arr[n] == CR && arr[n + 1] == LF && arr[n + 2] == CR && arr[n + 3] == LF) {
                    break
                }
            }
            if (size > 65536) return null
        }
        val text = String(head.toByteArray(), Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return requestLine to headers
    }

    private fun route(
        sock: Socket,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
    ) {
        Log.d(TAG, "ROUTE $method $path host=${headers["host"]}")
        val lower = path.lowercase()
        when {
            lower == "/description.xml" || lower.endsWith("/description.xml") ->
                writeText(sock, 200, "text/xml; charset=utf-8", deviceDescription())

            lower.contains("/scpd/") ->
                writeText(sock, 200, "text/xml; charset=utf-8", scpdFor(lower))

            lower.contains("/event/") ->
                handleEvent(sock, method, path, headers)

            method == "POST" && lower.contains("avtransport") ->
                handleSoap(sock, SERVICE_AVT, body)

            method == "POST" && lower.contains("renderingcontrol") ->
                handleSoap(sock, SERVICE_RCS, body)

            method == "POST" && lower.contains("contentdirectory") ->
                handleSoap(sock, SERVICE_CDS, body)

            lower.contains("/media/") ->
                if (method == "GET" || method == "HEAD") {
                    serveMedia(sock, method, path, headers)
                } else {
                    writeText(sock, 405, "text/plain; charset=utf-8", "method not allowed")
                }

            lower == "/" ->
                writeText(
                    sock, 200, "text/html; charset=utf-8",
                    "<html><head><meta charset=\"utf-8\"><title>云音乐</title></head>" +
                        "<body><h1>云音乐 DLNA</h1></body></html>"
                )

            else -> writeText(sock, 404, "text/plain; charset=utf-8", "not found")
        }
    }

    private fun writeText(sock: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(bytes.size).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        val out = BufferedOutputStream(sock.getOutputStream())
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "OK"
    }

    // ---------------- 设备描述 ----------------

    private fun deviceDescription(): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" configId="0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>$DEVICE_TYPE</deviceType>
    <friendlyName>$FRIENDLY_NAME</friendlyName>
    <manufacturer>CloudMusic</manufacturer>
    <manufacturerURL>https://music.163.com</manufacturerURL>
    <modelDescription>云音乐 DLNA 媒体渲染器</modelDescription>
    <modelName>CloudMusic DMR</modelName>
    <modelNumber>1.0</modelNumber>
    <modelURL>https://music.163.com</modelURL>
    <serialNumber>$uuid</serialNumber>
    <UDN>$uuid</UDN>
    <serviceList>
      <service>
        <serviceType>$SERVICE_AVT</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/scpd/avtransport.xml</SCPDURL>
        <controlURL>/control/avtransport</controlURL>
        <eventSubURL>/event/avtransport</eventSubURL>
      </service>
      <service>
        <serviceType>$SERVICE_RCS</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/scpd/renderingcontrol.xml</SCPDURL>
        <controlURL>/control/renderingcontrol</controlURL>
        <eventSubURL>/event/renderingcontrol</eventSubURL>
      </service>
      <service>
        <serviceType>$SERVICE_CDS</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <SCPDURL>/scpd/contentdirectory.xml</SCPDURL>
        <controlURL>/control/contentdirectory</controlURL>
        <eventSubURL>/event/contentdirectory</eventSubURL>
      </service>
    </serviceList>
    <presentationURL>/</presentationURL>
  </device>
</root>"""

    private fun scpdFor(path: String): String {
        val actions: List<Pair<String, List<String>>> = when {
            path.contains("avtransport") -> listOf(
                "SetAVTransportURI" to listOf("InstanceID", "CurrentURI", "CurrentURIMetaData"),
                "SetNextAVTransportURI" to listOf("InstanceID", "NextURI", "NextURIMetaData"),
                "Play" to listOf("InstanceID", "Speed"),
                "Pause" to listOf("InstanceID"),
                "Stop" to listOf("InstanceID"),
                "Seek" to listOf("InstanceID", "Unit", "Target"),
                "GetTransportInfo" to listOf("InstanceID"),
                "GetPositionInfo" to listOf("InstanceID"),
                "GetMediaInfo" to listOf("InstanceID"),
                "GetDeviceCapabilities" to listOf("InstanceID"),
                "GetCurrentConnectionIDs" to listOf("InstanceID")
            )

            path.contains("renderingcontrol") -> listOf(
                "GetVolume" to listOf("InstanceID", "Channel"),
                "SetVolume" to listOf("InstanceID", "Channel", "DesiredVolume"),
                "GetMute" to listOf("InstanceID", "Channel"),
                "SetMute" to listOf("InstanceID", "Channel", "DesiredMute")
            )

            else -> listOf(
                "GetContentDirectory" to listOf(
                    "ObjectID", "BrowseFlag", "Filter", "StartingIndex", "RequestedCount", "SortCriteria"
                ),
                "BrowseMetadata" to listOf(
                    "ObjectID", "Filter", "StartingIndex", "RequestedCount", "SortCriteria"
                )
            )
        }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
        sb.append("<specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
        val vars = linkedSetOf<String>()
        for ((name, args) in actions) {
            sb.append("<action><name>").append(name).append("</name><argumentList>")
            for (a in args) {
                val v = "A_ARG_TYPE_" + a.replaceFirstChar { it.uppercaseChar() }
                vars.add(v)
                sb.append("<argument><name>").append(a)
                    .append("</name><direction>in</direction><relatedStateVariable>")
                    .append(v).append("</relatedStateVariable></argument>")
            }
            sb.append("</argumentList></action>")
        }
        sb.append("</actionList><serviceStateTable>")
        for (v in vars) {
            sb.append("<stateVariable sendEvents=\"no\"><name>").append(v)
                .append("</name><dataType>string</dataType></stateVariable>")
        }
        sb.append("</serviceStateTable></scpd>")
        return sb.toString()
    }

    // ---------------- SOAP ----------------

    private class SoapRequest(val action: String, val args: Map<String, String>)

    private fun handleSoap(sock: Socket, serviceType: String, body: String) {
        val req = parseSoap(body)
        if (req == null) {
            writeText(sock, 500, "text/xml; charset=utf-8", soapFault("解析请求失败"))
            return
        }
        val inner = dispatch(serviceType, req)
        writeText(sock, 200, "text/xml; charset=\"utf-8\"", soapEnvelope(inner))
    }

    private fun parseSoap(body: String): SoapRequest? {
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val doc = factory.newDocumentBuilder()
                .parse(java.io.ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
            val envelope = doc.documentElement
            var bodyNode: org.w3c.dom.Element? = null
            val kids = envelope.childNodes
            for (i in 0 until kids.length) {
                val n = kids.item(i)
                if (n is org.w3c.dom.Element && localName(n) == "Body") {
                    bodyNode = n
                    break
                }
            }
            val bodyEl = bodyNode ?: return null
            var actionEl: org.w3c.dom.Element? = null
            val bodyKids = bodyEl.childNodes
            for (i in 0 until bodyKids.length) {
                val n = bodyKids.item(i)
                if (n is org.w3c.dom.Element) {
                    actionEl = n
                    break
                }
            }
            val action = actionEl ?: return null
            val args = mutableMapOf<String, String>()
            val argKids = action.childNodes
            for (i in 0 until argKids.length) {
                val n = argKids.item(i)
                if (n is org.w3c.dom.Element) {
                    args[localName(n)] = n.textContent?.trim().orEmpty()
                }
            }
            SoapRequest(localName(action), args)
        } catch (e: Exception) {
            Log.w(TAG, "SOAP 解析失败：${e.message}")
            null
        }
    }

    private fun localName(e: org.w3c.dom.Element): String {
        val ln = e.localName
        return if (!ln.isNullOrEmpty()) ln else e.tagName.substringAfter(':')
    }

    private fun soapEnvelope(inner: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append(inner)
        append("</s:Body></s:Envelope>")
    }

    private fun soapFault(reason: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<s:Fault><faultcode>s:Client</faultcode><faultstring>")
        append(xmlEscape(reason))
        append("</faultstring></s:Fault></s:Body></s:Envelope>")
    }

    private fun dispatch(serviceType: String, req: SoapRequest): String {
        return when (serviceType) {
            SERVICE_AVT -> dispatchAvTransport(req)
            SERVICE_RCS -> dispatchRenderingControl(req)
            SERVICE_CDS -> dispatchContentDirectory(req)
            else -> emptyResponse(serviceType, req.action)
        }
    }

    /** 没有返回值的动作也要给一个空的 Response 元素，渲染器认这个 */
    private fun emptyResponse(serviceType: String, action: String): String =
        "<u:${action}Response xmlns:u=\"$serviceType\"/>"

    private fun dispatchAvTransport(req: SoapRequest): String = when (req.action) {
        "SetAVTransportURI" -> {
            currentUri = req.args["CurrentURI"].orEmpty()
            currentMeta = req.args["CurrentURIMetaData"].orEmpty()
            seekBaseMs = 0
            playStartedAt = 0
            setState("STOPPED")
            emptyResponse(SERVICE_AVT, req.action)
        }

        "SetNextAVTransportURI" -> {
            nextUri = req.args["NextURI"].orEmpty()
            nextMeta = req.args["NextURIMetaData"].orEmpty()
            emptyResponse(SERVICE_AVT, req.action)
        }

        "Play" -> {
            setState("PLAYING")
            emptyResponse(SERVICE_AVT, req.action)
        }

        "Pause" -> {
            setState("PAUSED_PLAYBACK")
            emptyResponse(SERVICE_AVT, req.action)
        }

        "Stop" -> {
            setState("STOPPED")
            emptyResponse(SERVICE_AVT, req.action)
        }

        "Seek" -> {
            val unit = req.args["Unit"] ?: "REL_TIME"
            val target = req.args["Target"] ?: "0:00:00"
            val ms = parseHmsMs(target)
            if (unit.equals("ABS_TIME", ignoreCase = true)) seekBaseMs = ms else seekBaseMs += ms
            if (transportState == "PLAYING") playStartedAt = System.currentTimeMillis()
            notifyEventSubscribers()
            emptyResponse(SERVICE_AVT, req.action)
        }

        // 渲染器靠它知道自己能放哪些格式
        "GetProtocolInfo" ->
            "<u:GetProtocolInfoResponse xmlns:u=\"$SERVICE_AVT\">" +
                "<Source>http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*," +
                "http-get:*:audio/x-flac:*,http-get:*:audio/flac:*,http-get:*:*:*</Source>" +
                "<Sink/></u:GetProtocolInfoResponse>"

        "GetTransportInfo" ->
            "<u:GetTransportInfoResponse xmlns:u=\"$SERVICE_AVT\">" +
                "<CurrentTransportState>$transportState</CurrentTransportState>" +
                "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
                "<CurrentSpeed>1</CurrentSpeed></u:GetTransportInfoResponse>"

        "GetPositionInfo" -> {
            val pos = formatHms(positionMs())
            "<u:GetPositionInfoResponse xmlns:u=\"$SERVICE_AVT\">" +
                "<Track>1</Track>" +
                "<TrackDuration>${formatHms(currentDurationMs())}</TrackDuration>" +
                "<TrackMetaData>${xmlEscape(currentMeta)}</TrackMetaData>" +
                "<TrackURI>${xmlEscape(currentUri)}</TrackURI>" +
                "<RelTime>$pos</RelTime><AbsTime>$pos</AbsTime>" +
                "<RelCount>0</RelCount><AbsCount>0</AbsCount>" +
                "</u:GetPositionInfoResponse>"
        }

        "GetMediaInfo" ->
            "<u:GetMediaInfoResponse xmlns:u=\"$SERVICE_AVT\">" +
                "<NrTracks>1</NrTracks>" +
                "<MediaDuration>${formatHms(currentDurationMs())}</MediaDuration>" +
                "<CurrentURI>${xmlEscape(currentUri)}</CurrentURI>" +
                "<CurrentURIMetaData>${xmlEscape(currentMeta)}</CurrentURIMetaData>" +
                "<NextURI>${xmlEscape(nextUri)}</NextURI>" +
                "<NextURIMetaData>${xmlEscape(nextMeta)}</NextURIMetaData>" +
                "<PlayMedium>NETWORK</PlayMedium>" +
                "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>" +
                "<WriteStatus>OK</WriteStatus></u:GetMediaInfoResponse>"

        "GetDeviceCapabilities" ->
            "<u:GetDeviceCapabilitiesResponse xmlns:u=\"$SERVICE_AVT\">" +
                "<PlayMedia>YES</PlayMedia><RecMedia>NO</RecMedia>" +
                "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>" +
                "</u:GetDeviceCapabilitiesResponse>"

        "GetCurrentConnectionIDs" ->
            "<u:GetCurrentConnectionIDsResponse xmlns:u=\"$SERVICE_AVT\">" +
                "<CurrentConnectionIDs>0</CurrentConnectionIDs>" +
                "</u:GetCurrentConnectionIDsResponse>"

        else -> emptyResponse(SERVICE_AVT, req.action)
    }

    private fun dispatchRenderingControl(req: SoapRequest): String = when (req.action) {
        "GetVolume" ->
            "<u:GetVolumeResponse xmlns:u=\"$SERVICE_RCS\">" +
                "<CurrentVolume>$volume</CurrentVolume></u:GetVolumeResponse>"

        "SetVolume" -> {
            volume = req.args["DesiredVolume"]?.toIntOrNull() ?: volume
            emptyResponse(SERVICE_RCS, req.action)
        }

        "GetMute" ->
            "<u:GetMuteResponse xmlns:u=\"$SERVICE_RCS\">" +
                "<CurrentMute>${if (muted) 1 else 0}</CurrentMute></u:GetMuteResponse>"

        "SetMute" -> {
            val want = req.args["DesiredMute"].orEmpty()
            muted = want == "1" || want.equals("true", ignoreCase = true)
            emptyResponse(SERVICE_RCS, req.action)
        }

        else -> emptyResponse(SERVICE_RCS, req.action)
    }

    private fun dispatchContentDirectory(req: SoapRequest): String = when (req.action) {
        "GetContentDirectory", "BrowseMetadata", "BrowseDirectChildren" -> {
            val objectId = req.args["ObjectID"] ?: "0"
            val flag = req.args["BrowseFlag"] ?: "BrowseDirectChildren"
            val (didl, count) = if (objectId == "0" && flag == "BrowseMetadata") {
                rootContainer() to 1
            } else {
                contentDidl()
            }
            "<u:GetContentDirectoryResponse xmlns:u=\"$SERVICE_CDS\"><Result>" +
                "<NumberReturned>$count</NumberReturned>" +
                "<TotalMatches>$count</TotalMatches>" +
                "<UpdateID>1</UpdateID>" +
                "<ResultList>$didl</ResultList>" +
                "</Result></u:GetContentDirectoryResponse>"
        }

        "GetSearchCapabilities" ->
            "<u:GetSearchCapabilitiesResponse xmlns:u=\"$SERVICE_CDS\">" +
                "<SearchCaps>dc:title,dc:creator,upnp:album</SearchCaps>" +
                "</u:GetSearchCapabilitiesResponse>"

        "GetSortCapabilities" ->
            "<u:GetSortCapabilitiesResponse xmlns:u=\"$SERVICE_CDS\">" +
                "<SortCaps>dc:title,dc:creator,upnp:album</SortCaps>" +
                "</u:GetSortCapabilitiesResponse>"

        else -> emptyResponse(SERVICE_CDS, req.action)
    }

    private fun rootContainer(): String =
        DIDL_OPEN +
            "<container id=\"0\" parentID=\"-1\" restricted=\"1\" searchable=\"0\">" +
            "<dc:title>${xmlEscape(FRIENDLY_NAME)}</dc:title>" +
            "<upnp:class>object.container.storageFolder</upnp:class>" +
            "</container></DIDL-Lite>"

    /** 把整个曲库作为 ContentDirectory 的子项吐给电视 */
    private fun contentDidl(): Pair<String, Int> {
        val tracks = tracksProvider()
        val sb = StringBuilder()
        sb.append(DIDL_OPEN)
        var n = 0
        for (t in tracks) {
            if (n >= 500) break
            sb.append(didlItem(t, mediaUri(t)))
            n++
        }
        sb.append("</DIDL-Lite>")
        return sb.toString() to n
    }

    private fun didlItem(track: Track, uri: String): String {
        val duration = if (track.durationSec > 0) formatHms((track.durationSec * 1000).toLong()) else "0:00:00"
        val size = if (track.size > 0) " size=\"${track.size}\"" else ""
        return "<item id=\"${xmlEscape(track.key)}\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(track.title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<dc:creator>${xmlEscape(track.artist)}</dc:creator>" +
            "<upnp:album>${xmlEscape(track.album)}</upnp:album>" +
            "<res protocolInfo=\"http-get:*:${xmlEscape(track.mime.ifBlank { "audio/mpeg" })}:*\"" +
            "$size duration=\"$duration\">${xmlEscape(uri)}</res>" +
            "</item>"
    }

    // ---------------- 状态 ----------------

    private fun setState(newState: String) {
        val old = transportState
        if (old == "PLAYING" && newState != "PLAYING") {
            seekBaseMs = positionMs()
        }
        if (newState == "PLAYING") {
            playStartedAt = System.currentTimeMillis()
        }
        if (newState == "STOPPED") {
            seekBaseMs = 0
        }
        transportState = newState
        if (old != newState) {
            onStateChanged?.invoke(newState)
            notifyEventSubscribers()
        }
    }

    private fun positionMs(): Long {
        return if (transportState == "PLAYING") {
            seekBaseMs + (System.currentTimeMillis() - playStartedAt)
        } else {
            seekBaseMs
        }
    }

    private fun currentDurationMs(): Long {
        val key = keyOf(currentUri)
        if (key.isEmpty()) return 0
        val track = tracksProvider().firstOrNull { it.key == key }
            ?: castTrack?.takeIf { it.key == key } ?: return 0
        return (track.durationSec * 1000).toLong()
    }

    private fun keyOf(uri: String): String {
        if (uri.isEmpty()) return ""
        val raw = uri.substringAfter("/media/", "")
        if (raw.isEmpty() || raw == uri) return ""
        return try {
            URLDecoder.decode(raw.substringBefore('?'), "UTF-8")
        } catch (e: Exception) {
            raw
        }
    }

    /** 取 URL 上的查询参数，比如 `/media/xxx?start=1234` 里的 start */
    private fun queryOf(path: String, name: String): String? {
        val q = path.substringAfter('?', "")
        if (q.isEmpty()) return null
        for (pair in q.split('&')) {
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == name) {
                return try {
                    URLDecoder.decode(kv[1], "UTF-8")
                } catch (e: Exception) {
                    kv[1]
                }
            }
        }
        return null
    }

    private fun parseHmsMs(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0
        val parts = t.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].trim().toLong()
                    val m = parts[1].trim().toLong()
                    val sec = parts[2].trim().toDouble()
                    (h * 3600 + m * 60 + sec.toLong()) * 1000
                }

                2 -> {
                    val m = parts[0].trim().toLong()
                    val sec = parts[1].trim().toDouble()
                    (m * 60 + sec.toLong()) * 1000
                }

                else -> (t.toDouble() * 1000).toLong()
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun formatHms(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    }

    // ---------------- 媒体代理 ----------------

    /** 探测上游总大小（track.size 未知时，用于把毫秒偏移换算成字节）。 */
    private fun probeContentLength(url: String, extra: Map<String, String>): Long? {
        return try {
            val req = Request.Builder().url(url)
                .header("Range", "bytes=0-1")
                .header("User-Agent", BROWSER_UA)
            for ((k, v) in extra) req.header(k, v)
            http.newCall(req.build()).execute().use { resp ->
                resp.header("Content-Range")?.let { cr ->
                    val i = cr.lastIndexOf('/')
                    if (i >= 0) cr.substring(i + 1).toLongOrNull() else null
                } ?: resp.header("Content-Length")?.toLongOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun serveMedia(
        sock: Socket,
        method: String,
        path: String,
        headers: Map<String, String>,
    ) {
        val key = keyOf(path)
        val track = tracksProvider().firstOrNull { it.key == key }
            ?: castTrack?.takeIf { it.key == key }
        if (track == null) {
            writeText(sock, 404, "text/plain; charset=utf-8", "找不到这首歌")
            return
        }
        val url = track.url
        if (url.isBlank()) {
            writeText(sock, 404, "text/plain; charset=utf-8", "这首歌没有可播放地址")
            return
        }

        val builder = Request.Builder().url(url)
        // 起始偏移优先于设备自己发的 Range ——
        // 设备重拉时会发 Range: bytes=0-，但我们要从用户拖到的位置开始给。
        // 注意 setStartOffset / ?start= 记的都是「毫秒」，HTTP Range 要的是「字节」，
        // 这里按平均码率换算一次，否则像小米这类设备会几乎从头播。
        val durMs = (track.durationSec * 1000.0).toLong().coerceAtLeast(1L)
        // 在线歌等未知文件大小时，先探测上游总大小（才能把毫秒偏移换算成字节）
        var sizeB = track.size.toLong()
        if (sizeB <= 0) {
            sizeB = sizeCache[track.key] ?: run {
                val p = probeContentLength(url, track.extraHeaders)
                if (p != null) sizeCache[track.key] = p
                p ?: 0L
            }
        }
        fun msToByte(ms: Long): Long =
            if (sizeB > 0 && durMs > 0) (ms * sizeB / durMs).coerceIn(0L, sizeB) else 0
        val offsetMs = startOffsetOf(track.key)
        // 无时长断点：用「上次已发字节数」做字节级偏移（不需要时长也能续播）
        val offsetBytes = startOffsetBytesOf(track.key)
        val range = when {
            offsetBytes > 0 -> "bytes=$offsetBytes-"
            offsetMs > 0 -> msToByte(offsetMs).takeIf { it > 0 }?.let { "bytes=$it-" }
            else -> null
        } ?: headers["range"]?.takeIf { it.isNotBlank() }
            ?: queryOf(path, "start")?.toLongOrNull()?.takeIf { it > 0 }
                ?.let { msToByte(it) }?.let { "bytes=$it-" }
        range?.let { builder.header("Range", it) }
        // 探测请求（bytes=0-0 / 0-1）只是测大小，不算已播放进度
        val isProbe = range == "bytes=0-0" || range == "bytes=0-1"
        // 本次代理实际写给设备的字节数（暂停重拉时作为字节级断点起点）
        var sent = 0L
        Log.d(TAG, "serveMedia GET key=$key durSec=${track.durationSec} offsetMs=$offsetMs offsetBytes=$offsetBytes finalRange=$range devRange=${headers["range"]}")
        StreamAuth.header(prefs, hostOf(url))?.let { builder.header("Authorization", it) }
        // 用常规浏览器 UA：部分 CDN（如 QQ 音乐）会拦截非常规 UA 直接 403
        builder.header("User-Agent", BROWSER_UA)
        // 转发平台防盗链 header（QQ/网易云直链常需要 Referer/Cookie 等才放行，
        // 否则上游直接 403）。放最后，让平台自己的 header 优先于默认 UA。
        for ((k, v) in track.extraHeaders) {
            builder.header(k, v)
        }

        try {
            http.newCall(builder.build()).execute().use { resp ->
                val out = BufferedOutputStream(sock.getOutputStream())
                val head = StringBuilder()
                head.append("HTTP/1.1 ").append(resp.code).append(' ')
                    .append(reason(resp.code)).append("\r\n")
                head.append("Content-Type: ").append(contentTypeOf(resp, track)).append("\r\n")
                resp.header("Content-Range")?.let { head.append("Content-Range: ").append(it).append("\r\n") }
                head.append("Accept-Ranges: bytes\r\n")

                val upstreamLen = resp.header("Content-Length")
                Log.d(TAG, "serveMedia upstream code=${resp.code} hasLen=${upstreamLen != null}")
                if (upstreamLen != null) {
                    head.append("Content-Length: ").append(upstreamLen).append("\r\n")
                    head.append("Connection: close\r\n\r\n")
                    out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                    if (method != "HEAD") {
                        resp.body?.byteStream()?.use { ins -> sent += copy(ins, out, key, isProbe) }
                    }
                } else {
                    // 上游是分块传输的。我们自己重新分块，
                    // 别让渲染器对着一个既没长度也没分块头的响应干等到超时。
                    head.append("Transfer-Encoding: chunked\r\n")
                    head.append("Connection: close\r\n\r\n")
                    out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                    if (method != "HEAD") {
                        resp.body?.byteStream()?.use { ins ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n <= 0) break
                                sent += n
                                if (!isProbe) bytesSent[key] = sent
                                out.write("${Integer.toHexString(n)}\r\n".toByteArray(Charsets.ISO_8859_1))
                                out.write(buf, 0, n)
                                out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                            }
                        }
                        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    }
                }
                out.flush()
                // 记下本次发给设备的字节数（暂停后重拉时作为字节级断点起点）
                if (sent > 1024) bytesSent[key] = sent
            }
        } catch (e: Exception) {
            Log.w(TAG, "媒体代理失败：${e.message}")
            // 设备暂停会中断连接，这里 sent 已是断点处已发字节
            if (sent > 1024) bytesSent[key] = sent
            try {
                writeText(sock, 502, "text/plain; charset=utf-8", "读取网盘失败")
            } catch (_: Exception) {
            }
        }
    }

    private fun copy(ins: java.io.InputStream, out: java.io.OutputStream, key: String, isProbe: Boolean): Long {
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
            // 实时记下已发字节（暂停后重拉时作为断点起点），探测请求不记
            if (!isProbe) bytesSent[key] = total
        }
        return total
    }

    /**
     * 内容类型优先用网盘给的，但网盘常返回 application/octet-stream，
     * 那种渲染器不认，得换成歌曲自己的类型。
     */
    private fun contentTypeOf(resp: okhttp3.Response, track: Track): String {
        val upstream = resp.header("Content-Type").orEmpty()
        if (upstream.startsWith("audio/", true) || upstream.startsWith("video/", true)) {
            return upstream.substringBefore(';').trim()
        }
        return track.mime.ifBlank { "audio/mpeg" }
    }

    private fun hostOf(url: String): String {
        var rest = url.trim()
        val idx = rest.indexOf("://")
        if (idx >= 0) rest = rest.substring(idx + 3)
        val authority = rest.substringBefore('/')
        return authority.substringBefore(':').substringAfterLast('@')
    }

    // ---------------- 事件订阅（GENA）----------------

    private fun handleEvent(
        sock: Socket,
        method: String,
        path: String,
        headers: Map<String, String>,
    ) {
        when (method.uppercase()) {
            "SUBSCRIBE" -> {
                val callback = headers["callback"]
                val sid = "uuid:" + UUID.randomUUID()
                val service = when {
                    path.contains("avtransport") -> SERVICE_AVT
                    path.contains("renderingcontrol") -> SERVICE_RCS
                    else -> SERVICE_CDS
                }
                if (!callback.isNullOrBlank()) {
                    subscribers[callback] = Subscriber(callback, sid, service)
                }
                val head = StringBuilder()
                head.append("HTTP/1.1 200 OK\r\n")
                head.append("SID: ").append(sid).append("\r\n")
                head.append("TIMEOUT: Second-1800\r\n")
                head.append("Connection: close\r\n\r\n")
                sock.getOutputStream().write(head.toString().toByteArray(Charsets.ISO_8859_1))
                sock.getOutputStream().flush()
            }

            "UNSUBSCRIBE" -> {
                headers["callback"]?.let { subscribers.remove(it) }
                writeText(sock, 200, "text/plain; charset=utf-8", "")
            }

            else -> writeText(sock, 405, "text/plain; charset=utf-8", "method not allowed")
        }
    }

    private fun notifyEventSubscribers() {
        val lastChange = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">" +
            "<InstanceID val=\"0\"><TransportState val=\"$transportState\"/></InstanceID></Event>"
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            "<e:property><LastChange>${xmlEscape(lastChange)}</LastChange></e:property>" +
            "</e:propertyset>"
        for (sub in subscribers.values) {
            if (sub.service != SERVICE_AVT) continue
            try {
                LanHttp.request(
                    url = sub.callback,
                    method = "NOTIFY",
                    body = body,
                    contentType = "text/xml; charset=\"utf-8\"",
                    timeoutMs = 3000,
                    extraHeaders = mapOf(
                        "NT" to "upnp:event",
                        "NTS" to "upnp:propchange",
                        "SID" to sub.sid
                    )
                )
            } catch (e: Exception) {
                // 通知失败无所谓，电视会自己轮询
            }
        }
    }

    // ---------------- 网络 ----------------

    private fun findLanIp(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
        var fallback = ""
        for (ni in interfaces) {
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (addr.isSiteLocalAddress) return ip
                    if (fallback.isEmpty()) fallback = ip
                }
            }
        }
        return fallback
    }
}
