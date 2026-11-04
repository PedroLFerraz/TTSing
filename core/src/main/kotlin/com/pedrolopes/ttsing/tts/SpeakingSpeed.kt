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
 * figure settles and then barely moves, while a new voice gets a measurement of its own.
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
         * The prior is worth twenty minutes of listening. A one-minute prior was tried first
         * and on a real book it was not enough: a title page read as "W. H. D." measures two
         * characters in 700 ms, and each such fragment moved a 22-hour estimate by a quarter
         * of an hour. At twenty minutes one sentence moves it by a fraction of a percent.
         */
        const val PRIOR_MILLIS = 20 * 60_000.0
        const val PRIOR_CHARS = PRIOR_CPS * PRIOR_MILLIS / 1000.0

        /** A fresh measurement starting from [cps] — this device's last known speed — as prior. */
        fun seededAt(cps: Float): SpeakingSpeed {
            val prior = cps.takeIf { it in 1f..200f } ?: PRIOR_CPS
            return SpeakingSpeed(prior * PRIOR_MILLIS / 1000.0, PRIOR_MILLIS)
        }

        /**
         * Three hours of listening halve the weight of everything heard before. A long memory,
         * because speeds are kept per voice — a new voice starts its own measurement rather
         * than waiting for this one to adapt — so all the decay has to follow is slow drift.
         * A twenty-minute half-life was tried and let three minutes of a copyright page (read
         * digit by digit, so genuinely slow) move a 23-hour estimate by forty minutes.
         */
        const val DEFAULT_HALF_LIFE_MILLIS = 3 * 60 * 60_000.0

        /** Parses [encode]'s output; anything unreadable starts over from [fallbackCps]. */
        fun decode(value: String?, fallbackCps: Float = PRIOR_CPS): SpeakingSpeed {
            val parts = value?.split(',') ?: return seededAt(fallbackCps)
            val chars = parts.getOrNull(0)?.toDoubleOrNull()
            val millis = parts.getOrNull(1)?.toDoubleOrNull()
            if (chars == null || millis == null || chars <= 0.0 || millis <= 0.0) return seededAt(fallbackCps)
            // Never less sure than the prior: a speed saved after a few sentences keeps its
            // rate but is weighted as ten minutes' worth, or it would jump like a fresh one.
            if (millis < PRIOR_MILLIS) return SpeakingSpeed(chars / millis * PRIOR_MILLIS, PRIOR_MILLIS)
            return SpeakingSpeed(chars, millis)
        }
    }
}
