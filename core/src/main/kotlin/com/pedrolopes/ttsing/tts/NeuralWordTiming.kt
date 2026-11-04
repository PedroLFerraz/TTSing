package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.SentenceSpan

/** When one word is spoken within a sentence's synthesized audio. */
data class WordTiming(
    /** The word as char offsets into the owning block's text, matching [Narrator.Listener.onWordRange]. */
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

    /**
     * The same estimate, but anchored to the pieces a long sentence was synthesized in.
     *
     * Spreading one duration across a whole sentence assumes every character takes the same
     * time. That drifts on a long sentence, where one clause is spoken faster than another
     * and each piece's audio ends with a small pause of its own. Given where each piece
     * starts and ends, the drift is contained within it: a word's estimate can only be as
     * wrong as its own clause is.
     */
    fun estimateInPieces(
        pieces: List<Piece>,
        wordSpans: List<SentenceSpan>,
        startInBlock: Int,
    ): List<WordTiming> = pieces.flatMap { piece ->
        val within = wordSpans.filter { it.start >= piece.chars.first && it.start <= piece.chars.last }
        if (within.isEmpty()) return@flatMap emptyList()
        val length = piece.chars.last - piece.chars.first + 1
        val duration = piece.endMs - piece.startMs
        estimate(
            sentenceLength = length,
            wordSpans = within.map { SentenceSpan(it.start - piece.chars.first, it.end - piece.chars.first) },
            startInBlock = startInBlock + piece.chars.first,
            totalDurationMs = duration,
        ).map { timing ->
            timing.copy(startMs = timing.startMs + piece.startMs, endMs = timing.endMs + piece.startMs)
        }
    }

    /** One piece of a sentence: which characters it covers, and when its audio plays. */
    data class Piece(val chars: IntRange, val startMs: Int, val endMs: Int)

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
