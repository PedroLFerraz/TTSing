package com.pedrolopes.ttsing.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WordSplitterTest {

    private fun wordsOf(text: String, locale: Locale): List<String> =
        WordSplitter.split(text, locale).map { text.substring(it.start, it.end) }

    @Test
    fun `splits english words and drops punctuation`() {
        val text = "Hello, world! How are you?"
        assertEquals(listOf("Hello", "world", "How", "are", "you"), wordsOf(text, Locale.ENGLISH))
    }

    @Test
    fun `keeps portuguese accented words whole`() {
        val text = "Ele não tinha coração para isso."
        assertEquals(
            listOf("Ele", "não", "tinha", "coração", "para", "isso"),
            wordsOf(text, Locale.forLanguageTag("pt-PT")),
        )
    }

    @Test
    fun `spans index back into the source text`() {
        val text = "  First   second.  "
        val spans = WordSplitter.split(text, Locale.ENGLISH)
        for (span in spans) {
            assertTrue(span.start >= 0 && span.end <= text.length && span.start < span.end)
            assertTrue(text.substring(span.start, span.end).any { it.isLetterOrDigit() })
        }
        assertEquals("First", text.substring(spans[0].start, spans[0].end))
        assertEquals("second", text.substring(spans[1].start, spans[1].end))
    }

    @Test
    fun `drops quotes and dashes but keeps the words around them`() {
        val text = "— “Bom dia”, disse ela."
        assertEquals(listOf("Bom", "dia", "disse", "ela"), wordsOf(text, Locale.forLanguageTag("pt-PT")))
    }

    @Test
    fun `hyphenated compounds stay a single word`() {
        val text = "Um guarda-chuva velho."
        assertEquals(
            listOf("Um", "guarda-chuva", "velho"),
            wordsOf(text, Locale.forLanguageTag("pt-PT")),
        )
    }

    @Test
    fun `contractions stay a single word`() {
        assertEquals(listOf("I", "don't", "know"), wordsOf("I don't know", Locale.ENGLISH))
    }

    @Test
    fun `blank text yields no words`() {
        assertTrue(WordSplitter.split("   ", Locale.ENGLISH).isEmpty())
        assertTrue(WordSplitter.split("", Locale.ENGLISH).isEmpty())
    }
}
