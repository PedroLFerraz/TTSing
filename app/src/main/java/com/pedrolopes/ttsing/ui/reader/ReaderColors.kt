package com.pedrolopes.ttsing.ui.reader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
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
fun readerPalette(theme: ReaderTheme): ReaderPalette = paletteFor(theme, isSystemInDarkTheme())

/** The palette for [theme]; [systemDark] only matters to [ReaderTheme.SYSTEM]. */
internal fun paletteFor(theme: ReaderTheme, systemDark: Boolean): ReaderPalette {
    val dark = when (theme) {
        ReaderTheme.SYSTEM -> systemDark
        ReaderTheme.DARK -> true
        else -> false
    }
    return when {
        theme == ReaderTheme.SEPIA -> ReaderPalette(
            background = Color(0xFFF4ECD8),
            text = Color(0xFF2A2018),
            secondaryText = Color(0xFF6E5F4C),
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

/**
 * True when the page is light, so the status and navigation bars over it need dark icons.
 * Light means black text reads better on it than white does (relative luminance above ~0.18).
 */
internal val ReaderPalette.isLight: Boolean get() = relativeLuminance(background) > 0.179

/** WCAG relative luminance of an opaque sRGB colour. */
internal fun relativeLuminance(color: Color): Double {
    fun channel(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** WCAG contrast ratio between two opaque colours, 1 (identical) to 21 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
