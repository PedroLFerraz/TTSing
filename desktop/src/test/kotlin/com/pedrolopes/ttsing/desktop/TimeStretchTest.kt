package com.pedrolopes.ttsing.desktop

import com.pedrolopes.ttsing.desktop.tts.TimeStretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Faster speech must keep its pitch: shorter audio, same frequencies, no clicks of silence. */
class TimeStretchTest {

    private val rate = 22050

    private fun tone(hz: Double, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    /** Zero crossings per second, which for a pure tone is twice its frequency. */
    private fun crossingsPerSecond(samples: FloatArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) if ((samples[i - 1] < 0f) != (samples[i] < 0f)) crossings++
        return crossings * rate.toDouble() / samples.size
    }

    @Test
    fun `normal speed is left alone`() {
        val input = tone(200.0, 1.0)
        assertSame(input, TimeStretch.stretch(input, rate, 1f))
    }

    @Test
    fun `twice as fast is half as long`() {
        val out = TimeStretch.stretch(tone(200.0, 2.0), rate, 2f)
        assertEquals(rate.toDouble(), out.size.toDouble(), rate * 0.03)
    }

    @Test
    fun `the pitch stays where it was`() {
        val input = tone(220.0, 2.0)
        val out = TimeStretch.stretch(input, rate, 1.8f)
        val trimmed = out.copyOfRange(rate / 10, out.size - rate / 10)
        assertEquals(crossingsPerSecond(input), crossingsPerSecond(trimmed), 12.0)
    }

    @Test
    fun `the level stays where it was`() {
        val out = TimeStretch.stretch(tone(180.0, 2.0), rate, 1.5f)
        val middle = out.copyOfRange(rate / 4, out.size - rate / 4)
        val peak = middle.maxOf { abs(it) }
        assertTrue("peak $peak", peak in 0.4f..0.6f)
    }
}
