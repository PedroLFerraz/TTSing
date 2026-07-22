package com.pedrolopes.ttsing.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** A single sentence ready to be spoken. */
data class SentenceRef(
    val position: ReadingPosition,
    val text: String,
    /** Char offset of this sentence inside its block's text. */
    val startInBlock: Int,
)

/**
 * Wraps [TextToSpeech], speaking one sentence per utterance and keeping a small
 * queue ahead so playback is seamless. All callbacks are delivered on the main thread.
 */
class SpeechEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {

    interface ContentSource {
        /** First speakable sentence at or after [position], or null at end of book. */
        suspend fun firstAtOrAfter(position: ReadingPosition): SentenceRef?

        /** Sentence strictly after [position], or null at end of book. */
        suspend fun next(position: ReadingPosition): SentenceRef?

        /** Sentence strictly before [position], or null at start of book. */
        suspend fun prev(position: ReadingPosition): SentenceRef?
    }

    interface Listener {
        fun onSentenceStart(ref: SentenceRef)

        /** [rangeInBlock] is the currently spoken word as offsets into the block's text. */
        fun onWordRange(ref: SentenceRef, rangeInBlock: IntRange)

        fun onBookFinished()

        fun onEngineError(message: String)
    }

    private val ready = CompletableDeferred<Boolean>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    private var source: ContentSource? = null
    private var generation = 0
    private val queued = LinkedHashMap<String, SentenceRef>()

    var currentRef: SentenceRef? = null
        private set

    var isSpeaking: Boolean = false
        private set

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                scope.launch(Dispatchers.Main) { handleStart(utteranceId) }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                scope.launch(Dispatchers.Main) { handleRange(utteranceId, start, end) }
            }

            override fun onDone(utteranceId: String) {
                scope.launch(Dispatchers.Main) { handleDone(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                scope.launch(Dispatchers.Main) { handleDone(utteranceId) }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                scope.launch(Dispatchers.Main) { handleDone(utteranceId) }
            }
        })
    }

    suspend fun awaitReady(): Boolean = ready.await()

    fun setContentSource(contentSource: ContentSource) {
        source = contentSource
    }

    /** Returns true if the language is available on the active engine. */
    suspend fun configureLanguage(locale: Locale, preferredVoiceName: String?): Boolean {
        if (!awaitReady()) return false
        val result = tts.setLanguage(locale)
        val available = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        val match = preferredVoiceName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (match != null) {
            tts.voice = match
        } else {
            // No stored choice (or it's gone): fall back to the engine's default voice for
            // this language, which is the "smooth default" the user hears out of the box.
            val default = tts.defaultVoice
            if (default != null && default.locale.language == locale.language) tts.voice = default
        }
        return available
    }

    /** The name of the voice currently in use (what the user is actually hearing). */
    fun currentVoiceName(): String? = runCatching { tts.voice?.name }.getOrNull()

    /** The engine's default voice name for [locale], shown as the "Device default" choice. */
    fun defaultVoiceName(locale: Locale): String? = runCatching {
        tts.defaultVoice?.takeIf { it.locale.language == locale.language }?.name
    }.getOrNull()

    /**
     * Every language the engine can actually speak, one entry per language (not per region),
     * sorted by display name. This is what the language picker offers, so the user can only
     * choose something they will really hear.
     */
    fun availableLanguages(): List<Locale> =
        tts.voices
            .orEmpty()
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .map { it.locale.language }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { Locale.forLanguageTag(it) }
            .sortedBy { it.displayLanguage.lowercase() }

    /** All usable (installed) voices for the language, including online ones. */
    fun voicesFor(locale: Locale): List<Voice> =
        tts.voices
            .orEmpty()
            .filter { it.locale.language == locale.language }
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .sortedWith(compareBy({ it.locale.country }, { -it.quality }, { it.name }))

    fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    fun setPitch(pitch: Float) {
        tts.setPitch(pitch)
    }

    fun playFrom(position: ReadingPosition) {
        scope.launch(Dispatchers.Main) {
            if (!awaitReady()) {
                listener.onEngineError("Text-to-speech engine failed to initialize")
                return@launch
            }
            val src = source ?: return@launch
            generation++
            queued.clear()
            tts.stop()
            var ref = src.firstAtOrAfter(position)
            if (ref == null) {
                isSpeaking = false
                listener.onBookFinished()
                return@launch
            }
            isSpeaking = true
            currentRef = ref
            var flush = true
            var count = 0
            while (ref != null && count < LOOKAHEAD) {
                enqueue(ref, flush)
                flush = false
                count++
                ref = src.next(ref.position)
            }
        }
    }

    fun pause() {
        generation++
        queued.clear()
        isSpeaking = false
        tts.stop()
    }

    fun resume() {
        val ref = currentRef
        if (ref != null) playFrom(ref.position)
    }

    fun skipToNext() {
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

    fun skipToPrev() {
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

    fun moveTo(position: ReadingPosition) {
        scope.launch(Dispatchers.Main) {
            val src = source ?: return@launch
            val ref = src.firstAtOrAfter(position) ?: return@launch
            currentRef = ref
            if (isSpeaking) playFrom(ref.position) else listener.onSentenceStart(ref)
        }
    }

    fun shutdown() {
        generation++
        queued.clear()
        tts.stop()
        tts.shutdown()
    }

    private fun enqueue(ref: SentenceRef, flush: Boolean) {
        val id = "$generation|${ref.position.chapterIndex}|${ref.position.blockIndex}|${ref.position.sentenceIndex}"
        queued[id] = ref
        val text = if (ref.text.length >= TextToSpeech.getMaxSpeechInputLength()) {
            ref.text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
        } else {
            ref.text
        }
        tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
    }

    private fun isCurrentGeneration(utteranceId: String): Boolean =
        utteranceId.substringBefore('|').toIntOrNull() == generation

    private fun handleStart(utteranceId: String) {
        if (!isCurrentGeneration(utteranceId)) return
        val ref = queued[utteranceId] ?: return
        currentRef = ref
        isSpeaking = true
        listener.onSentenceStart(ref)
    }

    private fun handleRange(utteranceId: String, start: Int, end: Int) {
        if (!isCurrentGeneration(utteranceId)) return
        val ref = queued[utteranceId] ?: return
        listener.onWordRange(ref, (ref.startInBlock + start) until (ref.startInBlock + end))
    }

    private fun handleDone(utteranceId: String) {
        if (!isCurrentGeneration(utteranceId)) return
        val done = queued.remove(utteranceId) ?: return
        val src = source ?: return
        scope.launch(Dispatchers.Main) {
            val tail = queued.values.lastOrNull() ?: done
            val next = src.next(tail.position)
            if (next != null) {
                if (isSpeaking) enqueue(next, flush = false)
            } else if (queued.isEmpty()) {
                isSpeaking = false
                listener.onBookFinished()
            }
        }
    }

    private companion object {
        const val LOOKAHEAD = 3
    }
}
