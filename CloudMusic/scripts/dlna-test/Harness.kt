import com.cloudmusic.app.data.dlna.DlnaClient
import com.cloudmusic.app.data.dlna.LanHttp
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * DLNA 客户端协议自测。
 *
 * 跑法（见 run-dlna-test.ps1）：
 * 1. 先起 mock-renderer.mjs（假电视）
 * 2. 再跑这个程序，它去发现设备、推歌、问状态、调音量
 * 3. 最后检查假电视收到的 SOAP 报文是不是合法的
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

private fun textOf(root: Element, tag: String): String {
    val nodes = root.getElementsByTagName(tag)
    if (nodes.length == 0) return ""
    val n = nodes.item(0)
    return n.textContent?.trim().orEmpty()
}

/** 找到 SOAP Body 里第一个动作元素（注意 Body 带命名空间前缀，得按 localName 找） */
private fun actionElement(root: Element): Element? {
    val kids = root.childNodes
    for (i in 0 until kids.length) {
        val n = kids.item(i)
        if (n is Element && (n.localName ?: n.tagName.substringAfter(':')) == "Body") {
            val inner = n.childNodes
            for (j in 0 until inner.length) {
                val m = inner.item(j)
                if (m is Element) return m
            }
        }
    }
    return null
}

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "out" })

    println("[1] SSDP 发现")
    val client = DlnaClient()
    val devices = client.discover(4000)
    check("搜到 1 台设备", devices.size == 1, "实际 ${devices.size}")
    val device = devices.firstOrNull()
    if (device == null) {
        println("没有设备，退出")
        System.exit(1)
        return
    }
    check("设备名 = 测试电视", device.friendlyName == "测试电视", device.friendlyName)
    check("UDN 正确", device.udn == "uuid:mock-tv-0001", device.udn)
    check(
        "相对 controlURL 拼成了绝对地址",
        device.avTransportUrl.startsWith("http://127.0.0.1:") &&
            device.avTransportUrl.endsWith("/control/avtransport"),
        device.avTransportUrl
    )

    println("[2] 推送播放")
    val meta = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="webdav:/a.mp3" parentID="0" restricted="1"><dc:title>稻香</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><dc:creator>周杰伦</dc:creator><res protocolInfo="http-get:*:audio/mpeg:*" duration="0:03:20">http://192.168.1.9:1234/media/webdav%3A%2Fa.mp3</res></item></DIDL-Lite>"""
    // 假电视是「严格模式」：带 CurrentURIMetaData 的 SetAVTransportURI 会被拒，
    // 客户端必须自己降级成不带元数据再推一次，否则推不进去。
    val pushed = client.push(device, "http://192.168.1.9:1234/media/webdav%3A%2Fa.mp3", meta)
    check("SetAVTransportURI + Play 成功", pushed, client.lastError.orEmpty())
    check("失败原因没留在屏幕上", client.lastError == null, client.lastError.orEmpty())

    println("[3] 查询播放状态")
    val state = client.transportState(device)
    check("状态是 PLAYING", state == "PLAYING", state.orEmpty())

    println("[4] 设置音量")
    check("SetVolume 成功", client.setVolume(device, 42))

    println("[5] HTTP 响应解析")
    val base = device.location.substringBeforeLast('/')
    val chunked = LanHttp.get("$base/chunked", 5000)
    println("      chunked 响应头: ${chunked.headers}")
    check("chunked 能正确解开", chunked.text == "Hello World", "实际「${chunked.text}」")

    val started = System.currentTimeMillis()
    val keep = LanHttp.get("$base/keepalive", 5000)
    val elapsed = System.currentTimeMillis() - started
    check("对方不关连接也能立刻返回", keep.text == "keep-alive-body", "实际「${keep.text}」")
    check("没有等到超时", elapsed < 2000, "用了 ${elapsed}ms")

    println("[6] 校验假电视收到的 SOAP 报文")
    val files = outDir.listFiles { f -> f.name.endsWith(".xml") }?.sortedBy { it.name } ?: emptyList()
    // Stop + SetAVTransportURI(带元数据，被拒) + SetAVTransportURI(降级) + Play
    // + GetTransportInfo + SetVolume
    check("收到 6 条报文", files.size == 6, "实际 ${files.size}")

    if (files.size >= 6) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true

        fun actionOf(file: File): String? {
            val doc = factory.newDocumentBuilder().parse(file)
            return actionElement(doc.documentElement)?.localName
        }

        check("第 1 条是 Stop", actionOf(files[0]) == "Stop", actionOf(files[0]).orEmpty())
        check("第 2 条是 SetAVTransportURI（带元数据）", actionOf(files[1]) == "SetAVTransportURI")
        check("第 3 条是 SetAVTransportURI（降级重试）", actionOf(files[2]) == "SetAVTransportURI")
        check("第 4 条是 Play", actionOf(files[3]) == "Play", actionOf(files[3]).orEmpty())

        val setUriDoc = factory.newDocumentBuilder().parse(files[1])
        val uri = textOf(setUriDoc.documentElement, "CurrentURI")
        check("CurrentURI 传对了", uri == "http://192.168.1.9:1234/media/webdav%3A%2Fa.mp3", uri)

        // 元数据是塞在 XML 文本里的，取出来还得是合法的 DIDL
        val rawMeta = textOf(setUriDoc.documentElement, "CurrentURIMetaData")
        check("CurrentURIMetaData 没丢", rawMeta.contains("dc:title"))
        try {
            val didlDoc = factory.newDocumentBuilder()
                .parse(rawMeta.byteInputStream())
            val title = textOf(didlDoc.documentElement, "dc:title")
            check("DIDL 本身是合法 XML，标题 = 稻香", title == "稻香", title)
        } catch (e: Exception) {
            check("DIDL 本身是合法 XML", false, e.message.orEmpty())
        }

        val retryDoc = factory.newDocumentBuilder().parse(files[2])
        check("降级那次的 CurrentURI 还在", textOf(retryDoc.documentElement, "CurrentURI").isNotBlank())

        val infoDoc = factory.newDocumentBuilder().parse(files[4])
        check("第 5 条是 GetTransportInfo", actionElement(infoDoc.documentElement)?.localName == "GetTransportInfo")

        val volDoc = factory.newDocumentBuilder().parse(files[5])
        check("第 6 条是 SetVolume", actionElement(volDoc.documentElement)?.localName == "SetVolume")
        check("音量传的是 42", textOf(volDoc.documentElement, "DesiredVolume") == "42")
    }

    println("[7] 校验请求头")
    val headersFile = File(outDir, "headers.log")
    check("记录了请求头", headersFile.exists())
    if (headersFile.exists()) {
        val lines = headersFile.readLines().filter { it.isNotBlank() }
        check("6 条请求都有头", lines.size == 6, "实际 ${lines.size}")
        val bad = lines.filter { line ->
            val parts = line.split("|")
            val action = parts.getOrNull(0).orEmpty()
            val soapAction = parts.getOrNull(1).orEmpty()
            // SOAPACTION 必须写成 "服务类型#动作名"，缺了整个请求会被设备拒掉
            !soapAction.endsWith("#$action\"") || !soapAction.startsWith("\"")
        }
        check("每条都带对了 SOAPACTION", bad.isEmpty(), bad.toString())
        val badCt = lines.filter { !it.split("|").getOrNull(2).orEmpty().contains("text/xml") }
        check("Content-Type 都是 text/xml", badCt.isEmpty(), badCt.toString())
    }

    println("")
    if (failures == 0) {
        println("全部通过")
    } else {
        println("有 $failures 项没通过")
    }
    System.exit(if (failures == 0) 0 else 1)
}
