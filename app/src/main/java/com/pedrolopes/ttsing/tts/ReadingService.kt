package com.pedrolopes.ttsing.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.media.session.MediaButtonReceiver
import androidx.lifecycle.lifecycleScope
import com.pedrolopes.ttsing.MainActivity
import com.pedrolopes.ttsing.R
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.news.NewsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Foreground service that owns the [Narrator] so read-aloud continues with the
 * screen off. Exposes [PlaybackState] to the UI and media-style notification controls.
 */
class ReadingService : LifecycleService(), Narrator.Listener {

    inner class LocalBinder : Binder() {
        val service: ReadingService get() = this@ReadingService
    }

    private val binder = LocalBinder()

    private lateinit var engine: Narrator
    private var document: BookDocument? = null
    private var content: ReadableContent? = null
    private var openMutex = Mutex()

    /** Language the book is being read in; the user's override, else its `dc:language`. */
    private var activeLocale: Locale? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var resumeOnFocusGain = false
    private var sentencesSinceSave = 0
    private var saveJob: Job? = null
    private var isForeground = false

    // Speaking-speed measurement (drives time-to-finish estimates).
    private var previousSentenceStartedAt: Long? = null
    private var previousSentenceChars = 0
    private var speed = SpeakingSpeed()
    /** Which voice [speed] belongs to — speeds are kept per voice, since voices differ most. */
    private var speedVoiceKey: String? = null
    private var listenedSincePersistMs = 0L
    private var currentRate = 1f

    private val app: TTSingApp get() = application as TTSingApp

