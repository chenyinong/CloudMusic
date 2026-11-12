package com.cloudmusic.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 下拉时内容最多让出多少（dp）。 */
private const val PULL_HEIGHT = 68f

/** 指示器圆底的直径（dp）。 */
private const val INDICATOR_SIZE = 44f

/**
 * 指示器至少显示多久（毫秒）。
 *
 * 刷新要是一瞬间就完事，圈圈闪一下就没了，用户只会觉得「下拉没反应」。
 */
const val MIN_SPIN_MS = 600L

/**
 * 下拉刷新容器。
 *
 * 没用 Material3 自带的 `PullToRefreshBox`，自己拼了一层，原因：
 * 它那个**列表不会跟着手指下移**（内容纹丝不动，只剩个圈在原地转），
 * 下拉手势感觉很别扭；这里让内容跟着拉下来，跟常见 App 一个样。
 *
 * 指示器用的是 [CloudMusicRefreshIndicator]（白底圆片 + 箭头 / 声波 / 对勾四态）。
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
        CloudMusicRefreshIndicator(
            state = state,
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
