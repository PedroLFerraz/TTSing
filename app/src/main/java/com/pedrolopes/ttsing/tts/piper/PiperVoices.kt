package com.pedrolopes.ttsing.tts.piper

import android.content.Context
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

/**
 * A neural voice that ships with the app, rather than with the device's TTS engine.
 *
 * The models are Piper voices (MIT) run by sherpa-onnx, and they read from a folder in
 * `assets/piper/<id>` — see the `fetchPiperVoices` Gradle task. Nothing here assumes any
 * particular voice exists: a build without the assets simply offers none, and the reader
 * goes on using the device's engine.
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

    /** How this voice appears in the reader's voice list. */
    fun asEngineVoice(): Voice = Voice(
        id,
        locale,
        Voice.QUALITY_HIGH,
        Voice.LATENCY_NORMAL,
        false,
        setOf(FEATURE),
    )

    companion object {
        /** Marks our own voices in a list that otherwise holds the engine's. */
        const val FEATURE = "ttsing-piper"

        const val DATA_DIR_NAME = "espeak-ng-data"
    }
}

object PiperVoices {

    private const val ASSET_DIR = "piper"

    /** Reads the language out of a sherpa-onnx voice folder name, e.g. `vits-piper-pt_BR-…`. */
    fun localeOf(id: String): Locale? {
        val tag = Regex("""-([a-z]{2,3})_([A-Z]{2})-""").find(id)
            ?: return Regex("""-([a-z]{2,3})-""").find(id)?.let { Locale(it.groupValues[1]) }
        return Locale(tag.groupValues[1], tag.groupValues[2])
    }

    /** The voices this build ships inside the APK, unpacked or not. */
    fun bundled(context: Context): List<PiperVoice> =
        runCatching { context.assets.list(ASSET_DIR) }.getOrNull().orEmpty().mapNotNull { id ->
            val locale = localeOf(id) ?: return@mapNotNull null
            PiperVoice(id, locale, directoryFor(context, id).takeIf { it.isReadyFor(id) })
        }

    /** The shared espeak-ng data, beside the voices that use it. */
    fun sharedDataDir(context: Context): File =
        File(File(context.filesDir, ASSET_DIR), PiperVoice.DATA_DIR_NAME)

    /** The voices already downloaded into the app's own storage. */
    fun downloaded(context: Context): List<PiperVoice> {
        val root = File(context.filesDir, ASSET_DIR)
        return root.listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.isDirectory || !dir.isReadyFor(dir.name)) return@mapNotNull null
            val locale = localeOf(dir.name) ?: return@mapNotNull null
            PiperVoice(dir.name, locale, dir)
        }
    }

    /** Every voice the reader can speak with right now, bundled or downloaded, no repeats. */
    fun available(context: Context): List<PiperVoice> =
        (bundled(context) + downloaded(context)).distinctBy { it.id }.sortedBy { it.id }

    fun find(context: Context, name: String?): PiperVoice? =
        name?.let { available(context).firstOrNull { voice -> voice.id == it } }

    /** Where [id] lives (or will live) on disk. */
    fun directoryFor(context: Context, id: String): File = File(File(context.filesDir, ASSET_DIR), id)

    /**
     * The voice's folder on disk, unpacking it from the assets the first time. espeak-ng
     * reads its phoneme data as ordinary files, so the model cannot stay inside the APK.
     * Blocking and slow (tens of megabytes), so callers run it off the main thread.
     */
    fun unpack(context: Context, voice: PiperVoice): PiperVoice {
        val directory = directoryFor(context, voice.id)
        // A downloaded voice is already on disk; only the bundled one has to be copied out.
        if (!directory.isReadyFor(voice.id)) {
            directory.deleteRecursively()
            val shared = sharedDataDir(context)
            val sharedTemp = if (!shared.isDirectory) newSharedTemp(context) else null
            try {
                copyAsset(context, "$ASSET_DIR/${voice.id}", directory) { name ->
                    // The phoneme data goes to the shared folder, and only the first voice needs
                    // to write it; everything else belongs to the voice.
                    when {
                        !name.startsWith("${PiperVoice.DATA_DIR_NAME}/") -> File(directory, name)
                        sharedTemp != null -> File(sharedTemp, name.removePrefix("${PiperVoice.DATA_DIR_NAME}/"))
                        else -> null
                    }
                }
                sharedTemp?.let { publishShared(it, shared) }
            } finally {
                sharedTemp?.deleteRecursively()
            }
            File(directory, MARKER).writeText(voice.id)
        }
        return voice.copy(directory = directory)
    }

    /** A scratch folder for the shared phoneme data, unique so two writers never share one. */
    fun newSharedTemp(context: Context): File =
        File(File(context.filesDir, ASSET_DIR), "${PiperVoice.DATA_DIR_NAME}.partial-${System.nanoTime()}")

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

    private fun File.isReadyFor(id: String): Boolean =
        File(this, MARKER).takeIf { it.isFile }?.readText() == id

    /** Copies an asset folder out, with [destination] saying where each file goes (null: skip). */
    private fun copyAsset(
        context: Context,
        assetPath: String,
        target: File,
        relative: String = "",
        destination: (String) -> File?,
    ) {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull().orEmpty()
        if (children.isEmpty()) {
            val file = destination(relative) ?: return
            file.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            val childPath = if (relative.isEmpty()) child else "$relative/$child"
            copyAsset(context, "$assetPath/$child", target, childPath, destination)
        }
    }

    /** Frees a downloaded voice's files. The one inside the app is left alone. */
    fun delete(context: Context, id: String): Boolean =
        directoryFor(context, id).takeIf { it.isDirectory }?.deleteRecursively() ?: false
}
