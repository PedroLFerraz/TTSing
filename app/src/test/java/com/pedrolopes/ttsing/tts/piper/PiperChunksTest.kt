package com.pedrolopes.ttsing.tts.piper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperChunksTest {

    @Test
    fun `an ordinary sentence is left whole`() {
        val text = "Esta é a estória. Ia um menino, com os Tios, passar dias no lugar."
        assertEquals(listOf(text), PiperChunks.split(text))
    }

    @Test
    fun `a long sentence breaks where the writing already breathes`() {
        val text = "O contentamento — o medo. O fraque? O povo. O — ali, quem meio escondido, " +
            "me cutucando — o Alfeu! — do que ele tinha furtado: uma garrafa de genebra, " +
            "da adega dos padres — falava que era para dar mais alma de coragem."
        val pieces = PiperChunks.split(text)
        assertTrue("several pieces", pieces.size > 1)
        pieces.forEach { assertTrue("$it is ${it.length} long", it.length <= PiperChunks.LIMIT) }
        // Nothing is lost: the words come back in order.
        assertEquals(text.filter { !it.isWhitespace() }, pieces.joinToString("").filter { !it.isWhitespace() })
    }

    @Test
    fun `text with nowhere to break is still cut`() {
        val text = "a".repeat(500)
        val pieces = PiperChunks.split(text, limit = 100)
        assertEquals(5, pieces.size)
        assertEquals(text, pieces.joinToString(""))
    }

    @Test
    fun `a break too near the start is not used`() {
        // The comma at character 3 would leave "Sim," alone; the later one is taken instead.
        val text = "Sim, " + "palavra ".repeat(30)
        val pieces = PiperChunks.split(text, limit = 60)
        assertTrue("first piece is a phrase, not a scrap", pieces.first().length > 20)
    }
}
