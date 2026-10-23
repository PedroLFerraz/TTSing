package com.pedrolopes.ttsing.tts.piper

import android.content.Context
import android.speech.tts.Voice
import java.io.File

/** How this voice appears in the reader's voice list. */
fun PiperVoice.asEngineVoice(): Voice = Voice(
    id,
    locale,
    Voice.QUALITY_HIGH,
    Voice.LATENCY_NORMAL,
    false,
    setOf(PiperVoice.FEATURE),
)

object PiperVoices {

    private const val ASSET_DIR = "piper"

    /** The voices this build ships inside the APK, unpacked or not. */
    fun bundled(context: Context): List<PiperVoice> =
        runCatching { context.assets.list(ASSET_DIR) }.getOrNull().orEmpty().mapNotNull { id ->
            val locale = PiperVoiceFiles.localeOf(id) ?: return@mapNotNull null
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
            val locale = PiperVoiceFiles.localeOf(dir.name) ?: return@mapNotNull null
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
                sharedTemp?.let { PiperVoiceFiles.publishShared(it, shared) }
            } finally {
                sharedTemp?.deleteRecursively()
            }
            File(directory, PiperVoiceFiles.MARKER).writeText(voice.id)
        }
        return voice.copy(directory = directory)
    }

    /** A scratch folder for the shared phoneme data, unique so two writers never share one. */
    fun newSharedTemp(context: Context): File =
        File(File(context.filesDir, ASSET_DIR), "${PiperVoice.DATA_DIR_NAME}.partial-${System.nanoTime()}")

    private fun File.isReadyFor(id: String): Boolean =
        File(this, PiperVoiceFiles.MARKER).takeIf { it.isFile }?.readText() == id

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
