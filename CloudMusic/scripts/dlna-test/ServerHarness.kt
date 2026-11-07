import com.cloudmusic.app.data.Prefs
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.dlna.DlnaServer
import com.cloudmusic.app.data.dlna.LanHttp
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.OkHttpClient
import org.w3c.dom.Element

/**
 * DLNA 服务端（手机当渲染器）协议自测。
 *
 * 做法：
 * 1. 起一个假「网盘」HTTP 服务，提供一首带 Range 支持的音频
 * 2. 起真正的 DlnaServer，让它把这首歌代理出去
 * 3. 模拟电视去做一遍：取设备描述、SOAP 控制、拉流（还要能拖进度条）
 */

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("  PASS  $name")
    } else {
        failures++
        println("  FAIL  $name  $detail")
    }
}

private val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

private fun parseXml(text: String): Element =
    factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        .documentElement

private fun childText(parent: Element, name: String): String {
    val kids = parent.childNodes
    for (i in 0 until kids.length) {
        val c = kids.item(i)
        if (c is Element) {
            val ln = c.localName ?: c.tagName.substringAfter(':')
            if (ln.equals(name, ignoreCase = true) ||
                c.tagName.equals(name, ignoreCase = true)
            ) return c.textContent?.trim().orEmpty()
        }
    }
    // 找不到就往下再找一层（设备描述里 friendlyName 埋在 device 下面）
    for (i in 0 until kids.length) {
        val c = kids.item(i)
        if (c is Element) {
            val found = childText(c, name)
            if (found.isNotEmpty()) return found
        }
    }
    return ""
}

private fun xmlEscape(s: String): String = buildString {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}

private fun soapBody(serviceType: String, action: String, args: List<Pair<String, String>>): String =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
        "<u:$action xmlns:u=\"$serviceType\">" +
        args.joinToString("") { "<${it.first}>${xmlEscape(it.second)}</${it.first}>" } +
        "</u:$action></s:Body></s:Envelope>"

