package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.SentenceSpan

/** When one word is spoken within a sentence's synthesized audio. */
data class WordTiming(
    /** The word as char offsets into the owning block's text, matching [SpeechEngine.Listener.onWordRange]. */
    val rangeInBlock: IntRange,
    val startMs: Int,
    val endMs: Int,
)

/**
 * Reconstructs word-by-word timing for a neural voice.
 *
 * Android's [android.speech.tts.TextToSpeech] reports word boundaries live via
 * `onRangeStart`, which drives the karaoke highlight. Neural engines (Piper via
 * sherpa-onnx) emit no such callback — only a block of audio samples. This distributes the
 * sentence's measured duration across its words **in proportion to character position**,
 * counting the spaces and punctuation between words too, which tracks perceived timing
 * closely enough for the highlight to follow along.
 *
 * Pure function of its inputs, so the highlight logic is unit-tested even though the audio
 * path it feeds can only run on a device.
 */
object NeuralWordTiming {

    fun estimate(
        sentenceLength: Int,
        /** Word spans as sentence-local [start, end), e.g. from [com.pedrolopes.ttsing.data.epub.WordSplitter]. */
        wordSpans: List<SentenceSpan>,
        /** Offset of the sentence within its block, so ranges come out in block coordinates. */
        startInBlock: Int,
        totalDurationMs: Int,
    ): List<WordTiming> {
        if (sentenceLength <= 0 || totalDurationMs <= 0) return emptyList()
        val perChar = totalDurationMs.toDouble() / sentenceLength
        return wordSpans.mapNotNull { span ->
            if (span.start >= span.end) return@mapNotNull null
            val startMs = (span.start * perChar).toInt().coerceIn(0, totalDurationMs)
            val endMs = (span.end * perChar).toInt().coerceIn(startMs, totalDurationMs)
            WordTiming(
                rangeInBlock = (startInBlock + span.start) until (startInBlock + span.end),
                startMs = startMs,
                endMs = endMs,
            )
        }
    }

    /** Duration in milliseconds of [sampleCount] samples at [sampleRate] Hz. */
    fun durationMs(sampleCount: Int, sampleRate: Int): Int =
        if (sampleRate <= 0) 0 else (sampleCount * 1000L / sampleRate).toInt()

    /**
     * The word being spoken at [positionMs], or null before the first word. Used by the
     * player to pick the highlight from the audio's current playback position.
     */
    fun wordAt(timings: List<WordTiming>, positionMs: Int): WordTiming? =
        timings.lastOrNull { positionMs >= it.startMs }
}
