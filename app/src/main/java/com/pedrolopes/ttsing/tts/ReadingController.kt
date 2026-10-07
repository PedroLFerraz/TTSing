package com.pedrolopes.ttsing.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * App-side handle to [ReadingService]: binds for live [PlaybackState] observation and
 * in-process control, and uses startForegroundService for the play action so background
 * playback is established correctly.
 */
class ReadingController(context: Context) {

    private val appContext = context.applicationContext
    private var service: ReadingService? = null
    private var bound = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectorJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? ReadingService.LocalBinder)?.service ?: return
            service = svc
            bound = true
            _connected.value = true
            stateCollectorJob?.cancel()
            stateCollectorJob = scope.launch { svc.state.collect { _state.value = it } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            _connected.value = false
        }
    }

    fun bind() {
        if (bound) return
        appContext.bindService(
            Intent(appContext, ReadingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind() {
        if (!bound) return
        stateCollectorJob?.cancel()
        runCatching { appContext.unbindService(connection) }
        bound = false
        service = null
        _connected.value = false
    }

    /** Loads a book (without playing) so the reader can restore/highlight the saved position. */
    fun prepare(bookId: String, onReady: (ReadingPosition) -> Unit = {}) {
        service?.prepareBook(bookId, onReady)
    }

    fun play(bookId: String, position: ReadingPosition? = null) {
        ContextCompat.startForegroundService(appContext, ReadingService.playIntent(appContext, bookId, position))
    }

    fun togglePlayPause(bookId: String) {
        val svc = service
        if (svc != null && svc.state.value.isSpeaking) {
            svc.pause()
        } else {
            // No position for a finished book (it plays from the start; its parked position is
            // the last sentence, which would be spoken alone) nor for one that isn't the loaded
            // book (the position is another book's; the saved place is used).
            val current = _state.value
            play(bookId, current.position.takeIf { current.bookId == bookId && !current.finished })
        }
    }

    fun pause() {
        service?.pause()
    }

    fun next() {
        service?.skipSentence(forward = true)
    }

    fun previous() {
        service?.skipSentence(forward = false)
    }

    fun seekTo(position: ReadingPosition, alsoPlay: Boolean, bookId: String) {
        if (alsoPlay || _state.value.isSpeaking) play(bookId, position) else service?.moveTo(position)
    }

    fun stop() {
        service?.stopPlayback()
    }

    /** Voices for a language the caller names, so the UI never depends on service timing. */
    fun voicesFor(locale: Locale): List<Voice> = service?.voicesFor(locale).orEmpty()

    /** Stops the reading after [minutes], or at the end of the chapter, or not at all. */
    fun setSleepTimer(minutes: Int?, atChapterEnd: Boolean = false) =
        service?.setSleepTimer(minutes, atChapterEnd) ?: Unit

    fun currentVoiceName(): String? = service?.currentVoiceName()

    fun defaultVoiceNameFor(locale: Locale): String? = service?.defaultVoiceNameFor(locale)

    /** Languages the TTS engine offers, downloaded ones first, for the reader's picker. */
    fun availableLanguages(): List<LanguageOption> = service?.availableLanguages().orEmpty()

    /** The language the current book is being read in. */
    fun activeLocale(): Locale? = service?.activeLocale()

    fun selectLanguage(languageTag: String) {
        service?.selectLanguage(languageTag)
    }

    fun setSpeechRate(rate: Float) {
        service?.setSpeechRate(rate)
    }

    fun selectVoice(voiceName: String?) {
        service?.selectVoice(voiceName)
    }

    /** The book at [oldId] was moved to a new file, so it is now [newId]: keep playing it under that. */
    fun bookMoved(oldId: String, newId: String) {
        service?.bookMoved(oldId, newId)
    }
}
