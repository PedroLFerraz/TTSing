package com.pedrolopes.ttsing.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardStripeTest {

    // Across the bar, one repeat is the axis period divided by the lean's cosine.
    private fun repeatsAcross(width: Float, axisPeriod: Float) = width / (axisPeriod / 0.906f)

    @Test
    fun `the stripes repeat a whole number of times across the bar`() {
        for (width in listOf(300f, 817f, 1080f, 1234.5f, 2400f)) {
            val repeats = repeatsAcross(width, fittedStripePeriod(width, 24f * 2.625f)).toDouble()
            assertEquals("width $width", Math.rint(repeats), repeats, 0.001)
        }
    }

    @Test
    fun `fitting barely changes the period it was asked for`() {
        val wanted = 24f * 2.625f
        val fitted = fittedStripePeriod(1080f, wanted)
        assertTrue("$fitted vs $wanted", Math.abs(fitted - wanted) / wanted < 0.1f)
    }

    @Test
    fun `a bar narrower than one repeat still holds one`() {
        val repeats = repeatsAcross(20f, fittedStripePeriod(20f, 63f)).toDouble()
        assertEquals(1.0, repeats, 0.001)
    }

    @Test
    fun `no width is left as asked`() {
        assertEquals(63f, fittedStripePeriod(0f, 63f), 0f)
    }
}
