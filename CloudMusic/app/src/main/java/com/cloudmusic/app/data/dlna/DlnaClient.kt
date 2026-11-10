package com.cloudmusic.app.data.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * 局域网里的一台渲染器（电视、音箱、投影仪……）。
 */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val location: String,
    val avTransportUrl: String,
    val renderingControlUrl: String,
    /**
     * 备选控制地址。
     *
     * 设备描述里的 controlURL 写法五花八门：有的写绝对路径，有的写相对路径，
     * 而相对路径该以哪一段为基准，不同厂商不一样（小米音箱就有两种）。
     * 主地址不通时就挨个试。
     */
    val avTransportUrls: List<String> = emptyList(),
)

/**
 * DLNA 控制端（手机当遥控器用）。
 *
 * 流程：SSDP 广播 M-SEARCH 找渲染器 → 取设备描述 XML 拿到控制地址 →
 * 用 SOAP 让它播放我们的地址。
 */
class DlnaClient(private val context: Context? = null) {

    companion object {
        private const val TAG = "DlnaClient"
        private const val GROUP = "239.255.255.250"
        private const val PORT = 1900
        const val SERVICE_AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val SERVICE_RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    }

    /**
     * 搜索局域网里的媒体渲染器。
     *
     * @param timeoutMs 搜索时长。SSDP 的回复很慢（设备可能隔几秒才回），给 4 秒比较稳。
     */
    fun discover(timeoutMs: Long = 4000): List<DlnaDevice> {
        val locations = linkedSetOf<String>()
        val lock = acquireMulticastLock()
        Log.d(TAG, "discover 开始 timeout=$timeoutMs 多播锁=${lock != null}")
        try {
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 1000

                val group = InetAddress.getByName(GROUP)
                // 必须加入组播组：不少设备（小米某些型号就是）只做组播回复，
                // 不加这一句就永远搜不到它们。
                try {
                    socket.joinGroup(group)
                } catch (e: Exception) {
                    Log.w(TAG, "加入组播组失败，只靠单播回复了：${e.message}")
                }

                val query = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $GROUP:$PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(query, query.size, group, PORT))
                Log.d(TAG, "已发出 M-SEARCH")

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        Log.w(TAG, "接收异常中断：${e.message}")
                        break
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1)
                    // 只认媒体渲染器，别把打印机、路由器的回复也当成电视
                    val st = headerOf(text, "ST") ?: continue
                    if (!st.contains("MediaRenderer", ignoreCase = true)) continue
                    val location = headerOf(text, "LOCATION") ?: continue
                    Log.d(TAG, "收到渲染器回复 location=$location")
                    if (location.isNotBlank()) locations.add(location.trim())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "搜索设备失败：${e.message}")
        } finally {
            lock?.release()
        }

        Log.d(TAG, "discover 结束：location 数=${locations.size}")
        return locations.mapNotNull { u ->
            describe(u).also { if (it == null) Log.w(TAG, "describe 解析失败：$u") }
        }.distinctBy { it.udn }
    }

    /**
     * Android 上收组播要拿组播锁，不然系统可能直接把包丢掉。
     *
     * 拿不到也不影响：设备大多还会单播回复一份给我们。
     */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        return try {
            val wifi = context?.applicationContext
                ?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock("cloudmusic-dlna")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 取设备描述，解析出名字和控制地址 */
    private fun describe(location: String): DlnaDevice? {
        return try {
            val resp = LanHttp.get(location, 5000)
            if (resp.code != 200) return null
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val doc = factory.newDocumentBuilder()
                .parse(InputSource(ByteArrayInputStream(resp.body)))
            val device = findRenderer(doc.documentElement) ?: return null

            val friendly = childText(device, "friendlyName").orEmpty()
            val udn = childText(device, "UDN").orEmpty()
            var av = ""
            var rc = ""
            val avCandidates = linkedSetOf<String>()
            val services = device.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val s = services.item(i) as? Element ?: continue
                val type = childText(s, "serviceType") ?: continue
                val control = childText(s, "controlURL") ?: continue
                when (type) {
                    SERVICE_AVT -> {
                        val absolute = resolve(location, control)
                        av = absolute
                        // 相对路径有两种可能的基准，都存下来当备选
                        avCandidates += absolute
                        avCandidates += resolveAsDir(location, control)
                    }
                    SERVICE_RCS -> rc = resolve(location, control)
                }
            }
            if (av.isEmpty()) return null
            DlnaDevice(
                udn = udn.ifBlank { location },
                friendlyName = friendly.ifBlank { "未知设备" },
                location = location,
                avTransportUrl = av,
                renderingControlUrl = rc,
                avTransportUrls = avCandidates.toList()
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析设备描述失败：${e.message}")
            null
        }
    }

    /** 让设备播放指定的地址 */
    fun push(device: DlnaDevice, uri: String, metadata: String): Boolean {
        val urls = if (device.avTransportUrls.isEmpty()) {
            listOf(device.avTransportUrl)
        } else {
            device.avTransportUrls
        }
        for (url in urls) {
            if (pushTo(url, uri, metadata)) {
                lastError = null
                return true
            }
            Log.i(TAG, "控制地址 $url 不通，换下一个候选")
        }
        return false
    }

    private fun pushTo(controlUrl: String, uri: String, metadata: String): Boolean {
        // 有些渲染器上一次的播放还挂着，不先 Stop 会推不进去（小米音箱就这样）
        try {
            soap(controlUrl, SERVICE_AVT, "Stop", listOf("InstanceID" to "0"))
            // Stop 之后设备还在收尾，立刻 SetAVTransportURI 有时会失败
            Thread.sleep(250)
        } catch (e: Exception) {
            // Stop 不支持也没关系，接着往下走
        }

        // 先带着元数据推。有些渲染器对 DIDL 挑得很细，
        // 挑不动就退一步不带元数据再试一次，只要能出声就行。
        var ok = soap(
            controlUrl, SERVICE_AVT, "SetAVTransportURI",
            listOf(
                "InstanceID" to "0",
                "CurrentURI" to uri,
                "CurrentURIMetaData" to metadata
            )
        )
        if (!ok) {
            Log.i(TAG, "带元数据推歌失败，改用不带元数据重试")
            ok = soap(
                controlUrl, SERVICE_AVT, "SetAVTransportURI",
                listOf(
                    "InstanceID" to "0",
                    "CurrentURI" to uri
                )
            )
        }
        if (!ok) {
            // 有些音箱（小米那一类）不接受第二次 SetAVTransportURI，
            // 但它记得住当前 URI，刚才只是被我们 Stop 了 —— 试试直接 Play
            Log.i(TAG, "换 URI 失败，改用直接 Play 试试")
            ok = soap(
                controlUrl, SERVICE_AVT, "Play",
                listOf("InstanceID" to "0", "Speed" to "1")
            )
        }
        if (!ok) return false
        return soap(
            controlUrl, SERVICE_AVT, "Play",
            listOf("InstanceID" to "0", "Speed" to "1")
        )
    }

    fun stop(device: DlnaDevice): Boolean =
        soapAny(device, SERVICE_AVT, "Stop", listOf("InstanceID" to "0"))

    fun pause(device: DlnaDevice): Boolean =
        soapAny(device, SERVICE_AVT, "Pause", listOf("InstanceID" to "0"))

    /**
     * 让设备继续播。
     *
     * 投屏之后手机自己是暂停的，用户在手机上点「播放」，
     * 期望的是电视开始出声 —— 这个指令就是发给电视的。
     */
    fun play(device: DlnaDevice): Boolean =
        soapAny(device, SERVICE_AVT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))

    /**
     * 设备自己队列里的下一首。
     *
     * 我们是单首推送过去的，设备队列里通常只有这一首，
     * 所以这个多半会失败 —— 真正管用的「下一首」是
     * 手机换歌后重新推一遍（见 DlnaViewModel.nextCast）。
     */
    fun next(device: DlnaDevice): Boolean =
        soapAny(device, SERVICE_AVT, "Next", listOf("InstanceID" to "0"))

    fun previous(device: DlnaDevice): Boolean =
        soapAny(device, SERVICE_AVT, "Previous", listOf("InstanceID" to "0"))

    /**
     * 设备当前的播放位置（毫秒）。
     *
     * 投屏后进度条得跟着电视走，不然看着像卡住了。
     * relTime 的格式是 `H:MM:SS` 或 `H:MM:SS.mmm`。
     */
    fun positionMs(device: DlnaDevice): Long? {
        return try {
            val resp = LanHttp.postSoap(
                device.avTransportUrl,
                soapBody(SERVICE_AVT, "GetPositionInfo", listOf("InstanceID" to "0")),
                5000
            )
            if (resp.code != 200) return null
            val m = Regex("<(?:[A-Za-z0-9]+:)?relTime>([^<]*)</")
                .find(resp.text) ?: return null
            val parts = m.groupValues[1].trim().split(":")
            if (parts.size != 3) return null
            val h = parts[0].toLongOrNull() ?: 0L
            val min = parts[1].toLongOrNull() ?: 0L
            val sec = parts[2].toDoubleOrNull() ?: 0.0
            ((h * 3600L + min * 60L) * 1000L + (sec * 1000).toLong())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 让设备跳到某个位置。
     *
     * Unit 用 REL_TIME（相对时间），格式是 `H:MM:SS`。
     */
    fun seek(device: DlnaDevice, positionMs: Long): Boolean {
        val totalSec = (positionMs / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        return soapAny(
            device, SERVICE_AVT, "Seek",
            listOf(
                "InstanceID" to "0",
                "Unit" to "REL_TIME",
                "Target" to "$h:$m:$s"
            )
        )
    }

    /**
     * 控制指令走一遍所有候选地址。
     *
     * 设备描述里的 controlURL 写法五花八门，主地址不通时挨个试。
     */
    private fun soapAny(
        device: DlnaDevice,
        serviceType: String,
        action: String,
        args: List<Pair<String, String>>,
    ): Boolean {
        val urls = if (device.avTransportUrls.isEmpty()) {
            listOf(device.avTransportUrl)
        } else {
            device.avTransportUrls
        }
        for (url in urls) {
            if (soap(url, serviceType, action, args)) return true
        }
        return false
    }

    fun setVolume(device: DlnaDevice, value: Int): Boolean {
        if (device.renderingControlUrl.isEmpty()) return false
        return soap(
            device.renderingControlUrl, SERVICE_RCS, "SetVolume",
            listOf(
                "InstanceID" to "0",
                "Channel" to "Master",
                "DesiredVolume" to value.coerceIn(0, 100).toString()
            )
        )
    }

    /** 设备当前的音量（0-100）。读不到返回 null。 */
    fun getVolume(device: DlnaDevice): Int? {
        if (device.renderingControlUrl.isEmpty()) return null
        return try {
            val resp = LanHttp.postSoap(
                device.renderingControlUrl,
                soapBody(
                    SERVICE_RCS, "GetVolume",
                    listOf("InstanceID" to "0", "Channel" to "Master")
                ),
                5000
            )
            if (resp.code != 200) return null
            val m = Regex("<(?:[A-Za-z0-9]+:)?CurrentVolume>([^<]*)</")
                .find(resp.text) ?: return null
            m.groupValues[1].trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** 问设备现在什么状态（PLAYING / PAUSED_PLAYBACK / STOPPED） */
    fun transportState(device: DlnaDevice): String? {
        return try {
            val resp = LanHttp.postSoap(
                device.avTransportUrl,
                soapBody(SERVICE_AVT, "GetTransportInfo", listOf("InstanceID" to "0")),
                5000
            )
            if (resp.code != 200) return null
            val m = Regex("<CurrentTransportState>([^<]*)</CurrentTransportState>")
                .find(resp.text) ?: return null
            m.groupValues[1].trim()
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 上一次失败的原因，给界面显示用。
     *
     * 只说「播放失败」没法排查，得告诉用户是哪个动作、什么状态码、
     * 设备回了什么话（比如它说「地址打不开」还是「忙」）。
     */
    @Volatile
    var lastError: String? = null
        private set

    private fun soap(
        url: String,
        serviceType: String,
        action: String,
        args: List<Pair<String, String>>,
    ): Boolean {
        return try {
            val resp = LanHttp.postSoap(
                url,
                soapBody(serviceType, action, args),
                6000
            )
            if (resp.code != 200) {
                lastError = "$action 返回 HTTP ${resp.code}${faultOf(resp.text)}"
                Log.w(TAG, "SOAP $action 失败：HTTP ${resp.code} ${resp.text.take(300)}")
                return false
            }
            // 设备也可能用 200 回一个 Fault
            if (resp.text.contains("Fault")) {
                lastError = "$action 被设备拒绝${faultOf(resp.text)}"
                Log.w(TAG, "SOAP $action 被拒：${resp.text.take(300)}")
                return false
            }
            lastError = null
            true
        } catch (e: Exception) {
            lastError = "$action 没能送达：${e.message ?: e.javaClass.simpleName}"
            Log.w(TAG, "SOAP $action 异常：${e.message}")
            false
        }
    }

    /** 从 SOAP Fault 里把设备说的话抠出来 */
    private fun faultOf(text: String): String {
        if (text.isBlank()) return ""
        val m = Regex("<(?:[A-Za-z0-9]+:)?(?:errorDescription|faultstring)>([^<]*)</")
            .find(text)
        val detail = m?.groupValues?.get(1)?.trim()
        val code = Regex("<(?:[A-Za-z0-9]+:)?errorCode>([^<]*)</").find(text)
            ?.groupValues?.get(1)?.trim()
        return when {
            detail != null && detail.isNotEmpty() -> "（$detail）"
            code != null -> "（错误码 $code）"
            else -> ""
        }
    }

    private fun soapBody(
        serviceType: String,
        action: String,
        args: List<Pair<String, String>>,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
        for ((k, v) in args) {
            append('<').append(k).append('>').append(xmlEscape(v))
                .append("</").append(k).append('>')
        }
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    private fun headerOf(text: String, name: String): String? {
        for (line in text.split("\r\n", "\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    /** 设备描述里的 controlURL 可能是相对路径，要拼成完整地址 */
    private fun resolve(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        val cut = base.substringBeforeLast('/', base)
        return cut + "/" + relative.trimStart('/')
    }

    /**
     * 另一种相对基准：把描述文件所在的**目录**当基准。
     *
     * 比如描述文件在 `http://ip:9999/<uuid>`，而 controlURL 写的是
     * `AVTransport/control`，按 RFC 该落到 `http://ip:9999/AVTransport/control`，
     * 但小米那种「描述文件本身就是目录」的设备其实是
     * `http://ip:9999/<uuid>/AVTransport/control`。两个都留着试。
     */
    private fun resolveAsDir(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        val cut = base.substringBeforeLast('/', base)
        val dir = base.substringAfterLast('/', "")
        if (dir.isEmpty()) return resolve(base, relative)
        return "$cut/$dir/" + relative.trimStart('/')
    }

    private fun findRenderer(node: Element): Element? {
        val type = childText(node, "deviceType")
        if (type != null && type.contains("MediaRenderer")) return node
        val kids = node.childNodes
        for (i in 0 until kids.length) {
            val c = kids.item(i)
            if (c is Element) {
                val found = findRenderer(c)
                if (found != null) return found
            }
        }
        return null
    }

    private fun childText(parent: Element, name: String): String? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val c = kids.item(i)
            if (c is Element) {
                val ln = c.localName ?: c.tagName.substringAfter(':')
                if (ln.equals(name, ignoreCase = true)) return c.textContent?.trim()
            }
        }
        return null
    }
}
