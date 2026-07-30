package com.pedrolopes.ttsing.tts

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Frame accounting for app-owned playback. [SynthesizedSentence.frameCount] is what the
 * player waits for before starting the next sentence, so getting it wrong either clips the
 * tail of every sentence or — as happened once — waits forever and stops the book dead.
 */
class SynthesizedSentenceTest {

    private fun sentence(
        byteCount: Int,
        format: Int = AudioFormat.ENCODING_PCM_16BIT,
        channels: Int = 1,
        sampleRate: Int = 22050,
    ) = SynthesizedSentence(
        pcm = ByteArray(byteCount),
        sampleRateHz = sampleRate,
        audioFormat = format,
        channelCount = channels,
        marks = emptyList(),
    )

    @Test
    fun `16-bit mono is two bytes per frame`() {
        val s = sentence(byteCount = 4410)
        assertEquals(2, s.bytesPerFrame)
        assertEquals(2205, s.frameCount)
    }

    @Test
    fun `stereo doubles the bytes per frame`() {
        val s = sentence(byteCount = 4410, channels = 2)
        assertEquals(4, s.bytesPerFrame)
        assertEquals(1102, s.frameCount)
    }

    @Test
    fun `8-bit and float formats are sized correctly`() {
        assertEquals(1, sentence(100, format = AudioFormat.ENCODING_PCM_8BIT).bytesPerFrame)
        assertEquals(4, sentence(100, format = AudioFormat.ENCODING_PCM_FLOAT).bytesPerFrame)
    }

    @Test
    fun `an unexpected format falls back to 16-bit sizing rather than dividing by zero`() {
        val s = sentence(byteCount = 1000, format = 0xDEAD)
        assertEquals(2, s.bytesPerFrame)
        assertEquals(500, s.frameCount)
    }

    @Test
    fun `frame count is never zero, so a drain wait always has a target`() {
        assertEquals(1, sentence(byteCount = 0).frameCount)
    }

    @Test
    fun `duration matches the sample rate`() {
        // 22050 frames at 22.05 kHz is exactly one second.
        assertEquals(1000, sentence(byteCount = 22050 * 2).durationMs)
        assertEquals(500, sentence(byteCount = 11025 * 2).durationMs)
    }
}
