package com.cloudmusic.app.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 下拉刷新容器。
 *
 * 薄薄一层包装，主要是为了两件事：
 * - 把 Material3 那个 `@ExperimentalMaterial3Api` 的 opt-in 收在一处，
 *   页面里就不用每个都写一遍；
 * - 名字跟 Material3 的原函数区分开，免得自己调自己。
 *
 * `PullToRefreshBox` 是 Material3 1.3 才有的，所以依赖版本钉在 1.3.0
 * （BOM 里写的是 1.2.1，实际解析被别的依赖顶上去了 —— 这里写死，
 * 免得哪天顶上来的那个依赖没了就编译不过）。
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
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        content = content,
    )
}
