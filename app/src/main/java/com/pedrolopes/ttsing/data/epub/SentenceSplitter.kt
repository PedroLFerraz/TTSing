package com.pedrolopes.ttsing.data.epub

import java.text.BreakIterator
import java.util.Locale

object SentenceSplitter {

    fun split(text: String, locale: Locale): List<SentenceSpan> {
        if (text.isBlank()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(text)
        val spans = mutableListOf<SentenceSpan>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            var s = start
            var e = end
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) spans.add(SentenceSpan(s, e))
            start = end
            end = iterator.next()
        }
        return spans
    }
}
