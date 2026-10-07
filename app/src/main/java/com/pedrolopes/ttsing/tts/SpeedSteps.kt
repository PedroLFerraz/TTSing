package com.pedrolopes.ttsing.tts

import kotlin.math.roundToInt

/** The reading speeds the reader offers, and how a neural voice reaches them. */
object SpeedSteps {

    /** Every speed the reader offers, in the footer's menu and in the settings sheet. */
    val ALL = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f)

    /**
     * The fastest a neural voice is asked to talk by itself. Past this the model squeezes
     * short sounds down to a frame or two, and short words stop being words; the rest of the
     * speed comes from playing its audio faster instead, pitch kept.
     */
    const val MAX_MODEL_SPEED = 1.5f

    /** How a [rate] is reached by a neural voice: its own pace, times playback speed. */
    data class Split(val model: Float, val playback: Float)

    fun split(rate: Float): Split {
        val model = rate.coerceAtMost(MAX_MODEL_SPEED)
        return Split(model = model, playback = if (model > 0f) rate / model else 1f)
    }

    /** The step [rate] is, or null when it sits between steps (the settings slider can leave it there). */
    fun selected(rate: Float): Float? = ALL.firstOrNull { kotlin.math.abs(it - rate) < 0.01f }

    /** The next speed up from [rate], wrapping round to the slowest past the top. */
    fun next(rate: Float): Float = ALL.firstOrNull { it > rate + 0.01f } ?: ALL.first()

    /** "1×", "1.5×", "2.25×" — no trailing zeros, because the chip is tiny. */
    fun format(rate: Float): String {
        val rounded = (rate * 100).roundToInt() / 100f
        val text = if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}" else "$rounded"
        return "$text×"
    }
}
