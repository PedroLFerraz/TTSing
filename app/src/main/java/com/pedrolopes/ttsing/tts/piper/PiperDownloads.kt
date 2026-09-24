package com.pedrolopes.ttsing.tts.piper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** How far along a voice's download is, if one is running. */
data class DownloadProgress(val id: String, val megabytes: Int, val done: Int, val failed: Boolean = false) {
    val fraction: Float get() = if (megabytes <= 0) 0f else (done.toFloat() / megabytes).coerceIn(0f, 1f)
}

/**
 * Fetches a voice into the app's own storage, where the reader looks for it.
 *
 * The models come as `.tar.bz2` from the sherpa-onnx model releases: one `.onnx`, its tokens
 * and the espeak-ng phoneme data. Unpacked, they sit beside the voice that ships inside the
 * app, so nothing downstream needs to know where a voice came from. An interrupted download
 * leaves a half-written folder, which is deleted rather than kept: a voice is only usable
 * whole, and 60 MB is cheap to fetch again.
 */
class PiperDownloads(private val context: Context) {

    private val _progress = MutableStateFlow<DownloadProgress?>(null)

    /** The download in flight, or null. One at a time: these are big, and phones are not. */
    val progress: StateFlow<DownloadProgress?> = _progress

    suspend fun download(voice: CatalogVoice): Boolean = withContext(Dispatchers.IO) {
        if (_progress.value?.let { !it.failed } == true) return@withContext false
        _progress.value = DownloadProgress(voice.id, voice.megabytes, 0)
        val target = PiperVoices.directoryFor(context, voice.id)
        val ok = runCatching { fetch(voice, target) }.getOrDefault(false)
        if (!ok) target.deleteRecursively()
        _progress.value = if (ok) null else DownloadProgress(voice.id, voice.megabytes, 0, failed = true)
        ok
    }

    fun clearError() {
        if (_progress.value?.failed == true) _progress.value = null
    }

    private fun fetch(voice: CatalogVoice, target: File): Boolean {
        target.deleteRecursively()
        target.mkdirs()
        val connection = (URL(voice.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        connection.inputStream.use { raw ->
            val counting = CountingStream(BufferedInputStream(raw, 1 shl 16)) { bytes ->
                val mb = (bytes / 1_000_000).toInt()
                val current = _progress.value
                if (current?.id == voice.id && mb != current.done) {
                    _progress.value = current.copy(done = mb)
                }
            }
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    // The archive holds one top folder; its contents land directly in `target`.
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isNotEmpty() && !relative.contains("..")) {
                        val file = File(target, relative)
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
        if (!File(target, "tokens.txt").isFile) return false
        File(target, PiperVoices.MARKER).writeText(voice.id)
        return true
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
