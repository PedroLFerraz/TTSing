package com.pedrolopes.ttsing.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.anki.AnkiExporter
import com.pedrolopes.ttsing.anki.CardAudio
import com.pedrolopes.ttsing.anki.CardDraft
import com.pedrolopes.ttsing.data.BookRepository
import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.TocEntry
import com.pedrolopes.ttsing.data.news.ArticleImageStore
import com.pedrolopes.ttsing.data.news.NewsRepository
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
    /** Article only: the full page couldn't be fetched, so this is just the feed's teaser. */
    val isTruncated: Boolean = false,
    /** Article only: its address on the web, so the reader can offer to open it there. */
    val articleLink: String? = null,
)

class ReaderViewModel(
    private val bookId: String,
    private val repo: BookRepository,
    private val news: NewsRepository,
    private val articleImages: ArticleImageStore,
    private val cardAudio: CardAudio,
    private val anki: AnkiExporter,
    private val settings: SettingsRepository,
) : ViewModel() {

    private var document: BookDocument? = null
    private var source: BookContentSource? = null

    /** True when this "book" is actually a news article: one section, no chapters, no TOC. */
    private val isArticle = NewsRepository.isArticle(bookId)

    /** Set once the article's text is in memory, so [load] is idempotent like the book path. */
    private var articleLoaded = false

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    fun load() {
        if (isArticle) {
            loadArticle()
            return
        }
        if (document != null) return
        viewModelScope.launch {
            val savedChapter = repo.getBook(bookId)?.chapterIndex ?: 0
            val opened = runCatching { repo.openBook(bookId) }.getOrNull()
            if (opened == null) {
                _ui.value = _ui.value.copy(isLoading = false, error = "Could not open this book.")
                return@launch
            }
            document = opened
            _ui.value = _ui.value.copy(
                title = opened.title,
                toc = opened.toc,
                chapterCount = opened.sectionCount,
                languageTag = opened.language,
            )
            opened.unreadableReason?.let { reason ->
                _ui.value = _ui.value.copy(isLoading = false, error = reason)
                return@launch
            }
            source = BookContentSource(opened)
            showChapter(savedChapter.coerceIn(0, opened.sectionCount - 1))
        }
        // Character counts drive the time estimates; computing them walks the whole book,
        // so do it off the critical path and cache it in the database.
        viewModelScope.launch {
            val counts = runCatching { repo.chapterCharCounts(bookId) }.getOrDefault(emptyList())
            if (counts.isNotEmpty()) _ui.value = _ui.value.copy(chapterCharCounts = counts)
        }
    }

    /**
     * Loads a news article as a single-section "chapter", so the reader screen, karaoke
     * highlight and flashcard gesture all work on it unchanged. Fetching the full text can
     * hit the network, hence the loading state.
     */
    private fun loadArticle() {
        if (articleLoaded) return
        articleLoaded = true
        viewModelScope.launch {
            _ui.value = _ui.value.copy(isLoading = true)
            val article = news.article(bookId)
            if (article == null) {
                _ui.value = _ui.value.copy(isLoading = false, error = "This article is no longer available.")
                return@launch
            }
            val locale = news.feedLocale(article.feedUrl) ?: Locale.getDefault()
            when (val body = news.body(bookId, locale)) {
                is NewsRepository.ArticleBody.Failed ->
                    _ui.value = _ui.value.copy(isLoading = false, title = article.title, error = body.message)

                is NewsRepository.ArticleBody.Ready -> {
                    articleLocale = locale
                    _ui.value = _ui.value.copy(
                        title = article.title,
                        chapterCount = 1,
                        chapterIndex = 0,
                        blocks = body.blocks,
                        languageTag = locale.toLanguageTag(),
                        isLoading = false,
                        error = null,
                        isTruncated = body.truncated,
                        articleLink = article.link,
                        chapterCharCounts = listOf(
                            body.blocks.filterIsInstance<Block.Text>().sumOf { it.text.length },
                        ),
                    )
                }
            }
        }
    }

    fun showChapter(index: Int) {
        if (isArticle) return
        val src = source ?: return
        if (index !in 0 until (document?.sectionCount ?: 0)) return
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

    /**
     * Bytes for an image block. Book images come out of the EPUB zip; article images are
     * absolute URLs, downloaded once and then served from disk.
     */
    suspend fun imageBytes(key: String): ByteArray? =
        if (isArticle) {
            articleImages.bytes(key)
        } else {
            document?.readImage(key)
        }

    // ---- Anki cards ----

    /** The language the EPUB declares; the user's per-book override is applied on top. */
    fun bookLocale(): Locale = articleLocale ?: document?.locale() ?: Locale.getDefault()

    /** Language of the article being read, resolved from its feed. */
    private var articleLocale: Locale? = null

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
            val locale = current.localeFor(bookId, bookLocale())
            cardAudio.speak(draft.sentence, locale, current.voiceFor(locale.language), current.pitch)
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
                    val locale = current.localeFor(bookId, bookLocale())
                    val audio = cardAudio.synthesize(
                        text = draft.sentence,
                        locale = locale,
                        voiceName = current.voiceFor(locale.language),
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

    override fun onCleared() {
        document?.close()
        document = null
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
                news = app.news,
                articleImages = ArticleImageStore(app),
                cardAudio = CardAudio(app),
                anki = AnkiExporter(app, app.settings),
                settings = app.settings,
            )
        }
    }
}
