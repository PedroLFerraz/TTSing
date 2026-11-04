package com.pedrolopes.ttsing.desktop.tts

import com.pedrolopes.ttsing.tts.SpokenText
import com.pedrolopes.ttsing.tts.TimeStretch
import com.pedrolopes.ttsing.tts.piper.PiperChunks
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

/**
 * Speaks with Google Translate's voice, as the gTTS library does: each piece of a sentence is
 * sent to Translate's web endpoint, which answers with an MP3. It needs a connection, sends the
 * text to Google, and is not an official API, so it can change under us; [failure] says so
 * rather than leaving the reader wondering why the voice stopped.
 *
 * The voice has one pace; every bit of the speed is time-stretched ([TimeStretch]), which this
 * voice takes well.
 */
class GoogleEngine : SpeechEngine {

    override var failure: String? = null
        private set

    override fun synthesize(text: String, speed: Float, locale: Locale): Speech? {
        val speech = Speech.Builder(text)
        for (piece in PiperChunks.split(text, MAX_CHARS)) {
            val mp3 = try {
                request(SpokenText.normalize(piece, locale), locale)
            } catch (e: IOException) {
                failure = "The Google voice needs an internet connection (${e.message ?: e.javaClass.simpleName})"
                return null
            }
            if (mp3 == null) {
                failure = "Google didn't answer with speech. Its voice may have changed; try another one."
                return null
            }
            val (samples, rate) = decode(mp3)
            speech.add(piece, TimeStretch.stretch(samples, rate, speed), rate)
        }
        failure = null
        return speech.build()
    }

    /** The MP3 Translate makes of [text], or null if its answer holds none. */
    private fun request(text: String, locale: Locale): ByteArray? {
        val language = locale.language
        // What the page itself sends: the text, the language, normal (not slow) speed.
        val inner = JsonArray(listOf(JsonPrimitive(text), JsonPrimitive(language), JsonNull, JsonPrimitive("null")))
        val outer = JsonArray(listOf(JsonArray(listOf(JsonArray(listOf(JsonPrimitive(RPC), JsonPrimitive(inner.toString()), JsonNull, JsonPrimitive("generic")))))))
        // Translate's Brazilian site gives Portuguese its Brazilian voice.
        val host = if (language == "pt") "translate.google.com.br" else "translate.google.com"
        val connection = URI("https://$host/_/TranslateWebserverUi/data/batchexecute").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.outputStream.use { it.write("f.req=${URLEncoder.encode(outer.toString(), Charsets.UTF_8)}".toByteArray()) }
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            val audio = ANSWER.find(body)?.groupValues?.get(1) ?: return null
            return Base64.getDecoder().decode(audio)
        } finally {
            connection.disconnect()
        }
    }

    /** [mp3] as mono float samples, and their rate. */
    private fun decode(mp3: ByteArray): Pair<FloatArray, Int> {
        val bitstream = Bitstream(ByteArrayInputStream(mp3))
        val decoder = Decoder()
        var out = FloatArray(mp3.size * 8)
        var size = 0
        var rate = 0
        try {
            while (true) {
                val header = bitstream.readFrame() ?: break
                val frame = decoder.decodeFrame(header, bitstream) as SampleBuffer
                rate = frame.sampleFrequency
                val channels = frame.channelCount
                val buffer = frame.buffer
                if (size + frame.bufferLength > out.size) out = out.copyOf((size + frame.bufferLength) * 2)
                // Interleaved when stereo: keep the first channel.
                for (i in 0 until frame.bufferLength step channels) out[size++] = buffer[i] / 32768f
                bitstream.closeFrame()
            }
        } finally {
            bitstream.close()
        }
        return out.copyOf(size) to rate
    }

    override fun release() {}

    private companion object {
        /** Translate won't speak more than this in one request. */
        const val MAX_CHARS = 100
        const val TIMEOUT_MS = 15_000
        const val RPC = "jQ1olc"
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        val ANSWER = Regex("""jQ1olc","\[\\"(.*?)\\"]""")
    }
}
