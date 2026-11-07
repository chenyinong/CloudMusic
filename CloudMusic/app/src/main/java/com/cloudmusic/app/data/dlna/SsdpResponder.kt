package com.cloudmusic.app.data.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSDP 应答端（手机当渲染器时用）。
 *
 * 电视/音箱会在局域网里广播 M-SEARCH，问「谁家是媒体渲染器」。
 * 我们收到就**单播**回一个 200 OK，告诉它去哪儿取设备描述。
 *
 * 注意 Android 上收组播包需要拿 [WifiManager.MulticastLock]，
 * 否则收不到 M-SEARCH（除非手机刚好有别的 App 开着组播锁）。
 */
class SsdpResponder(
    private val context: Context,
    private val locationProvider: () -> String,
    private val uuidProvider: () -> String,
    /** 端口绑好了没（绑不上通常是 1900 被别的 App 占了） */
    private val onReady: (Boolean) -> Unit = {},
) {

    companion object {
        private const val TAG = "SsdpResponder"
        private const val GROUP = "239.255.255.250"
        private const val PORT = 1900
    }

    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var worker: Thread? = null
    private var ready = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ loop() }, "dlna-ssdp").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        try {
            lock?.release()
        } catch (_: Exception) {
        }
        lock = null
        worker = null
    }

    private fun loop() {
        var ms: MulticastSocket? = null
        try {
            acquireMulticastLock()
            ms = MulticastSocket(null)
            ms.reuseAddress = true
            ms.bind(InetSocketAddress(PORT))
            ms.joinGroup(InetAddress.getByName(GROUP))
            ms.soTimeout = 1500
            socket = ms
            ready = true
            onReady(true)

            val buf = ByteArray(2048)
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    ms.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "收包出错：${e.message}")
                    continue
                }
                val text = String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1)
                if (!isSearchForRenderer(text)) continue
                try {
                    val reply = buildResponse().toByteArray(Charsets.UTF_8)
                    ms.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                } catch (e: Exception) {
                    Log.w(TAG, "应答失败：${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP 应答线程退出：${e.message}")
        } finally {
            if (!ready) onReady(false)
            ready = false
            try {
                ms?.leaveGroup(InetAddress.getByName(GROUP))
            } catch (_: Exception) {
            }
            try {
                ms?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 只应答「找渲染器」的搜索；找根设备（rootdevice）也一并应了 */
    private fun isSearchForRenderer(text: String): Boolean {
        if (!text.startsWith("M-SEARCH", ignoreCase = true)) return false
        val upper = text.uppercase()
        return upper.contains("URN:SCHEMAS-UPNP-ORG:DEVICE:MEDIARENDERER:1") ||
            upper.contains("URN:SCHEMAS-UPNP-ORG:DEVICE:ROOTDEVICE:1") ||
            upper.contains("SSDP:ALL")
    }

    private fun buildResponse(): String {
        val uuid = uuidProvider()
        return buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("EXT:\r\n")
            append("LOCATION: ").append(locationProvider()).append("\r\n")
            append("SERVER: Android/UPnP/1.0 CloudMusic/1.0\r\n")
            append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
            append("USN: ").append(uuid).append("::urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
            append("\r\n")
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val l = wm.createMulticastLock("cloudmusic-dlna")
            l.setReferenceCounted(false)
            l.acquire()
            lock = l
        } catch (e: Exception) {
            // 没权限或者设备不支持就算了，最多就是电视搜不到我们
            Log.w(TAG, "拿不到组播锁：${e.message}")
        }
    }
}
