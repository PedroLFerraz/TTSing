package com.pedrolopes.ttsing.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ReaderTheme { SYSTEM, LIGHT, SEPIA, DARK }

/**
 * Collects the string preferences whose key starts with [prefix], stripped of it — the way
 * per-language and per-book entries are stored, since their key set isn't known up front.
 */
private fun Preferences.stringsWithPrefix(prefix: String): Map<String, String> =
    asMap().mapNotNull { (key, value) ->
        val name = key.name
        if (name.startsWith(prefix) && value is String && name.length > prefix.length) {
            name.removePrefix(prefix) to value
        } else {
            null
        }
    }.toMap()

data class AppSettings(
    val libraryFolderUri: String?,
    val speechRate: Float,
    val pitch: Float,
    val fontScale: Float,
    val readerTheme: ReaderTheme,
    /** Chosen voice name per language code ("en", "pt", "de", …); absent means engine default. */
    val voices: Map<String, String> = emptyMap(),
    /** Per-book language override (book id → BCP-47 tag), for books whose `dc:language` is wrong. */
    val bookLanguages: Map<String, String> = emptyMap(),
    /** Measured speaking speed in characters/second at rate 1.0, used for time estimates. */
    val charsPerSecond: Float = DEFAULT_CHARS_PER_SECOND,
    /** Cached AnkiDroid ids for the TTSing deck and note type; null until first card. */
    val ankiDeckId: Long? = null,
    val ankiModelId: Long? = null,
    /**
     * Play synthesized audio through the app's own AudioTrack instead of letting the TTS
     * engine play it. Required for Bluetooth/headset media buttons to reach this app, since
     * Android routes them to whichever app is actually producing the audio.
     */
    val ownAudioPlayback: Boolean = true,
) {
    /** The stored voice for a language, or null to use the engine's default. */
    fun voiceFor(languageCode: String): String? = voices[languageCode]

    /**
     * The language to read [bookId] in: the user's override if they set one, otherwise the
     * book's own `dc:language`. Books frequently declare the wrong language (or none), and
     * a book with passages in another language can only ever have one voice.
     */
    fun localeFor(bookId: String?, declared: Locale): Locale {
        val override = bookId?.let { bookLanguages[it] } ?: return declared
        return Locale.forLanguageTag(override).takeIf { it.language.isNotEmpty() } ?: declared
    }

    companion object {
        /** ~180 wpm at 5 chars+space per word — a reasonable prior before we measure. */
        const val DEFAULT_CHARS_PER_SECOND = 15f
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val folderUri = stringPreferencesKey("library_folder_uri")
        val speechRate = floatPreferencesKey("speech_rate")
        val pitch = floatPreferencesKey("pitch")
        val fontScale = floatPreferencesKey("font_scale")
        val readerTheme = stringPreferencesKey("reader_theme")
        val charsPerSecond = floatPreferencesKey("chars_per_second")

        /** Voices are stored one key per language, e.g. `voice_de`. */
        const val VOICE_PREFIX = "voice_"

        /** Language overrides are stored one key per book, e.g. `booklang_<sha1>`. */
        const val BOOK_LANGUAGE_PREFIX = "booklang_"

        fun voice(languageCode: String) = stringPreferencesKey("$VOICE_PREFIX$languageCode")

        fun bookLanguage(bookId: String) = stringPreferencesKey("$BOOK_LANGUAGE_PREFIX$bookId")
        val ankiDeckId = longPreferencesKey("anki_deck_id")
        val ankiModelId = longPreferencesKey("anki_model_id")
        val ownAudioPlayback = booleanPreferencesKey("own_audio_playback")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            libraryFolderUri = prefs[Keys.folderUri],
            speechRate = prefs[Keys.speechRate] ?: 1.0f,
            pitch = prefs[Keys.pitch] ?: 1.0f,
            fontScale = prefs[Keys.fontScale] ?: 1.0f,
            readerTheme = prefs[Keys.readerTheme]
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                ?: ReaderTheme.SYSTEM,
            voices = prefs.stringsWithPrefix(Keys.VOICE_PREFIX),
            bookLanguages = prefs.stringsWithPrefix(Keys.BOOK_LANGUAGE_PREFIX),
            charsPerSecond = prefs[Keys.charsPerSecond] ?: AppSettings.DEFAULT_CHARS_PER_SECOND,
            ankiDeckId = prefs[Keys.ankiDeckId],
            ankiModelId = prefs[Keys.ankiModelId],
            ownAudioPlayback = prefs[Keys.ownAudioPlayback] ?: true,
        )
    }

    /** Escape hatch: turn off app-owned playback and go back to TextToSpeech.speak(). */
    suspend fun setOwnAudioPlayback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ownAudioPlayback] = enabled }
    }

    /** Caches the AnkiDroid deck/note-type ids so they are looked up only once. */
    suspend fun setAnkiIds(deckId: Long, modelId: Long) {
        context.dataStore.edit {
            it[Keys.ankiDeckId] = deckId
            it[Keys.ankiModelId] = modelId
        }
    }

    /** Persists the measured speaking speed (characters/second normalised to rate 1.0). */
    suspend fun setCharsPerSecond(value: Float) {
        context.dataStore.edit { it[Keys.charsPerSecond] = value }
    }

    suspend fun setLibraryFolder(uri: String) {
        context.dataStore.edit { it[Keys.folderUri] = uri }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.dataStore.edit { it[Keys.speechRate] = rate }
    }

    suspend fun setPitch(pitch: Float) {
        context.dataStore.edit { it[Keys.pitch] = pitch }
    }

    suspend fun setFontScale(scale: Float) {
        context.dataStore.edit { it[Keys.fontScale] = scale }
    }

    suspend fun setReaderTheme(theme: ReaderTheme) {
        context.dataStore.edit { it[Keys.readerTheme] = theme.name }
    }

    /** Stores the chosen voice for a language, or clears it (null) to use the engine default. */
    suspend fun setVoice(languageCode: String, voiceName: String?) {
        context.dataStore.edit {
            val key = Keys.voice(languageCode)
            if (voiceName == null) it.remove(key) else it[key] = voiceName
        }
    }

    /** Overrides the language for one book, or clears it (null) to trust its `dc:language`. */
    suspend fun setBookLanguage(bookId: String, languageTag: String?) {
        context.dataStore.edit {
            val key = Keys.bookLanguage(bookId)
            if (languageTag == null) it.remove(key) else it[key] = languageTag
        }
    }
}
