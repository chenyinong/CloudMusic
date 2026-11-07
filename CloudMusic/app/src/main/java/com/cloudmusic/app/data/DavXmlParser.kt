package com.cloudmusic.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * PROPFIND (multistatus) 响应解析器。
 */
object DavXmlParser {

    fun parse(xml: String, basePath: String): List<DavEntry> {
        val out = mutableListOf<DavEntry>()
        if (xml.isBlank()) return out

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var href: String? = null
        var displayName: String? = null
        var size = 0L
        var contentType = ""
        var lastModified = 0L
        var isDirectory = false
        var inProp = false
        var inResType = false
        var capturing: String? = null
        val text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val n = parser.name.substringAfter(':').lowercase(Locale.US)
                    when {
                        n == "response" -> {
                            href = null; displayName = null; size = 0L
                            contentType = ""; lastModified = 0L; isDirectory = false
                        }
                        n == "prop" -> inProp = true
                        n == "resourcetype" -> inResType = true
                        n == "collection" -> if (inResType) isDirectory = true
                        n == "href" && !inProp -> { capturing = "href"; text.setLength(0) }
                        n == "displayname" && inProp -> { capturing = "displayname"; text.setLength(0) }
                        n == "getcontentlength" && inProp -> { capturing = "getcontentlength"; text.setLength(0) }
                        n == "getcontenttype" && inProp -> { capturing = "getcontenttype"; text.setLength(0) }
                        n == "getlastmodified" && inProp -> { capturing = "getlastmodified"; text.setLength(0) }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (capturing != null) text.append(parser.text)
                }

                XmlPullParser.END_TAG -> {
                    val n = parser.name.substringAfter(':').lowercase(Locale.US)
                    when {
                        n == "prop" -> inProp = false
                        n == "resourcetype" -> inResType = false
                        n == "response" -> {
                            val h = href
                            if (!h.isNullOrBlank()) {
                                val norm = normalize(h, basePath)
                                val nm = displayName?.takeIf { it.isNotBlank() } ?: nameFromHref(norm)
                                out.add(DavEntry(norm, nm, isDirectory, size, lastModified, contentType))
                            }
                        }
                        capturing != null && n == capturing -> {
                            when (capturing) {
                                "href" -> href = text.toString().trim()
                                "displayname" -> displayName = text.toString().trim()
                                "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0L
                                "getcontenttype" -> contentType = text.toString().trim()
                                "getlastmodified" -> lastModified = parseDate(text.toString().trim())
                            }
                            capturing = null
                            text.setLength(0)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /**
     * 解析空间用量。
     *
     * 响应长这样：
     * ```xml
     * <D:propstat><D:prop>
     *   <D:quota-available-bytes>1073741824</D:quota-available-bytes>
     *   <D:quota-used-bytes>536870912</D:quota-used-bytes>
     * </D:prop></D:propstat>
     * ```
     * 少了任何一个就当这个服务端不支持（返回 null），别显示成 0 字节 ——
     * 那会让人以为网盘满了。
     */
    fun parseQuota(xml: String): DavQuota? {
        if (xml.isBlank()) return null
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try {
            parser.setInput(StringReader(xml))
        } catch (e: Exception) {
            return null
        }

        var available = -1L
        var used = -1L
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val n = parser.name.substringAfter(':').lowercase(Locale.US)
                    if (n == "quota-available-bytes" || n == "quota-used-bytes") {
                        val v = parser.nextText().trim().toLongOrNull() ?: -1L
                        if (n == "quota-available-bytes") available = v else used = v
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // 解析到一半失败：已经拿到的值照样用
        }

        if (available < 0L && used < 0L) return null
        return DavQuota(available.coerceAtLeast(0L), used.coerceAtLeast(0L))
    }

    /** 服务端可能返回相对路径，统一补全为以 basePath 开头的绝对路径 */
    fun normalize(href: String, basePath: String): String {
        val h = href.trim()
        if (h.isEmpty()) return "$basePath/"
        val path = if (h.startsWith("/")) h else "/$h"
        val bp = basePath.trimEnd('/')
        return if (path == bp || path.startsWith("$bp/")) path else bp + path
    }

    fun nameFromHref(href: String): String {
        val trimmed = href.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        val seg = if (idx >= 0) trimmed.substring(idx + 1) else trimmed
        return try {
            URLDecoder.decode(seg, "UTF-8")
        } catch (e: Exception) {
            seg
        }
    }

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US)
                if (f.contains("zzz")) sdf.timeZone = TimeZone.getTimeZone("GMT")
                if (f.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(raw)?.time ?: 0L
            } catch (e: Exception) {
                // try next format
            }
        }
        return 0L
    }
}
