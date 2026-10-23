package com.pedrolopes.ttsing.desktop

import com.pedrolopes.ttsing.desktop.tts.PiperEngine
import com.pedrolopes.ttsing.tts.piper.PiperVoiceFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * The real voice, end to end: sherpa-onnx's native library loads, a Piper model speaks, and the
 * sound card takes its format. Needs a voices folder (`-PpiperVoices=<folder>`, laid out as the
 * app keeps one), so it is skipped otherwise.
 */
class PiperEngineCheck {

    @Test
    fun `a Piper voice speaks, faster when asked`() {
        val folder = System.getProperty("piperVoices")?.let(::File)
        assumeTrue("set -PpiperVoices=<folder>", folder?.isDirectory == true)
        val voice = PiperVoiceFolder(folder!!).voices().firstOrNull()
        assertNotNull("no whole voice in $folder", voice)

        val engine = PiperEngine()
        try {
            assertTrue(engine.prepare(voice!!))
            val text = "A manhã estava clara e luminosa, e as folhas sussurravam baixinho."
            val normal = engine.synthesize(text, 1f, Locale("pt", "BR"))
            val fast = engine.synthesize(text, 2f, Locale("pt", "BR"))
            assertNotNull(normal)
            assertNotNull(fast)
            println("1×: ${normal!!.durationMs} ms, 2×: ${fast!!.durationMs} ms at ${normal.sampleRate} Hz")
            assertTrue(normal.durationMs in 2_000..10_000)
            // 1.5× from the model, the rest from TimeStretch: about half as long overall.
            assertEquals(0.5, fast.durationMs.toDouble() / normal.durationMs, 0.12)

            val format = AudioFormat(normal.sampleRate.toFloat(), 16, 1, true, false)
            assertTrue("no sound output takes $format", AudioSystem.isLineSupported(javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, format)))
        } finally {
            engine.release()
        }
    }
}
