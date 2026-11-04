package com.pedrolopes.ttsing.desktop.tts

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * The sound card, through Java Sound (PipeWire or PulseAudio serve it as ALSA's default
 * device). One line stays open while sentences follow one another at the same sample rate, so
 * nothing is reopened between them.
 *
 * Everything that touches the line runs on one thread of its own. ALSA is not thread-safe:
 * stopping a line from one thread while another is inside `write` crashed PipeWire's ALSA
 * plugin and left the writer blocked for good. So nothing here blocks either: the line is
 * only given as much as it has room for, and a cancelled [play] silences it from the same
 * thread on its way out.
 */
class AudioOut {

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "ttsing-audio").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    private var line: SourceDataLine? = null
    private var rate = 0

    private fun lineFor(sampleRate: Int): SourceDataLine {
        line?.takeIf { rate == sampleRate }?.let { return it }
        release()
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
     * Plays [speech] to the end, reporting how many of its frames have been heard as it goes
     * (on the audio thread). Returns false if cancelled first, in which case the line is cut off.
     */
    suspend fun play(speech: Speech, onFrame: (Int) -> Unit): Boolean = withContext(thread) {
        val out = lineFor(speech.sampleRate)
        val bytes = ByteArray(speech.pcm.size * 2)
        speech.pcm.forEachIndexed { i, sample ->
            bytes[i * 2] = sample.toInt().toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        out.start()
        val base = out.longFramePosition
        val total = speech.pcm.size
        debug { "play ${total} frames from $base, room ${out.available()}, running ${out.isRunning}" }
        var lastReport = System.currentTimeMillis()
        try {
            var written = 0
            while (written < bytes.size || out.longFramePosition - base < total) {
                if (!coroutineContext.isActive) return@withContext false
                val room = out.available() and 1.inv()
                if (written < bytes.size && room > 0) {
                    written += out.write(bytes, written, minOf(room, CHUNK_BYTES, bytes.size - written))
                } else {
                    delay(POLL_MS)
                }
                onFrame((out.longFramePosition - base).toInt())
                if (System.currentTimeMillis() - lastReport > 1_000) {
                    lastReport = System.currentTimeMillis()
                    debug { "written $written/${bytes.size}, heard ${out.longFramePosition - base}/$total, room ${out.available()}" }
                }
            }
            true
        } finally {
            if (!coroutineContext.isActive) {
                debug { "cut off" }
                out.stop()
                out.flush()
            }
        }
    }

    /** Closes the line; the next [play] opens a new one. */
    fun close() {
        thread.executor.execute { release() }
    }

    private fun release() {
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
        const val POLL_MS = 10L
    }
}
