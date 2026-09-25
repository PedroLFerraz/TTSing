package com.pedrolopes.ttsing.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppSettingsLanguageTest {

    private fun settings(
        voices: Map<String, String> = emptyMap(),
        bookLanguages: Map<String, String> = emptyMap(),
    ) = AppSettings(
        libraryFolderUri = null,
        speechRate = 1f,
        fontScale = 1f,
        readerTheme = ReaderTheme.SYSTEM,
        voices = voices,
        bookLanguages = bookLanguages,
    )

    private val german = Locale.forLanguageTag("de")
    private val english = Locale.forLanguageTag("en")

    @Test
    fun `falls back to the declared language when there is no override`() {
        assertEquals(english, settings().localeFor("book1", english))
    }

    @Test
    fun `an override wins over the declared language`() {
        val s = settings(bookLanguages = mapOf("book1" to "de"))
        assertEquals("de", s.localeFor("book1", english).language)
    }

    @Test
    fun `an override applies only to the book it was set for`() {
        val s = settings(bookLanguages = mapOf("book1" to "de"))
        assertEquals("de", s.localeFor("book1", english).language)
        assertEquals("en", s.localeFor("book2", english).language)
    }

    @Test
    fun `a null book id falls back to the declared language`() {
        val s = settings(bookLanguages = mapOf("book1" to "de"))
        assertEquals(english, s.localeFor(null, english))
    }

    @Test
    fun `a junk override falls back rather than yielding an empty locale`() {
        val s = settings(bookLanguages = mapOf("book1" to "!!!"))
        assertEquals(english, s.localeFor("book1", english))
    }

    @Test
    fun `region-qualified overrides keep their region`() {
        val s = settings(bookLanguages = mapOf("book1" to "de-AT"))
        val locale = s.localeFor("book1", english)
        assertEquals("de", locale.language)
        assertEquals("AT", locale.country)
    }

    @Test
    fun `voices are looked up per language`() {
        val s = settings(voices = mapOf("de" to "de-DE-x-nfh#female_1", "en" to "en-GB-lang"))
        assertEquals("de-DE-x-nfh#female_1", s.voiceFor("de"))
        assertEquals("en-GB-lang", s.voiceFor("en"))
        assertNull(s.voiceFor("pt"))
    }

    @Test
    fun `german and english voices no longer overwrite each other`() {
        // The old storage had only voiceEn/voicePt, so a German choice landed in voice_en.
        val s = settings(voices = mapOf("de" to "german-voice", "en" to "english-voice"))
        assertEquals("german-voice", s.voiceFor(german.language))
        assertEquals("english-voice", s.voiceFor(english.language))
    }
}
