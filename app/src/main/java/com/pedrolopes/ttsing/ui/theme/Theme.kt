package com.pedrolopes.ttsing.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D6B54),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8F2D8),
    onPrimaryContainer = Color(0xFF002016),
    secondary = Color(0xFF8A6F1D),
    surface = Color(0xFFFBF9F4),
    background = Color(0xFFFBF9F4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CD6BC),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513E),
    onPrimaryContainer = Color(0xFFA8F2D8),
    secondary = Color(0xFFD8C284),
    surface = Color(0xFF121412),
    background = Color(0xFF121412),
)

@Composable
fun TTSingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
