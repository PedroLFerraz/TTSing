package com.pedrolopes.ttsing.tts.piper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/** A voice folder counts only once it is whole: a half-unpacked or half-downloaded one is never offered. */
class PiperVoiceFolderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun whole(folder: PiperVoiceFolder, id: String) {
        folder.directoryFor(id).mkdirs()
        File(folder.directoryFor(id), PiperVoiceFiles.MARKER).writeText(id)
    }

    @Test
    fun `only folders marked whole are voices`() {
        val folder = PiperVoiceFolder(temp.root)
        whole(folder, "vits-piper-pt_BR-faber-medium")
        folder.directoryFor("vits-piper-en_US-amy-medium").mkdirs()
        folder.sharedDataDir.mkdirs()

        val voices = folder.voices()
        assertEquals(listOf("vits-piper-pt_BR-faber-medium"), voices.map { it.id })
        assertEquals(Locale("pt", "BR"), voices.single().locale)
        assertEquals(folder.directoryFor("vits-piper-pt_BR-faber-medium"), voices.single().directory)
    }

    @Test
    fun `a marker naming another voice does not count`() {
        val folder = PiperVoiceFolder(temp.root)
        folder.directoryFor("vits-piper-pt_BR-cadu-medium").mkdirs()
        File(folder.directoryFor("vits-piper-pt_BR-cadu-medium"), PiperVoiceFiles.MARKER).writeText("vits-piper-pt_BR-jeff-medium")
        assertFalse(folder.isReady("vits-piper-pt_BR-cadu-medium"))
    }

    @Test
    fun `delete frees the folder`() {
        val folder = PiperVoiceFolder(temp.root)
        whole(folder, "vits-piper-en_GB-alba-medium")
        assertTrue(folder.delete("vits-piper-en_GB-alba-medium"))
        assertTrue(folder.voices().isEmpty())
        assertFalse(folder.delete("vits-piper-en_GB-alba-medium"))
    }
}
