package com.pedrolopes.ttsing.tts.piper

import android.content.Context
import android.media.AudioFormat
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.pedrolopes.ttsing.tts.SynthesizedSentence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speaks with a voice the app carries itself, through sherpa-onnx.
 *
 * Same shape as [com.pedrolopes.ttsing.tts.PcmSynthesizer]: a sentence in, raw PCM out, which
 * the reader plays through its own `AudioTrack`. The model produces the whole sentence at
 * once and reports no word boundaries, so the highlight comes from
 * [com.pedrolopes.ttsing.tts.NeuralWordTiming] as it does for any engine that gives none.
 *
 * Speed is a property of the *model*: `generate(speed)` stretches or shortens the phoneme
 * durations, so 2.5× is properly faster speech rather than a pitched-up recording.
 */
class PiperSynthesizer(private val context: Context) {

    private var loaded: PiperVoice? = null
    private var tts: OfflineTts? = null

    /** Whether a sentence can be spoken with [voice] right now — loading it if need be. */
    suspend fun prepare(voice: PiperVoice): Boolean = withContext(Dispatchers.IO) {
        if (loaded?.id == voice.id && tts != null) return@withContext true
        val ready = runCatching { PiperVoices.unpack(context, voice) }.getOrNull()
            ?: return@withContext false
        val model = ready.model ?: return@withContext false
        val tokens = ready.tokens?.takeIf { it.isFile } ?: return@withContext false
        val dataDir = ready.dataDir?.takeIf { it.isDirectory } ?: return@withContext false
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = dataDir.absolutePath,
                ),
                numThreads = THREADS,
                debug = false,
            ),
        )
        val engine = runCatching { OfflineTts(assetManager = null, config = config) }.getOrNull()
            ?: return@withContext false
        release()
        tts = engine
        loaded = ready
        true
    }

    /** [text] spoken at [speed] (1.0 is the voice's own pace), or null if it could not be. */
    suspend fun synthesize(text: String, speed: Float): SynthesizedSentence? =
        withContext(Dispatchers.IO) {
            val engine = tts ?: return@withContext null
            val audio = runCatching { engine.generate(text, 0, speed.coerceIn(MIN_SPEED, MAX_SPEED)) }
                .getOrNull() ?: return@withContext null
            val samples = audio.samples
            if (samples.isEmpty()) return@withContext null
            SynthesizedSentence(
                pcm = samples.toPcm16(),
                sampleRateHz = audio.sampleRate,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
                channelCount = 1,
                marks = emptyList(),
            )
        }

    fun release() {
        runCatching { tts?.release() }
        tts = null
        loaded = null
    }

    private companion object {
        /** All the cores a phone will give us: synthesis is what keeps the reader fed. */
        val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        /** What the model will stretch to; past this the speech stops being speech. */
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3f

        /** Float samples in [-1, 1] as the 16-bit little-endian PCM the player expects. */
        fun FloatArray.toPcm16(): ByteArray {
            val buffer = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
            forEach { sample ->
                val clamped = sample.coerceIn(-1f, 1f)
                buffer.putShort((clamped * Short.MAX_VALUE).toInt().toShort())
            }
            return buffer.array()
        }
    }
}
