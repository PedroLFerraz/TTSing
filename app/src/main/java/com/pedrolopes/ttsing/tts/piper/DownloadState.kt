package com.pedrolopes.ttsing.tts.piper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** How far along a voice's download is, if one is running; [reason] says why it failed. */
data class DownloadProgress(
    val id: String,
    val megabytes: Int,
    val done: Int,
    val failed: Boolean = false,
    val reason: String? = null,
) {
    val fraction: Float get() = if (megabytes <= 0) 0f else (done.toFloat() / megabytes).coerceIn(0f, 1f)
}

enum class StartResult {
    STARTED,

    /** Another voice is downloading. */
    BUSY,

    /** This very voice is already on its way. */
    ALREADY_RUNNING,
}

/**
 * The one-download-at-a-time bookkeeping, kept apart from the network so it can be tested.
 * Starting is a compare-and-set: two taps at once cannot both begin, and so cannot both
 * wipe and refill the same folder.
 */
class DownloadState {
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress

    fun tryStart(id: String, megabytes: Int): StartResult {
        while (true) {
            val current = _progress.value
            if (current != null && !current.failed) {
                return if (current.id == id) StartResult.ALREADY_RUNNING else StartResult.BUSY
            }
            // A failed attempt is replaced: starting again is the retry.
            if (_progress.compareAndSet(current, DownloadProgress(id, megabytes, 0))) return StartResult.STARTED
        }
    }

    fun advance(id: String, megabytes: Int) {
        val current = _progress.value
        if (current != null && current.id == id && !current.failed && current.done != megabytes) {
            _progress.value = current.copy(done = megabytes)
        }
    }

    fun fail(id: String, reason: String) {
        val current = _progress.value ?: return
        if (current.id == id) _progress.value = current.copy(done = 0, failed = true, reason = reason)
    }

    /** Done or cancelled: nothing is in flight any more. */
    fun finish(id: String) {
        if (_progress.value?.id == id) _progress.value = null
    }

    fun clearError() {
        if (_progress.value?.failed == true) _progress.value = null
    }
}

/** The server answered, but not with the file. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** What to tell the reader about a download that stopped. */
object DownloadFailure {
    fun describe(error: Throwable): String = when {
        error is HttpStatusException -> "The server answered HTTP ${error.code}"
        error is UnknownHostException || error is ConnectException ||
            error is SocketTimeoutException || error is NoRouteToHostException ->
            "No connection — check your internet"
        error is IOException && (error.message.orEmpty().contains("ENOSPC") ||
            error.message.orEmpty().contains("No space left", ignoreCase = true)) ->
            "Not enough storage space"
        else -> "Download failed"
    }
}
