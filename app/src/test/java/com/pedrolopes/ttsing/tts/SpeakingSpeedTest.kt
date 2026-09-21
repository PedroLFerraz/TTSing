package com.pedrolopes.ttsing.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SpeakingSpeedTest {

    /**
     * A voice speaking at a steady 16 chars/second, plus a fixed 450 ms per sentence for the
     * pause and synthesis latency — the shape real TTS has, and the reason short sentences
     * "measure" slow.
     */
    private data class Sentence(val chars: Int, val millis: Long)

    private fun realisticSentences(count: Int, seed: Int = 7): List<Sentence> {
        val random = Random(seed)
        return List(count) {
            // Mostly mid-length prose, with the odd one-word line and the odd long period.
            val chars = when (random.nextInt(10)) {
                0 -> random.nextInt(3, 15)
                in 1..7 -> random.nextInt(40, 160)
                else -> random.nextInt(160, 400)
            }
            Sentence(chars, (chars * 1000L / 16) + 450)
        }
    }

    /** The old algorithm: exponential moving average of each sentence's own chars/second. */
    private class OldEma {
        var value: Float? = null
        fun add(s: Sentence) {
            val sample = s.chars * 1000f / s.millis
            value = value?.let { it * 0.8f + sample * 0.2f } ?: sample
        }
    }

    /** Largest sentence-to-sentence relative change in speed, i.e. in the time estimate. */
    private fun maxSwing(values: List<Float>): Float =
        values.zipWithNext { a, b -> abs(b - a) / a }.maxOrNull() ?: 0f

    @Test
    fun `old average swings the estimate by double digits every sentence`() {
        val sentences = realisticSentences(600)
        val old = OldEma()
        val values = sentences.map { old.add(it); old.value!! }.drop(200)
        println("old EMA max sentence-to-sentence swing: ${maxSwing(values)}")
        // Documented, not desired: this is the bug the user saw.
        assertTrue("old swing ${maxSwing(values)}", maxSwing(values) > 0.10f)
    }

    @Test
    fun `new speed holds still once warmed up`() {
        val sentences = realisticSentences(600)
        val speed = SpeakingSpeed()
        val values = sentences.map { speed.add(it.chars, it.millis, rate = 1f); speed.charsPerSecond }
            .drop(200)
        println("new max sentence-to-sentence swing: ${maxSwing(values)}")
        // Under 1% from one sentence to the next: on a 20-hour book that is a few minutes,
        // below the 5-minute rounding the footer shows.
        assertTrue("new swing ${maxSwing(values)}", maxSwing(values) < 0.01f)
    }

    @Test
    fun `new speed converges on the true throughput, pauses included`() {
        val sentences = realisticSentences(2000)
        val speed = SpeakingSpeed()
        sentences.forEach { speed.add(it.chars, it.millis, rate = 1f) }
        val truth = sentences.sumOf { it.chars } * 1000f / sentences.sumOf { it.millis }
        assertEquals(truth, speed.charsPerSecond, truth * 0.03f)
    }

    @Test
    fun `the prior is the default speed and is soon outweighed`() {
        val speed = SpeakingSpeed()
        assertEquals(SpeakingSpeed.PRIOR_CPS, speed.charsPerSecond, 0.001f)
        // Five minutes of a voice at exactly 10 cps.
        repeat(100) { speed.add(charsSpoken = 30, elapsedMillis = 3_000, rate = 1f) }
        assertEquals(10f, speed.charsPerSecond, 1.2f)
    }

    @Test
    fun `time at a faster rate is normalised back to rate one`() {
        val atDouble = SpeakingSpeed(chars = 0.0001, millis = 0.0001)
        repeat(200) { atDouble.add(charsSpoken = 32, elapsedMillis = 1_000, rate = 2f) }
        // 32 chars per second at 2x is 16 per second at 1x.
        assertEquals(16f, atDouble.charsPerSecond, 0.1f)
    }

    @Test
    fun `round trips through its stored form, and bad input falls back to the prior`() {
        val speed = SpeakingSpeed()
        repeat(50) { speed.add(80, 5_000, 1.25f) }
        val back = SpeakingSpeed.decode(speed.encode())
        assertEquals(speed.charsPerSecond, back.charsPerSecond, 0.0001f)
        assertEquals(SpeakingSpeed.PRIOR_CPS, SpeakingSpeed.decode("garbage").charsPerSecond, 0.001f)
        assertEquals(SpeakingSpeed.PRIOR_CPS, SpeakingSpeed.decode(null).charsPerSecond, 0.001f)
    }
}
