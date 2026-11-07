package com.cloudmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

private fun coverColors(seed: String): List<Color> {
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