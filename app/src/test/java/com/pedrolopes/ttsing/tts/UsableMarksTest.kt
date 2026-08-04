package com.pedrolopes.ttsing.tts

import android.media.AudioFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding whether the engine's own word timings can drive the highlight.
 *
 * `onRangeStart` is optional and its `frame` argument is only as trustworthy as the engine
 * supplying it. Engines that never advance the frame, or that report positions past the end of
 * the audio they produced, leave the highlight stuck on one word for the whole sentence — which
 * reads as "highlighting is broken". Those are rejected in favour of the estimate.
 */
class UsableMarksTest {

    private fun audio(frames: Int) = SynthesizedSentence(
        // 16-bit mono: two bytes per frame.
        pcm = ByteArray(frames * 2),
        sampleRateHz = 22050,
        audioFormat = AudioFormat.ENCODING_PCM_16BIT,
        channelCount = 1,
        marks = emptyList(),
    )

    private fun marks(vararg frames: Int): List<SynthesizedSentence.FrameMark> =
        frames.mapIndexed { i, frame -> SynthesizedSentence.FrameMark(frame, i * 5, i * 5 + 4) }

    /** Mirrors AudioTrackNarrator.usableFor. */
    private fun usable(marks: List<SynthesizedSentence.FrameMark>, audio: SynthesizedSentence): Boolean {
        if (marks.isEmpty()) return false
        if (marks.size == 1) return true
        val last = marks.maxOf { it.frame }
        return last > 0 && last <= audio.frameCount
    }

    @Test
    fun `normal advancing marks are used`() {
        assertTrue(usable(marks(0, 4000, 9000, 14000), audio(20_000)))
    }

    @Test
    fun `no marks at all means the estimate is needed`() {
        assertFalse(usable(emptyList(), audio(20_000)))
    }

    @Test
    fun `marks that never advance past zero are rejected`() {
        // An engine that reports every word at frame 0 would freeze the highlight on word one.
        assertFalse(usable(marks(0, 0, 0, 0), audio(20_000)))
    }

    @Test
    fun `marks running past the end of the audio are rejected`() {
        // Frames in the wrong unit land far beyond the sentence and never come due.
        assertFalse(usable(marks(0, 50_000, 120_000), audio(20_000)))
    }

    @Test
    fun `a mark exactly at the final frame is still usable`() {
        assertTrue(usable(marks(0, 10_000, 20_000), audio(20_000)))
    }

    @Test
    fun `a single mark is taken at face value`() {
        // One word, or one report: nothing to compare against, and it can only help.
        assertTrue(usable(marks(0), audio(20_000)))
        assertTrue(usable(marks(999_999), audio(20_000)))
    }

    @Test
    fun `marks are judged against this sentence's own length`() {
        val list = marks(0, 5_000, 18_000)
        assertTrue("fits a long sentence", usable(list, audio(20_000)))
        assertFalse("overruns a short one", usable(list, audio(6_000)))
    }
}
