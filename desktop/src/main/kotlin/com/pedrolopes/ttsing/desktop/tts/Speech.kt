package com.pedrolopes.ttsing.desktop.tts

import java.util.Locale

/** One sentence's audio: 16-bit mono samples, and where each piece of it begins. */
class Speech(
    val pcm: ShortArray,
    val sampleRate: Int,
    /** Each piece's first sample and the characters of the sentence it speaks. */
    val pieces: List<Piece>,
) {
    data class Piece(val frame: Int, val start: Int, val end: Int)

    val durationMs: Int get() = (pcm.size * 1000L / sampleRate).toInt()

    /**
     * Joins a sentence spoken piece by piece. The pieces are found in [text] in order, so the
     * word highlight can be spread over each piece's own stretch of audio.
     */
    class Builder(private val text: String) {
        private var sampleRate = 0
        private var frames = 0
        private var cursor = 0
        private val audio = mutableListOf<FloatArray>()
        private val pieces = mutableListOf<Piece>()

        fun add(piece: String, samples: FloatArray, rate: Int) {
            val start = text.indexOf(piece, cursor).takeIf { it >= 0 } ?: cursor
            cursor = start + piece.length
            if (samples.isEmpty()) return
            sampleRate = rate
            audio.add(samples)
            pieces.add(Piece(frames, start, cursor))
            frames += samples.size
        }

        fun build(): Speech? {
            if (audio.isEmpty() || sampleRate <= 0) return null
            val pcm = ShortArray(frames)
            var at = 0
            audio.forEach { piece ->
                piece.forEach { sample -> pcm[at++] = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
            }
            return Speech(pcm, sampleRate, pieces)
        }
    }
}

/**
 * Turns a sentence into audio. One sentence at a time: the narrator holds the lock.
 * [speed] is 1.0 at the voice's own pace.
 */
interface SpeechEngine {
    fun synthesize(text: String, speed: Float, locale: Locale): Speech?

    /** Why the last sentence could not be spoken, when it's worth telling the reader. */
    val failure: String? get() = null

    fun release()
}
