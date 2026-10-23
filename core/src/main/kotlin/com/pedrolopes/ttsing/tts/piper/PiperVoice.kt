package com.pedrolopes.ttsing.tts.piper

import java.io.File
import java.util.Locale

/**
 * A neural voice that ships with the app, rather than with the device's TTS engine.
 *
 * The models are Piper voices (MIT) run by sherpa-onnx, each read from a folder named for its
 * id: the Android app ships one in `assets/piper/<id>` (see the `fetchPiperVoices` Gradle task)
 * and downloads the rest. Nothing here assumes any particular voice exists: without one, the
 * Android reader goes on using the device's engine.
 */
data class PiperVoice(
    /** Folder name in assets, which doubles as the voice name the picker stores. */
    val id: String,
    val locale: Locale,
    /** Where the copied model lives once it has been unpacked, or null until then. */
    val directory: File? = null,
) {
    /** The model file, once unpacked: one `.onnx` beside `tokens.txt`. */
    val model: File? get() = directory?.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }

    val tokens: File? get() = directory?.let { File(it, "tokens.txt") }

    /**
     * espeak-ng's phoneme data. Every Piper voice ships the same 20 MB of it, so voices share
     * one copy; a voice that brought its own (downloaded before that was true) keeps using it.
     */
    val dataDir: File?
        get() = directory?.let { dir ->
            File(dir, DATA_DIR_NAME).takeIf { it.isDirectory }
                ?: File(dir.parentFile, DATA_DIR_NAME).takeIf { it.isDirectory }
        }

    companion object {
        /** Marks our own voices in a list that otherwise holds the engine's. */
        const val FEATURE = "ttsing-piper"

        const val DATA_DIR_NAME = "espeak-ng-data"
    }
}

/** What a Piper voice's folder holds and how it is put in place, wherever it was unpacked to. */
object PiperVoiceFiles {

    /** Reads the language out of a sherpa-onnx voice folder name, e.g. `vits-piper-pt_BR-…`. */
    fun localeOf(id: String): Locale? {
        val tag = Regex("""-([a-z]{2,3})_([A-Z]{2})-""").find(id)
            ?: return Regex("""-([a-z]{2,3})-""").find(id)?.let { Locale(it.groupValues[1]) }
        return Locale(tag.groupValues[1], tag.groupValues[2])
    }

    /**
     * Moves a fully written [temp] copy of the phoneme data into place as [shared]. The rename
     * is what makes the data appear all at once: an extraction that dies halfway leaves only
     * a scratch folder, never a [shared] that looks complete and is not. If another writer got
     * there first, theirs stands and [temp] is dropped. True when [shared] is there afterwards.
     */
    fun publishShared(temp: File, shared: File): Boolean {
        if (!shared.isDirectory && temp.renameTo(shared)) return true
        temp.deleteRecursively()
        return shared.isDirectory
    }

    /** Written once a voice's files are all in place, so a half-copy is never used. */
    const val MARKER = ".unpacked"
}
