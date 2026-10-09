package com.pedrolopes.ttsing.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.epub.WordSplitter
import com.pedrolopes.ttsing.tts.piper.PiperCatalog
import com.pedrolopes.ttsing.tts.piper.PiperSynthesizer
import com.pedrolopes.ttsing.tts.piper.PiperVoice
import com.pedrolopes.ttsing.tts.piper.PiperVoices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Reads a book aloud by synthesizing each sentence to PCM and playing it through an
 * app-owned [android.media.AudioTrack].
 *
 * Using [TextToSpeech.speak] instead would hand the audio to an `AudioTrack` inside the TTS
 * engine's own process, so Android would treat *that* app as the one playing and route
 * Bluetooth/headset media buttons there. Here the sound genuinely originates from this app.
 *
 * Word highlighting is driven entirely by [NeuralWordTiming]'s character-proportional
 * estimate, not by the engine's own `onRangeStart` callback. That callback is optional, and
 * in practice inconsistent enough across engines and voices to be worse than not trusting it
 * at all: an engine that never advances its reported frame, or reports one in the wrong
 * unit, doesn't just desync the highlight a little — it can make it jump to the last word
 * and sit there for the rest of the sentence. The estimate is instead built from data this
 * class already controls (the audio's own measured duration and our own `AudioTrack`
 * position), so its scale can never be wrong.
 */
