package com.pedrolopes.ttsing.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedStepsTest {

    @Test
    fun `steps run from half speed to three times`() {
        assertEquals(0.5f, SpeedSteps.ALL.first())
        assertEquals(3f, SpeedSteps.ALL.last())
        assertEquals(SpeedSteps.ALL.sorted(), SpeedSteps.ALL)
    }

    @Test
    fun `next steps up and wraps past three`() {
        assertEquals(2.75f, SpeedSteps.next(2.5f))
        assertEquals(3f, SpeedSteps.next(2.75f))
        assertEquals(0.5f, SpeedSteps.next(3f))
    }

    @Test
    fun `the current speed is the step it equals, if any`() {
        assertEquals(1f, SpeedSteps.selected(1f))
        assertEquals(1.25f, SpeedSteps.selected(1.2500001f))
        assertEquals(3f, SpeedSteps.selected(3f))
        assertNull(SpeedSteps.selected(1.1f))
        SpeedSteps.ALL.forEach { assertEquals(it, SpeedSteps.selected(it)) }
    }

    @Test
    fun `format drops trailing zeros`() {
        assertEquals("3×", SpeedSteps.format(3f))
        assertEquals("2.75×", SpeedSteps.format(2.75f))
        assertEquals("1.5×", SpeedSteps.format(1.5f))
    }

    @Test
    fun `slow and moderate speeds stay with the model`() {
        assertEquals(SpeedSteps.Split(1f, 1f), SpeedSteps.split(1f))
        assertEquals(SpeedSteps.Split(0.5f, 1f), SpeedSteps.split(0.5f))
        assertEquals(SpeedSteps.Split(1.5f, 1f), SpeedSteps.split(1.5f))
    }

    @Test
    fun `fast speeds are made up by playback`() {
        assertEquals(SpeedSteps.Split(1.5f, 2f), SpeedSteps.split(3f))
        SpeedSteps.ALL.forEach { rate ->
            val split = SpeedSteps.split(rate)
            assertEquals(rate, split.model * split.playback, 0.001f)
            assert(split.model <= SpeedSteps.MAX_MODEL_SPEED)
        }
    }
}
