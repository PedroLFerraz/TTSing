package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.SentenceSpan
import com.pedrolopes.ttsing.data.epub.WordSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NeuralWordTimingTest {

    @Test
    fun `distributes duration proportionally to character position`() {
        // "ab cd" — words at [0,2) and [3,5), 1000ms total, 200ms per char.
        val words = listOf(SentenceSpan(0, 2), SentenceSpan(3, 5))
        val timings = NeuralWordTiming.estimate(sentenceLength = 5, wordSpans = words, startInBlock = 0, totalDurationMs = 1000)

        assertEquals(2, timings.size)
        assertEquals(0, timings[0].startMs)
        assertEquals(400, timings[0].endMs)
        assertEquals(600, timings[1].startMs)
        assertEquals(1000, timings[1].endMs)
    }

    @Test
    fun `ranges are shifted into block coordinates`() {
        val words = listOf(SentenceSpan(0, 3), SentenceSpan(4, 7))
        val timings = NeuralWordTiming.estimate(7, words, startInBlock = 100, totalDurationMs = 700)
        assertEquals(100..102, timings[0].rangeInBlock)
        assertEquals(104..106, timings[1].rangeInBlock)
    }

    @Test
    fun `word starts are non-decreasing and stay within the audio`() {
        val sentence = "Der schnelle braune Fuchs springt."
        val words = WordSplitter.split(sentence, Locale.GERMAN)
        val total = 3200
        val timings = NeuralWordTiming.estimate(sentence.length, words, 0, total)

        assertEquals(words.size, timings.size)
        var last = 0
        for (t in timings) {
            assertTrue("start within audio", t.startMs in 0..total)
            assertTrue("end within audio", t.endMs in t.startMs..total)
            assertTrue("non-decreasing starts", t.startMs >= last)
            last = t.startMs
        }
    }

    @Test
    fun `zero duration or empty sentence yields nothing`() {
        assertTrue(NeuralWordTiming.estimate(5, listOf(SentenceSpan(0, 2)), 0, 0).isEmpty())
        assertTrue(NeuralWordTiming.estimate(0, listOf(SentenceSpan(0, 2)), 0, 1000).isEmpty())
        assertTrue(NeuralWordTiming.estimate(5, emptyList(), 0, 1000).isEmpty())
    }

    @Test
    fun `empty word spans are dropped`() {
        val words = listOf(SentenceSpan(0, 2), SentenceSpan(3, 3), SentenceSpan(4, 6))
        val timings = NeuralWordTiming.estimate(6, words, 0, 600)
        assertEquals(2, timings.size)
    }

    @Test
    fun `durationMs converts samples at sample rate`() {
        assertEquals(1000, NeuralWordTiming.durationMs(22050, 22050))
        assertEquals(500, NeuralWordTiming.durationMs(11025, 22050))
        assertEquals(0, NeuralWordTiming.durationMs(1000, 0))
    }

    @Test
    fun `wordAt selects the highlight for the current playback position`() {
        val timings = NeuralWordTiming.estimate(5, listOf(SentenceSpan(0, 2), SentenceSpan(3, 5)), 0, 1000)
        assertNull("before first word", NeuralWordTiming.wordAt(timings, -1))
        assertEquals(timings[0], NeuralWordTiming.wordAt(timings, 0))
        assertEquals(timings[0], NeuralWordTiming.wordAt(timings, 500))
        assertEquals(timings[1], NeuralWordTiming.wordAt(timings, 600))
        assertEquals(timings[1], NeuralWordTiming.wordAt(timings, 999))
    }
}
