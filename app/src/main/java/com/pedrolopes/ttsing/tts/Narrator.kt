package com.pedrolopes.ttsing.tts

import android.speech.tts.Voice
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import java.util.Locale

/**
 * What [ReadingService] needs from a read-aloud engine, so the two implementations are
 * interchangeable:
 *
 * - [SpeechEngine] hands text to `TextToSpeech.speak()`; the engine's own process plays it.
 * - [AudioTrackNarrator] synthesizes to PCM and plays it through an app-owned `AudioTrack`,
 *   which is what makes Bluetooth/headset media buttons route to this app.
 *
 * Kept as an interface rather than replacing SpeechEngine outright so the old path stays a
 * one-setting fallback if the new one misbehaves on some device or TTS engine.
 */
interface Narrator {

    val currentRef: SentenceRef?

    val isSpeaking: Boolean

    suspend fun awaitReady(): Boolean

    fun setContentSource(contentSource: SpeechEngine.ContentSource)

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
