package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.SentenceSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralWordTimingPiecesTest {

    // "um dois tres quatro": two pieces, the second spoken at half the pace of the first.
    private val words = listOf(
        SentenceSpan(0, 2),    // um
        SentenceSpan(3, 7),    // dois
        SentenceSpan(8, 12),   // tres
        SentenceSpan(13, 19),  // quatro
    )
    private val pieces = listOf(
        NeuralWordTiming.Piece(chars = 0 until 8, startMs = 0, endMs = 1000),
        NeuralWordTiming.Piece(chars = 8 until 19, startMs = 1000, endMs = 3000),
    )

    @Test
    fun `each word is timed inside the piece that spoke it`() {
        val timings = NeuralWordTiming.estimateInPieces(pieces, words, startInBlock = 0)
        assertEquals(4, timings.size)
        assertEquals(0..1, timings[0].rangeInBlock)
        assertTrue("um starts at the top", timings[0].startMs == 0)
        assertTrue("dois is inside the first piece", timings[1].startMs in 1..999)
        assertTrue("tres starts the second piece", timings[2].startMs in 1000..1200)
        assertTrue("quatro is inside the second piece", timings[3].startMs in 1200..3000)
        assertTrue("nothing runs past the audio", timings.all { it.endMs <= 3000 })
    }

    @Test
    fun `block offsets carry through`() {
        val timings = NeuralWordTiming.estimateInPieces(pieces, words, startInBlock = 100)
        assertEquals(100..101, timings.first().rangeInBlock)
        assertEquals(113..118, timings.last().rangeInBlock)
    }

    @Test
    fun `a piece nobody speaks in contributes nothing`() {
        val silent = listOf(NeuralWordTiming.Piece(chars = 40 until 60, startMs = 0, endMs = 500))
        assertTrue(NeuralWordTiming.estimateInPieces(silent, words, startInBlock = 0).isEmpty())
    }
}