class AudioTrackNarrator(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: Narrator.Listener,
) : Narrator {

    private val appContext = context.applicationContext

    private val ready = CompletableDeferred<Boolean>()

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    private val synthesizer = PcmSynthesizer(tts, appContext.cacheDir)

    /** The voices the app carries itself; null unless one of them is the chosen voice. */
    private val piper = PiperSynthesizer(appContext)
    private var piperVoice: PiperVoice? = null

    private val player = SentencePlayer()

    /** Held while a sentence is being synthesized: one at a time, whatever the voice. */
    private val synthesis = Mutex()

    private var source: Narrator.ContentSource? = null
    private var readingJob: Job? = null

    /** The coroutine bringing a reading up: cancelled by the next start, and by [pause]. */
    private var startJob: Job? = null
    private var releaseJob: Job? = null

    /** Playback speed, applied by the engine during synthesis. */
    private var speechRate = 1f

    /** Language in use, for the word-splitting fallback below. */
    private var locale: Locale = Locale.getDefault()

    /** Skips run one at a time, so a quick second tap starts from where the first one landed. */
    private val skipLock = Mutex()

    @Volatile
    override var currentRef: SentenceRef? = null
        private set

    @Volatile
    override var isSpeaking: Boolean = false
        private set

    init {
        synthesizer.attachListener()
    }

    override suspend fun awaitReady(): Boolean = ready.await()

    override fun setContentSource(contentSource: Narrator.ContentSource) {
        source = contentSource
        // The previous book's sentence must not be what a Play in this one resumes from.
        currentRef = null
    }

    override suspend fun configureLanguage(locale: Locale, preferredVoiceName: String?): Boolean {
        if (!awaitReady()) return false
        this.locale = locale
        val result = tts.setLanguage(locale)
        val available = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        val bundled = PiperVoices.find(appContext, preferredVoiceName)
        // The model is loaded and let go under the synthesis lock, so it is never swapped out
        // from under a sentence being synthesized.
        if (bundled != null && synthesis.withLock { piper.prepare(bundled) }) {
            piperVoice = bundled
            return true
        }
        // Moving to a device voice: the neural model's memory (~270 MB) is no longer needed.
        piperVoice = null
        synthesis.withLock { piper.release() }
        val voices = tts.voices.orEmpty()
        val chosenName = preferredVoiceName?.takeIf { name -> voices.any { it.name == name } }
            ?: VoiceChoice.pick(locale, voices.map { it.info() }, tts.defaultVoice?.info())
        voices.firstOrNull { it.name == chosenName }?.let { tts.voice = it }
        return available
    }

    override fun currentVoiceName(): String? =
        piperVoice?.id ?: runCatching { tts.voice?.name }.getOrNull()

    override fun defaultVoiceName(locale: Locale): String? = runCatching {
        tts.defaultVoice?.takeIf { it.locale.language == locale.language }?.name
    }.getOrNull()

    /**
     * Every language variant there is a voice for — "Portuguese (Brazil)" and "Portuguese
     * (Portugal)" apart, since a bare "pt" leaves the engine to pick one — device and neural.
     */
    override fun availableLanguages(): List<LanguageOption> {
        val installedNeural = PiperVoices.available(appContext).map { it.id }.toSet()
        val offered = tts.voices.orEmpty().map { it.locale to !it.needsDownload() } +
            PiperCatalog.voices.map { it.locale to (it.id in installedNeural) }
        return LanguageOption.variantsOf(offered)
    }

    override fun voicesFor(locale: Locale): List<Voice> =
        (tts.voices.orEmpty() + PiperVoices.available(appContext).map { it.asEngineVoice() })
            .filter { it.locale.language == locale.language }
            .sortedWith(
                compareBy({ it.needsDownload() }, { it.locale.country }, { -it.quality }, { it.name }),
            )

    override fun setSpeechRate(rate: Float) {
        speechRate = rate
        tts.setSpeechRate(rate)
    }

    override fun playFrom(position: ReadingPosition) {
        releaseJob?.cancel()
        // A start already under way is abandoned before this one begins. Starting is not
        // instant — it waits for the sentence being spoken to come to a stop, and may have to
        // load a voice — so two starts overlapping used to leave *both* reading: the book read
        // by two voices at once, with only the newer one answering to pause. Changing the
        // speed restarts the reading, so dragging the speed slider was enough to do it.
        val earlier = startJob
        startJob = scope.launch(Dispatchers.Main) {
            runCatching { earlier?.cancelAndJoin() }
            stopReading()
            // Reloads the model when a long pause let it go; a no-op the rest of the time.
            piperVoice?.let { if (!synthesis.withLock { piper.prepare(it) }) piperVoice = null }
            if (!awaitReady()) {
                listener.onEngineError("Text-to-speech engine failed to initialize")
                return@launch
            }
            val src = source ?: return@launch
            ensureActive()
            isSpeaking = true
            readingJob = scope.launch(Dispatchers.Default) { readLoop(src, position) }
        }
    }

    /**
     * Synthesizes and plays sentence after sentence until the book ends or we're stopped.
     *
     * The next sentence is synthesized *while the current one plays*. Synthesis and playback
     * are the same length of work for a neural voice, so doing them one after the other left
     * a silence before every sentence — audible as stuttering, and worse the faster you read,
     * because the audio gets shorter while the synthesis does not. One sentence of look-ahead
     * hides it whenever the voice can synthesize faster than it speaks. Only ever one
     * synthesis runs at a time, which both engines require.
     */
    private suspend fun readLoop(src: Narrator.ContentSource, from: ReadingPosition) = coroutineScope {
        var ref = src.firstAtOrAfter(from)
        if (ref == null) {
            finishBook()
            return@coroutineScope
        }
        var consecutiveFailures = 0
        // Sentences waiting to be spoken, each already synthesized or being synthesized now.
        val ahead = ArrayDeque<Pair<SentenceRef, Deferred<SynthesizedSentence?>>>()
        while (ref != null && coroutineIsActive()) {
            currentRef = ref
            val speaking = ref
            val audio = (ahead.removeFirstOrNull()?.second ?: synthesizeAsync(speaking)).await()
            if (!coroutineIsActive()) return@coroutineScope
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
                    return@coroutineScope
                }
                ref = src.next(speaking.position)
                continue
            }
            consecutiveFailures = 0
            // Keep the queue topped up before this sentence starts playing. Two sentences of
            // look-ahead, not one, so a long sentence's synthesis can overlap two short ones.
            var tail = ahead.lastOrNull()?.first ?: speaking
            while (ahead.size < LOOK_AHEAD) {
                val upcoming = src.next(tail.position) ?: break
                ahead.addLast(upcoming to synthesizeAsync(upcoming))
                tail = upcoming
            }
            val next = ahead.firstOrNull()?.first ?: src.next(speaking.position)
            val marks = estimatedMarks(speaking, audio)
            withMain { listener.onSentenceStart(speaking) }

            var lastRange: IntRange? = null
            val completed = player.play(audio) { frame ->
                val mark = markAt(marks, frame) ?: return@play
                val range = (speaking.startInBlock + mark.start) until (speaking.startInBlock + mark.end)
                // The position poll runs far faster than words change; only publish on change.
                if (range != lastRange) {
                    lastRange = range
                    scope.launch(Dispatchers.Main) {
                        // Late word of a sentence already left behind (or of another book).
                        if (currentRef === speaking) listener.onWordRange(speaking, range)
                    }
                }
            }
            if (!completed || !coroutineIsActive()) return@coroutineScope
            ref = next
        }
        if (coroutineIsActive()) finishBook()
    }

    /**
     * One sentence's audio, from whichever voice is in use.
     *
     * The lock is what makes look-ahead safe: both synthesizers handle one sentence at a time
     * — [PcmSynthesizer] keeps a single pending request, which a second call would replace,
     * leaving the first waiting for a result that never comes. Kotlin's mutex is first-come,
     * first-served, so the queue is still synthesized in reading order.
     */
    private fun CoroutineScope.synthesizeAsync(ref: SentenceRef): Deferred<SynthesizedSentence?> =
        async(Dispatchers.Default) {
            synthesis.withLock {
                piperVoice
                    ?.let { piper.synthesize(ref.text, speechRate, locale) }
                    ?: synthesizer.synthesize(SpokenText.normalize(ref.text, locale), utteranceIdFor(ref))
            }
        }

    private fun markAt(
        marks: List<SynthesizedSentence.FrameMark>,
        frame: Int,
    ): SynthesizedSentence.FrameMark? = marks.lastOrNull { it.frame <= frame }

    /**
     * Spreads the sentence's measured audio duration across its words by character position.
     * See the class doc for why this is used unconditionally rather than trusting the
     * engine's own `onRangeStart` timing.
     */
    private fun estimatedMarks(
        ref: SentenceRef,
        audio: SynthesizedSentence,
    ): List<SynthesizedSentence.FrameMark> {
        val words = WordSplitter.split(ref.text, locale)
        if (words.isEmpty()) return emptyList()
        val framesPerMsRate = audio.sampleRateHz / 1000f
        val timings = if (audio.pieces.isEmpty()) {
            NeuralWordTiming.estimate(
                sentenceLength = ref.text.length,
                wordSpans = words,
                startInBlock = 0,
                totalDurationMs = audio.durationMs,
            )
        } else {
            // A sentence synthesized in pieces knows when each of them plays, so the estimate
            // is anchored piece by piece instead of spread across the whole sentence.
            val pieces = audio.pieces.mapIndexed { index, anchor ->
                val endFrame = audio.pieces.getOrNull(index + 1)?.frame ?: audio.frameCount
                NeuralWordTiming.Piece(
                    chars = anchor.start until anchor.end,
                    startMs = (anchor.frame / framesPerMsRate).toInt(),
                    endMs = (endFrame / framesPerMsRate).toInt(),
                )
            }
            NeuralWordTiming.estimateInPieces(pieces, words, startInBlock = 0)
        }
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
        startJob?.cancel()
        isSpeaking = false
        player.stop()
        synthesizer.cancel()
        readingJob?.cancel()
        readingJob = null
        scheduleModelRelease()
    }

    override suspend fun halt() {
        val reading = readingJob
        pause()
        // pause() cancels a start still under way; wait for it so it cannot launch a reading
        // after this returns, then for the reading itself (which may be in the middle of a
        // synthesis the model cannot interrupt).
        startJob?.join()
        reading?.cancelAndJoin()
    }

    /**
     * Gives the neural model's memory back when the reading has been stopped for a while.
     * onnxruntime holds around 270 MB once it has been running, which is a lot to sit on in
     * the background; loading it again costs a second or two, and only after a long pause.
     */
    private fun scheduleModelRelease() {
        if (piperVoice == null) return
        releaseJob?.cancel()
        releaseJob = scope.launch(Dispatchers.Default) {
            delay(IDLE_RELEASE_MS)
            synthesis.withLock { if (!isSpeaking) piper.release() }
        }
    }

    override fun resume() {
        currentRef?.let { playFrom(it.position) }
    }

    override fun skipToNext() = skip(forward = true)

    override fun skipToPrev() = skip(forward = false)

    private fun skip(forward: Boolean) {
        scope.launch(Dispatchers.Main) {
            skipLock.withLock {
                val src = source ?: return@launch
                val current = currentRef ?: return@launch
                val target = PlaybackPolicy.skipTarget(src, current, forward) ?: return@launch
                // Set before the (asynchronous) restart, so a second tap while it is under way
                // moves on from here and not from the sentence that was playing.
                currentRef = target
                if (isSpeaking) playFrom(target.position) else listener.onSentenceStart(target)
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
        releaseJob?.cancel()
        // Under the synthesis lock, on a scope of its own (the caller's is going away): a
        // sentence still being synthesized finishes before the model is freed.
        CoroutineScope(Dispatchers.Default).launch { synthesis.withLock { piper.release() } }
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
        /** Sentences synthesized ahead of the one being spoken. */
        const val LOOK_AHEAD = 2

        /** How long a pause has to run before the neural model's memory is handed back. */
        const val IDLE_RELEASE_MS = 5 * 60_000L

        /** Enough to ride out one odd sentence, few enough to report a broken voice quickly. */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
