package com.pedrolopes.ttsing.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Reads a book aloud by synthesizing each sentence to PCM and playing it through an
 * app-owned [android.media.AudioTrack].
 *
 * Drop-in alternative to [SpeechEngine], which uses [TextToSpeech.speak] instead. The
 * difference matters for one specific reason: with `speak()` the audio comes out of the TTS
 * engine's own process, so Android treats *that* app as the one playing audio and routes
 * Bluetooth/headset media buttons there. Here the sound genuinely originates from this app.
 *
 * Word highlighting also gets better as a side effect: the engine reports each word's exact
 * audio frame via `onRangeStart`, and playback position is compared against those frames, so
 * the highlight is measured rather than estimated.
 */
class AudioTrackNarrator(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: SpeechEngine.Listener,
) : Narrator {

    private val ready = CompletableDeferred<Boolean>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    private val synthesizer = PcmSynthesizer(tts, context.applicationContext.cacheDir)
    private val player = SentencePlayer()

    private var source: SpeechEngine.ContentSource? = null
    private var readingJob: Job? = null

    /** Playback speed; applied by resampling the request, mirroring SpeechEngine's contract. */
    private var speechRate = 1f

    override var currentRef: SentenceRef? = null
        private set

    override var isSpeaking: Boolean = false
        private set

    init {
        synthesizer.attachListener()
    }

    override suspend fun awaitReady(): Boolean = ready.await()

    override fun setContentSource(contentSource: SpeechEngine.ContentSource) {
        source = contentSource
    }

    override suspend fun configureLanguage(locale: Locale, preferredVoiceName: String?): Boolean {
        if (!awaitReady()) return false
        val result = tts.setLanguage(locale)
        val available = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        val match = preferredVoiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (match != null) {
            tts.voice = match
        } else {
            val default = tts.defaultVoice
            if (default != null && default.locale.language == locale.language) tts.voice = default
        }
        return available
    }

    override fun currentVoiceName(): String? = runCatching { tts.voice?.name }.getOrNull()

    override fun defaultVoiceName(locale: Locale): String? = runCatching {
        tts.defaultVoice?.takeIf { it.locale.language == locale.language }?.name
    }.getOrNull()

    override fun availableLanguages(): List<LanguageOption> =
        tts.voices
            .orEmpty()
            .filter { it.locale.language.isNotEmpty() }
            .groupBy { it.locale.language }
            .map { (language, voices) ->
                LanguageOption(
                    locale = Locale.forLanguageTag(language),
                    downloaded = voices.any { !it.needsDownload() },
                )
            }
            .sortedWith(
                compareByDescending<LanguageOption> { it.downloaded }
                    .thenBy { it.locale.displayLanguage.lowercase() },
            )

    override fun voicesFor(locale: Locale): List<Voice> =
        tts.voices
            .orEmpty()
            .filter { it.locale.language == locale.language }
            .sortedWith(
                compareBy({ it.needsDownload() }, { it.locale.country }, { -it.quality }, { it.name }),
            )

    override fun setSpeechRate(rate: Float) {
        speechRate = rate
        tts.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        tts.setPitch(pitch)
    }

    override fun playFrom(position: ReadingPosition) {
        scope.launch(Dispatchers.Main) {
            stopReading()
            if (!awaitReady()) {
                listener.onEngineError("Text-to-speech engine failed to initialize")
                return@launch
            }
            val src = source ?: return@launch
            isSpeaking = true
            readingJob = scope.launch(Dispatchers.Default) { readLoop(src, position) }
        }
    }

    /** Synthesizes and plays sentence after sentence until the book ends or we're stopped. */
    private suspend fun readLoop(src: SpeechEngine.ContentSource, from: ReadingPosition) {
        var ref = src.firstAtOrAfter(from)
        if (ref == null) {
            finishBook()
            return
        }
        while (ref != null && coroutineIsActive()) {
            currentRef = ref
            val audio = synthesizer.synthesize(ref.text, utteranceIdFor(ref))
            if (!coroutineIsActive()) return
            if (audio == null) {
                // Skip a sentence the engine refused rather than stalling the whole book.
                ref = src.next(ref.position)
                continue
            }
            val speaking = ref
            withMain { listener.onSentenceStart(speaking) }

            val completed = player.play(audio) { frame ->
                val mark = markAt(audio, frame) ?: return@play
                val range = (speaking.startInBlock + mark.start) until (speaking.startInBlock + mark.end)
                scope.launch(Dispatchers.Main) { listener.onWordRange(speaking, range) }
            }
            if (!completed || !coroutineIsActive()) return
            ref = src.next(speaking.position)
        }
        if (coroutineIsActive()) finishBook()
    }

    private fun markAt(audio: SynthesizedSentence, frame: Int): SynthesizedSentence.FrameMark? =
        audio.marks.lastOrNull { it.frame <= frame }

    private suspend fun finishBook() {
        isSpeaking = false
        withMain { listener.onBookFinished() }
    }

    private suspend fun coroutineIsActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    private suspend fun withMain(block: () -> Unit) =
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }

    override fun pause() {
        isSpeaking = false
        player.stop()
        synthesizer.cancel()
        readingJob?.cancel()
        readingJob = null
    }

    override fun resume() {
        currentRef?.let { playFrom(it.position) }
    }

    override fun skipToNext() {
        scope.launch(Dispatchers.Main) {
            val src = source ?: return@launch
            val current = currentRef ?: return@launch
            val next = src.next(current.position) ?: return@launch
            if (isSpeaking) {
                playFrom(next.position)
            } else {
                currentRef = next
                listener.onSentenceStart(next)
            }
        }
    }

    override fun skipToPrev() {
        scope.launch(Dispatchers.Main) {
            val src = source ?: return@launch
            val current = currentRef ?: return@launch
            val prev = src.prev(current.position) ?: current
            if (isSpeaking) {
                playFrom(prev.position)
            } else {
                currentRef = prev
                listener.onSentenceStart(prev)
            }
        }
    }

    override fun moveTo(position: ReadingPosition) {
        scope.launch(Dispatchers.Main) {
            val src = source ?: return@launch
            val ref = src.firstAtOrAfter(position) ?: return@launch
            currentRef = ref
            if (isSpeaking) playFrom(ref.position) else listener.onSentenceStart(ref)
        }
    }

    override fun shutdown() {
        pause()
        runCatching { tts.shutdown() }
    }

    private suspend fun stopReading() {
        player.stop()
        synthesizer.cancel()
        readingJob?.cancelAndJoin()
        readingJob = null
    }

    private fun utteranceIdFor(ref: SentenceRef): String =
        "u|${ref.position.chapterIndex}|${ref.position.blockIndex}|${ref.position.sentenceIndex}"
}
