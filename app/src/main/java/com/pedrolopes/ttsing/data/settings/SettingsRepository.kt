package com.pedrolopes.ttsing.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
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

/** A PDF shown as its own pages, with the spoken text marked on them, or reflowed like a book. */
enum class PdfView { PAGES, TEXT }

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
    val fontScale: Float,
    val readerTheme: ReaderTheme,
    /** Chosen voice name per language code ("en", "pt", "de", …); absent means engine default. */
    val voices: Map<String, String> = emptyMap(),
    /** Per-book language override (book id → BCP-47 tag), for books whose `dc:language` is wrong. */
    val bookLanguages: Map<String, String> = emptyMap(),
    /**
     * Last measured speaking speed in characters/second at rate 1.0 — what the reader shows
     * before the service is running. The service's live figure takes over once it is.
     */
    val charsPerSecond: Float = DEFAULT_CHARS_PER_SECOND,
    /** Per-voice speed accumulators ([com.pedrolopes.ttsing.tts.SpeakingSpeed.encode]). */
    val speeds: Map<String, String> = emptyMap(),
    /** Cached AnkiDroid ids for the TTSing deck and note type; null until first card. */
    val ankiDeckId: Long? = null,
    val ankiModelId: Long? = null,
    /** How each PDF is shown (book id → [PdfView] name), for the ones not on the default. */
    val pdfViews: Map<String, String> = emptyMap(),
) {
    /** Pages as printed unless the user switched this PDF to reflowed text. */
    fun pdfViewFor(bookId: String): PdfView =
        pdfViews[bookId]?.let { runCatching { PdfView.valueOf(it) }.getOrNull() } ?: PdfView.PAGES

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
        val fontScale = floatPreferencesKey("font_scale")
        val readerTheme = stringPreferencesKey("reader_theme")
        val charsPerSecond = floatPreferencesKey("chars_per_second")

        /** Voices are stored one key per language, e.g. `voice_de`. */
        const val VOICE_PREFIX = "voice_"

        /** Language overrides are stored one key per book, e.g. `booklang_<sha1>`. */
        const val BOOK_LANGUAGE_PREFIX = "booklang_"

        /** Per-voice speaking-speed accumulators, keyed by voice name. */
        const val SPEED_PREFIX = "speed_"

        /** How each PDF is shown, one key per book, e.g. `pdfview_<sha1>`. */
        const val PDF_VIEW_PREFIX = "pdfview_"

        fun voice(languageCode: String) = stringPreferencesKey("$VOICE_PREFIX$languageCode")

        fun bookLanguage(bookId: String) = stringPreferencesKey("$BOOK_LANGUAGE_PREFIX$bookId")
        val ankiDeckId = longPreferencesKey("anki_deck_id")
        val ankiModelId = longPreferencesKey("anki_model_id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            libraryFolderUri = prefs[Keys.folderUri],
            speechRate = prefs[Keys.speechRate] ?: 1.0f,
            fontScale = prefs[Keys.fontScale] ?: 1.0f,
            readerTheme = prefs[Keys.readerTheme]
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                // Black by default: the app around the page is black whatever the system
                // says, and a white page inside it is a jolt nobody asked for.
                ?: ReaderTheme.DARK,
            voices = prefs.stringsWithPrefix(Keys.VOICE_PREFIX),
            bookLanguages = prefs.stringsWithPrefix(Keys.BOOK_LANGUAGE_PREFIX),
            charsPerSecond = prefs[Keys.charsPerSecond] ?: AppSettings.DEFAULT_CHARS_PER_SECOND,
            speeds = prefs.stringsWithPrefix(Keys.SPEED_PREFIX),
            ankiDeckId = prefs[Keys.ankiDeckId],
            ankiModelId = prefs[Keys.ankiModelId],
            pdfViews = prefs.stringsWithPrefix(Keys.PDF_VIEW_PREFIX),
        )
    }

    /** Caches the AnkiDroid deck/note-type ids so they are looked up only once. */
    suspend fun setAnkiIds(deckId: Long, modelId: Long) {
        context.dataStore.edit {
            it[Keys.ankiDeckId] = deckId
            it[Keys.ankiModelId] = modelId
        }
    }

    /**
     * Persists one voice's speed accumulators, plus the resulting figure as the global
     * "last known" speed the reader falls back to when nothing is playing.
     */
    suspend fun setSpeed(voiceKey: String, encoded: String, charsPerSecond: Float) {
        context.dataStore.edit {
            it[stringPreferencesKey(Keys.SPEED_PREFIX + voiceKey)] = encoded
            it[Keys.charsPerSecond] = charsPerSecond
        }
    }

    suspend fun setLibraryFolder(uri: String) {
        context.dataStore.edit { it[Keys.folderUri] = uri }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.dataStore.edit { it[Keys.speechRate] = rate }
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

    suspend fun setPdfView(bookId: String, view: PdfView) {
        context.dataStore.edit {
            val key = stringPreferencesKey(Keys.PDF_VIEW_PREFIX + bookId)
            if (view == PdfView.PAGES) it.remove(key) else it[key] = view.name
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
