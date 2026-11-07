package com.cloudmusic.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6BE4),
    secondary = Color(0xFF00A99D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB8FF),
    secondary = Color(0xFF4DD9CB)
)

@Composable
fun CloudMusicTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}