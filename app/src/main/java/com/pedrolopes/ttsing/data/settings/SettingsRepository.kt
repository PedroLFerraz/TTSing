package com.pedrolopes.ttsing.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ReaderTheme { SYSTEM, LIGHT, SEPIA, DARK }

data class AppSettings(
    val libraryFolderUri: String?,
    val speechRate: Float,
    val pitch: Float,
    val fontScale: Float,
    val readerTheme: ReaderTheme,
    val voiceEn: String?,
    val voicePt: String?,
    /** Measured speaking speed in characters/second at rate 1.0, used for time estimates. */
    val charsPerSecond: Float = DEFAULT_CHARS_PER_SECOND,
    /** Cached AnkiDroid ids for the TTSing deck and note type; null until first card. */
    val ankiDeckId: Long? = null,
    val ankiModelId: Long? = null,
) {
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
        val voiceEn = stringPreferencesKey("voice_en")
        val voicePt = stringPreferencesKey("voice_pt")
        val charsPerSecond = floatPreferencesKey("chars_per_second")
        val ankiDeckId = longPreferencesKey("anki_deck_id")
        val ankiModelId = longPreferencesKey("anki_model_id")
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
            voiceEn = prefs[Keys.voiceEn],
            voicePt = prefs[Keys.voicePt],
            charsPerSecond = prefs[Keys.charsPerSecond] ?: AppSettings.DEFAULT_CHARS_PER_SECOND,
            ankiDeckId = prefs[Keys.ankiDeckId],
            ankiModelId = prefs[Keys.ankiModelId],
        )
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
            val key = if (languageCode == "pt") Keys.voicePt else Keys.voiceEn
            if (voiceName == null) it.remove(key) else it[key] = voiceName
        }
    }
}
