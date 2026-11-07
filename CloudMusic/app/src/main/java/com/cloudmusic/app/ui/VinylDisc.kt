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
        // 唱臂：右上角支点 → 斜压到唱片右下边缘（QQ 音乐银白唱臂）
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(size.width * 0.90f, size.height * 0.04f)
            val head = Offset(size.width * 0.76f, size.height * 0.70f)
            val minD = size.minDimension
            // 支点座（银灰）
            drawCircle(Color(0xFFB9BEC4), radius = minD * 0.045f, center = pivot)
            drawCircle(Color(0xFFE9ECEF), radius = minD * 0.028f, center = pivot)
            // 唱臂杆（银 + 一道深色中线，金属感）
            drawLine(
                color = Color(0xFFC7CCD1),
                start = pivot,
                end = head,
                strokeWidth = minD * 0.018f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFF9AA0A6),
                start = pivot,
                end = head,
                strokeWidth = minD * 0.006f,
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
        }
    }
}

/**
 * 画一张黑胶唱片（位图）。
 *
 * 关键在**沟槽是螺旋线，不是同心圆** —— 同心圆转起来眼睛看不出在动，
 * 螺旋线一转就明显了（真黑胶就是这么绕的）。
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

    // 唱片底：银白（QQ 那种银胶，不是纯黑）
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = 0xFFEDEDED.toInt()
    canvas.drawCircle(cx, cy, r, paint)

    // 螺旋沟槽
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = size * 0.0035f
    paint.color = 0xFFD7D7D7.toInt()
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

    // 外圈亮边：银灰一圈，像 QQ 音乐那张银胶
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = size * 0.012f
    paint.color = 0xFFC9CDD1.toInt()
    canvas.drawCircle(cx, cy, r - size * 0.006f, paint)

    // 中心标签底色（浅色，封面圆图会盖在上面）
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = 0xFFF4F4F4.toInt()
    canvas.drawCircle(cx, cy, r * 0.34f, paint)

    // 高光：左上一道柔光，银胶反射的质感
    paint.style = android.graphics.Paint.Style.FILL
    paint.shader = android.graphics.RadialGradient(
        cx * 0.7f, cy * 0.6f, r * 1.1f,
        0x66FFFFFF.toInt(), 0x00FFFFFF,
        android.graphics.Shader.TileMode.CLAMP
    )
    canvas.drawCircle(cx, cy, r, paint)
    paint.shader = null

    return bmp.asImageBitmap()
}
