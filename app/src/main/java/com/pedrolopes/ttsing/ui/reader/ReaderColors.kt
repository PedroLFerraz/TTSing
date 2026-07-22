package com.pedrolopes.ttsing.ui.reader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pedrolopes.ttsing.data.settings.ReaderTheme

data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val sentenceHighlight: Color,
    val wordHighlight: Color,
    val wordText: Color,
)

@Composable
fun readerPalette(theme: ReaderTheme): ReaderPalette {
    val dark = when (theme) {
        ReaderTheme.SYSTEM -> isSystemInDarkTheme()
        ReaderTheme.DARK -> true
        else -> false
    }
    return when {
        theme == ReaderTheme.SEPIA -> ReaderPalette(
            background = Color(0xFFF4ECD8),
            text = Color(0xFF4A3F35),
            secondaryText = Color(0xFF7A6A57),
            sentenceHighlight = Color(0xFFE4D2A6),
            wordHighlight = Color(0xFFD8A94B),
            wordText = Color(0xFF2A2018),
        )
        dark -> ReaderPalette(
            background = Color(0xFF121412),
            text = Color(0xFFE6E3DC),
            secondaryText = Color(0xFF9CA39A),
            sentenceHighlight = Color(0xFF29423A),
            wordHighlight = Color(0xFF57C99E),
            wordText = Color(0xFF07130E),
        )
        else -> ReaderPalette(
            background = Color(0xFFFBF9F4),
            text = Color(0xFF20241F),
            secondaryText = Color(0xFF5C635A),
            sentenceHighlight = Color(0xFFD6EFE4),
            wordHighlight = Color(0xFF2E9E76),
            wordText = Color(0xFFFFFFFF),
        )
    }
}
