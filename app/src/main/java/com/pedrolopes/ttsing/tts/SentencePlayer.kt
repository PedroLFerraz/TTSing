package com.pedrolopes.ttsing.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Plays one sentence of PCM through an [AudioTrack] **owned by this app**.
 *
 * This is the point of the whole exercise: with [android.speech.tts.TextToSpeech.speak] the
 * AudioTrack lives in the TTS engine's process, so Android attributes the playback to the
 * engine app and routes Bluetooth/headset media buttons there instead of here. Writing the
 * audio ourselves makes TTSing the app that is audibly playing, which is what the platform
 * keys media-button routing off.
 *
 * Writing is chunked so pausing takes effect within a few milliseconds instead of at the end
 * of the sentence, and so playback position can drive the word highlight.
 */
class SentencePlayer {

    private var track: AudioTrack? = null

    /**
     * Plays [sentence] to completion, invoking [onFrame] as playback advances so the caller
     * can highlight the current word. Returns true if it finished, false if it was stopped
     * (via [stop]) or the coroutine was cancelled.
     */
    suspend fun play(
        sentence: SynthesizedSentence,
        onFrame: (Int) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val channelMask = if (sentence.channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        // Some engines report a format AudioTrack won't accept; 16-bit PCM is the safe default.
        val encoding = when (sentence.audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_FLOAT,
            -> sentence.audioFormat
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBuffer = AudioTrack.getMinBufferSize(sentence.sampleRateHz, channelMask, encoding)
            .takeIf { it > 0 } ?: DEFAULT_BUFFER_BYTES

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sentence.sampleRateHz)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, DEFAULT_BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = audioTrack
        audioTrack.play()

        val pcm = sentence.pcm
        var offset = 0
        var completed = true
        try {
            while (offset < pcm.size) {
                if (!currentCoroutineContext().isActive || audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    completed = false
                    break
                }
                val chunk = minOf(WRITE_CHUNK_BYTES, pcm.size - offset)
                val written = audioTrack.write(pcm, offset, chunk)
                if (written <= 0) {
                    completed = false
                    break
                }
                offset += written
                onFrame(audioTrack.playbackHeadPosition)
            }
            if (completed) {
                // Let the buffered tail actually reach the speaker before reporting done,
                // otherwise the next sentence clips the end of this one.
                audioTrack.stop()
                drain(audioTrack, sentence)
            }
        } catch (_: IllegalStateException) {
            // The track was released underneath us by stop(); treat as interrupted.
            completed = false
        } finally {
            runCatching { audioTrack.release() }
            if (track === audioTrack) track = null
        }
        completed
    }

    /** Stops playback immediately; the in-flight [play] returns false. */
    fun stop() {
        val current = track ?: return
        runCatching { current.pause() }
        runCatching { current.flush() }
        runCatching { current.stop() }
    }

    private suspend fun drain(audioTrack: AudioTrack, sentence: SynthesizedSentence) {
        val totalFrames = sentence.pcm.size / bytesPerFrame(sentence)
        while (currentCoroutineContext().isActive) {
            val head = runCatching { audioTrack.playbackHeadPosition }.getOrNull() ?: return
            if (head >= totalFrames) return
            kotlinx.coroutines.delay(DRAIN_POLL_MS)
        }
    }

    private fun bytesPerFrame(sentence: SynthesizedSentence): Int {
        val bytesPerSample = when (sentence.audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        return (bytesPerSample * sentence.channelCount).coerceAtLeast(1)
    }

    private companion object {
        const val DEFAULT_BUFFER_BYTES = 16 * 1024
        const val WRITE_CHUNK_BYTES = 4 * 1024
        const val DRAIN_POLL_MS = 20L
    }
}
