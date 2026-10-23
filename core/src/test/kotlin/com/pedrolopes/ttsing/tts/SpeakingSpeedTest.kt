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
    fun `the prior is the default speed and is outweighed as listening accumulates`() {
        val speed = SpeakingSpeed()
        assertEquals(SpeakingSpeed.PRIOR_CPS, speed.charsPerSecond, 0.001f)
        // Two hours of a voice at exactly 10 cps — a third of the way from the prior after
        // half an hour, most of the way after two. Deliberately unhurried: see PRIOR_MILLIS.
        repeat(600) { speed.add(charsSpoken = 30, elapsedMillis = 3_000, rate = 1f) }
        assertTrue(speed.charsPerSecond < 12.5f)
        repeat(1800) { speed.add(charsSpoken = 30, elapsedMillis = 3_000, rate = 1f) }
        assertEquals(10f, speed.charsPerSecond, 0.8f)
    }

    @Test
    fun `a speed saved after only a few sentences keeps its rate but not its jumpiness`() {
        // 40 seconds of listening at 14 cps, saved under the old one-minute prior.
        val light = SpeakingSpeed.decode("${14.0 * 40},40000.0")
        assertEquals(14f, light.charsPerSecond, 0.001f)
        assertEquals(SpeakingSpeed.PRIOR_MILLIS, light.millis, 0.001)
    }

    @Test
    fun `a fresh voice starts from the device's last speed, not the generic one`() {
        assertEquals(12.5f, SpeakingSpeed.seededAt(12.5f).charsPerSecond, 0.001f)
        assertEquals(12.5f, SpeakingSpeed.decode(null, fallbackCps = 12.5f).charsPerSecond, 0.001f)
        // Nonsense stored speeds fall back to the generic prior.
        assertEquals(SpeakingSpeed.PRIOR_CPS, SpeakingSpeed.seededAt(0f).charsPerSecond, 0.001f)
    }

    @Test
    fun `the title-page fragments that moved the estimate on the device no longer do`() {
        // Logged on the emulator reading Plato's title page and introduction, straight after
        // the voice's speed was first created. With the one-minute prior these moved the speed
        // by about 1% a sentence — some 13 minutes on a 22-hour book, each time.
        val logged = listOf(
            83 to 4745L, 24 to 2035L, 84 to 5304L, 16 to 1603L, 2 to 698L,
            2 to 679L, 5 to 922L, 37 to 2520L, 10 to 2132L, 276 to 20762L,
        )
        val speed = SpeakingSpeed.seededAt(15f)
        val values = listOf(speed.charsPerSecond) + logged.map { (chars, ms) ->
            speed.add(chars, ms, rate = 1f)
            speed.charsPerSecond
        }
        println("fresh-voice max sentence-to-sentence swing: ${maxSwing(values)}")
        // Under half a percent: about six minutes on 22 hours, inside the display's hysteresis.
        assertTrue("fresh swing ${maxSwing(values)}", maxSwing(values) < 0.005f)
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
