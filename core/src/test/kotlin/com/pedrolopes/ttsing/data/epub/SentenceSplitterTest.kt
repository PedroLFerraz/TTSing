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

    // ---- breaks that are not sentence ends ----

    private val pt = Locale.forLanguageTag("pt-BR")

    @Test
    fun `initials stay with the name - as on Plato's title page`() {
        assertEquals(
            listOf("Translated by W. H. D. Rouse.", "With a New Introduction by Matthew S. Santirocco."),
            sentencesOf("Translated by W. H. D. Rouse. With a New Introduction by Matthew S. Santirocco.", Locale.ENGLISH),
        )
    }

    @Test
    fun `a page reference does not end the sentence - as in Plato's Ion`() {
        assertEquals(
            listOf(
                "The dialogue foreshadows the views on art which are explained in the Republic (see pp. 481–482).",
                "SOCRATES: Good morning, Ion.",
            ),
            sentencesOf(
                "The dialogue foreshadows the views on art which are explained in the Republic (see pp. 481–482). " +
                    "SOCRATES: Good morning, Ion.",
                Locale.ENGLISH,
            ),
        )
    }

    @Test
    fun `titles of address stay with the name`() {
        assertEquals(
            listOf("O Sr. Silva e a Dra. Ana chegaram cedo.", "A Sra. Costa e o Dr. Lima também."),
            sentencesOf("O Sr. Silva e a Dra. Ana chegaram cedo. A Sra. Costa e o Dr. Lima também.", pt),
        )
        assertEquals(
            listOf("Mr. Smith met Dr. Jones and Prof. Brown.", "They talked."),
            sentencesOf("Mr. Smith met Dr. Jones and Prof. Brown. They talked.", Locale.ENGLISH),
        )
    }

    @Test
    fun `roman numerals do not end the sentence`() {
        assertEquals(
            listOf("Livro IV. O rio corria.", "Veio então o capítulo XIV. De novo o rio."),
            sentencesOf("Livro IV. O rio corria. Veio então o capítulo XIV. De novo o rio.", pt),
        )
        assertEquals(
            listOf("Part I. The Wall and Part II. The Garden follow."),
            sentencesOf("Part I. The Wall and Part II. The Garden follow.", Locale.ENGLISH),
        )
    }

    @Test
    fun `portuguese page and chapter references stay whole`() {
        assertEquals(
            listOf("Veja a pág. 12 e o cap. 3 do livro.", "Depois continue."),
            sentencesOf("Veja a pág. 12 e o cap. 3 do livro. Depois continue.", pt),
        )
    }

    @Test
    fun `D for Dona is an initial`() {
        assertEquals(
            listOf("Chegou D. Maria com a filha.", "Era tarde."),
            sentencesOf("Chegou D. Maria com a filha. Era tarde.", pt),
        )
    }

    @Test
    fun `a lower-case continuation joins the sentence`() {
        assertEquals(
            listOf("We met at 5 p.m. on the corner, e.g. near the shop.", "Then we left."),
            sentencesOf("We met at 5 p.m. on the corner, e.g. near the shop. Then we left.", Locale.ENGLISH),
        )
        assertEquals(
            listOf("— Não? — disse ele.", "E saiu."),
            sentencesOf("— Não? — disse ele. E saiu.", pt),
        )
    }

    @Test
    fun `a lone list marker joins what it numbers`() {
        assertEquals(
            listOf("1. Introduction to the garden.", "2. The wall itself."),
            sentencesOf("1. Introduction to the garden. 2. The wall itself.", Locale.ENGLISH),
        )
    }

    // ---- real sentence ends stay ends ----

    @Test
    fun `one-word sentences stay their own sentences`() {
        assertEquals(
            listOf("Sem fazer véspera.", "Sou doido?", "Não.", "Na nossa casa, a palavra doido não se falava."),
            sentencesOf("Sem fazer véspera. Sou doido? Não. Na nossa casa, a palavra doido não se falava.", pt),
        )
        assertEquals(
            listOf("Yes.", "No!", "Why?", "Indeed.", "No.", "Then let us go."),
            sentencesOf("Yes. No! Why? Indeed. No. Then let us go.", Locale.ENGLISH),
        )
    }

    @Test
    fun `the pronoun I ends a sentence, the numeral I does not`() {
        assertEquals(
            listOf("MENON: Not I.", "But look here, Socrates."),
            sentencesOf("MENON: Not I. But look here, Socrates.", Locale.ENGLISH),
        )
        assertEquals(listOf("I. PLATO AND PHILOSOPHY"), sentencesOf("I. PLATO AND PHILOSOPHY", Locale.ENGLISH))
        assertEquals(
            listOf("Livro I. O rio corria.", "Parou."),
            sentencesOf("Livro I. O rio corria. Parou.", pt),
        )
    }

    @Test
    fun `punctuation on its own joins the sentence before it`() {
        // As ICU hands them over on a device: a footnote mark and a stray stop as spans of
        // their own. (The JDK used in tests doesn't split there, so the merge is fed directly.)
        val text = "It was mine. * . Then it ended."
        val spans = listOf(SentenceSpan(0, 12), SentenceSpan(13, 14), SentenceSpan(15, 16), SentenceSpan(17, 31))
        assertEquals(
            listOf("It was mine. * .", "Then it ended."),
            SentenceSplitter.mergeFalseBreaks(text, spans).map { text.substring(it.start, it.end) },
        )
    }

    @Test
    fun `lower-case continuations stop growing a sentence past a breath`() {
        val piece = "Íamos ensaiar outra vez, ensaiar, ensaiar, senhor, com os rebuliços, as horas curtas, poucas"
        val text = (1..8).joinToString(" ") { "$piece! — e" } + " fim."
        val sentences = sentencesOf(text, pt)
        assertTrue("got ${sentences.size}", sentences.size > 1)
        assertTrue(sentences.all { it.length < 500 })
    }

    @Test
    fun `ordinary words and numbers ending a sentence are not mistaken for abbreviations`() {
        assertEquals(listOf("He did.", "Then he left."), sentencesOf("He did. Then he left.", Locale.ENGLISH))
        assertEquals(listOf("It was 1984.", "Then it was not."), sentencesOf("It was 1984. Then it was not.", Locale.ENGLISH))
        assertEquals(listOf("Ele disse que é.", "Depois foi."), sentencesOf("Ele disse que é. Depois foi.", pt))
        assertEquals(listOf("Comprou pão, leite etc.", "Voltou."), sentencesOf("Comprou pão, leite etc. Voltou.", pt))
    }
}
