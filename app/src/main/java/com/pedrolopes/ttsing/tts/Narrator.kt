package com.pedrolopes.ttsing.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import java.util.Locale

/**
 * A language the engine can speak. [downloaded] is false when every voice for it is flagged
 * `KEY_FEATURE_NOT_INSTALLED` — the engine still offers it, and selecting it starts the
 * download, so it belongs in the picker rather than being hidden.
 */
data class LanguageOption(
    val locale: Locale,
    val downloaded: Boolean,
)

/**
 * True when this voice's data still has to be fetched. Selecting it is what starts the
 * download, so these are offered rather than hidden.
 */
fun Voice.needsDownload(): Boolean =
    features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true

/** A single sentence ready to be spoken. */
data class SentenceRef(
    val position: ReadingPosition,
    val text: String,
    /** Char offset of this sentence inside its block's text. */
    val startInBlock: Int,
)

/**
 * Reads a book aloud, sentence by sentence.
 *
 * The one implementation, [AudioTrackNarrator], synthesizes each sentence to PCM and plays
 * it through an `AudioTrack` this app owns. That detail matters: with
 * `TextToSpeech.speak()` the audio comes out of the TTS engine's own process, so Android
 * attributes the playback to *that* app and routes Bluetooth/headset media buttons there
 * instead of here.
 */
interface Narrator {

    /** Supplies the sentences to read, in order, across block and chapter boundaries. */
    interface ContentSource {
        /** First speakable sentence at or after [position], or null at end of book. */
        suspend fun firstAtOrAfter(position: ReadingPosition): SentenceRef?

        /** Sentence strictly after [position], or null at end of book. */
        suspend fun next(position: ReadingPosition): SentenceRef?

        /** Sentence strictly before [position], or null at start of book. */
        suspend fun prev(position: ReadingPosition): SentenceRef?
    }

    /** Reports reading progress; all callbacks arrive on the main thread. */
    interface Listener {
        fun onSentenceStart(ref: SentenceRef)

        /** [rangeInBlock] is the currently spoken word as offsets into the block's text. */
        fun onWordRange(ref: SentenceRef, rangeInBlock: IntRange)

        fun onBookFinished()

        fun onEngineError(message: String)
    }

    val currentRef: SentenceRef?

    val isSpeaking: Boolean

    suspend fun awaitReady(): Boolean

    fun setContentSource(contentSource: ContentSource)

    /** Returns true if the language is available on the active engine. */
    suspend fun configureLanguage(locale: Locale, preferredVoiceName: String?): Boolean

    fun currentVoiceName(): String?

    fun defaultVoiceName(locale: Locale): String?

    fun availableLanguages(): List<LanguageOption>

    fun voicesFor(locale: Locale): List<Voice>

    fun setSpeechRate(rate: Float)

    fun setPitch(pitch: Float)

    fun playFrom(position: ReadingPosition)

    fun pause()

    fun resume()

    fun skipToNext()

    fun skipToPrev()

    fun moveTo(position: ReadingPosition)

    fun shutdown()
}