private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
private const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
private const val CDS = "urn:schemas-upnp-org:service:ContentDirectory:1"
fun main() {
    val audio = ByteArray(100000) { (it % 251).toByte() }
    val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    origin.createContext("/song.mp3") { ex ->
        val range = ex.requestHeaders.getFirst("Range")
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=")
            val parts = spec.split("-")
            val start = parts[0].trim().toInt()
            val end = if (parts.size > 1 && parts[1].isNotBlank()) {
                parts[1].trim().toInt()
            } else {
                audio.size - 1
            }
            val len = end - start + 1
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${audio.size}")
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.sendResponseHeaders(206, len.toLong())
            ex.responseBody.write(audio, start, len)
            ex.responseBody.close()
        } else {
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.sendResponseHeaders(200, audio.size.toLong())
            ex.responseBody.write(audio)
            ex.responseBody.close()
        }
    }
    origin.start()
    val originPort = origin.address.port
    println("[0] 假网盘已启动 127.0.0.1:$originPort")

    val track = Track(
        key = "webdav:/音乐/稻香 &晴天.mp3",
        id = "/音乐/稻香 &晴天.mp3",
        url = "http://127.0.0.1:$originPort/song.mp3",
        fileName = "稻香 &晴天.mp3",
        title = "稻香 &晴天",
        artist = "周杰伦",
        album = "魔杰座",
        folder = "/音乐",
        size = audio.size.toLong(),
        mime = "audio/mpeg",
        durationSec = 200.0
    )

    val server = DlnaServer(
        context = android.content.Context(),
        prefs = Prefs(),
        http = OkHttpClient.Builder().build(),
        tracksProvider = { listOf(track) }
    )
    check("服务端能启动", server.start(), "启动失败")
    val base = "http://127.0.0.1:${server.port}"

    println("[1] 设备描述")
    val desc = LanHttp.get("$base/description.xml", 5000)
    check("description.xml 返回 200", desc.code == 200, "code=${desc.code}")
    val descXml = parseXml(desc.text)
    check("friendlyName = 云音乐", childText(descXml, "friendlyName") == "云音乐", childText(descXml, "friendlyName"))
    check(
        "deviceType 是 MediaRenderer",
        childText(descXml, "deviceType") == "urn:schemas-upnp-org:device:MediaRenderer:1",
        childText(descXml, "deviceType")
    )
    val services = descXml.getElementsByTagName("service")
    check("一共 3 个服务", services.length == 3, "实际 ${services.length}")
    val controlUrls = (0 until services.length).map {
        childText(services.item(it) as Element, "controlURL")
    }
    check(
        "三个 controlURL 都在",
        controlUrls.containsAll(
            listOf("/control/avtransport", "/control/renderingcontrol", "/control/contentdirectory")
        ),
        controlUrls.toString()
    )

    println("[2] SCPD")
    val scpd = LanHttp.get("$base/scpd/avtransport.xml", 5000)
    check("SCPD 返回 200", scpd.code == 200, "code=${scpd.code}")
    check("SCPD 内容对", scpd.text.contains("<scpd") && scpd.text.contains("SetAVTransportURI"))

    println("[3] SOAP 控制")
    val mediaUrl = "$base/media/${URLEncoder.encode(track.key, "UTF-8")}"
    val setUri = LanHttp.postSoap(
        "$base/control/avtransport",
        soapBody(
            AVT, "SetAVTransportURI",
            listOf(
                "InstanceID" to "0",
                "CurrentURI" to mediaUrl,
                "CurrentURIMetaData" to server.didlFor(track)
            )
        ),
        5000
    )
    check("SetAVTransportURI 返回 200", setUri.code == 200, "code=${setUri.code}")
    check(
        "响应里有 SetAVTransportURIResponse",
        setUri.text.contains("SetAVTransportURIResponse"),
        setUri.text.take(200)
    )
    val info0 = LanHttp.postSoap(
        "$base/control/avtransport",
        soapBody(AVT, "GetTransportInfo", listOf("InstanceID" to "0")),
        5000
    )
    check("初始状态是 STOPPED", info0.text.contains("<CurrentTransportState>STOPPED</CurrentTransportState>"))

    LanHttp.postSoap(
        "$base/control/avtransport",
        soapBody(AVT, "Play", listOf("InstanceID" to "0", "Speed" to "1")),
        5000
    )
    val info1 = LanHttp.postSoap(
        "$base/control/avtransport",
        soapBody(AVT, "GetTransportInfo", listOf("InstanceID" to "0")),
        5000
    )
    check("播放后状态是 PLAYING", info1.text.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"))

    val pos = LanHttp.postSoap(
        "$base/control/avtransport",
        soapBody(AVT, "GetPositionInfo", listOf("InstanceID" to "0")),
        5000
    )
    check("GetPositionInfo 有返回", pos.code == 200 && pos.text.contains("TrackDuration"))
    check(
        "时长对得上（200 秒 -> 0:03:20）",
        pos.text.contains("<TrackDuration>0:03:20</TrackDuration>"),
        pos.text.take(400)
    )

    val vol = LanHttp.postSoap(
        "$base/control/renderingcontrol",
        soapBody(RCS, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")),
        5000
    )
    check("GetVolume 有返回", vol.text.contains("<CurrentVolume>"))

    println("[4] 拉流（媒体代理）")
    val full = LanHttp.get(mediaUrl, 8000)
    check("拉流返回 200", full.code == 200, "code=${full.code}")
    check("内容长度对", full.body.size == audio.size, "${full.body.size} != ${audio.size}")
    check(
        "Content-Type 对",
        (full.header("content-type") ?: "").contains("audio/mpeg"),
        full.header("content-type").orEmpty()
    )
    check("内容字节一致", full.body.contentEquals(audio))
    check("声明支持 Range", full.header("accept-ranges") == "bytes", full.header("accept-ranges").orEmpty())

    val part = LanHttp.request(mediaUrl, "GET", null, null, 8000, mapOf("Range" to "bytes=100-199"))
    check("拖进度条返回 206", part.code == 206, "code=${part.code}")
    check(
        "Content-Range 对",
        part.header("content-range") == "bytes 100-199/${audio.size}",
        part.header("content-range").orEmpty()
    )
    check("只返回了 100 字节", part.body.size == 100, "${part.body.size}")
    check("字节内容对", part.body.contentEquals(audio.copyOfRange(100, 200)))

    println("[4b] HEAD 探测与分块上游")
    val headResp = LanHttp.request(mediaUrl, "HEAD", null, null, 8000)
    check("HEAD 返回 200", headResp.code == 200, "code=${headResp.code}")
    check("HEAD 带上 Content-Length", headResp.header("content-length") == "${audio.size}", headResp.header("content-length").orEmpty())
    check("HEAD 不带 body", headResp.body.isEmpty(), "${headResp.body.size} 字节")

    // 假网盘再开一个「分块传输」的地址，模拟网盘不给 Content-Length 的情况
    origin.createContext("/chunked-song.mp3") { ex ->
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.write(audio)
        ex.responseBody.close()
    }
    val chunkTrack = track.copy(
        key = "webdav:/音乐/分块.mp3",
        url = "http://127.0.0.1:$originPort/chunked-song.mp3"
    )
    val chunkServer = DlnaServer(
        context = android.content.Context(),
        prefs = Prefs(),
        http = OkHttpClient.Builder().build(),
        tracksProvider = { listOf(chunkTrack) }
    )
    chunkServer.start()
    val chunkUrl = "http://127.0.0.1:${chunkServer.port}/media/" +
        URLEncoder.encode(chunkTrack.key, "UTF-8")
    val chunkResp = LanHttp.get(chunkUrl, 8000)
    check("上游分块时也能拉到", chunkResp.code == 200, "code=${chunkResp.code}")
    check("内容长度对", chunkResp.body.size == audio.size, "${chunkResp.body.size} != ${audio.size}")
    check("字节内容一致", chunkResp.body.contentEquals(audio))
    chunkServer.stop()
    println("[5] 曲库浏览")
    val browse = LanHttp.postSoap(
        "$base/control/contentdirectory",
        soapBody(
            CDS, "GetContentDirectory",
            listOf(
                "ObjectID" to "0",
                "BrowseFlag" to "BrowseDirectChildren",
                "Filter" to "*",
                "StartingIndex" to "0",
                "RequestedCount" to "100",
                "SortCriteria" to ""
            )
        ),
        5000
    )
    check("ContentDirectory 返回 200", browse.code == 200, "code=${browse.code}")
    val didl = browse.text.substringAfter("<ResultList>").substringBefore("</ResultList>")
    try {
        val didlXml = parseXml(didl)
        check("DIDL 是合法 XML", true)
        check("DIDL 里有歌名", childText(didlXml, "dc:title") == "稻香 &晴天", childText(didlXml, "dc:title"))
        check("DIDL 里的音频地址对", didl.contains("media/"))
    } catch (e: Exception) {
        check("DIDL 是合法 XML", false, e.toString())
    }

    println("[6] 杂项")
    val notFound = LanHttp.get("$base/nope", 5000)
    check("未知路径返回 404", notFound.code == 404, "code=${notFound.code}")

    server.stop()
    origin.stop(0)

    println("")
    if (failures == 0) {
        println("全部通过")
    } else {
        println("有 $failures 项没通过")
    }
    System.exit(if (failures == 0) 0 else 1)
}