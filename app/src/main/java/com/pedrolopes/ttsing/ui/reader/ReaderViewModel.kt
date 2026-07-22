package com.pedrolopes.ttsing.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.anki.AnkiExporter
import com.pedrolopes.ttsing.anki.CardAudio
import com.pedrolopes.ttsing.anki.CardDraft
import com.pedrolopes.ttsing.data.BookRepository
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.EpubBook
import com.pedrolopes.ttsing.data.epub.EpubParser
import com.pedrolopes.ttsing.data.epub.TocEntry
import com.pedrolopes.ttsing.data.settings.AppSettings
import com.pedrolopes.ttsing.data.settings.SettingsRepository
import com.pedrolopes.ttsing.tts.BookContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ReaderUiState(
    val title: String = "",
    val toc: List<TocEntry> = emptyList(),
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String? = null,
    val blocks: List<Block> = emptyList(),
    val isLoading: Boolean = true,
    val languageTag: String? = null,
    val error: String? = null,
    /** Character count per chapter, for time-to-finish estimates; empty until computed. */
    val chapterCharCounts: List<Int> = emptyList(),
    /** True while a card's audio is being synthesized and handed to AnkiDroid. */
    val isSavingCard: Boolean = false,
)

class ReaderViewModel(
    private val bookId: String,
    private val repo: BookRepository,
    private val cardAudio: CardAudio,
    private val anki: AnkiExporter,
    private val settings: SettingsRepository,
) : ViewModel() {

    private var parser: EpubParser? = null
    private var source: BookContentSource? = null
    private var book: EpubBook? = null

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    fun load() {
        if (book != null) return
        viewModelScope.launch {
            val savedChapter = repo.getBook(bookId)?.chapterIndex ?: 0
            val opened = runCatching { repo.openEpub(bookId) }.getOrNull()
            if (opened == null) {
                _ui.value = _ui.value.copy(isLoading = false, error = "Could not open this book.")
                return@launch
            }
            parser = opened.first
            book = opened.second
            source = BookContentSource(opened.first, opened.second)
            _ui.value = _ui.value.copy(
                title = opened.second.title,
                toc = opened.second.toc,
                chapterCount = opened.second.spine.size,
                languageTag = opened.second.language,
            )
            showChapter(savedChapter.coerceIn(0, opened.second.spine.size - 1))
        }
        // Character counts drive the time estimates; computing them walks the whole book,
        // so do it off the critical path and cache it in the database.
        viewModelScope.launch {
            val counts = runCatching { repo.chapterCharCounts(bookId) }.getOrDefault(emptyList())
            if (counts.isNotEmpty()) _ui.value = _ui.value.copy(chapterCharCounts = counts)
        }
    }

    fun showChapter(index: Int) {
        val src = source ?: return
        if (index !in 0 until (book?.spine?.size ?: 0)) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(isLoading = true)
            val chapter: Chapter? = runCatching { src.chapter(index) }.getOrNull()
            _ui.value = _ui.value.copy(
                chapterIndex = index,
                chapterTitle = chapter?.title,
                blocks = chapter?.blocks.orEmpty(),
                isLoading = false,
                error = if (chapter == null) "Could not load this chapter." else null,
            )
        }
    }

    /** Ensures the displayed chapter matches the chapter TTS is currently reading. */
    fun syncToChapter(chapterIndex: Int) {
        if (chapterIndex != _ui.value.chapterIndex && !_ui.value.isLoading) {
            showChapter(chapterIndex)
        }
    }

    fun nextChapter() = showChapter(_ui.value.chapterIndex + 1)

    fun previousChapter() = showChapter(_ui.value.chapterIndex - 1)

    suspend fun imageBytes(zipPath: String): ByteArray? =
        withContext(Dispatchers.IO) { parser?.readEntry(zipPath) }

    // ---- Anki cards ----

    fun bookLocale(): Locale = book?.locale() ?: Locale.getDefault()

    /**
     * Builds an empty card for the sentence containing [offsetInBlock] — the character the
     * user long-pressed. Returns null if that block has no speakable text.
     */
    fun draftFor(blockIndex: Int, offsetInBlock: Int): CardDraft? {
        val block = _ui.value.blocks.getOrNull(blockIndex) as? Block.Text ?: return null
        val span = block.sentences.getOrNull(block.sentenceIndexAt(offsetInBlock)) ?: return null
        return CardDraft(
            sentence = block.text.substring(span.start, span.end),
            bookTitle = _ui.value.title,
            languageTag = _ui.value.languageTag,
        )
    }

    /** Speaks the draft's sentence so the user can hear the card before saving it. */
    fun previewCardAudio(draft: CardDraft) {
        viewModelScope.launch {
            val current = settings.settings.first()
            cardAudio.speak(draft.sentence, bookLocale(), voiceFor(current), current.pitch)
        }
    }

    /**
     * Synthesizes the sentence audio and adds the note to AnkiDroid, reporting a
     * user-facing message either way. Runs in [viewModelScope] so it survives rotation.
     */
    fun submitCard(draft: CardDraft, onResult: (String) -> Unit) {
        if (_ui.value.isSavingCard) return
        _ui.value = _ui.value.copy(isSavingCard = true)
        viewModelScope.launch {
            val message = try {
                if (!anki.isAnkiInstalled()) {
                    "AnkiDroid isn't installed"
                } else {
                    val current = settings.settings.first()
                    val audio = cardAudio.synthesize(
                        text = draft.sentence,
                        locale = bookLocale(),
                        voiceName = voiceFor(current),
                        pitch = current.pitch,
                    )
                    when (val result = anki.addCard(draft, audio)) {
                        is AnkiExporter.Result.Added ->
                            if (result.audioAttached) {
                                "Card added to ${AnkiExporter.DECK_NAME}"
                            } else {
                                "Card added to ${AnkiExporter.DECK_NAME} (without audio)"
                            }
                        AnkiExporter.Result.AnkiNotInstalled -> "AnkiDroid isn't installed"
                        AnkiExporter.Result.PermissionDenied -> "AnkiDroid permission denied"
                        is AnkiExporter.Result.Failed -> "Could not add the card: ${result.message}"
                    }
                }
            } finally {
                _ui.value = _ui.value.copy(isSavingCard = false)
            }
            onResult(message)
        }
    }

    private fun voiceFor(current: AppSettings): String? =
        if (bookLocale().language == "pt") current.voicePt else current.voiceEn

    override fun onCleared() {
        parser?.close()
        parser = null
        source = null
        cardAudio.shutdown()
        super.onCleared()
    }

    companion object {
        fun create(bookId: String): ReaderViewModel {
            val app = TTSingApp.instance
            return ReaderViewModel(
                bookId = bookId,
                repo = app.books,
                cardAudio = CardAudio(app),
                anki = AnkiExporter(app, app.settings),
                settings = app.settings,
            )
        }
    }
}
