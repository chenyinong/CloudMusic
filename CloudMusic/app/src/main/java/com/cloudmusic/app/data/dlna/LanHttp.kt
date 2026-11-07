package com.cloudmusic.app.data.dlna

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 局域网 HTTP 客户端（极简版）。
 *
 * 为什么不用 OkHttp：Android 的网络安全策略会拦掉明文 HTTP，
 * 而 DLNA 设备（电视、音箱）的控制地址天生就是
 * http://192.168.x.x:8200/... 这种明文地址，
 * 用 OkHttp 会直接抛
 * "CLEARTEXT communication to 192.168.x.x not permitted by network security policy"。
 * 裸 Socket 不走那套策略，正好用来跟局域网设备打交道。
 */
object LanHttp {

    private const val MAX_BODY = 4 * 1024 * 1024
    private const val CR: Byte = 13
    private const val LF: Byte = 10

    class Response(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val text: String get() = String(body, Charsets.UTF_8)
        fun header(name: String): String? = headers[name.lowercase()]
    }

    fun get(url: String, timeoutMs: Int = 8000): Response =
        request(url, "GET", null, null, timeoutMs)

    /**
     * 发一个 SOAP 请求。
     *
     * UPnP 规范要求带 `SOAPACTION: "服务类型#动作名"` 这个头，
     * 小爱音箱这类设备缺了会直接拒绝（返回 500 或干脆不回包）。
     * 这里默认从请求体里把服务类型和动作名抠出来自动拼上，
     * 保证这个头和请求体永远一致，不会出现「两处各写一份、结果写漏了」。
     */
    fun postSoap(url: String, body: String, timeoutMs: Int = 8000): Response =
        postSoap(url, body, soapActionOf(body), timeoutMs)

    fun postSoap(
        url: String,
        body: String,
        soapAction: String,
        timeoutMs: Int = 8000,
    ): Response {
        val headers = if (soapAction.isBlank()) {
            emptyMap()
        } else {
            mapOf("SOAPACTION" to "\"$soapAction\"")
        }
        return request(
            url, "POST", body, "text/xml; charset=\"utf-8\"", timeoutMs, headers
        )
    }

    /** 从 SOAP 请求体里推出 "服务类型#动作名"，推不出来就返回空 */
    private fun soapActionOf(body: String): String {
        val service = Regex("xmlns:u=\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
        val action = Regex("<u:([A-Za-z0-9]+)[\\s>]").find(body)?.groupValues?.get(1).orEmpty()
        return if (service.isNotEmpty() && action.isNotEmpty()) "$service#$action" else ""
    }

    fun request(
        url: String,
        method: String,
        body: String?,
        contentType: String?,
        timeoutMs: Int,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response {
        val target = splitUrl(url)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
            socket.soTimeout = timeoutMs
            val bodyBytes = body?.toByteArray(Charsets.UTF_8)
            val head = StringBuilder()
            head.append(method).append(' ').append(target.path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(target.host).append(':').append(target.port).append("\r\n")
            head.append("Connection: close\r\n")
            head.append("User-Agent: CloudMusic/1.0 (Android)\r\n")
            extraHeaders.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            if (bodyBytes != null) {
                head.append("Content-Type: ").append(contentType ?: "text/xml").append("\r\n")
                head.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (bodyBytes != null) out.write(bodyBytes)
            out.flush()
            return parse(socket.getInputStream())
        }
    }

    // ---------------- 内部工具 ----------------

    private data class Target(val host: String, val port: Int, val path: String)

    /**
     * 手动拆 URL，不走 [java.net.URI]：
     * URI 碰到中文路径或者没转义的字符会直接抛异常，
     * 而设备描述里的地址什么格式都可能有。
     */
    private fun splitUrl(url: String): Target {
        var rest = url.trim()
        val schemeIdx = rest.indexOf("://")
        if (schemeIdx >= 0) rest = rest.substring(schemeIdx + 3)
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "/" else rest.substring(slash)
        val hostPort = authority.substringAfterLast('@')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "").toIntOrNull() ?: 80
        return Target(host, port, path.ifEmpty { "/" })
    }

    private fun parse(input: InputStream): Response {
        val headBytes = readHead(input) ?: return Response(0, emptyMap(), ByteArray(0))
        val headText = String(headBytes, Charsets.ISO_8859_1)
        val lines = headText.split("\r\n")
        val code = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        return Response(code, headers, readBody(input, headers))
    }

    /** 读到空行为止的头部（含结尾的 CRLFCRLF） */
    private fun readHead(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (out.size() < 65536) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            val size = out.size()
            if (size >= 4) {
                val arr = out.toByteArray()
                val n = size - 4
                if (arr[n] == CR && arr[n + 1] == LF && arr[n + 2] == CR && arr[n + 3] == LF) {
                    return arr
                }
            }
        }
        return null
    }

    /**
     * 按 Content-Length / chunked 精确读 body。
     *
     * 不能一股脑读到 EOF：不少渲染器回应完 SOAP 并不关连接，
     * 那样会一直等到超时才返回，白白拖慢每一次操作。
     */
    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val te = headers["transfer-encoding"]
        if (te != null && te.contains("chunked", true)) return readChunked(input)
        val len = headers["content-length"]?.trim()?.toIntOrNull()
        if (len != null) {
            if (len <= 0) return ByteArray(0)
            return readExactly(input, len) ?: ByteArray(0)
        }
        return readAll(input)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.trim().substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            val chunk = readExactly(input, size) ?: break
            out.write(chunk)
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == LF.toInt()) return sb.toString().removeSuffix("\r")
            sb.append(b.toChar())
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) return null
            read += r
        }
        return buf
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < MAX_BODY) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
