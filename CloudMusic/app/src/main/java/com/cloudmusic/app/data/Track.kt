package com.cloudmusic.app.data

/**
 * 一首曲目。id 为 WebDAV href，url 为可直接播放的完整地址。
 */
data class Track(
    /** 全局唯一键：网盘类型 + 网盘内 id，两个网盘可能有同名文件 */
    val key: String,
    /** 网盘内的原始 id（WebDAV 路径 / OneDrive item id） */
    val id: String,
    val url: String,
    /** 原始文件名（含扩展名） */
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val size: Long,
    val mime: String,
    val durationSec: Double = 0.0,
    /** 来自哪个网盘：webdav / onedrive */
    val provider: String = ProviderKind.WEBDAV.id
)