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
            val folder = folder(context)
            PiperVoice(id, locale, folder.directoryFor(id).takeIf { folder.isReady(id) })
        }

    /** Where voices are unpacked and downloaded to. */
    fun folder(context: Context): PiperVoiceFolder = PiperVoiceFolder(File(context.filesDir, ASSET_DIR))

    /** Every voice the reader can speak with right now, bundled or downloaded, no repeats. */
    fun available(context: Context): List<PiperVoice> =
        (bundled(context) + folder(context).voices()).distinctBy { it.id }.sortedBy { it.id }

    fun find(context: Context, name: String?): PiperVoice? =
        name?.let { available(context).firstOrNull { voice -> voice.id == it } }

    /**
     * The voice's folder on disk, unpacking it from the assets the first time. espeak-ng
     * reads its phoneme data as ordinary files, so the model cannot stay inside the APK.
     * Blocking and slow (tens of megabytes), so callers run it off the main thread.
     */
    fun unpack(context: Context, voice: PiperVoice): PiperVoice {
        val folder = folder(context)
        val directory = folder.directoryFor(voice.id)
        // A downloaded voice is already on disk; only the bundled one has to be copied out.
        if (!folder.isReady(voice.id)) {
            directory.deleteRecursively()
            val shared = folder.sharedDataDir
            val sharedTemp = if (!shared.isDirectory) folder.newSharedTemp() else null
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

}
