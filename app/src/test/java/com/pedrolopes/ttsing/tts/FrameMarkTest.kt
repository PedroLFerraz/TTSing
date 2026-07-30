package com.pedrolopes.ttsing.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The word highlight under app-owned playback comes from matching the AudioTrack's playback
 * position against the frame marks the TTS engine reported via `onRangeStart`. That lookup
 * is the one piece of the new audio path that is pure logic, so it is pinned here.
 */
class FrameMarkTest {

    private fun marks(vararg triples: Triple<Int, Int, Int>): List<SynthesizedSentence.FrameMark> =
        triples.map { SynthesizedSentence.FrameMark(it.first, it.second, it.third) }

    /** Mirrors AudioTrackNarrator.markAt. */
    private fun markAt(
        marks: List<SynthesizedSentence.FrameMark>,
        frame: Int,
    ): SynthesizedSentence.FrameMark? = marks.lastOrNull { it.frame <= frame }

    @Test
    fun `no word is highlighted before the first mark`() {
        val list = marks(Triple(100, 0, 3))
        assertNull(markAt(list, 0))
        assertNull(markAt(list, 99))
    }

    @Test
    fun `the word holds until the next mark begins`() {
        val list = marks(Triple(0, 0, 3), Triple(500, 4, 9))
        assertEquals(0, markAt(list, 0)?.start)
        assertEquals(0, markAt(list, 499)?.start)
        assertEquals(4, markAt(list, 500)?.start)
        assertEquals(4, markAt(list, 10_000)?.start)
    }

    @Test
    fun `the last word stays highlighted through the tail of the audio`() {
        val list = marks(Triple(0, 0, 3), Triple(200, 4, 9), Triple(900, 10, 14))
        val last = markAt(list, 50_000)
        assertEquals(10, last?.start)
        assertEquals(14, last?.end)
    }

    @Test
    fun `an empty mark list never highlights`() {
        assertNull(markAt(emptyList(), 0))
        assertNull(markAt(emptyList(), 12_345))
    }

    @Test
    fun `marks arriving out of order are usable once sorted`() {
        // PcmSynthesizer sorts by frame before handing the sentence over.
        val unsorted = marks(Triple(900, 10, 14), Triple(0, 0, 3), Triple(200, 4, 9))
        val sorted = unsorted.sortedBy { it.frame }
        assertEquals(0, markAt(sorted, 100)?.start)
        assertEquals(4, markAt(sorted, 300)?.start)
        assertEquals(10, markAt(sorted, 1000)?.start)
    }

    @Test
    fun `word ranges are offset into block coordinates`() {
        // AudioTrackNarrator shifts sentence-local marks by the sentence's block offset.
        val startInBlock = 250
        val mark = SynthesizedSentence.FrameMark(frame = 0, start = 4, end = 9)
        val range = (startInBlock + mark.start) until (startInBlock + mark.end)
        assertEquals(254, range.first)
        assertEquals(258, range.last)
    }
}
