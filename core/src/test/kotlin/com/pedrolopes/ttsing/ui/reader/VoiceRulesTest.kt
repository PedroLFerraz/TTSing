package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.tts.piper.CatalogVoice
import com.pedrolopes.ttsing.tts.piper.DownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceRulesTest {

    private val faber = CatalogVoice("vits-piper-pt_BR-faber-medium", Locale("pt", "BR"), "Faber", 67)
    private val amy = CatalogVoice("vits-piper-en_US-amy-medium", Locale("en", "US"), "Amy", 67)

    @Test
    fun `the engine having no voices is only the whole story without neural ones`() {
        assertEquals(VoiceRules.NoVoices.NONE, VoiceRules.noVoices(deviceVoiceCount = 3, neuralVoiceCount = 0))
        assertEquals(VoiceRules.NoVoices.DEVICE_ONLY, VoiceRules.noVoices(deviceVoiceCount = 0, neuralVoiceCount = 2))
        assertEquals(VoiceRules.NoVoices.ALL, VoiceRules.noVoices(deviceVoiceCount = 0, neuralVoiceCount = 0))
    }

    @Test
    fun `deleting a voice clears every language that had chosen it`() {
        val stored = mapOf("pt" to faber.id, "en" to amy.id, "es" to faber.id)
        assertEquals(setOf("pt", "es"), VoiceRules.languagesToClear(stored, faber.id).toSet())
        assertEquals(listOf("en"), VoiceRules.languagesToClear(stored, amy.id))
        assertTrue(VoiceRules.languagesToClear(stored, "other").isEmpty())
    }

    @Test
    fun `prompts say what is at stake`() {
        assertEquals("Delete Faber? Frees about 67 MB.", VoiceRules.deletePrompt(faber))
        assertEquals("About 67 MB on mobile data. Download Faber anyway?", VoiceRules.meteredPrompt(faber))
    }

    @Test
    fun `a tap while another voice downloads is told so, not ignored`() {
        val running = DownloadProgress(amy.id, 67, 10)
        assertEquals(VoiceRules.DownloadTap.BUSY, VoiceRules.downloadTap(faber, running, metered = false))
        assertEquals(VoiceRules.DownloadTap.IGNORE, VoiceRules.downloadTap(amy, running, metered = false))
    }

    @Test
    fun `a failed download does not block the next tap, and mobile data asks first`() {
        val failed = DownloadProgress(amy.id, 67, 0, failed = true, reason = "No connection")
        assertEquals(VoiceRules.DownloadTap.START, VoiceRules.downloadTap(faber, failed, metered = false))
        assertEquals(VoiceRules.DownloadTap.ASK_METERED, VoiceRules.downloadTap(faber, null, metered = true))
        assertEquals(VoiceRules.DownloadTap.START, VoiceRules.downloadTap(faber, null, metered = false))
    }

    @Test
    fun `a neural row shows progress, the failure reason, or where the voice is from`() {
        val region = "Portuguese (Brazil)"
        assertEquals(
            "Downloading — 12 of 67 MB",
            VoiceRules.neuralSubtitle(faber, false, DownloadProgress(faber.id, 67, 12), region, false),
        )
        assertEquals(
            "No connection — tap to retry",
            VoiceRules.neuralSubtitle(
                faber, false, DownloadProgress(faber.id, 67, 0, failed = true, reason = "No connection"), region, false,
            ),
        )
        assertEquals(
            "Portuguese (Brazil) · offline · in this app · neural",
            VoiceRules.neuralSubtitle(faber, true, null, region, false),
        )
        assertEquals(
            "Ready for when you read in Portuguese (Brazil)",
            VoiceRules.neuralSubtitle(faber, true, null, region, true),
        )
        // Another voice's progress says nothing about this row.
        assertEquals(
            "Portuguese (Brazil) · tap to download · 67 MB",
            VoiceRules.neuralSubtitle(faber, false, DownloadProgress(amy.id, 67, 5), region, false),
        )
    }
}

class SleepTimerUiTest {

    private fun lit(chip: Int?, chapter: Boolean, started: Int?, left: Int?) =
        SleepTimerUi.chipSelected(chip, chapter, started, left)

    @Test
    fun `the chip that started the timer stays lit as it counts down`() {
        // A 30-minute timer, 15 minutes in, used to light "15"; 60 at 40 left lit nothing.
        assertTrue(lit(30, false, started = 30, left = 15))
        assertFalse(lit(15, false, started = 30, left = 15))
        assertTrue(lit(60, false, started = 60, left = 40))
        assertFalse(lit(30, false, started = 60, left = 40))
    }

    @Test
    fun `off and chapter chips follow their own state`() {
        assertTrue(lit(null, false, started = null, left = null))
        assertFalse(lit(null, false, started = 15, left = 10))
        assertTrue(lit(null, true, started = null, left = CHAPTER_END))
        assertFalse(lit(15, false, started = null, left = CHAPTER_END))
        assertFalse(lit(null, true, started = 15, left = 10))
    }

    @Test
    fun `remaining time reads as a sentence`() {
        assertNull(SleepTimerUi.remaining(null))
        assertEquals("Stops in 23 min", SleepTimerUi.remaining(23))
        assertEquals("Stops at the end of the chapter", SleepTimerUi.remaining(CHAPTER_END))
    }
}
