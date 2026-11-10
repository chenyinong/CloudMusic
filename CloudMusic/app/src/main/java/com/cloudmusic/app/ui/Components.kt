package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

/**
 * 用标题生成稳定的渐变封面，避免解析内嵌封面图带来的复杂度。
 */
@Composable
fun GradientCover(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    corner: Dp = 12.dp
) {
    val colors = coverColors(seed)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = seed.take(1).uppercase().ifBlank { "?" },
            color = Color.White.copy(alpha = 0.92f),
            fontSize = (size.value / 2.2f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

fun coverColors(seed: String): List<Color> {
    val palettes = listOf(
        listOf(Color(0xFF5B8DEF), Color(0xFF2C5FD8)),
        listOf(Color(0xFF00B8A9), Color(0xFF00796B)),
        listOf(Color(0xFFFF8A65), Color(0xFFD84315)),
        listOf(Color(0xFFBA68C8), Color(0xFF6A1B9A)),
        listOf(Color(0xFFFFD54F), Color(0xFFF57F17)),
        listOf(Color(0xFF4DD0E1), Color(0xFF00838F))
    )
    val h = seed.hashCode().absoluteValue
    return palettes[h % palettes.size]
}

/**
 * 跟着歌曲封面色的「氛围底」：顶部只是很淡的一层色，越往下越明显。
 *
 * 之前是「上半截纯白 + 下半截突然出现颜色」，分界很生硬；
 * 这里改成三层叠加：不透明白底 → 连续彩色渐变 → 白色渐隐蒙版，
 * 顶部近白带一点色、底部彩更足，中间没有断层。
 *
 * @param seed 一般用歌名，和播放页取色是同一个 [coverColors]
 */
@Composable
fun Modifier.songAmbientBackground(seed: String): Modifier {
    val bg = coverColors(seed)
    val surface = MaterialTheme.colorScheme.surface
    return this
        .background(surface)
        .background(
            Brush.verticalGradient(
                0f to bg[0].copy(alpha = 0.20f),
                0.5f to bg[0].copy(alpha = 0.32f),
                1f to bg[1].copy(alpha = 0.44f)
            )
        )
        .background(
            Brush.verticalGradient(
                0f to surface.copy(alpha = 0.80f),
                0.45f to surface.copy(alpha = 0.40f),
                1f to Color.Transparent
            )
        )
}

/**
 * QQ 音乐风格的弹出菜单。
 *
 * Material 默认那个太窄、圆角小、项高 48dp，一眼就是"系统控件"。
 * 这里固定 176dp 宽、14dp 圆角、只有投影没有色调分层，项也压紧一点。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    /**
     * 不套 Material 的 DropdownMenu 了：它内部自带 elevation（又厚又泛绿），
     * 这个版本还没法改形状/颜色。自己画 Popup，样式完全可控。
     *
     * 定位跟 M3 一样：右边缘对齐触发按钮的右边缘、贴在按钮下面，
     * 并 clamp 在屏幕内，靠右的按钮菜单往左展开。
     */
    val positionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                return IntOffset(
                    (anchorBounds.right - popupContentSize.width).coerceIn(0, maxX),
                    anchorBounds.bottom.coerceIn(0, maxY)
                )
            }
        }
    }
    if (expanded) {
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true)
        ) {
            Column(
                modifier
                    // 固定宽度：Popup 自己没有宽度约束，不写死的话
                    // 菜单项的 fillMaxWidth() 会把菜单撑到整屏
                    .width(168.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    // 不用阴影，一条极淡的描边就跟背景分开了，干净
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(vertical = 6.dp),
                content = content
            )
        }
    }
}

/** 菜单顶部的一行小标题（比如当前歌曲名） */
@Composable
fun AppMenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
    )
}

/** 菜单里的一项：图标 + 文字，行高收紧 */
@Composable
fun AppMenuItem(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 自绘开关。
 *
 * Material 默认的 Switch 轨道是硬边灰色块（未选中色还是带色调的
 * surfaceContainerHighest），落在浅色/渐变底上很扎眼。
 * 这里轨道用半透明色（透出底下的渐变），滑块是白色小圆 + 一丝阴影。
 */
@Composable
fun SoftSwitch(
    checked: Boolean,
    trackColor: Color = MaterialTheme.colorScheme.primary,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        Modifier
            .width(46.dp)
            .height(27.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (checked) trackColor.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            )
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(21.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .shadow(1.dp, CircleShape)
        )
    }
}

/** 小胶囊按钮：30dp 高、全圆角，比 Material 默认 Button 小巧得多 */
@Composable
fun SmallPill(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * QQ 音乐风格的搜索框：中性浅灰底、40dp 高、全圆角，无描边。
 *
 * Material 自带的 OutlinedTextField 又高又带粗边（而且默认底色偏紫），
 * 自己画一个更省事也更贴合。
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** 输入框左侧图标：搜索页用放大镜，链接输入框传链接图标 */
    leadingIcon: ImageVector = Icons.Filled.Search,
    onImeSearch: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            leadingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            keyboardOptions = KeyboardOptions(
                imeAction = if (onImeSearch != null) ImeAction.Search else ImeAction.Default
            ),
            keyboardActions = KeyboardActions(onSearch = { onImeSearch?.invoke() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    inner()
                }
            }
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "清空",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onValueChange("") }
            )
        }
    }
}