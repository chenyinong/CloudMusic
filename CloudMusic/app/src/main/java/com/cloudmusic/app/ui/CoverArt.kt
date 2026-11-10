package com.cloudmusic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.cloudmusic.app.App
import com.cloudmusic.app.data.Track
import kotlin.math.abs

/**
 * 列表/迷你条用的封面缩略图：真封面优先，没查到（或还没下完）画程序化占位图。
 *
 * 只负责「显示」，检索和缓存全在 [com.cloudmusic.app.data.CoverRepository]：
 * 同一首歌 SQLite 里存了直链就秒出，没存过则按全局限流排队查 iTunes。
 * LazyColumn 里只有可见行会组合，天然就是按需加载。
 */
@Composable
fun CoverThumb(
    track: Track?,
    seed: String,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as? App
    val key = track?.key
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(key, app) {
        if (track == null || app == null) {
            bitmap = null
            return@LaunchedEffect
        }
        val url = app.coverRepository.load(track)
        bitmap = url?.let { app.coverRepository.artwork(it)?.asImageBitmap() }
    }

    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            GeneratedCover(seed = seed.ifBlank { "?" }, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 查不到封面时的程序化占位画：
 * 歌名哈希选调色板 → 渐变天 + 圆日 + 三层山影。
 * 每首歌构图略有不同，但整体风格统一；播放页大卡和列表小图共用这一张画。
 */
@Composable
fun GeneratedCover(seed: String, modifier: Modifier = Modifier) {
    val palette = coverColors(seed)
    val variant = abs(seed.hashCode())
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // 天空：调色板深色在上、亮色在下
        drawRect(
            Brush.verticalGradient(
                colors = listOf(palette[1], palette[0]),
                startY = 0f, endY = h
            )
        )

        // 圆日：位置大小随歌名哈希微调
        val sunR = h * (0.13f + (variant % 40) / 400f)
        val sunX = w * (0.30f + (variant % 30) / 100f)
        val sunY = h * (0.22f + (variant % 17) / 100f)
        drawCircle(
            Color(0xFFF6EFD4).copy(alpha = 0.15f),
            radius = sunR * 2.0f,
            center = Offset(sunX, sunY)
        )
        drawCircle(
            Color(0xFFF6EFD4).copy(alpha = 0.95f),
            radius = sunR,
            center = Offset(sunX, sunY)
        )

        // 山影：三层叠弧，一层比一层深、一层比一层近
        drawHill(lerp(palette[1], Color.Black, 0.25f), w * -0.15f, h * 0.88f, w * 0.75f, h * 0.60f)
        drawHill(lerp(palette[1], Color.Black, 0.40f), w * 0.08f, h * 0.92f, w * 0.85f, h * 0.62f)
        drawHill(lerp(palette[1], Color.Black, 0.55f), w * 0.55f, h * 0.96f, w * 0.80f, h * 0.66f)
    }
}

/** 一座山：从底线拱起来的抛物线，闭合填色。 */
private fun DrawScope.drawHill(color: Color, left: Float, baseY: Float, width: Float, height: Float) {
    val path = Path().apply {
        moveTo(left, baseY)
        quadraticBezierTo(left + width / 2f, baseY - height, left + width, baseY)
        close()
    }
    drawPath(path, color)
}
