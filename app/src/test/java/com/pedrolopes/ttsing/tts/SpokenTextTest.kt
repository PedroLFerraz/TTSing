package com.pedrolopes.ttsing.tts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SpokenTextTest {

    private val pt = Locale.forLanguageTag("pt-BR")
    private val en = Locale.forLanguageTag("en-US")

    @Test
    fun `portuguese titles are spelled out`() {
        assertEquals("O Doutor Lima chegou.", SpokenText.normalize("O Dr. Lima chegou.", pt))
        assertEquals("a Senhora Ana e o Senhor Rui.", SpokenText.normalize("a Sra. Ana e o Sr. Rui.", pt))
        assertEquals("A Doutora Silva e os Doutores Lima.", SpokenText.normalize("A Dra. Silva e os Drs. Lima.", pt))
    }

    @Test
    fun `english titles are spelled out`() {
        assertEquals("Doctor Smith met Missus Hudson.", SpokenText.normalize("Dr. Smith met Mrs. Hudson.", en))
    }

    @Test
    fun `dona and dom follow the name`() {
        assertEquals("Dona Maria chegou.", SpokenText.normalize("D. Maria chegou.", pt))
        assertEquals("e Dom Pedro chegou.", SpokenText.normalize("e D. Pedro chegou.", pt))
        assertEquals("e Dona Isabel chegou.", SpokenText.normalize("e D. Isabel chegou.", pt))
    }

    @Test
    fun `S before a name is a saint`() {
        assertEquals("em São Paulo ficou.", SpokenText.normalize("em S. Paulo ficou.", pt))
    }

    @Test
    fun `an initial inside a name only loses its dot`() {
        assertEquals("José S Lima veio.", SpokenText.normalize("José S. Lima veio.", pt))
        assertEquals("J R R Tolkien wrote it.", SpokenText.normalize("J. R. R. Tolkien wrote it.", en))
        assertEquals("by J P Morgan.", SpokenText.normalize("by J. P. Morgan.", en))
    }

    @Test
    fun `other known abbreviations lose their dot`() {
        assertEquals("Livro IV O rio.", SpokenText.normalize("Livro IV. O rio.", pt))
        assertEquals("see p 12 and pp 14.", SpokenText.normalize("see p. 12 and pp. 14.", pt))
    }

    @Test
    fun `the sentence's own stop is kept`() {
        assertEquals("Chamou o Dr.", SpokenText.normalize("Chamou o Dr.", pt))
        assertEquals("Fim.", SpokenText.normalize("Fim.", pt))
    }

    @Test
    fun `text without abbreviations is unchanged`() {
        val text = "Era uma vez, num jardim pequeno, uma flor. E outra."
        assertEquals(text, SpokenText.normalize(text, pt))
    }

    @Test
    fun `opening punctuation and capitals are kept`() {
        assertEquals("(Doutor Lima) disse.", SpokenText.normalize("(Dr. Lima) disse.", pt))
    }
}
