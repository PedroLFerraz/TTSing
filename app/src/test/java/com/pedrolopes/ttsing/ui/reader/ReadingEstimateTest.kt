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
    fun `shows the minute, however long the book`() {
        assertEquals("59 min", ReadingEstimate.formatDuration(59 * 60))
        assertEquals("3 h 22 min", ReadingEstimate.formatDuration(3 * 3600 + 22 * 60))
        assertEquals("3 h 23 min", ReadingEstimate.formatDuration(3 * 3600 + 23 * 60 + 59))
        assertEquals("1 h 58 min", ReadingEstimate.formatDuration(3600 + 58 * 60))
        assertEquals("22 h 13 min", ReadingEstimate.formatMinutes(22 * 60 + 13))
        assertEquals("< 1 min", ReadingEstimate.formatMinutes(0))
    }
}
