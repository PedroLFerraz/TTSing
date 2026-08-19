package com.pedrolopes.ttsing.ui.reader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pedrolopes.ttsing.data.settings.ReaderTheme
import com.pedrolopes.ttsing.ui.theme.Ink

data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    /**
     * A wash behind the sentence being spoken. [Color.Transparent] on the black theme, which
     * tints the sentence itself instead — see [sentenceText].
     */
    val sentenceHighlight: Color,
    /** Colour the current sentence's glyphs take, or null to leave them as [text]. */
    val sentenceText: Color?,
    val wordHighlight: Color,
    val wordText: Color,
    /** The accent for chrome around the page: progress, the play button, live counts. */
    val accent: Color,
    /** Rules and tracks over [background]. */
    val hairline: Color,
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
            text = Color(0xFF2A2018),
            secondaryText = Color(0xFF7A6A57),
            sentenceHighlight = Color(0xFFE9DCB4),
            sentenceText = null,
            wordHighlight = Ink.LiveOnPaper,
            wordText = Color(0xFFFFFBEF),
            accent = Ink.LiveOnPaper,
            hairline = Color(0x33000000),
        )
        dark -> ReaderPalette(
            background = Ink.Surface,
            text = Ink.ReaderText,
            secondaryText = Ink.Muted,
            // The black theme marks the sentence by colouring it, not by painting behind it:
            // a yellow wash over a whole sentence would drown the page.
            sentenceHighlight = Color.Transparent,
            sentenceText = Ink.Live,
            wordHighlight = Ink.Live,
            wordText = Ink.Surface,
            accent = Ink.Live,
            hairline = Ink.Hairline,
        )
        else -> ReaderPalette(
            background = Color(0xFFFFFFFF),
            text = Color(0xFF111111),
            secondaryText = Color(0xFF6B6B66),
            sentenceHighlight = Color(0xFFFFF0B3),
            sentenceText = null,
            wordHighlight = Ink.LiveOnPaper,
            wordText = Color(0xFFFFFFFF),
            accent = Ink.LiveOnPaper,
            hairline = Color(0x22000000),
        )
    }
}
