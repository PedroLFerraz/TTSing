package com.pedrolopes.ttsing.ui.reader

import androidx.compose.ui.graphics.Color
import com.pedrolopes.ttsing.data.settings.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderColorsTest {

    private val all = listOf(
        "light" to paletteFor(ReaderTheme.LIGHT, systemDark = false),
        "sepia" to paletteFor(ReaderTheme.SEPIA, systemDark = false),
        "dark" to paletteFor(ReaderTheme.DARK, systemDark = false),
        "system on a dark phone" to paletteFor(ReaderTheme.SYSTEM, systemDark = true),
        "system on a light phone" to paletteFor(ReaderTheme.SYSTEM, systemDark = false),
    )

    @Test
    fun `light and sepia pages are light, black is not`() {
        assertTrue(paletteFor(ReaderTheme.LIGHT, systemDark = true).isLight)
        assertTrue(paletteFor(ReaderTheme.SEPIA, systemDark = true).isLight)
        assertFalse(paletteFor(ReaderTheme.DARK, systemDark = false).isLight)
    }

    @Test
    fun `the system theme is as light as the phone`() {
        assertFalse(paletteFor(ReaderTheme.SYSTEM, systemDark = true).isLight)
        assertTrue(paletteFor(ReaderTheme.SYSTEM, systemDark = false).isLight)
    }

    @Test
    fun `contrast of black on white is 21 and of a colour on itself 1`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01)
        assertEquals(1.0, contrastRatio(Color(0xFF856100), Color(0xFF856100)), 0.0001)
    }

    @Test
    fun `accent text is readable on every page`() {
        all.forEach { (name, p) ->
            val ratio = contrastRatio(p.accent, p.background)
            assertTrue("$name: accent $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `the word highlight keeps its text readable`() {
        all.forEach { (name, p) ->
            val ratio = contrastRatio(p.wordText, p.wordHighlight)
            assertTrue("$name: word $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `body, secondary and sentence text are readable on every page`() {
        all.forEach { (name, p) ->
            assertTrue("$name: text", contrastRatio(p.text, p.background) >= 7.0)
            val secondary = contrastRatio(p.secondaryText, p.background)
            assertTrue("$name: secondary $secondary", secondary >= 4.5)
            // A wash is drawn over the page; a coloured sentence is drawn on it.
            if (p.sentenceHighlight.alpha > 0f) {
                assertTrue("$name: sentence wash", contrastRatio(p.text, p.sentenceHighlight) >= 4.5)
            }
            p.sentenceText?.let { assertTrue("$name: sentence text", contrastRatio(it, p.background) >= 4.5) }
        }
    }
}
