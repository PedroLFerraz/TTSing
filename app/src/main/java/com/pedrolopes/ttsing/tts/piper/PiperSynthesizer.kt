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
        // The first sentence through a fresh model is much slower than the rest — onnxruntime
        // is still working out its plan. Spend that here, while the reader is still choosing a
        // voice, rather than on the first line of the book.
        runCatching { engine.generate(WARM_UP, 0, 1f) }
        true
    }

    /**
     * [text] spoken at [speed] (1.0 is the voice's own pace), or null if it could not be.
     *
     * A long sentence is synthesized clause by clause — see [PiperChunks] for why — and the
     * pieces are played as one, so nothing downstream knows the difference.
     */
    suspend fun synthesize(text: String, speed: Float): SynthesizedSentence? =
        withContext(Dispatchers.IO) {
            val engine = tts ?: return@withContext null
            val rate = speed.coerceIn(MIN_SPEED, MAX_SPEED)
            var sampleRate = 0
            var frame = 0
            var cursor = 0
            val audio = mutableListOf<FloatArray>()
            val anchors = mutableListOf<SynthesizedSentence.FrameMark>()
            PiperChunks.split(text).forEach { piece ->
                val generated = runCatching { engine.generate(piece, 0, rate) }.getOrNull()
                val samples = generated?.samples?.takeIf { it.isNotEmpty() }
                // Where this piece's words sit in the sentence, for the highlight.
                val start = text.indexOf(piece, cursor).takeIf { it >= 0 } ?: cursor
                cursor = start + piece.length
                if (samples == null) return@forEach
                sampleRate = generated.sampleRate
                audio.add(samples)
                anchors.add(SynthesizedSentence.FrameMark(frame, start, cursor))
                frame += samples.size
            }
            if (audio.isEmpty() || sampleRate <= 0) return@withContext null
            SynthesizedSentence(
                pcm = audio.toPcm16(),
                sampleRateHz = sampleRate,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
                channelCount = 1,
                marks = emptyList(),
                pieces = anchors.takeIf { it.size > 1 }.orEmpty(),
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

        /** Short and ordinary: enough to make onnxruntime do its first-run work. */
        const val WARM_UP = "Um, dois, três."

        /** What the model will stretch to; past this the speech stops being speech. */
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3f

        /** Float samples in [-1, 1] as the 16-bit little-endian PCM the player expects. */
        fun List<FloatArray>.toPcm16(): ByteArray {
            val buffer = ByteBuffer.allocate(sumOf { it.size } * 2).order(ByteOrder.LITTLE_ENDIAN)
            forEach { piece ->
                piece.forEach { sample ->
                    val clamped = sample.coerceIn(-1f, 1f)
                    buffer.putShort((clamped * Short.MAX_VALUE).toInt().toShort())
                }
            }
            return buffer.array()
        }
    }
}
