package com.pedrolopes.ttsing.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardStripeTest {

    private val wanted = 24f * 2.625f
    private val height = 10f * 2.625f
    private val bandShare = 14f / 24f
    private val widths = listOf(300f, 817f, 1000f, 1080f, 1234.5f, 1440f, 2400f)

    /** Phase (0..1 within a repeat; yellow is [0, bandShare)) of the point (x, y), as the gradient sees it. */
    private fun phase(x: Float, y: Float, period: Float): Double {
        val p = (x * 0.906 + y * 0.423) / period
        return p - Math.floor(p)
    }

    @Test
    fun `the right edge shows only the gap, top to bottom`() {
        for (width in widths) {
            val period = fittedStripePeriod(width, height, wanted, bandShare)
            for (i in 0..10) {
                val ph = phase(width, height * i / 10f, period)
                assertTrue("width $width y step $i phase $ph", ph > bandShare + 0.001 && ph < 1.0 - 0.001)
            }
        }
    }

    @Test
    fun `fitting barely changes the period it was asked for`() {
        for (width in widths.drop(1)) {
            val fitted = fittedStripePeriod(width, height, wanted, bandShare)
            assertTrue("width $width: $fitted vs $wanted", Math.abs(fitted - wanted) / wanted < 0.1f)
        }
    }

    @Test
    fun `a bar narrower than one repeat still gets a usable period`() {
        // Too narrow for the slant to fit in a gap at all; just stay positive and finite.
        val period = fittedStripePeriod(20f, height, wanted, bandShare)
        assertTrue(period > 0f && period.isFinite())
    }

    @Test
    fun `no width is left as asked`() {
        assertEquals(63f, fittedStripePeriod(0f, height, 63f, bandShare), 0f)
    }
}
