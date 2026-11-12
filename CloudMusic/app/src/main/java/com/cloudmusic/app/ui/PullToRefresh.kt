package com.cloudmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive

/** 下拉时内容最多让出多少（dp）。 */
private const val PULL_HEIGHT = 72f

/** 指示器圆底的直径（dp）。 */
private const val INDICATOR_SIZE = 36f

/**
 * 下拉刷新容器。
 *
 * 没用 Material3 自带的 `PullToRefreshBox`，自己拼了一层，原因有两个：
 * - 它那个**列表不会跟着手指下移**（内容纹丝不动，只剩个圈在原地转），
 *   下拉手势感觉很别扭；这里让内容跟着拉下来，跟常见 App 一个样；
 * - 指示器也换掉了 —— 默认那个是灰底黑箭头，跟本项目调性差得远。
 *
 * `PullToRefreshBox` / `pullToRefresh` 是 Material3 1.3 才有的，
 * 所以依赖版本钉在 1.3.0（BOM 里写的是 1.2.1，实际解析被别的依赖顶上去
 * 了 —— 这里写死，免得哪天顶上来的那个依赖没了就编译不过）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()

    // 内容跟着手指往下走多少
    val pull = state.distanceFraction.coerceIn(0f, 1f)
    val contentOffset = (pull * PULL_HEIGHT).dp
    // 指示器落在内容让出来的那条缝里（居中）
    val indicatorOffset = (pull * PULL_HEIGHT / 2f - INDICATOR_SIZE / 2f).dp

    Box(modifier) {
        // 指示器画在下层：内容下移之后，正好从让出来的那条缝里露出来
        CloudRefreshIndicator(
            distanceFraction = pull,
            isRefreshing = refreshing,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = indicatorOffset)
        )
        Box(
            Modifier
                .offset(y = contentOffset)
                .fillMaxSize()
                .pullToRefresh(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    state = state
                )
        ) {
            content()
        }
    }
}

/**
 * 下拉刷新的指示器。
 *
 * 底子沿用 Material3 那个深色圆底（看着挺顺眼），里面那个图形换掉：
 * - M3 默认画的是一段带箭头的弧（转起来像个怪月牙），改成干净的白色圆弧
 * - 下拉时圆弧**跟着手指转**，转到一整圈就是「松手就刷新」的信号
 * - 拉够了圆底整个变成主色，一眼就知道可以松手了
 * - 刷新中圆弧匀速转，松手时圆底还原成深色
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudRefreshIndicator(
    distanceFraction: Float,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    // 下拉的进度：0 = 没拉，1 = 拉够了
    val progress = distanceFraction.coerceIn(0f, 1f)
    val ready = progress >= 0.999f

    // 刷新中的转角。
    //
    // 用 Animatable 自己累加（每圈 +360），不能用 infiniteRepeatable ——
    // 那个到 360° 会跳回 0°，弧有缺口的话就会看到**倒转**一下。
    // 从 360° 起步，正好接上下拉转满的那一圈，松手到刷新是连续的。
    val spin = remember { Animatable(360f) }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) return@LaunchedEffect
        spin.snapTo(360f)
        while (isActive) {
            spin.animateTo(spin.value + 360f, tween(850, easing = LinearEasing))
        }
    }

    // 下拉时弧跟手转；刷新中匀速转
    val rotation = if (isRefreshing) spin.value else progress * 360f

    // 平时是深色圆底（跟 Material3 默认那个一样），拉够了变成主色
    val container =
        if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inverseSurface
    // 弧线一律白色：深色底上是白，主色底上是 onPrimary（也是白的）
    val arc = if (ready) MaterialTheme.colorScheme.onPrimary else Color.White

    // 没拉的时候完全看不见；拉的时候从小变大
    val alpha = if (isRefreshing) 1f else progress
    val scale = if (isRefreshing) 1f else 0.45f + 0.55f * progress

    Box(
        modifier
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .size(INDICATOR_SIZE.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 2.4.dp.toPx()
            drawArc(
                color = arc,
                startAngle = rotation - 90f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}
