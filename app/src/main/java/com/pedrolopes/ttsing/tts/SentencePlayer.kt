package com.pedrolopes.ttsing.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays one sentence of PCM through an [AudioTrack] **owned by this app**.
 *
 * This is the point of the whole exercise: with [android.speech.tts.TextToSpeech.speak] the
 * AudioTrack lives in the TTS engine's process, so Android attributes the playback to the
 * engine app and routes Bluetooth/headset media buttons there instead of here. Writing the
 * audio ourselves makes TTSing the app that is audibly playing, which is what the platform
 * keys media-button routing off.
 */
class SentencePlayer {

    private var track: AudioTrack? = null

    /**
     * Plays [sentence] to completion, invoking [onFrame] with the playback position so the
     * caller can highlight the current word. Returns true if it finished, false if it was
     * stopped (via [stop]) or the coroutine was cancelled.
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
        val pcm = sentence.pcm
        val totalFrames = sentence.frameCount

        // Report position on a timer rather than after each write. Writes are small and
        // finish almost immediately into the buffer, long before that audio is audible, so
        // driving the highlight off them would freeze it on the first word.
        val progress = launch {
            while (isActive) {
                val head = runCatching { audioTrack.playbackHeadPosition }.getOrNull() ?: break
                onFrame(head)
                if (head >= totalFrames) break
                delay(PROGRESS_POLL_MS)
            }
        }

        var completed = true
        try {
            audioTrack.play()
            var offset = 0
            while (offset < pcm.size) {
                if (!currentCoroutineContext().isActive ||
                    audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING
                ) {
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
            }
            // Wait for the queued audio to actually reach the speaker BEFORE stopping:
            // AudioTrack.stop() resets playbackHeadPosition to zero, so polling it after
            // stopping waits on a counter that never advances again.
            if (completed) completed = awaitDrain(audioTrack, totalFrames, sentence.sampleRateHz)
        } catch (_: IllegalStateException) {
            // The track was released underneath us by stop(); treat as interrupted.
            completed = false
        } finally {
            progress.cancel()
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
            if (track === audioTrack) track = null
        }
        completed
    }

    /** Stops playback immediately; the in-flight [play] returns false. */
    fun stop() {
        val current = track ?: return
        runCatching { current.pause() }
        // flush() discards what's buffered, which also unblocks a write() waiting for space.
        runCatching { current.flush() }
        runCatching { current.stop() }
    }

    /**
     * Waits until the whole sentence has been heard, so the next one doesn't clip its tail.
     * Bounded by the sentence's own duration plus a grace margin: a device that never
     * reports the final frame must not be able to wedge the reader.
     */
    private suspend fun awaitDrain(audioTrack: AudioTrack, totalFrames: Int, sampleRateHz: Int): Boolean {
        val expectedMs = totalFrames * 1000L / sampleRateHz.coerceAtLeast(1)
        val deadline = SystemClock.elapsedRealtime() + expectedMs + DRAIN_GRACE_MS
        while (currentCoroutineContext().isActive) {
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) return false
            val head = runCatching { audioTrack.playbackHeadPosition }.getOrNull() ?: return true
            if (head >= totalFrames) return true
            if (SystemClock.elapsedRealtime() > deadline) return true
            delay(DRAIN_POLL_MS)
        }
        return false
    }

    private companion object {
        const val DEFAULT_BUFFER_BYTES = 16 * 1024
        const val WRITE_CHUNK_BYTES = 4 * 1024
        const val PROGRESS_POLL_MS = 40L
        const val DRAIN_POLL_MS = 20L
        const val DRAIN_GRACE_MS = 750L
    }
}
