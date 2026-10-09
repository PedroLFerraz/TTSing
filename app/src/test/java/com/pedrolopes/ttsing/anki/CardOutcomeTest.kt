package com.pedrolopes.ttsing.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardOutcomeTest {

    @Test
    fun `added clears the draft`() {
        val outcome = cardOutcome(false, AnkiExporter.Result.Added(audioAttached = true))
        assertFalse(outcome.keepDraft)
        assertEquals("Card added to TTSing", outcome.message)
        assertFalse(outcome.offerWithoutAudio)
    }

    @Test
    fun `added without audio says so and still clears the draft`() {
        val outcome = cardOutcome(false, AnkiExporter.Result.Added(audioAttached = false))
        assertFalse(outcome.keepDraft)
        assertEquals("Card added to TTSing (without audio)", outcome.message)
    }

    @Test
    fun `every failure keeps the draft`() {
        listOf(
            AnkiExporter.Result.AnkiNotInstalled,
            AnkiExporter.Result.PermissionDenied,
            AnkiExporter.Result.Failed("boom"),
        ).forEach { result ->
            val outcome = cardOutcome(false, result)
            assertTrue("$result", outcome.keepDraft)
            assertFalse("$result", outcome.offerWithoutAudio)
        }
    }

    @Test
    fun `failure messages say what went wrong`() {
        assertEquals("AnkiDroid isn't installed", cardOutcome(false, AnkiExporter.Result.AnkiNotInstalled).message)
        assertEquals("AnkiDroid permission denied", cardOutcome(false, AnkiExporter.Result.PermissionDenied).message)
        assertEquals("Could not add the card: boom", cardOutcome(false, AnkiExporter.Result.Failed("boom")).message)
    }

    @Test
    fun `audio failure keeps the draft and offers adding without audio`() {
        val outcome = cardOutcome(true, null)
        assertTrue(outcome.keepDraft)
        assertTrue(outcome.offerWithoutAudio)
    }

    @Test
    fun `not installed wins over everything`() {
        assertEquals(AnkiState.NotInstalled, ankiState(installed = false, granted = true, deniedOnce = true, canAskAgain = false))
        assertEquals(AnkiState.NotInstalled, ankiState(installed = false, granted = false, deniedOnce = false, canAskAgain = true))
    }

    @Test
    fun `granted is ready`() {
        assertEquals(AnkiState.Ready, ankiState(installed = true, granted = true, deniedOnce = false, canAskAgain = false))
        // Granted later through the app settings, after a permanent denial.
        assertEquals(AnkiState.Ready, ankiState(installed = true, granted = true, deniedOnce = true, canAskAgain = false))
    }

    @Test
    fun `never asked is ready even though rationale is false`() {
        assertEquals(AnkiState.Ready, ankiState(installed = true, granted = false, deniedOnce = false, canAskAgain = false))
    }

    @Test
    fun `denied once with rationale can ask again`() {
        assertEquals(AnkiState.PermissionDenied, ankiState(installed = true, granted = false, deniedOnce = true, canAskAgain = true))
    }

    @Test
    fun `denied once without rationale is blocked`() {
        assertEquals(AnkiState.PermissionBlocked, ankiState(installed = true, granted = false, deniedOnce = true, canAskAgain = false))
    }
}
