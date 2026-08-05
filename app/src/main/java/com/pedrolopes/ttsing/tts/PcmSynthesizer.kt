package com.pedrolopes.ttsing.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream
import java.io.File

/** Raw audio for one sentence, plus where each word falls inside it. */
data class SynthesizedSentence(
    val pcm: ByteArray,
    val sampleRateHz: Int,
    /** An [android.media.AudioFormat] ENCODING_PCM_* constant. */
    val audioFormat: Int,
    val channelCount: Int,
    /** Word ranges tagged with the audio frame at which each is spoken. */
    val marks: List<FrameMark>,
) {
    /** A word boundary reported by the engine, positioned in audio frames. */
    data class FrameMark(val frame: Int, val start: Int, val end: Int)

    val bytesPerFrame: Int
        get() {
            val bytesPerSample = when (audioFormat) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            return (bytesPerSample * channelCount).coerceAtLeast(1)
        }

    val frameCount: Int get() = (pcm.size / bytesPerFrame).coerceAtLeast(1)

    val durationMs: Int get() = NeuralWordTiming.durationMs(frameCount, sampleRateHz)

    // ByteArray in a data class: identity comparison is what we want (these are large
    // buffers, only ever compared to themselves), so equals/hashCode are made explicit.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Synthesizes a sentence to raw PCM **without letting the TTS engine play it**.
 *
 * [TextToSpeech.speak] hands audio to an `AudioTrack` living inside the TTS engine's own
 * process, so the *engine* — not this app — is what Android sees as "playing audio
 * locally". That is the documented basis for routing Bluetooth/headset media buttons, so
 * button presses never reach us. Going through [TextToSpeech.synthesizeToFile] instead
 * means no engine-side playback happens at all: we receive the audio via
 * `onAudioAvailable` and play it ourselves (see [AudioTrackNarrator]), making this app the
 * one producing sound.
 *
 * `onRangeStart` marks are still collected into [SynthesizedSentence.marks] where the engine
 * supplies them, but nothing in this app currently trusts them for the highlight — see
 * [AudioTrackNarrator]'s class doc for why. They're kept around rather than dropped in case a
 * future engine-specific refinement wants them.
 */
class PcmSynthesizer(private val tts: TextToSpeech, private val cacheDir: File) {

    private class Pending(val id: String) {
        val audio = ByteArrayOutputStream()
        val marks = mutableListOf<SynthesizedSentence.FrameMark>()
        var sampleRateHz = DEFAULT_SAMPLE_RATE
        var audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        var channelCount = 1
        val done = CompletableDeferred<Boolean>()
    }

    private val lock = Any()
    private var pending: Pending? = null

    /** Installs the listener that collects audio chunks and word marks. */
    fun attachListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onBeginSynthesis(
                utteranceId: String,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                withPending(utteranceId) {
                    it.sampleRateHz = sampleRateInHz
                    it.audioFormat = audioFormat
                    it.channelCount = channelCount
                }
            }

            override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                withPending(utteranceId) { it.audio.write(audio) }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                withPending(utteranceId) {
                    it.marks.add(SynthesizedSentence.FrameMark(frame, start, end))
                }
            }

            override fun onDone(utteranceId: String) {
                finish(utteranceId, success = true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                finish(utteranceId, success = false)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                finish(utteranceId, success = false)
            }
        })
    }

    /**
     * Synthesizes [text], returning its audio and word marks, or null if the engine failed.
     * One sentence at a time; callers serialize through the narrator's queue.
     */
    suspend fun synthesize(text: String, utteranceId: String): SynthesizedSentence? {
        val request = Pending(utteranceId)
        synchronized(lock) { pending = request }

        // synthesizeToFile needs a destination, but the audio we actually use arrives via
        // onAudioAvailable; the file is just a required sink.
        val sink = File(cacheDir, SINK_FILE)
        val queued = tts.synthesizeToFile(text, Bundle(), sink, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            synchronized(lock) { pending = null }
            return null
        }

        val ok = request.done.await()
        synchronized(lock) { pending = null }
        val bytes = request.audio.toByteArray()
        if (!ok || bytes.isEmpty()) return null

        return SynthesizedSentence(
            pcm = bytes,
            sampleRateHz = request.sampleRateHz,
            audioFormat = request.audioFormat,
            channelCount = request.channelCount,
            marks = request.marks.sortedBy { it.frame },
        )
    }

    /** Abandons the in-flight synthesis, e.g. on pause or seek. */
    fun cancel() {
        tts.stop()
        synchronized(lock) { pending }?.done?.complete(false)
    }

    private inline fun withPending(utteranceId: String, action: (Pending) -> Unit) {
        val request = synchronized(lock) { pending }
        if (request != null && request.id == utteranceId) {
            synchronized(request) { action(request) }
        }
    }

    private fun finish(utteranceId: String, success: Boolean) {
        val request = synchronized(lock) { pending }
        if (request != null && request.id == utteranceId) request.done.complete(success)
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 22050
        const val SINK_FILE = "tts-sink.wav"
    }
}
