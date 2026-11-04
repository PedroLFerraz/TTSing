package com.pedrolopes.ttsing.desktop

import com.pedrolopes.ttsing.desktop.tts.CoquiEngine
import com.pedrolopes.ttsing.desktop.tts.CoquiSetup
import com.pedrolopes.ttsing.desktop.tts.GoogleEngine
import com.pedrolopes.ttsing.desktop.tts.Speech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * The Google and Coqui voices, for real: Google over the network, and Tacotron 2 installed the
 * way the voice list's Get does it, into TTSing's own folder (a gigabyte, so it stays there for
 * the app). Skipped unless `-PliveVoices=1`. What they said lands in `build/voice-checks`.
 */
class VoiceEnginesCheck {

    private val out = File("build/voice-checks").apply { mkdirs() }

    private fun live() = assumeTrue("set -PliveVoices=1", System.getProperty("liveVoices") == "1")

    @Test
    fun `Google speaks Portuguese and English, faster when asked`() {
        live()
        val engine = GoogleEngine()
        val pt = "O jardim estava silencioso pela manhã, e o orvalho ainda cobria a grama."
        val normal = engine.synthesize(pt, 1f, Locale("pt", "BR"))
        assertNull(engine.failure)
        assertNotNull(normal)
        val fast = engine.synthesize(pt, 2.5f, Locale("pt", "BR"))!!
        println("Google pt: 1×: ${normal!!.durationMs} ms, 2.5×: ${fast.durationMs} ms at ${normal.sampleRate} Hz")
        assertEquals(0.4, fast.durationMs.toDouble() / normal.durationMs, 0.06)
        // Longer than one request takes: it goes in pieces, each its own stretch of audio.
        val long = "The garden was quiet in the morning, and the birds sang in the hedges while the dew was still on the grass; " +
            "Maria opened the window and breathed the cool air before going back to her book."
        val english = engine.synthesize(long, 1f, Locale.US)!!
        println("Google en: ${english.durationMs} ms in ${english.pieces.size} pieces")
        assertTrue(english.pieces.size >= 2)
        save(normal, "google-pt-1x")
        save(fast, "google-pt-2.5x")
        save(english, "google-en-1x")
    }

    @Test
    fun `Tacotron 2 installs and speaks`() {
        live()
        val setup = CoquiSetup(AppDirs.default(), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        if (!setup.ready.value) {
            setup.start()
            runBlocking {
                withTimeout(90 * 60_000L) {
                    while (!setup.ready.value) {
                        val progress = setup.progress.value
                        if (progress?.failed == true) error(progress.reason.orEmpty())
                        delay(2_000)
                    }
                }
            }
        }
        val engine = CoquiEngine(setup)
        try {
            assertTrue(engine.failure ?: "", engine.prepare())
            val text = "The garden was quiet in the morning, and the dew was still on the grass."
            val normal = engine.synthesize(text, 1f, Locale.US)!!
            val started = System.currentTimeMillis()
            val fast = engine.synthesize(text, 2.5f, Locale.US)!!
            val took = System.currentTimeMillis() - started
            println("Tacotron 2: 1×: ${normal.durationMs} ms, 2.5×: ${fast.durationMs} ms, made in $took ms at ${normal.sampleRate} Hz")
            assertTrue(normal.durationMs in 2_000..10_000)
            assertEquals(0.4, fast.durationMs.toDouble() / normal.durationMs, 0.06)
            save(normal, "tacotron2-en-1x")
            save(fast, "tacotron2-en-2.5x")
        } finally {
            engine.release()
        }
    }

    private fun save(speech: Speech, name: String) {
        val bytes = ByteBuffer.allocate(speech.pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        speech.pcm.forEach { bytes.putShort(it) }
        val format = AudioFormat(speech.sampleRate.toFloat(), 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(bytes.array()), format, speech.pcm.size.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, File(out, "$name.wav"))
        }
    }
}
