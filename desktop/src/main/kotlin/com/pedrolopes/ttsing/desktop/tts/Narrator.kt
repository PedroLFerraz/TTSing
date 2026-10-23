package com.pedrolopes.ttsing.desktop.tts

import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.epub.WordSplitter
import com.pedrolopes.ttsing.tts.ContentSource
import com.pedrolopes.ttsing.tts.NeuralWordTiming
import com.pedrolopes.ttsing.tts.PlaybackPolicy
import com.pedrolopes.ttsing.tts.SentenceRef
import com.pedrolopes.ttsing.tts.WordTiming
import com.pedrolopes.ttsing.tts.piper.PiperVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** What the reader shows of the voice: the sentence and the word being spoken. */
data class Narration(
    val sentence: SentenceRef? = null,
    /** The word being spoken, as offsets into its block's text. */
    val word: IntRange? = null,
    val speaking: Boolean = false,
    /** Set while a voice is being loaded: the first sentence takes a moment. */
    val preparing: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)

/**
 * Reads a book aloud, sentence by sentence, as the Android app's narrator does: each sentence
 * is synthesized to samples and played through a line this app owns, with the next two being
 * synthesized while it plays so no silence opens up between them.
 */
class Narrator(private val scope: CoroutineScope) {

    private val engine = PiperEngine()
    private val audio = AudioOut()

    /** Only one synthesis runs at a time; the engine requires it. First come, first served. */
    private val synthesis = Mutex()

    private val _state = MutableStateFlow(Narration())
    val state: StateFlow<Narration> = _state.asStateFlow()

    private var job: Job? = null

    /** Called after each sentence heard in full, with how long it took at [rate]. */
    var onSpoken: (SentenceRef, Long, Float) -> Unit = { _, _, _ -> }

    /** Reads [source] from [from] on, in [voice], at [rate] times the voice's own pace. */
    fun play(source: ContentSource, from: ReadingPosition, voice: PiperVoice, locale: Locale, rate: Float) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            _state.update { it.copy(speaking = true, preparing = engine.voice?.id != voice.id, finished = false, error = null) }
            val ready = withContext(Dispatchers.IO) { synthesis.withLock { engine.prepare(voice) } }
            _state.update { it.copy(preparing = false) }
            if (!ready) {
                _state.update { it.copy(speaking = false, error = "The voice ${voice.id} could not be loaded") }
                return@launch
            }
            readLoop(source, from, locale, rate)
        }
    }

    /** Stops the voice where it is; [state] keeps the sentence, so play resumes from it. */
    fun pause() {
        job?.cancel()
        audio.stop()
        _state.update { it.copy(speaking = false, word = null) }
    }

    /** The sentence [forward] or back from the one being read, or null at the end of the book. */
    suspend fun skipTarget(source: ContentSource, forward: Boolean): SentenceRef? {
        val current = _state.value.sentence ?: return null
        return PlaybackPolicy.skipTarget(source, current, forward)
    }

    /** Puts the voice on [ref] without reading it: where play will start. */
    fun park(ref: SentenceRef?) {
        _state.update { it.copy(sentence = ref, word = null, finished = false) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun shutdown() {
        job?.cancel()
        audio.close()
        engine.release()
    }

    private suspend fun readLoop(source: ContentSource, from: ReadingPosition, locale: Locale, rate: Float) = coroutineScope {
        var ref = source.firstAtOrAfter(from)
        var failures = 0
        val ahead = ArrayDeque<Pair<SentenceRef, Deferred<Speech?>>>()
        fun synthesizeAsync(sentence: SentenceRef) = async(Dispatchers.Default) {
            synthesis.withLock { engine.synthesize(sentence.text, rate, locale) }
        }
        while (ref != null && currentCoroutineContext().isActive) {
            val speaking: SentenceRef = ref
            _state.update { it.copy(sentence = speaking, word = null) }
            val speech = (ahead.removeFirstOrNull()?.second ?: synthesizeAsync(speaking)).await()
            if (speech == null) {
                // A run of refusals means the voice can't speak this text at all: say so.
                if (++failures >= MAX_FAILURES) {
                    _state.update { it.copy(speaking = false, error = "This voice can't speak the text. Try another voice.") }
                    return@coroutineScope
                }
                ref = source.next(speaking.position)
                continue
            }
            failures = 0
            var tail = ahead.lastOrNull()?.first ?: speaking
            while (ahead.size < LOOK_AHEAD) {
                val upcoming = source.next(tail.position) ?: break
                ahead.addLast(upcoming to synthesizeAsync(upcoming))
                tail = upcoming
            }
            val next = ahead.firstOrNull()?.first ?: source.next(speaking.position)

            val words = wordTimings(speaking, speech, locale)
            val msPerFrame = 1000f / speech.sampleRate
            var lastWord: IntRange? = null
            val started = System.currentTimeMillis()
            val heard = withContext(Dispatchers.IO) {
                audio.play(speech) { frame ->
                    val word = NeuralWordTiming.wordAt(words, (frame * msPerFrame).toInt())?.rangeInBlock
                    if (word != null && word != lastWord) {
                        lastWord = word
                        _state.update { if (it.sentence === speaking) it.copy(word = word) else it }
                    }
                }
            }
            if (!heard || !currentCoroutineContext().isActive) return@coroutineScope
            onSpoken(speaking, System.currentTimeMillis() - started, rate)
            ref = next
        }
        if (currentCoroutineContext().isActive) {
            _state.update { it.copy(speaking = false, word = null, finished = true) }
        }
    }

    /** When each word is spoken, spread over the audio by character position, piece by piece. */
    private fun wordTimings(ref: SentenceRef, speech: Speech, locale: Locale): List<WordTiming> {
        val words = WordSplitter.split(ref.text, locale)
        if (words.isEmpty()) return emptyList()
        val perMs = speech.sampleRate / 1000f
        val pieces = speech.pieces.mapIndexed { index, piece ->
            val end = speech.pieces.getOrNull(index + 1)?.frame ?: speech.pcm.size
            NeuralWordTiming.Piece(piece.start until piece.end, (piece.frame / perMs).toInt(), (end / perMs).toInt())
        }
        return NeuralWordTiming.estimateInPieces(pieces, words, ref.startInBlock)
    }

    private companion object {
        const val LOOK_AHEAD = 2
        const val MAX_FAILURES = 3
    }
}
