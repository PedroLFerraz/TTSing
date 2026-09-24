package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.BookLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class VoiceChoiceTest {

    private fun voice(name: String, tag: String, quality: Int = 400, needsDownload: Boolean = false) =
        VoiceInfo(name, Locale.forLanguageTag(tag), quality, needsDownload)

    // Google's engine as the emulator has it: Portugal installed, Brazil on first use.
    private val googlePortuguese = listOf(
        voice("pt-pt-language", "pt-PT"),
        voice("pt-pt-x-jfb-local", "pt-PT"),
        voice("pt-br-language", "pt-BR", needsDownload = true),
        voice("pt-br-x-afs-local", "pt-BR", needsDownload = true),
    )

    @Test
    fun `a Brazilian book is not read in European Portuguese`() {
        val chosen = VoiceChoice.pick(
            Locale("pt", "BR"),
            googlePortuguese,
            engineDefault = voice("pt-pt-language", "pt-PT"),
        )
        assertEquals("pt-br-language", chosen)
    }

    @Test
    fun `a book from Portugal keeps a Portuguese voice`() {
        val chosen = VoiceChoice.pick(Locale("pt", "PT"), googlePortuguese, engineDefault = null)
        assertEquals("pt-pt-language", chosen)
    }

    @Test
    fun `an installed voice from the right country beats a better one that must download`() {
        val voices = listOf(
            voice("pt-br-plain", "pt-BR", quality = 300),
            voice("pt-br-fancy", "pt-BR", quality = 500, needsDownload = true),
        )
        assertEquals("pt-br-plain", VoiceChoice.pick(Locale("pt", "BR"), voices, null))
    }

    @Test
    fun `the engine's own default is kept when it fits`() {
        val voices = listOf(voice("pt-br-one", "pt-BR"), voice("pt-br-two", "pt-BR"))
        val chosen = VoiceChoice.pick(Locale("pt", "BR"), voices, engineDefault = voice("pt-br-two", "pt-BR"))
        assertEquals("pt-br-two", chosen)
    }

    @Test
    fun `no voice for the language at all`() {
        assertNull(VoiceChoice.pick(Locale("ja"), googlePortuguese, engineDefault = null))
    }

    @Test
    fun `a bare pt becomes Brazilian, and a stated region is left alone`() {
        val device = Locale("en", "US")
        assertEquals(Locale("pt", "BR"), BookLanguage.withRegion(Locale("pt"), device))
        assertEquals(Locale("pt", "PT"), BookLanguage.withRegion(Locale("pt", "PT"), device))
    }

    @Test
    fun `the reader's own region wins when they speak the language`() {
        assertEquals(Locale("pt", "PT"), BookLanguage.withRegion(Locale("pt"), Locale("pt", "PT")))
    }

    @Test
    fun `a language with no usual region is left as it is`() {
        assertEquals(Locale("fi"), BookLanguage.withRegion(Locale("fi"), Locale("en", "US")))
    }
}