    /**
     * Fired when the current audio route (wired or Bluetooth headset) is about to
     * disappear - e.g. the headphones were just unplugged. Without this, playback
     * would carry on out loud through the speaker the instant they're pulled out.
     */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_state.value.isSpeaking) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = AudioTrackNarrator(this, lifecycleScope, this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "TTSing").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = skipSentence(forward = true)
                override fun onSkipToPrevious() = skipSentence(forward = false)
                override fun onStop() = stopPlayback()
            })
        }
        // Only the system can ever send this broadcast, so it's safe (and required on
        // Android 13+) to mark the receiver as not exported to other apps.
        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            // Forwarded by the manifest-declared MediaButtonReceiver when our MediaSession
            // was no longer the one Android routes hardware media keys to directly (e.g.
            // this service got killed while paused) - translates the KeyEvent back into
            // the same onPlay/onPause/onSkipToNext/onSkipToPrevious calls below.
            mediaSession?.let { MediaButtonReceiver.handleIntent(it, intent) }
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY -> {
                // Foreground must be established quickly; do it before the (async) book open.
                ensureForeground()
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: _state.value.bookId
                val position = if (intent.hasExtra(EXTRA_CHAPTER)) {
                    ReadingPosition(
                        intent.getIntExtra(EXTRA_CHAPTER, 0),
                        intent.getIntExtra(EXTRA_BLOCK, 0),
                        intent.getIntExtra(EXTRA_SENTENCE, 0),
                    )
                } else {
                    null
                }
                if (bookId != null) play(bookId, position) else stopSelfIfIdle()
            }
            ACTION_PLAY_PAUSE -> if (_state.value.isSpeaking) pause() else resume()
            ACTION_NEXT -> skipSentence(forward = true)
            ACTION_PREV -> skipSentence(forward = false)
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    /** Loads the book into the engine (no-op if already loaded) and reports the restored position. */
    fun prepareBook(bookId: String, onReady: (ReadingPosition) -> Unit = {}) {
        lifecycleScope.launch {
            val position = openBookIfNeeded(bookId) ?: return@launch
            onReady(position)
        }
    }

    fun play(bookId: String, position: ReadingPosition? = null) {
        ensureForeground()
        lifecycleScope.launch {
            val restored = openBookIfNeeded(bookId) ?: run { stopSelfIfIdle(); return@launch }
            if (!requestAudioFocus()) return@launch
            acquireWakeLock()
            engine.playFrom(position ?: engine.currentRef?.position ?: restored)
            _state.value = _state.value.copy(isSpeaking = true, error = null)
            updateMetadata()
            updateSessionAndNotification()
        }
    }

    private fun stopSelfIfIdle() {
        if (!_state.value.isSpeaking) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun pause() {
        engine.pause()
        previousSentenceStartedAt = null // a paused gap is not reading time
        persistSpeed()
        resumeOnFocusGain = false
        _state.value = _state.value.copy(isSpeaking = false, wordRange = null)
        updateSessionAndNotification()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        releaseWakeLock()
        persistPosition()
    }

    fun resume() {
        val bookId = _state.value.bookId ?: return
        play(bookId, engine.currentRef?.position)
    }

    fun skipSentence(forward: Boolean) {
        previousSentenceStartedAt = null // a skipped sentence was never spoken in full
        if (forward) engine.skipToNext() else engine.skipToPrev()
    }

    fun moveTo(position: ReadingPosition) {
        previousSentenceStartedAt = null
        engine.moveTo(position)
    }

    /**
     * Voices for an explicitly given language.
     *
     * The caller passes the language rather than this reading [activeLocale]: that field is a
     * plain var the UI cannot observe, so a screen asking "what voices are there now?" raced
     * the service updating it and could show the previous language's list indefinitely.
     */
    fun voicesFor(locale: Locale) = engine.voicesFor(locale)

    fun currentVoiceName(): String? = engine.currentVoiceName()

    fun defaultVoiceNameFor(locale: Locale): String? = engine.defaultVoiceName(locale)

    fun availableLanguages() = engine.availableLanguages()

    /** The language the current book is actually being read in (override or `dc:language`). */
    fun activeLocale(): Locale = activeLocale ?: content?.locale ?: Locale.getDefault()

    /**
     * Applies the language the user chose. The choice itself is written to settings by the
     * caller, which is what the UI reads back — this only reconfigures the running engine.
     *
     * Deliberately does *not* bail out when no book is loaded: the previous version returned
     * early on a null bookId, so choosing a language before playback had ever started silently
     * did nothing.
     */
    fun selectLanguage(languageTag: String) {
        lifecycleScope.launch {
            val locale = Locale.forLanguageTag(languageTag).takeIf { it.language.isNotEmpty() }
                ?: return@launch
            activeLocale = locale
            val available = engine.configureLanguage(locale, app.settings.settings.first().voiceFor(locale.language))
            _state.value = _state.value.copy(languageAvailable = available, error = null)
            loadSpeedForCurrentVoice()
            if (engine.isSpeaking) engine.currentRef?.let { engine.playFrom(it.position) }
        }
    }

    fun applySpeechSettings(rate: Float, pitch: Float) {
        currentRate = rate
        previousSentenceStartedAt = null // speed changed; don't mix samples across rates
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        if (engine.isSpeaking) engine.currentRef?.let { engine.playFrom(it.position) }
    }

    /** Selects a voice by name, or null to fall back to the engine default. */
    fun selectVoice(voiceName: String?) {
        lifecycleScope.launch {
            engine.configureLanguage(activeLocale(), voiceName)
            loadSpeedForCurrentVoice()
            if (engine.isSpeaking) engine.currentRef?.let { engine.playFrom(it.position) }
        }
    }

    fun stopPlayback() {
        pause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun openBookIfNeeded(bookId: String): ReadingPosition? = openMutex.withLock {
        if (_state.value.bookId == bookId && content != null) {
            return engine.currentRef?.position ?: _state.value.position
        }
        persistPosition()
        document?.close()
        document = null
        content = null
        activeLocale = null

        val opened = if (NewsRepository.isArticle(bookId)) openArticle(bookId) else openBook(bookId)
        val (source, restored) = opened ?: return null
        content = source
        engine.setContentSource(source)

        val settings = app.settings.settings.first()
        currentRate = settings.speechRate
        engine.setSpeechRate(settings.speechRate)
        engine.setPitch(settings.pitch)
        val locale = settings.localeFor(bookId, source.locale)
        activeLocale = locale
        val languageOk = engine.configureLanguage(locale, settings.voiceFor(locale.language))
        loadSpeedForCurrentVoice()

        _state.value = PlaybackState(
            bookId = bookId,
            bookTitle = source.title,
            author = source.author,
            isActive = true,
            isSpeaking = false,
            position = restored,
            languageAvailable = languageOk,
            charsPerSecond = speed.charsPerSecond,
        )
        engine.moveTo(restored)
        updateMetadata()
        return restored
    }

    private suspend fun openBook(bookId: String): Pair<ReadableContent, ReadingPosition>? {
        val entity = app.books.getBook(bookId) ?: run {
            _state.value = PlaybackState(error = "Book not found")
            return null
        }
        val opened = runCatching { app.books.openBook(bookId) }.getOrNull() ?: run {
            _state.value = PlaybackState(error = "Could not open book file")
            return null
        }
        opened.unreadableReason?.let { reason ->
            opened.close()
            _state.value = PlaybackState(error = reason)
            return null
        }
        document = opened
        return BookContentSource(opened) to
            ReadingPosition(entity.chapterIndex, entity.blockIndex, entity.sentenceIndex)
    }

    /**
     * Loads a news article. Its full text may still need fetching, which is why this can fail
     * with a network-flavoured message rather than a missing-file one.
     */
    private suspend fun openArticle(articleId: String): Pair<ReadableContent, ReadingPosition>? {
        val article = app.news.article(articleId) ?: run {
            _state.value = PlaybackState(error = "Article not found")
            return null
        }
        val settings = app.settings.settings.first()
        val feedLocale = app.news.feedLocale(article.feedUrl) ?: Locale.getDefault()
        val locale = settings.localeFor(articleId, feedLocale)

        return when (val body = app.news.body(articleId, locale)) {
            is NewsRepository.ArticleBody.Failed -> {
                _state.value = PlaybackState(error = body.message)
                null
            }
            is NewsRepository.ArticleBody.Ready -> {
                val source = ArticleContentSource(
                    articleId = articleId,
                    title = article.title,
                    locale = feedLocale,
                    blocks = body.blocks,
                )
                source to ReadingPosition(0, article.blockIndex, article.sentenceIndex)
            }
        }
    }

    // ---- Narrator.Listener ----

    override fun onSentenceStart(ref: SentenceRef) {
        _state.value = _state.value.copy(
            position = ref.position,
            sentenceRange = ref.startInBlock until (ref.startInBlock + ref.text.length),
            wordRange = null,
            isSpeaking = engine.isSpeaking,
        )
        if (engine.isSpeaking) {
            recordSpeakingSpeed(ref)
            updateSessionAndNotification()
            if (++sentencesSinceSave >= SAVE_EVERY_SENTENCES) persistPosition()
        }
    }

    /**
     * Times how long each sentence actually took, from its start to the next one's, and feeds
     * it to [speed] — see [SpeakingSpeed] for why this is a ratio of sums rather than an
     * average of per-sentence speeds.
     */
    private fun recordSpeakingSpeed(ref: SentenceRef) {
        val now = android.os.SystemClock.elapsedRealtime()
        val startedAt = previousSentenceStartedAt
        val previousChars = previousSentenceChars
        previousSentenceStartedAt = now
        previousSentenceChars = ref.text.length

        if (startedAt == null || previousChars <= 0) return
        val elapsedMs = now - startedAt
        // Guards against anything that is not one sentence of continuous speech: a callback
        // arriving implausibly fast, or a gap long enough that something else happened.
        if (elapsedMs < 150 || elapsedMs > 60_000) return
        val sample = previousChars * 1000f / elapsedMs / currentRate.coerceAtLeast(0.1f)
        if (sample < 1f || sample > 200f) return

        speed.add(previousChars, elapsedMs, currentRate)
        _state.value = _state.value.copy(charsPerSecond = speed.charsPerSecond)
        android.util.Log.d(SPEED_TAG, "cps=%.3f after %dch/%dms".format(speed.charsPerSecond, previousChars, elapsedMs))

        listenedSincePersistMs += elapsedMs
        if (listenedSincePersistMs >= SPEED_PERSIST_EVERY_MS) {
            listenedSincePersistMs = 0
            persistSpeed()
        }
    }

    /** Swaps [speed] for the stored accumulators of whichever voice is now speaking. */
    private suspend fun loadSpeedForCurrentVoice() {
        val key = engine.currentVoiceName() ?: "default:${activeLocale().language}"
        if (key == speedVoiceKey) return
        if (speedVoiceKey != null) persistSpeed()
        speedVoiceKey = key
        speed = SpeakingSpeed.decode(app.settings.settings.first().speeds[key])
        previousSentenceStartedAt = null
        _state.value = _state.value.copy(charsPerSecond = speed.charsPerSecond)
    }

    private fun persistSpeed() {
        val key = speedVoiceKey ?: return
        val encoded = speed.encode()
        val cps = speed.charsPerSecond
        lifecycleScope.launch { app.settings.setSpeed(key, encoded, cps) }
    }

    override fun onWordRange(ref: SentenceRef, rangeInBlock: IntRange) {
        _state.value = _state.value.copy(wordRange = rangeInBlock)
    }

    override fun onBookFinished() {
        _state.value = _state.value.copy(isSpeaking = false, wordRange = null)
        updateSessionAndNotification()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        releaseWakeLock()
        persistPosition()
    }

    override fun onEngineError(message: String) {
        _state.value = _state.value.copy(error = message, isSpeaking = false)
    }

    // ---- Audio focus ----

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        val wasSpeaking = _state.value.isSpeaking
                        pause()
                        resumeOnFocusGain = wasSpeaking
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        resume()
                    }
                }
            }
            .build()
            .also { focusRequest = it }
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // ---- Notification ----

    /** Promotes the service to the foreground, satisfying the startForeground deadline. Idempotent. */
    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private fun updateMetadata() {
        val s = _state.value
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.author ?: "")
                .build(),
        )
        mediaSession?.setSessionActivity(openBookPendingIntent(s.bookId))
    }

    private fun updateSessionAndNotification() {
        val speaking = _state.value.isSpeaking
        mediaSession?.isActive = true
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    if (speaking) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
        if (_state.value.isActive) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /** Reopens the app on the book currently loaded - used by both the notification tap and the media session (lock screen art, Android Auto, Wear). */
    private fun openBookPendingIntent(bookId: String?): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_BOOK_ID, bookId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildNotification(): Notification {
        val s = _state.value
        val openIntent = openBookPendingIntent(s.bookId)
        val playPauseAction = NotificationCompat.Action(
            if (s.isSpeaking) R.drawable.ic_pause else R.drawable.ic_play,
            if (s.isSpeaking) "Pause" else "Play",
            servicePendingIntent(ACTION_PLAY_PAUSE),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(s.bookTitle.ifEmpty { getString(R.string.app_name) })
            .setContentText(s.author ?: "")
            .setContentIntent(openIntent)
            .setOngoing(s.isSpeaking)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_previous, "Previous sentence", servicePendingIntent(ACTION_PREV))
            .addAction(playPauseAction)
            .addAction(R.drawable.ic_skip_next, "Next sentence", servicePendingIntent(ACTION_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, ReadingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Read-aloud playback controls"
                setShowBadge(false)
            },
        )
    }

    // ---- Persistence / wake lock ----

    private fun persistPosition() {
        val s = _state.value
        val bookId = s.bookId ?: return
        sentencesSinceSave = 0
        val source = content ?: return
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            if (NewsRepository.isArticle(bookId)) {
                app.news.savePosition(bookId, s.position.blockIndex, s.position.sentenceIndex)
                return@launch
            }
            val progress = charProgress(bookId, source, s.position) ?: run {
                // Before the book's character counts exist: the old section-based guess.
                val blocks = source.blockCount(s.position.chapterIndex).coerceAtLeast(1)
                val sectionCount = source.sectionCount.coerceAtLeast(1)
                (s.position.chapterIndex + s.position.blockIndex.toFloat() / blocks) / sectionCount
            }
            app.books.savePosition(bookId, s.position, progress)
        }
    }

    /**
     * How far through the book [position] is by characters, which is what the library's bar
     * should show: counting sections instead made a book with many tiny spine items (a
     * cover, a title page, a dedication) look far further along than it was.
     */
    private suspend fun charProgress(bookId: String, source: ReadableContent, position: ReadingPosition): Float? {
        val counts = app.books.cachedChapterCharCounts(bookId) ?: return null
        val total = counts.sum().takeIf { it > 0 } ?: return null
        val blocks = (source as? BookContentSource)?.chapter(position.chapterIndex)?.blocks ?: return null
        val before = counts.take(position.chapterIndex).sum() +
            blocks.take(position.blockIndex).filterIsInstance<Block.Text>().sumOf { it.text.length }
        return before.toFloat() / total
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TTSing:reading").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        persistPosition()
        engine.shutdown()
        document?.close()
        mediaSession?.release()
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 42
        const val ACTION_PLAY = "com.pedrolopes.ttsing.PLAY"
        const val ACTION_PLAY_PAUSE = "com.pedrolopes.ttsing.PLAY_PAUSE"
        const val ACTION_NEXT = "com.pedrolopes.ttsing.NEXT"
        const val ACTION_PREV = "com.pedrolopes.ttsing.PREV"
        const val ACTION_STOP = "com.pedrolopes.ttsing.STOP"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_BLOCK = "block"
        const val EXTRA_SENTENCE = "sentence"
        const val SAVE_EVERY_SENTENCES = 5
        const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        /** Accumulators are written after this much listening, and on pause and voice change. */
        const val SPEED_PERSIST_EVERY_MS = 30_000L
        const val SPEED_TAG = "TTSingSpeed"

        fun playIntent(context: Context, bookId: String, position: ReadingPosition?): Intent =
            Intent(context, ReadingService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_BOOK_ID, bookId)
                if (position != null) {
                    putExtra(EXTRA_CHAPTER, position.chapterIndex)
                    putExtra(EXTRA_BLOCK, position.blockIndex)
                    putExtra(EXTRA_SENTENCE, position.sentenceIndex)
                }
            }
    }
}
