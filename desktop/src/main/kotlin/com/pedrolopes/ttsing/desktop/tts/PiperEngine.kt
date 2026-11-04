package com.pedrolopes.ttsing.desktop.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.pedrolopes.ttsing.tts.SpeedSteps
import com.pedrolopes.ttsing.tts.SpokenText
import com.pedrolopes.ttsing.tts.TimeStretch
import com.pedrolopes.ttsing.tts.piper.PiperChunks
import com.pedrolopes.ttsing.tts.piper.PiperVoice
import java.util.Locale

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
class PiperEngine : SpeechEngine {

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
    override fun synthesize(text: String, speed: Float, locale: Locale): Speech? {
        val engine = tts ?: return null
        val split = SpeedSteps.split(speed.coerceAtLeast(MIN_SPEED))
        val speech = Speech.Builder(text)
        PiperChunks.split(text).forEach { piece ->
            val generated = runCatching { engine.generate(SpokenText.normalize(piece, locale), 0, split.model) }.getOrNull()
            val rate = generated?.sampleRate ?: 0
            val samples = generated?.samples ?: FloatArray(0)
            speech.add(piece, if (split.playback > 1.01f) TimeStretch.stretch(samples, rate, split.playback) else samples, rate)
        }
        return speech.build()
    }

    override fun release() {
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
