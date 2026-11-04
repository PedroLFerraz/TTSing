package com.pedrolopes.ttsing.desktop.tts

import kotlin.math.PI
import kotlin.math.cos

/**
 * Plays audio faster with its pitch kept (WSOLA: waveform-similarity overlap-add).
 *
 * A neural voice is asked to talk at most [com.pedrolopes.ttsing.tts.SpeedSteps.MAX_MODEL_SPEED]
 * times its own pace; past that it swallows short words, and the rest of the speed comes from
 * playing its audio faster. Android's `AudioTrack` does that itself; Java Sound has no such
 * thing, so it is done here, to the samples, before they are played.
 *
 * The input is cut into overlapping windows that are laid down closer together than they were
 * taken; each window is taken from wherever, near its nominal place, it best continues the one
 * before, so the waveform stays smooth and the voice doesn't warble.
 */
object TimeStretch {

    fun stretch(samples: FloatArray, sampleRate: Int, speed: Float): FloatArray {
        val frame = (sampleRate * FRAME_SECONDS).toInt().coerceAtLeast(16) and 1.inv()
        if (speed in 0.99f..1.01f || samples.size < frame * 4) return samples
        val hop = frame / 2
        val tolerance = (sampleRate * TOLERANCE_SECONDS).toInt().coerceAtLeast(1)
        // A periodic Hann window sums to exactly one at half overlap: no gain to undo.
        val window = FloatArray(frame) { (0.5 - 0.5 * cos(2 * PI * it / frame)).toFloat() }
        val outLength = (samples.size / speed).toInt()
        val out = FloatArray(outLength + frame)

        var previous = 0
        var outPos = 0
        var nominal = 0.0
        while (outPos + frame <= out.size) {
            val ideal = nominal.toInt()
            if (ideal + frame + tolerance >= samples.size) break
            val start = if (outPos == 0) ideal else bestMatch(samples, previous + hop, ideal, tolerance, hop)
            for (i in 0 until frame) out[outPos + i] += samples[start + i] * window[i]
            previous = start
            outPos += hop
            nominal += hop * speed
        }
        return out.copyOf(minOf(outLength, outPos + hop))
    }

    /**
     * Where, within [tolerance] of [ideal], a window starts that best continues the audio that
     * followed the last window (at [natural]): the highest correlation over [length] samples.
     */
    private fun bestMatch(samples: FloatArray, natural: Int, ideal: Int, tolerance: Int, length: Int): Int {
        if (natural + length > samples.size) return ideal
        var best = ideal
        var bestScore = Double.NEGATIVE_INFINITY
        val from = (ideal - tolerance).coerceAtLeast(0)
        val to = (ideal + tolerance).coerceAtMost(samples.size - length)
        for (candidate in from..to) {
            var score = 0.0
            for (i in 0 until length) score += samples[candidate + i] * samples[natural + i]
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    /** 30 ms windows: long enough to hold a couple of pitch periods of a low voice. */
    private const val FRAME_SECONDS = 0.030

    /** How far a window may move from its nominal place to line up with the last. */
    private const val TOLERANCE_SECONDS = 0.012
}
