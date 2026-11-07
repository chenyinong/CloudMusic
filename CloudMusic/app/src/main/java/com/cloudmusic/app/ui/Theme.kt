package com.cloudmusic.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 中性黑白灰主题（去掉绿色强调色）：
 * - 主色用近黑 / 近白，靠明度层次表达层级，不再靠彩色抢视线；
 * - 圆角整体放大，卡片 / 按钮更柔和有表达力（Material 3 的强项）；
 * - 深色不用纯黑，用接近 #121212 的深灰，长时间看更舒服。
 * - error 红保留：那是危险操作的语义色，不属于品牌配色。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF23272E),
    onPrimary = Color.White,
    secondary = Color(0xFF5C636D),
    // 卡片 / 面板用纯白，页面底色是很淡的灰 —— QQ 音乐就是这样分的
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFF5F6F8),
    // 胶囊、输入框这些用中性灰，Material 默认的 surfaceVariant 偏紫不好看
    surfaceVariant = Color(0xFFEBECEE),
    onSurfaceVariant = Color(0xFF6E7175),
    outlineVariant = Color(0xFFE3E5E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8EAEE),
    onPrimary = Color(0xFF16181C),
    secondary = Color(0xFFB4B9C2),
    surface = Color(0xFF1C1C1E),
    background = Color(0xFF121212),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFF9B9BA0),
    outlineVariant = Color(0xFF333338),
)

// 比 M3 默认再柔和一点的圆角，统一卡片 / 输入 / 按钮的观感
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CloudMusicTheme(
    dark: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
