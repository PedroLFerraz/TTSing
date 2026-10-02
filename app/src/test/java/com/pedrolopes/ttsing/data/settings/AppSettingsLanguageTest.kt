package com.pedrolopes.ttsing.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppSettingsLanguageTest {

    private fun settings(
        voices: Map<String, String> = emptyMap(),
        bookLanguages: Map<String, String> = emptyMap(),
        variants: Map<String, String> = emptyMap(),
    ) = AppSettings(
        libraryFolderUri = null,
        speechRate = 1f,
        fontScale = 1f,
        readerTheme = ReaderTheme.SYSTEM,
        voices = voices,
        bookLanguages = bookLanguages,
        variants = variants,
    )

    private val german = Locale.forLanguageTag("de")
    private val english = Locale.forLanguageTag("en")
    private val portuguese = Locale.forLanguageTag("pt")

    /** A device set to German in Germany, so no test depends on the machine running it. */
    private val device = Locale.GERMANY

    @Test
    fun `falls back to the declared language when there is no override`() {
        assertEquals(Locale.US, settings().localeFor("book1", english, device))
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
        assertEquals(Locale.US, s.localeFor(null, english, device))
    }

    @Test
    fun `a junk override falls back rather than yielding an empty locale`() {
        val s = settings(bookLanguages = mapOf("book1" to "!!!"))
        assertEquals(Locale.US, s.localeFor("book1", english, device))
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

    @Test
    fun `a bare language is given its most spoken region`() {
        assertEquals(Locale("pt", "BR"), settings().localeFor("book1", portuguese, device))
    }

    @Test
    fun `the preferred variant wins over the region a book declares`() {
        val s = settings(variants = mapOf("pt" to "pt-PT"))
        assertEquals(Locale("pt", "PT"), s.localeFor("book1", portuguese, device))
        assertEquals(Locale("pt", "PT"), s.localeFor("book1", Locale("pt", "BR"), device))
    }

    @Test
    fun `a bare override takes the preferred variant`() {
        val s = settings(bookLanguages = mapOf("book1" to "pt"), variants = mapOf("pt" to "pt-BR"))
        assertEquals(Locale("pt", "BR"), s.localeFor("book1", english, device))
    }

    @Test
    fun `an override with its own region beats the preferred variant`() {
        val s = settings(bookLanguages = mapOf("book1" to "pt-PT"), variants = mapOf("pt" to "pt-BR"))
        assertEquals(Locale("pt", "PT"), s.localeFor("book1", english, device))
    }

    @Test
    fun `a variant of another language is ignored`() {
        val s = settings(variants = mapOf("pt" to "en-GB"))
        assertEquals(Locale("pt", "BR"), s.localeFor("book1", portuguese, device))
    }
}
