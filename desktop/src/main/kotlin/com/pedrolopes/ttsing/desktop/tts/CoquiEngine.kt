package com.pedrolopes.ttsing.desktop.tts

import com.pedrolopes.ttsing.tts.SpokenText
import com.pedrolopes.ttsing.tts.TimeStretch
import com.pedrolopes.ttsing.tts.piper.PiperChunks
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Speaks with Coqui's Tacotron 2 through the helper [CoquiSetup] installed: one Python process,
 * the model loaded once, a piece of a sentence in and its samples out (see `helper.py` for the
 * protocol). Tacotron has no speed of its own, so all of it is time-stretched ([TimeStretch]).
 *
 * Not thread-safe: one sentence at a time.
 */
class CoquiEngine(private val setup: CoquiSetup) : SpeechEngine {

    private var process: Process? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var sampleRate = 0

    override var failure: String? = null
        private set

    /** Starts the helper and waits for its model to load, unless it's already running. */
    fun prepare(): Boolean {
        if (process?.isAlive == true) return true
        release()
        if (!setup.ready.value) {
            failure = "Get Tacotron 2 in the voice list first"
            return false
        }
        val started = try {
            setup.startHelper()
        } catch (e: IOException) {
            failure = "The Coqui helper couldn't start: ${e.message}"
            return false
        }
        process = started
        input = BufferedInputStream(started.inputStream)
        output = started.outputStream
        val ready = readLine()
        if (ready == null || !ready.startsWith("READY ")) {
            failure = "The Coqui helper didn't start (see coqui/helper.log in TTSing's folder)"
            release()
            return false
        }
        sampleRate = ready.removePrefix("READY ").trim().toInt()
        failure = null
        return true
    }

    override fun synthesize(text: String, speed: Float, locale: Locale): Speech? {
        val speech = Speech.Builder(text)
        for (piece in PiperChunks.split(text)) {
            val samples = speak(SpokenText.normalize(piece, locale)) ?: return null
            speech.add(piece, TimeStretch.stretch(samples, sampleRate, speed), sampleRate)
        }
        return speech.build()
    }

    /** [text]'s samples; empty when the model refused it, null when the helper is gone. */
    private fun speak(text: String): FloatArray? {
        val out = output ?: return null
        val answers = input ?: return null
        try {
            out.write((JsonObject(mapOf("text" to JsonPrimitive(text))).toString() + "\n").toByteArray())
            out.flush()
            val header = readLine() ?: throw IOException("the helper stopped")
            // A piece the model can't say is left out, as Piper's engine does.
            if (header.startsWith("ERROR")) return FloatArray(0)
            val count = header.removePrefix("AUDIO ").trim().toInt()
            val bytes = answers.readNBytes(count * 4)
            if (bytes.size < count * 4) throw IOException("the helper stopped mid-sentence")
            return FloatArray(count).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        } catch (e: IOException) {
            failure = "The Coqui helper stopped: ${e.message}"
            release()
            return null
        }
    }

    /** One line of the helper's answer, without its newline; null at the end of its output. */
    private fun readLine(): String? {
        val stream = input ?: return null
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = stream.read()
            if (byte < 0) return null
            if (byte == '\n'.code) return line.toString(Charsets.UTF_8)
            line.write(byte)
        }
    }

    override fun release() {
        process?.let { helper ->
            runCatching { helper.outputStream.close() }
            helper.destroy()
        }
        process = null
        input = null
        output = null
    }
}
