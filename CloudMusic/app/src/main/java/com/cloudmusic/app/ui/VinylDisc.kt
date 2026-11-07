package com.cloudmusic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 网易云风格的「黑胶唱片 + 唱臂」。
 *
 * 唱臂固定不动（真实唱臂也就那样），唱片在放的时候转、暂停就停。
 * 封面用的是渐变占位 —— 曲库里没有封面图，但有这张黑胶在，视觉上就到了。
 */
@Composable
fun VinylDisc(
    title: String,
    rotating: Boolean,
    modifier: Modifier = Modifier,
    discSize: Int = 300,
) {
    // 唱片角度：播放时转，暂停时停在原地
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(rotating) {
        if (!rotating) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    // withFrameNanos 给的是**纳秒**，先换算成秒再乘角速度。
                    // （之前直接除 1000 当毫秒用，每帧几万度，转成糊的了）
                    val deltaSec = (now - last) / 1_000_000_000f
                    // 一秒转 4 度 —— 一分半一圈
                    angle = (angle + deltaSec * 4f) % 360f
                }
                last = now
            }
        }
    }

    // 唱片纹路画一次缓存成位图，每帧只做旋转 ——
    // 否则几百条螺旋线每帧重绘会掉帧
    val disc = remember(discSize) { makeDiscBitmap(discSize) }

    Box(
        modifier.size(width = discSize.dp, height = discSize.dp),
        contentAlignment = Alignment.Center
    ) {
        // 唱臂：右上角支点 → 斜压到唱片上
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(size.width * 0.88f, size.height * 0.07f)
            val head = Offset(size.width * 0.585f, size.height * 0.40f)
            val minD = size.minDimension
            // 支点座
            drawCircle(Color(0xFF3A3A3A), radius = minD * 0.045f, center = pivot)
            // 唱臂
            drawLine(
                color = Color(0xFFD8D8D8),
                start = pivot,
                end = head,
                strokeWidth = minD * 0.018f,
                cap = StrokeCap.Round,
            )
            // 唱头（白色小方块）
            drawRoundRect(
                color = Color(0xFFF2F2F2),
                topLeft = Offset(
                    head.x - minD * 0.022f,
                    head.y - minD * 0.030f
                ),
                size = Size(minD * 0.044f, minD * 0.060f),
                cornerRadius = CornerRadius(6f, 6f),
            )
        }

        // 唱片本体（跟着转）
        Box(
            Modifier
                .size((discSize * 0.80f).dp)
                .rotate(angle),
            contentAlignment = Alignment.Center
        ) {
            // 唱片：黑胶底 + 螺旋沟槽 + 偏心标记点（预渲染，旋转时只做位图变换）
            Image(
                bitmap = disc,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )

            // 封面（圆形裁剪）
            GradientCover(
                seed = title.ifBlank { "?" },
                size = (discSize * 0.80f * 0.62f).dp,
                corner = (discSize * 0.80f * 0.31f).dp,
            )

            // 中心孔
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color(0xFF0E0E0E),
                    radius = size.minDimension * 0.022f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        }
    }
}

/**
 * 画一张黑胶唱片（位图）。
 *
 * 关键在**沟槽是螺旋线，不是同心圆** —— 同心圆转起来眼睛看不出在动，
 * 螺旋线一转就明显了（真黑胶就是这么绕的）。
 * 另外加一个偏心的白色标记点，转起来更直观。
 */
private fun makeDiscBitmap(size: Int): ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(
        size, size, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    val r = size / 2f
    val cx = r
    val cy = r

    // 黑胶底
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = 0xFF161616.toInt()
    canvas.drawCircle(cx, cy, r, paint)

    // 螺旋沟槽
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = size * 0.0035f
    paint.color = 0xFF262626.toInt()
    val turns = 7f
    val steps = 420
    var prevX = 0f
    var prevY = 0f
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val theta = t * turns * 2f * Math.PI.toFloat()
        val rr = r * 0.36f + (r * 0.94f - r * 0.36f) * t
        val x = cx + cos(theta) * rr
        val y = cy + sin(theta) * rr
        if (i > 0) canvas.drawLine(prevX, prevY, x, y, paint)
        prevX = x
        prevY = y
    }

    // 外圈亮边
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = size * 0.012f
    paint.color = 0xFF303030.toInt()
    canvas.drawCircle(cx, cy, r - size * 0.006f, paint)

    // 中心标签底色
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = 0xFF1E1E1E.toInt()
    canvas.drawCircle(cx, cy, r * 0.34f, paint)

    // 偏心标记点 —— 一眼就能看出唱片在转
    paint.color = 0xFFE8E8E8.toInt()
    canvas.drawCircle(cx + r * 0.78f, cy - r * 0.16f, size * 0.020f, paint)

    return bmp.asImageBitmap()
}
