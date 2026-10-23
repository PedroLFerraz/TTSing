package com.pedrolopes.ttsing.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File

/** The page's colours. The app around it is always black. */
@Serializable
enum class ReaderTheme { DARK, LIGHT, SEPIA }

@Serializable
data class Settings(
    /** The books folder, absolute; null until one is chosen. */
    val libraryFolder: String? = null,
    val theme: ReaderTheme = ReaderTheme.DARK,
    /** Multiplies the page's type sizes; moves in 5% steps so each size is one layout. */
    val fontScale: Float = 1f,
    val speechRate: Float = 1f,
    /** The voice chosen for each language, by its language code. */
    val voiceByLanguage: Map<String, String> = emptyMap(),
    /** A book's reading language when its file declares the wrong one, by book id. */
    val languageByBook: Map<String, String> = emptyMap(),
    /** PDFs read as reflowed text rather than as their own pages, by book id. */
    val pdfAsText: Set<String> = emptySet(),
    /** How wide PDF pages are drawn, as a share of the room there is. */
    val pdfZoom: Float = 1f,
    /** Whether the library shows covers or a list. */
    val libraryAsList: Boolean = false,
)

class SettingsStore(dirs: AppDirs) {
    private val file = JsonFile(File(dirs.root, "settings.json"), Settings.serializer()) { Settings() }
    private val _state = MutableStateFlow(file.read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val value: Settings get() = _state.value

    fun update(change: (Settings) -> Settings) {
        _state.update(change)
        file.write(_state.value)
    }

    companion object {
        const val MIN_FONT_SCALE = 0.7f
        const val MAX_FONT_SCALE = 2f

        /** [scale] on the 5% grid the font size moves in, within bounds. */
        fun fontStep(scale: Float): Float =
            (Math.round(scale * 20f) / 20f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }
}
