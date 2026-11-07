package com.cloudmusic.app.data

import java.util.Locale

/**
 * 把字节数说成人话。
 *
 * 网盘空间这种数字，用户看「8.7 GB」比看「9337852416 字节」有用得多。
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    // 大单位保留一位小数就够了，小单位取整免得显示一堆 0
    return if (value >= 100) {
        "${value.toInt()} ${units[unit]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
