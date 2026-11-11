package com.cloudmusic.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    // 手动换封面 / 重新查找后，跟着 epoch 重新取一次（命中缓存，秒换）
    val epoch = app?.coverRepository?.changes?.collectAsState()?.value ?: 0

    LaunchedEffect(key, app, epoch) {
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

/**
 * 从封面位图提取一个主题色，给播放页的氛围渐变用。
 *
 * 做法：采样约 4k 个像素，按 12 个色相桶做「饱和度 × 明度」加权投票，
 * 取权重最大的桶求平均。死黑、纯白和无彩的灰不参与投票；
 * 黑白封面提不出色相时返回 null，调用方回落到 [coverColors] 的歌名调色板。
 *
 * 平均会把颜色揉得又灰又闷，最后把 HSV 归一到「中饱和、中亮」区间 ——
 * 观感对齐歌名调色板那档柔和明快的淡彩，渐变过渡才不会发脏。
 */
fun extractCoverPalette(bmp: Bitmap): Color? {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return null

    val weight = FloatArray(12)
    val sumR = FloatArray(12)
    val sumG = FloatArray(12)
    val sumB = FloatArray(12)
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    val step = maxOf(1, pixels.size / 4096)

    var i = 0
    while (i < pixels.size) {
        val p = pixels[i]
        val r = (p shr 16 and 0xFF) / 255f
        val g = (p shr 8 and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val v = max
        val s = if (max > 0f) (max - min) / max else 0f
        if (v >= 0.12f && s >= 0.15f && !(v > 0.96f && s < 0.10f)) {
            var hue = when {
                max == min -> 0f
                max == r -> 60f * (g - b) / (max - min)
                max == g -> 60f * (2f + (b - r) / (max - min))
                else -> 60f * (4f + (r - g) / (max - min))
            }
            if (hue < 0f) hue += 360f
            val bucket = (hue / 30f).toInt().coerceIn(0, 11)
            val wt = s * v
            weight[bucket] += wt
            sumR[bucket] += r * wt
            sumG[bucket] += g * wt
            sumB[bucket] += b * wt
        }
        i += step
    }

    val best = weight.indices.maxByOrNull { weight[it] } ?: return null
    if (weight[best] <= 0f) return null

    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (sumR[best] / weight[best] * 255f).toInt(),
        (sumG[best] / weight[best] * 255f).toInt(),
        (sumB[best] / weight[best] * 255f).toInt(),
        hsv
    )
    hsv[1] = hsv[1].coerceIn(0.40f, 0.68f)
    hsv[2] = hsv[2].coerceIn(0.62f, 0.92f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
