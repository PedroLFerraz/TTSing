package com.pedrolopes.ttsing.tts.piper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PiperCatalogTest {

    @Test
    fun `a voice folder says which language it speaks`() {
        assertEquals(Locale("pt", "BR"), PiperVoices.localeOf("vits-piper-pt_BR-faber-medium"))
        assertEquals(Locale("en", "US"), PiperVoices.localeOf("vits-piper-en_US-lessac-medium"))
        assertNull(PiperVoices.localeOf("something-else"))
    }

    @Test
    fun `the app's own voice is offered first`() {
        val pt = PiperCatalog.forLanguage(Locale("pt", "BR"))
        assertEquals("vits-piper-pt_BR-faber-medium", pt.first().id)
        assertTrue(pt.first().bundled)
        assertTrue("all Brazilian", pt.all { it.locale.country == "BR" })
    }

    @Test
    fun `a book's language finds voices from any of its countries`() {
        // A British book gets the American voices too: same language, and the reader can hear
        // the difference and choose.
        val en = PiperCatalog.forLanguage(Locale("en", "GB"))
        assertTrue("has GB", en.any { it.locale.country == "GB" })
        assertTrue("has US", en.any { it.locale.country == "US" })
    }

    @Test
    fun `voices are named for people, not for files`() {
        assertEquals("Faber", PiperCatalog.nameFor("vits-piper-pt_BR-faber-medium"))
        // One the catalogue doesn't list, dropped in by hand.
        assertEquals("Karen (high)", PiperCatalog.nameFor("vits-piper-en_GB-karen-high"))
        assertEquals("Hfc female (medium)", PiperCatalog.nameFor("vits-piper-xx_XX-hfc_female-medium"))
    }

    @Test
    fun `every catalogue voice downloads from the sherpa-onnx model release`() {
        PiperCatalog.voices.forEach { voice ->
            assertTrue(
                voice.id,
                voice.downloadUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/") &&
                    voice.downloadUrl.endsWith("${voice.id}.tar.bz2"),
            )
            assertEquals("${voice.id} locale", voice.locale, PiperVoices.localeOf(voice.id))
        }
    }

    @Test
    fun `download progress reads as a fraction`() {
        val progress = DownloadProgress("id", megabytes = 60, done = 15)
        assertEquals(0.25f, progress.fraction, 1e-6f)
        assertEquals(0f, DownloadProgress("id", megabytes = 0, done = 5).fraction, 1e-6f)
        assertEquals(1f, DownloadProgress("id", megabytes = 60, done = 99).fraction, 1e-6f)
    }
}
