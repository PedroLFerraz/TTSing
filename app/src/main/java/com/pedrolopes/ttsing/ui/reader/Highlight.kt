package com.pedrolopes.ttsing.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * Builds the chapter text with a soft highlight over the current sentence and a strong
 * highlight over the word being spoken. Both ranges are char offsets into [text].
 */
fun highlightedText(
    text: String,
    sentenceRange: IntRange?,
    wordRange: IntRange?,
    sentenceColor: Color,
    wordColor: Color,
    wordTextColor: Color,
): AnnotatedString = buildAnnotatedString {
    append(text)
    sentenceRange?.let { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(SpanStyle(background = sentenceColor), start, end)
    }
    wordRange?.let { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) {
            addStyle(
                SpanStyle(background = wordColor, color = wordTextColor, fontWeight = FontWeight.SemiBold),
                start,
                end,
            )
        }
    }
}
