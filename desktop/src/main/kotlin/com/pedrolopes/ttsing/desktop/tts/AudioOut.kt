package com.pedrolopes.ttsing.desktop.tts

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * The sound card, through Java Sound (PipeWire or PulseAudio serve it as ALSA's default
 * device). One line stays open while sentences follow one another at the same sample rate, so
 * nothing is reopened between them.
 */
class AudioOut {

    private var line: SourceDataLine? = null
    private var rate = 0

    private fun lineFor(sampleRate: Int): SourceDataLine {
        line?.takeIf { rate == sampleRate }?.let { return it }
        close()
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        // A short buffer, so pausing stops the voice at once rather than a second later.
        val bufferBytes = sampleRate * 2 * BUFFER_MS / 1000
        return AudioSystem.getSourceDataLine(format).also {
            it.open(format, bufferBytes)
            line = it
            rate = sampleRate
        }
    }

    /**
     * Plays [speech] to the end, reporting how many of its frames have been heard as it goes.
     * Returns false if the coroutine was cancelled first, in which case the line is cut off.
     */
    suspend fun play(speech: Speech, onFrame: (Int) -> Unit): Boolean {
        val out = lineFor(speech.sampleRate)
        val bytes = ByteArray(speech.pcm.size * 2)
        speech.pcm.forEachIndexed { i, sample ->
            bytes[i * 2] = sample.toInt().toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        out.start()
        val base = out.longFramePosition
        val total = speech.pcm.size
        try {
            var written = 0
            while (written < bytes.size) {
                if (!coroutineContext.isActive) return false
                val chunk = minOf(CHUNK_BYTES, bytes.size - written)
                written += out.write(bytes, written, chunk)
                onFrame((out.longFramePosition - base).toInt())
            }
            // The tail is still in the line's buffer: wait for it to be heard.
            while (out.longFramePosition - base < total) {
                if (!coroutineContext.isActive) return false
                onFrame((out.longFramePosition - base).toInt())
                delay(POLL_MS)
            }
            return true
        } finally {
            if (!coroutineContext.isActive) stop()
        }
    }

    /** Silences the line at once, dropping whatever it still had to play. */
    fun stop() {
        line?.let {
            it.stop()
            it.flush()
        }
    }

    fun close() {
        line?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        line = null
        rate = 0
    }

    private companion object {
        const val BUFFER_MS = 150
        const val CHUNK_BYTES = 2048
        const val POLL_MS = 15L
    }
}
