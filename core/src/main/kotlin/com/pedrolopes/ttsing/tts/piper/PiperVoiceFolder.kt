package com.pedrolopes.ttsing.tts.piper

import java.io.File

/**
 * The folder voices are unpacked or downloaded into: one folder per voice id, beside the one
 * copy of the espeak-ng phoneme data they all share. A voice's folder counts only once its
 * [PiperVoiceFiles.MARKER] says it is whole.
 */
class PiperVoiceFolder(val root: File) {

    /** Where [id] lives (or will live) on disk. */
    fun directoryFor(id: String): File = File(root, id)

    /** The shared espeak-ng data, beside the voices that use it. */
    val sharedDataDir: File get() = File(root, PiperVoice.DATA_DIR_NAME)

    /** A scratch folder for the shared phoneme data, unique so two writers never share one. */
    fun newSharedTemp(): File = File(root, "${PiperVoice.DATA_DIR_NAME}.partial-${System.nanoTime()}")

    /** Whether [id]'s folder has all its files. */
    fun isReady(id: String): Boolean =
        File(directoryFor(id), PiperVoiceFiles.MARKER).takeIf { it.isFile }?.readText() == id

    /** The voices whose folders are whole. */
    fun voices(): List<PiperVoice> =
        root.listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.isDirectory || !isReady(dir.name)) return@mapNotNull null
            val locale = PiperVoiceFiles.localeOf(dir.name) ?: return@mapNotNull null
            PiperVoice(dir.name, locale, dir)
        }

    /** Frees [id]'s files. */
    fun delete(id: String): Boolean =
        directoryFor(id).takeIf { it.isDirectory }?.deleteRecursively() ?: false
}
