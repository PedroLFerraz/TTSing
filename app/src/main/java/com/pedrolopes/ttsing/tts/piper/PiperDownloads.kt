package com.pedrolopes.ttsing.tts.piper

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a voice into the app's own storage, where the reader looks for it.
 *
 * The models come as `.tar.bz2` from the sherpa-onnx model releases: one `.onnx`, its tokens
 * and the espeak-ng phoneme data. Unpacked, they sit beside the voice that ships inside the
 * app, so nothing downstream needs to know where a voice came from. An interrupted download
 * leaves a half-written folder, which is deleted rather than kept: a voice is only usable
 * whole, and 60 MB is cheap to fetch again.
 *
 * One instance lives with the app ([com.pedrolopes.ttsing.TTSingApp]) and runs in a scope of
 * its own: closing the settings sheet, or leaving the reader, neither loses the progress nor
 * lets a second download start into the same folder.
 */
class PiperDownloads(private val context: Context, private val scope: CoroutineScope) {

    private val state = DownloadState()

    /** The download in flight (or the last one to fail), or null. One at a time: these are big. */
    val progress: StateFlow<DownloadProgress?> = state.progress

    private val _installed = MutableStateFlow<Set<String>>(emptySet())

    /** The ids of the voices the reader can speak with right now, kept current. */
    val installed: StateFlow<Set<String>> = _installed

    private var job: Job? = null

    @Volatile
    private var connection: HttpURLConnection? = null

    init {
        refresh()
    }

    /** Looks at the disk again, off the main thread. */
    fun refresh() {
        scope.launch { _installed.value = scan() }
    }

    private fun scan(): Set<String> = PiperVoices.available(context).map { it.id }.toSet()

    fun start(voice: CatalogVoice): StartResult {
        val result = state.tryStart(voice.id, voice.megabytes)
        if (result != StartResult.STARTED) return result
        job = scope.launch {
            val target = PiperVoices.directoryFor(context, voice.id)
            // A tap can never wipe a voice that works: the folder is only cleared for a fetch.
            if (PiperVoices.find(context, voice.id) != null) {
                state.finish(voice.id)
                return@launch
            }
            try {
                fetch(voice, target) { ensureActive() }
                state.finish(voice.id)
            } catch (e: Throwable) {
                target.deleteRecursively()
                if (isActive) state.fail(voice.id, DownloadFailure.describe(e)) else state.finish(voice.id)
                if (e is CancellationException) throw e
            } finally {
                connection = null
                _installed.value = scan()
            }
        }
        return result
    }

    /** Stops the running download and throws its half-written files away. */
    fun cancel() {
        connection?.disconnect()
        job?.cancel()
    }

    fun clearError() = state.clearError()

    /** Frees a downloaded voice (stopping its download first, if that is what is running). */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        if (state.progress.value?.id == id) {
            cancel()
            job?.join()
        }
        PiperVoices.delete(context, id)
        _installed.value = scan()
    }

    private fun fetch(voice: CatalogVoice, target: File, checkActive: () -> Unit) {
        target.deleteRecursively()
        target.mkdirs()
        val http = (URL(voice.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        connection = http
        // The phoneme data is written beside its final folder and renamed into place whole,
        // so an interrupted extraction never leaves a folder that merely looks complete.
        val shared = PiperVoices.sharedDataDir(context)
        val sharedTemp = if (!shared.isDirectory) PiperVoices.newSharedTemp(context) else null
        try {
            if (http.responseCode !in 200..299) throw HttpStatusException(http.responseCode)
            http.inputStream.use { raw ->
                val counting = CountingStream(BufferedInputStream(raw, 1 shl 16)) { bytes ->
                    checkActive()
                    state.advance(voice.id, (bytes / 1_000_000).toInt())
                }
                TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        checkActive()
                        // The archive holds one top folder; its contents land directly in `target`,
                        // except the phoneme data, which every voice shares one copy of.
                        val relative = entry.name.substringAfter('/', "")
                        val phonemes = relative.startsWith("${PiperVoice.DATA_DIR_NAME}/")
                        val file = when {
                            relative.isEmpty() || relative.contains("..") -> null
                            !phonemes -> File(target, relative)
                            sharedTemp != null ->
                                File(sharedTemp, relative.removePrefix("${PiperVoice.DATA_DIR_NAME}/"))
                            else -> null
                        }
                        if (file != null) {
                            if (entry.isDirectory) {
                                file.mkdirs()
                            } else {
                                file.parentFile?.mkdirs()
                                file.outputStream().use { out -> tar.copyTo(out) }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            if (!File(target, "tokens.txt").isFile) throw IOException("The download was incomplete")
            sharedTemp?.let { PiperVoices.publishShared(it, shared) }
            File(target, PiperVoices.MARKER).writeText(voice.id)
        } finally {
            sharedTemp?.deleteRecursively()
            http.disconnect()
        }
    }

    /** Counts bytes as they are read, so progress reflects the download and not the unpacking. */
    private class CountingStream(
        private val source: java.io.InputStream,
        private val onRead: (Long) -> Unit,
    ) : java.io.InputStream() {
        private var total = 0L

        override fun read(): Int = source.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            source.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        override fun close() = source.close()

        private fun count(bytes: Long) {
            total += bytes
            onRead(total)
        }
    }
}
