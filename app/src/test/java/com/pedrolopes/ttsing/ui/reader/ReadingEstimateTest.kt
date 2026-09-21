package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.SentenceSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingEstimateTest {

    private fun text(body: String) = Block.Text(
        text = body,
        kind = Block.Text.Kind.PARAGRAPH,
        sentences = listOf(SentenceSpan(0, body.length)),
    )

    private val blocks = listOf(
        text("a".repeat(100)),
        text("b".repeat(200)),
        Block.Image("img.png", null),
        text("c".repeat(300)),
    )

    @Test
    fun `counts remaining characters from the start`() {
        assertEquals(600, ReadingEstimate.remainingCharsInChapter(blocks, 0, 0))
    }

    @Test
    fun `counts from partway through a block`() {
        // Half of block 0 consumed, plus blocks 1 and 3 in full.
        assertEquals(550, ReadingEstimate.remainingCharsInChapter(blocks, 0, 50))
    }

    @Test
    fun `counts from a later block and ignores images`() {
        assertEquals(300, ReadingEstimate.remainingCharsInChapter(blocks, 3, 0))
    }

    @Test
    fun `offset past the end does not go negative`() {
        assertEquals(0, ReadingEstimate.remainingCharsInChapter(blocks, 3, 9999))
    }

    @Test
    fun `book remainder adds later chapters only`() {
        val counts = listOf(1000, 2000, 3000, 4000)
        assertEquals(7500, ReadingEstimate.remainingCharsInBook(500, counts, chapterIndex = 1))
    }

    @Test
    fun `last chapter book remainder equals chapter remainder`() {
        val counts = listOf(1000, 2000)
        assertEquals(250, ReadingEstimate.remainingCharsInBook(250, counts, chapterIndex = 1))
    }

    @Test
    fun `speech rate scales the estimate`() {
        val chars = 3000
        assertEquals(200, ReadingEstimate.secondsFor(chars, charsPerSecond = 15f, speechRate = 1f))
        assertEquals(100, ReadingEstimate.secondsFor(chars, charsPerSecond = 15f, speechRate = 2f))
        assertEquals(80, ReadingEstimate.secondsFor(chars, charsPerSecond = 15f, speechRate = 2.5f))
    }

    @Test
    fun `formats durations readably`() {
        assertEquals("< 1 min", ReadingEstimate.formatDuration(30))
        assertEquals("12 min", ReadingEstimate.formatDuration(12 * 60))
        assertEquals("2 h", ReadingEstimate.formatDuration(2 * 3600))
        assertEquals("3 h 20 min", ReadingEstimate.formatDuration(3 * 3600 + 20 * 60))
    }

    @Test
    fun `past an hour, rounds to five minutes`() {
        assertEquals("59 min", ReadingEstimate.formatDuration(59 * 60))
        assertEquals("3 h 20 min", ReadingEstimate.formatDuration(3 * 3600 + 22 * 60))
        assertEquals("3 h 25 min", ReadingEstimate.formatDuration(3 * 3600 + 23 * 60))
        assertEquals("2 h", ReadingEstimate.formatDuration(3600 + 58 * 60))
        // Every minute from 5 h 08 to 5 h 12 reads the same, so on a long book the figure
        // changes once per five minutes of reading rather than on every page turn.
        val window = (8..12).map { ReadingEstimate.formatDuration(5 * 3600 + it * 60) }.toSet()
        assertEquals(setOf("5 h 10 min"), window)
    }

    @Test
    fun `the shown figure holds against small wobbles and follows real movement`() {
        val onScreen = 5 * 3600 + 12 * 60
        // A speed wobble of a minute or two either side: the display does not move.
        assertEquals(onScreen, ReadingEstimate.steady(onScreen, onScreen + 100))
        assertEquals(onScreen, ReadingEstimate.steady(onScreen, onScreen - 150))
        // Five minutes of reading, or a jump: it does.
        assertEquals(onScreen - 300, ReadingEstimate.steady(onScreen, onScreen - 300))
        assertEquals(3600 * 2, ReadingEstimate.steady(onScreen, 3600 * 2))
        // Upwards it needs a real rise: 2% of 5 h is 6 min, so +5 min holds and +10 goes.
        assertEquals(onScreen, ReadingEstimate.steady(onScreen, onScreen + 300))
        assertEquals(onScreen + 600, ReadingEstimate.steady(onScreen, onScreen + 600))
        // Nothing shown yet: take the estimate as it is.
        assertEquals(42, ReadingEstimate.steady(null, 42))
        // Under an hour the step is finer.
        assertEquals(600, ReadingEstimate.steady(600, 630))
        assertEquals(540, ReadingEstimate.steady(600, 540))
    }
}
