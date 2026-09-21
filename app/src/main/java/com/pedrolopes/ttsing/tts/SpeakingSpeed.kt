package com.pedrolopes.ttsing.tts

import kotlin.math.pow

/**
 * How fast this voice actually gets through text, in characters per second at rate 1.0.
 *
 * The previous approach averaged each sentence's own chars/second. That is the mean of
 * ratios, and it is badly behaved here: every sentence carries a roughly fixed cost (the pause
 * after it, synthesis latency), so "Yes." measures ~6 cps and a long sentence ~16, and an
 * average that moved 20% toward every sample swung the book's time-to-finish by hours from one
 * sentence to the next.
 *
 * This keeps the ratio of sums instead — total characters over total time — which weights
 * each sentence by how long it took, i.e. by how much it actually contributes to how long the
 * book will take. Both totals decay with *listening time* (not with sentence count), so the
 * figure settles and then barely moves, but still follows a real change such as a new voice
 * over the next half hour of listening.
 *
 * Pauses between sentences are counted on purpose: they are part of how long the book takes.
 */
class SpeakingSpeed(
    chars: Double = PRIOR_CHARS,
    millis: Double = PRIOR_MILLIS,
    private val halfLifeMillis: Double = DEFAULT_HALF_LIFE_MILLIS,
) {
    var chars: Double = chars
        private set
    var millis: Double = millis
        private set

    /** Characters per second at rate 1.0. */
    val charsPerSecond: Float
        get() = if (millis <= 0.0) PRIOR_CPS else (chars * 1000.0 / millis).toFloat()

    /**
     * Records one sentence: [charsSpoken] took [elapsedMillis] of wall-clock time at speech
     * [rate]. Time is normalised to rate 1.0 before it is added, so speeds measured at 1.5×
     * and at 1× feed one figure.
     */
    fun add(charsSpoken: Int, elapsedMillis: Long, rate: Float) {
        if (charsSpoken <= 0 || elapsedMillis <= 0) return
        val normalizedMillis = elapsedMillis * rate.coerceAtLeast(0.1f).toDouble()
        val keep = 0.5.pow(normalizedMillis / halfLifeMillis)
        chars = chars * keep + charsSpoken
        millis = millis * keep + normalizedMillis
    }

    /** "chars,millis" — the persisted form. */
    fun encode(): String = "$chars,$millis"

    companion object {
        /** ~180 wpm at 5 chars+space per word — the figure used before anything is measured. */
        const val PRIOR_CPS = 15f

        /**
         * The prior is worth one minute of listening: enough that the first few sentences
         * can't send the estimate somewhere absurd, little enough that a few minutes of real
         * speech outweigh it.
         */
        const val PRIOR_MILLIS = 60_000.0
        const val PRIOR_CHARS = PRIOR_CPS * PRIOR_MILLIS / 1000.0

        /** Twenty minutes of listening halves the weight of everything heard before it. */
        const val DEFAULT_HALF_LIFE_MILLIS = 20 * 60_000.0

        /** Parses [encode]'s output; anything unreadable falls back to the prior. */
        fun decode(value: String?): SpeakingSpeed {
            val parts = value?.split(',') ?: return SpeakingSpeed()
            val chars = parts.getOrNull(0)?.toDoubleOrNull()
            val millis = parts.getOrNull(1)?.toDoubleOrNull()
            if (chars == null || millis == null || chars <= 0.0 || millis <= 0.0) return SpeakingSpeed()
            return SpeakingSpeed(chars, millis)
        }
    }
}
