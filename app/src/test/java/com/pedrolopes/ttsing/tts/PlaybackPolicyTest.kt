package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.ReadingPosition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {

    private val last = ReadingPosition(9, 4, 2)
    private val middle = ReadingPosition(3, 1, 0)
    private val saved = ReadingPosition(2, 0, 1)

    // ---- where Play starts ----

    @Test
    fun playAfterFinishStartsTheBookOver() {
        val start = PlaybackPolicy.startPosition(requested = null, finished = true, current = last, restored = saved)
        assertEquals(ReadingPosition.START, start)
    }

    @Test
    fun anExplicitPositionWinsEvenAfterFinish() {
        val start = PlaybackPolicy.startPosition(requested = middle, finished = true, current = last, restored = saved)
        assertEquals(middle, start)
    }

    @Test
    fun playCarriesOnFromTheNarratorsSentence() {
        val start = PlaybackPolicy.startPosition(requested = null, finished = false, current = middle, restored = saved)
        assertEquals(middle, start)
    }

    @Test
    fun playWithNothingSpokenYetUsesTheSavedPlace() {
        val start = PlaybackPolicy.startPosition(requested = null, finished = false, current = null, restored = saved)
        assertEquals(saved, start)
    }

    @Test
    fun finishedIsOffByDefaultAndCopyKeepsItSeparate() {
        assertFalse(PlaybackState().finished)
        assertTrue(PlaybackState().copy(finished = true).finished)
    }

    // ---- what is saved when playback ends ----

    @Test
    fun aFinishedArticleIsSavedAtItsStart() {
        assertEquals(
            ReadingPosition.START,
            PlaybackPolicy.positionToSave(isArticle = true, finishing = true, position = ReadingPosition(0, 7, 3)),
        )
    }

    @Test
    fun aFinishedBookKeepsItsParkedPosition() {
        assertEquals(last, PlaybackPolicy.positionToSave(isArticle = false, finishing = true, position = last))
    }

    @Test
    fun anArticlePausedMidwayKeepsItsPlace() {
        val place = ReadingPosition(0, 7, 3)
        assertEquals(place, PlaybackPolicy.positionToSave(isArticle = true, finishing = false, position = place))
    }

    // ---- the notification ----

    @Test
    fun theNotificationShowsPlayingWhileStarting() {
        assertTrue(PlaybackPolicy.notificationShowsPlaying(isSpeaking = false, starting = true))
        assertTrue(PlaybackPolicy.notificationShowsPlaying(isSpeaking = true, starting = false))
        assertFalse(PlaybackPolicy.notificationShowsPlaying(isSpeaking = false, starting = false))
    }

    // ---- a Play from a headset or the notification ----

    @Test
    fun playWithABookLoadedPlaysIt() {
        assertEquals(PlaybackPolicy.ResumeTarget.Loaded, PlaybackPolicy.resumeTarget("a", "b"))
    }

    @Test
    fun playOnAFreshServiceTakesTheMostRecentBook() {
        assertEquals(PlaybackPolicy.ResumeTarget.Recent("b"), PlaybackPolicy.resumeTarget(null, "b"))
    }

    @Test
    fun playWithNothingAnywhereHasNothingToPlay() {
        assertEquals(PlaybackPolicy.ResumeTarget.NothingToPlay, PlaybackPolicy.resumeTarget(null, null))
    }

    // ---- skipping ----

    /** Sentences numbered 0..count-1 in one block. */
    private class Numbered(private val count: Int) : Narrator.ContentSource {
        fun at(i: Int) = SentenceRef(ReadingPosition(0, 0, i), "s$i", i * 10)
        override suspend fun firstAtOrAfter(position: ReadingPosition) = at(position.sentenceIndex).takeIf { position.sentenceIndex < count }
        override suspend fun next(position: ReadingPosition) = at(position.sentenceIndex + 1).takeIf { position.sentenceIndex + 1 < count }
        override suspend fun prev(position: ReadingPosition) = at(position.sentenceIndex - 1).takeIf { position.sentenceIndex > 0 }
    }

    @Test
    fun skippingForwardGoesToTheNextSentenceAndNullAtTheEnd() = runBlocking {
        val book = Numbered(3)
        assertEquals(book.at(1), PlaybackPolicy.skipTarget(book, book.at(0), forward = true))
        assertNull(PlaybackPolicy.skipTarget(book, book.at(2), forward = true))
    }

    @Test
    fun skippingBackFromTheFirstSentenceStaysOnIt() = runBlocking {
        val book = Numbered(3)
        assertEquals(book.at(1), PlaybackPolicy.skipTarget(book, book.at(2), forward = false))
        assertEquals(book.at(0), PlaybackPolicy.skipTarget(book, book.at(0), forward = false))
    }

    @Test
    fun twoQuickTapsMoveTwoSentencesWhenEachStartsFromWhereTheLastLanded() = runBlocking {
        // The narrator now records each landing as the current sentence before restarting, so
        // the second tap starts from it. (Starting both from the sentence playing is the bug.)
        val book = Numbered(10)
        var current = book.at(4)
        repeat(2) { current = PlaybackPolicy.skipTarget(book, current, forward = true)!! }
        assertEquals(book.at(6), current)
    }
}
