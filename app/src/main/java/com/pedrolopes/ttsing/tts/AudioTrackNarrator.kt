package com.pedrolopes.ttsing.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.epub.WordSplitter
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
 * Using [TextToSpeech.speak] instead would hand the audio to an `AudioTrack` inside the TTS
 * engine's own process, so Android would treat *that* app as the one playing and route
 * Bluetooth/headset media buttons there. Here the sound genuinely originates from this app.
 *
 * Word highlighting also gets better as a side effect: the engine reports each word's exact
 * audio frame via `onRangeStart`, and playback position is compared against those frames, so
 * the highlight is measured rather than estimated.
 */
class AudioTrackNarrator(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: Narrator.Listener,
) : Narrator {

    private val ready = CompletableDeferred<Boolean>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    private val synthesizer = PcmSynthesizer(tts, context.applicationContext.cacheDir)
    private val player = SentencePlayer()

    private var source: Narrator.ContentSource? = null
    private var readingJob: Job? = null

    /** Playback speed, applied by the engine during synthesis. */
    private var speechRate = 1f

    /** Language in use, for the word-splitting fallback below. */
    private var locale: Locale = Locale.getDefault()

    override var currentRef: SentenceRef? = null
        private set

    override var isSpeaking: Boolean = false
        private set

    init {
        synthesizer.attachListener()
    }

    override suspend fun awaitReady(): Boolean = ready.await()

    override fun setContentSource(contentSource: Narrator.ContentSource) {
        source = contentSource
    }

    override suspend fun configureLanguage(locale: Locale, preferredVoiceName: String?): Boolean {
        if (!awaitReady()) return false
        this.locale = locale
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
    private suspend fun readLoop(src: Narrator.ContentSource, from: ReadingPosition) {
        var ref = src.firstAtOrAfter(from)
        if (ref == null) {
            finishBook()
            return
        }
        var consecutiveFailures = 0
        while (ref != null && coroutineIsActive()) {
            currentRef = ref
            val audio = synthesizer.synthesize(ref.text, utteranceIdFor(ref))
            if (!coroutineIsActive()) return
            if (audio == null) {
                // One refused sentence is skipped rather than stalling the whole book. A run of
                // them means the voice itself cannot speak — a language whose data never
                // downloaded, most often — and silently skipping every sentence looks exactly
                // like "playback does nothing and the highlight is dead", with no way to tell
                // why. Say so instead.
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    isSpeaking = false
                    withMain {
                        listener.onEngineError(
                            "This voice can't speak the text. Its data may still need downloading — " +
                                "try another language or voice.",
                        )
                    }
                    return
                }
                ref = src.next(ref.position)
                continue
            }
            consecutiveFailures = 0
            val speaking = ref
            val marks = audio.marks.takeIf { it.usableFor(audio) } ?: estimatedMarks(speaking, audio)
            withMain { listener.onSentenceStart(speaking) }

            var lastRange: IntRange? = null
            val completed = player.play(audio) { frame ->
                val mark = markAt(marks, frame) ?: return@play
                val range = (speaking.startInBlock + mark.start) until (speaking.startInBlock + mark.end)
                // The position poll runs far faster than words change; only publish on change.
                if (range != lastRange) {
                    lastRange = range
                    scope.launch(Dispatchers.Main) { listener.onWordRange(speaking, range) }
                }
            }
            if (!completed || !coroutineIsActive()) return
            ref = src.next(speaking.position)
        }
        if (coroutineIsActive()) finishBook()
    }

    private fun markAt(
        marks: List<SynthesizedSentence.FrameMark>,
        frame: Int,
    ): SynthesizedSentence.FrameMark? = marks.lastOrNull { it.frame <= frame }

    /**
     * Whether engine-reported word timings can actually drive the highlight.
     *
     * `onRangeStart`'s `frame` argument is only as good as the engine supplying it, and they
     * vary: some never advance it past zero, some report positions past the end of the audio
     * they produced. Either way the highlight sticks on one word for the whole sentence, which
     * looks like it is broken. Marks that fail this check are replaced by the estimate, which
     * is always roughly right.
     */
    private fun List<SynthesizedSentence.FrameMark>.usableFor(audio: SynthesizedSentence): Boolean {
        if (isEmpty()) return false
        if (size == 1) return true
        val last = maxOf { it.frame }
        return last > 0 && last <= audio.frameCount
    }

    /**
     * Word marks for engines that report none for `synthesizeToFile` (`onRangeStart` is
     * optional - the platform only calls it if the engine supplies timing). Falls back to
     * spreading the measured audio duration across the words by character position, so the
     * highlight still tracks instead of disappearing.
     */
    private fun estimatedMarks(
        ref: SentenceRef,
        audio: SynthesizedSentence,
    ): List<SynthesizedSentence.FrameMark> {
        val words = WordSplitter.split(ref.text, locale)
        if (words.isEmpty()) return emptyList()
        val timings = NeuralWordTiming.estimate(
            sentenceLength = ref.text.length,
            wordSpans = words,
            startInBlock = 0,
            totalDurationMs = audio.durationMs,
        )
        val framesPerMs = audio.sampleRateHz / 1000f
        return timings.map { timing ->
            SynthesizedSentence.FrameMark(
                frame = (timing.startMs * framesPerMs).toInt(),
                start = timing.rangeInBlock.first,
                end = timing.rangeInBlock.last + 1,
            )
        }
    }

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

    private companion object {
        /** Enough to ride out one odd sentence, few enough to report a broken voice quickly. */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
