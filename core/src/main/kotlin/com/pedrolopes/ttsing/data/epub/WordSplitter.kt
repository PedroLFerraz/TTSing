package com.pedrolopes.ttsing.data.epub

import java.text.BreakIterator
import java.util.Locale

object WordSplitter {

    /**
     * Splits [text] into word spans using the book's [locale], dropping the whitespace and
     * punctuation segments [BreakIterator] also reports. Offsets index back into [text].
     */
    fun split(text: String, locale: Locale): List<SentenceSpan> {
        if (text.isBlank()) return emptyList()
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)
        val spans = mutableListOf<SentenceSpan>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (hasLetter(text, start, end)) spans.add(SentenceSpan(start, end))
            start = end
            end = iterator.next()
        }
        return spans
    }

    private fun hasLetter(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (text[i].isLetter() || text[i].isDigit()) return true
        }
        return false
    }
}
