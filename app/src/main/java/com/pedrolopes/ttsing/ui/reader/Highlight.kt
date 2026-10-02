package com.pedrolopes.ttsing.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Builds the chapter text with the current sentence marked — tinted, washed, or both — and
 * the word being spoken inverted on top of it. Both ranges are char offsets into [text].
 *
 * [sentenceColor] may be [Color.Transparent], in which case only [sentenceTextColor] shows;
 * that is how the black theme marks a sentence without flooding the page with yellow.
 */
fun highlightedText(
    text: String,
    sentenceRange: IntRange?,
    wordRange: IntRange?,
    sentenceColor: Color,
    sentenceTextColor: Color?,
    wordColor: Color,
    wordTextColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(text)
    sentenceRange?.let { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) {
            addStyle(
                SpanStyle(
                    background = sentenceColor,
                    color = sentenceTextColor ?: Color.Unspecified,
                ),
                start,
                end,
            )
        }
    }
    wordRange?.let { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) {
            addStyle(
                // Colour only: a heavier weight is wider and makes the line re-wrap.
                SpanStyle(background = wordColor, color = wordTextColor),
                start,
                end,
            )
        }
    }
}
