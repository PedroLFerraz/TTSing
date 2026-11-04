package com.pedrolopes.ttsing.desktop.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.pedrolopes.ttsing.tts.SpeedSteps
import com.pedrolopes.ttsing.tts.SpokenText
import com.pedrolopes.ttsing.tts.piper.PiperChunks
import com.pedrolopes.ttsing.tts.piper.PiperVoice
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
}

/**
 * Speaks with a Piper voice through sherpa-onnx's JVM bindings: a sentence in, samples out.
 * The model reports no word boundaries; the highlight comes from
 * [com.pedrolopes.ttsing.tts.NeuralWordTiming], as on Android.
 *
 * Speed up to [SpeedSteps.MAX_MODEL_SPEED] is the model's own (shorter phonemes, properly faster
 * speech); past that the audio is played faster, pitch kept, by [TimeStretch].
 *
 * Not thread-safe: one sentence at a time.
 */
class PiperEngine {

    private var loaded: PiperVoice? = null
    private var tts: OfflineTts? = null

    val voice: PiperVoice? get() = loaded

    /** Loads [voice], unless it already is. False when its files can't be used. */
    fun prepare(voice: PiperVoice): Boolean {
        if (loaded?.id == voice.id && tts != null) return true
        val model = voice.model ?: return false
        val tokens = voice.tokens?.takeIf { it.isFile } ?: return false
        val dataDir = voice.dataDir?.takeIf { it.isDirectory } ?: return false
        val config = OfflineTtsConfig.builder()
            .setModel(
                OfflineTtsModelConfig.builder()
                    .setVits(
                        OfflineTtsVitsModelConfig.builder()
                            .setModel(model.absolutePath)
                            .setTokens(tokens.absolutePath)
                            .setDataDir(dataDir.absolutePath)
                            // Less random variation in how long each sound lasts: steadier words.
                            .setNoiseScaleW(DURATION_NOISE)
                            .build(),
                    )
                    .setNumThreads(THREADS)
                    .setDebug(false)
                    .build(),
            )
            .build()
        val engine = runCatching { OfflineTts(config) }.getOrNull() ?: return false
        release()
        tts = engine
        loaded = voice
        // The first sentence through a fresh model is much slower than the rest; spend that now.
        runCatching { engine.generate(WARM_UP, 0, 1f) }
        return true
    }

    /**
     * [text] spoken at [speed] (1.0 is the voice's own pace), or null if it could not be.
     * A long sentence is synthesized clause by clause ([PiperChunks]) and joined.
     */
    fun synthesize(text: String, speed: Float, locale: Locale): Speech? {
        val engine = tts ?: return null
        val split = SpeedSteps.split(speed.coerceAtLeast(MIN_SPEED))
        var sampleRate = 0
        var frame = 0
        var cursor = 0
        val audio = mutableListOf<FloatArray>()
        val pieces = mutableListOf<Speech.Piece>()
        PiperChunks.split(text).forEach { piece ->
            val generated = runCatching { engine.generate(SpokenText.normalize(piece, locale), 0, split.model) }.getOrNull()
            val start = text.indexOf(piece, cursor).takeIf { it >= 0 } ?: cursor
            cursor = start + piece.length
            var samples = generated?.samples?.takeIf { it.isNotEmpty() } ?: return@forEach
            sampleRate = generated.sampleRate
            if (split.playback > 1.01f) samples = TimeStretch.stretch(samples, sampleRate, split.playback)
            audio.add(samples)
            pieces.add(Speech.Piece(frame, start, cursor))
            frame += samples.size
        }
        if (audio.isEmpty() || sampleRate <= 0) return null
        val pcm = ShortArray(frame)
        var at = 0
        audio.forEach { piece ->
            piece.forEach { sample -> pcm[at++] = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        }
        return Speech(pcm, sampleRate, pieces)
    }

    fun release() {
        runCatching { tts?.release() }
        tts = null
        loaded = null
    }

    private companion object {
        val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        const val WARM_UP = "Um, dois, três."
        const val MIN_SPEED = 0.25f
        const val DURATION_NOISE = 0.5f
    }
}
