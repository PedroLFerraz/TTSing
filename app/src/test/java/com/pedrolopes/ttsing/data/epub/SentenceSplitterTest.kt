package com.pedrolopes.ttsing.data.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SentenceSplitterTest {

    private fun sentencesOf(text: String, locale: Locale): List<String> =
        SentenceSplitter.split(text, locale).map { text.substring(it.start, it.end) }

    @Test
    fun `splits english sentences`() {
        val text = "Hello world. How are you today? I am fine!"
        val sentences = sentencesOf(text, Locale.ENGLISH)
        assertEquals(listOf("Hello world.", "How are you today?", "I am fine!"), sentences)
    }

    @Test
    fun `splits portuguese sentences`() {
        val text = "Era uma vez um menino. Ele morava no Porto. Gostava muito de ler?"
        val sentences = sentencesOf(text, Locale.forLanguageTag("pt-PT"))
        assertEquals(3, sentences.size)
        assertEquals("Era uma vez um menino.", sentences[0])
        assertEquals("Ele morava no Porto.", sentences[1])
    }

    @Test
    fun `spans are trimmed and within bounds`() {
        val text = "  First sentence.   Second one.  "
        val spans = SentenceSplitter.split(text, Locale.ENGLISH)
        for (span in spans) {
            assertTrue(span.start >= 0 && span.end <= text.length && span.start < span.end)
            assertTrue(!text[span.start].isWhitespace())
            assertTrue(!text[span.end - 1].isWhitespace())
        }
        assertEquals("First sentence.", text.substring(spans[0].start, spans[0].end))
        assertEquals("Second one.", text.substring(spans[1].start, spans[1].end))
    }

    @Test
    fun `blank text yields no sentences`() {
        assertTrue(SentenceSplitter.split("   ", Locale.ENGLISH).isEmpty())
    }
}
