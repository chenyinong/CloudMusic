package com.cloudmusic.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 下拉刷新图标的状态。
 *
 * 下拉中（箭头 + 进度圈）→ 松开刷新（箭头翻转）→ 刷新中（声波柱起伏）。
 */
enum class RefreshPhase { Pulling, Ready, Refreshing }

/** 圆底的直径（dp）。没底子了，这个只是占位、用来对齐全下移量。 */
private val CHIP_SIZE = 44.dp

/** 里面那个图形的边长（dp）。底子去掉后，图形是唯一的视觉元素，画大一点看得清。 */
private val ICON_SIZE = 34.dp

/** 这个图标用到的颜色。深浅色各一套，浅色那套是网易云的青绿。 */
private data class RefreshColors(
    /** 箭头、进度圈 */
    val accent: Color,
    /** 进度圈没走到的底色 */
    val track: Color,
    /** 声波柱渐变起点 */
    val barStart: Color,
    /** 声波柱渐变终点 */
    val barEnd: Color,
)

/**
 * 取当前主题下的这套颜色。
 *
 * 浅色：青绿，跟网易云一个路子；
 * 深色：绿色提亮一点，不然在黑底上不够清楚。
 */
@Composable
private fun refreshColors(): RefreshColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        RefreshColors(
            accent = Color(0xFF3FD3B5),
            track = Color(0xFF1E3B36),
            barStart = Color(0xFF8B7BFF),
            barEnd = Color(0xFF3FD3B5),
        )
    } else {
        RefreshColors(
            accent = Color(0xFF12806E),
            track = Color(0xFFDDF0EC),
            barStart = Color(0xFF6C5FD6),
            barEnd = Color(0xFF12A08A),
        )
    }
}

/**
 * 下拉刷新的指示器。
 *
 * 位置（上下偏移）由调用方决定 —— 本项目的 [RefreshBox] 自己管内容怎么让位，
 * 这里只负责画图形：没拉的时候不画，下拉时从上面滑出来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudMusicRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = state.distanceFraction
    val phase = when {
        isRefreshing -> RefreshPhase.Refreshing
        fraction >= 1f -> RefreshPhase.Ready
        else -> RefreshPhase.Pulling
    }
    // 还没开始下拉就完全不画
    if (phase == RefreshPhase.Pulling && fraction <= 0f) return

    CloudRefreshChip(
        phase = phase,
        progress = fraction,
        modifier = modifier.graphicsLayer {
            // 下拉时从小变大长出来
            val s = if (phase == RefreshPhase.Pulling) (fraction * 1.2f).coerceIn(0f, 1f) else 1f
            scaleX = s
            scaleY = s
        },
    )
}

/** 无状态的圆形图标。底子透明，只画图形（进度圈 + 箭头 / 声波柱）。 */
@Composable
fun CloudRefreshChip(
    phase: RefreshPhase,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val colors = refreshColors()
    Box(
        modifier = modifier.size(CHIP_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        if (phase == RefreshPhase.Refreshing) {
            EqualizerBars(colors, Modifier.size(ICON_SIZE))
        } else {
            StaticIcon(colors, phase, progress, Modifier.size(ICON_SIZE))
        }
    }
}

// ───────────────────────── 绘制（48 单位画布，按尺寸等比缩放） ─────────────────────────

private fun DrawScope.drawRing(colors: RefreshColors, progress: Float?) {
    val u = size.width / 48f
    val stroke = Stroke(width = 3f * u, cap = StrokeCap.Round)
    drawCircle(colors.track, radius = 20f * u, center = Offset(24f * u, 24f * u), style = stroke)
    if (progress != null) {
        drawArc(
            color = colors.accent,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(4f * u, 4f * u),
            size = Size(40f * u, 40f * u),
            style = stroke,
        )
    }
}

@Composable
private fun StaticIcon(
    colors: RefreshColors,
    phase: RefreshPhase,
    progress: Float,
    modifier: Modifier,
) {
    // 松开刷新时箭头翻转 180°
    val arrowRotation by animateFloatAsState(
        targetValue = if (phase == RefreshPhase.Ready) 180f else 0f,
        animationSpec = tween(200),
        label = "arrowRotation",
    )
    Canvas(modifier) {
        val u = size.width / 48f
        val line = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRing(colors, if (phase == RefreshPhase.Pulling) progress else 1f)
        rotate(arrowRotation, pivot = Offset(24f * u, 24f * u)) {
            val arrow = Path().apply {
                moveTo(24f * u, 18f * u); lineTo(24f * u, 30f * u)
                moveTo(19f * u, 25f * u); lineTo(24f * u, 30f * u); lineTo(29f * u, 25f * u)
            }
            drawPath(arrow, colors.accent, style = line)
        }
    }
}

@Composable
private fun EqualizerBars(colors: RefreshColors, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    // 中间一根先动，两侧依次延迟 150ms
    fun spec(delayMs: Int) = infiniteRepeatable<Float>(
        animation = tween(500, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse,
        initialStartOffset = StartOffset(delayMs),
    )
    val s0 by transition.animateFloat(0.6f, 1.25f, spec(0), label = "s0")
    val s1 by transition.animateFloat(0.6f, 1.25f, spec(150), label = "s1")
    val s2 by transition.animateFloat(0.6f, 1.25f, spec(300), label = "s2")
    val heights = floatArrayOf(8f, 16f, 24f, 16f, 8f)

    Canvas(modifier) {
        val u = size.width / 48f
        drawRing(colors, null)
        for (i in 0..4) {
            val scale = when (abs(i - 2)) { 0 -> s0; 1 -> s1; else -> s2 }
            val w = 4f * u
            val h = heights[i] * scale * u
            val x = (10.5f + 7f * i) * u
            val y = 24f * u - h / 2f
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(colors.barStart, colors.barEnd),
                    start = Offset(x, y),
                    end = Offset(x + w, y + h),
                ),
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = CornerRadius(2f * u),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F6FA)
@Composable
private fun RefreshStatesPreview() {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CloudRefreshChip(RefreshPhase.Pulling, 0.35f)
        CloudRefreshChip(RefreshPhase.Ready, 1f)
        CloudRefreshChip(RefreshPhase.Refreshing, 1f)
    }
}
