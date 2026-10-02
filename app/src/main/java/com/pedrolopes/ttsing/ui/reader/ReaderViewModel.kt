package com.pedrolopes.ttsing.ui.reader

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.anki.AnkiExporter
import com.pedrolopes.ttsing.anki.CardAudio
import com.pedrolopes.ttsing.anki.CardDraft
import com.pedrolopes.ttsing.data.BookRepository
import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.epub.TocEntry
import com.pedrolopes.ttsing.data.news.ArticleImageStore
import com.pedrolopes.ttsing.data.news.NewsRepository
import com.pedrolopes.ttsing.data.pdf.PdfDocument
import com.pedrolopes.ttsing.data.settings.SettingsRepository
import com.pedrolopes.ttsing.tts.BookContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** One chapter's blocks, as the reader holds them. */
data class LoadedChapter(
    val index: Int,
    val title: String?,
    val blocks: List<Block>,
)

/**
 * A one-off instruction to move the pager — opening the book, a table-of-contents jump, the
 * « » buttons. Reading along never produces one; the pager just turns. [id] makes two jumps to
 * the same place distinct.
 */
data class JumpRequest(val chapterIndex: Int, val blockIndex: Int, val id: Long)

data class ReaderUiState(
    val title: String = "",
    val toc: List<TocEntry> = emptyList(),
    val chapterCount: Int = 0,
    /**
     * The chapters the pager currently spans: the one being read and its nearest readable
     * neighbours on either side. Swiping into a neighbour recentres the window on it, so the
     * book reads as one continuous run of pages rather than as separate chapters.
     */
    val window: List<LoadedChapter> = emptyList(),
    /** The chapter [window] is centred on. */
    val anchorChapter: Int = 0,
    val jump: JumpRequest? = null,
    val isLoading: Boolean = true,
    val languageTag: String? = null,
    val error: String? = null,
    /** Character count per chapter, for time-to-finish estimates; empty until computed. */
    val chapterCharCounts: List<Int> = emptyList(),
    /** Pages per chapter at the current layout; null while they are being counted. */
    val pageCounts: List<Int>? = null,
    /** This book's listening totals as saved, for the time left before playback starts. */
    val listenedMs: Long = 0,
    val listenedChars: Long = 0,
    /** True while a card's audio is being synthesized and handed to AnkiDroid. */
    val isSavingCard: Boolean = false,
    /** Article only: the full page couldn't be fetched, so this is just the feed's teaser. */
    val isTruncated: Boolean = false,
    /** Article only: its address on the web, so the reader can offer to open it there. */
    val articleLink: String? = null,
    /**
     * PDF only: the pages in each section, in the PDF's own page numbering, so the page view
     * can number pages the way the PDF does. Empty for everything else.
     */
    val pdfSectionPages: List<Int> = emptyList(),
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

    /** Recently loaded chapters, so recentring the window on a neighbour is instant. */
    private val chapters = object : LinkedHashMap<Int, LoadedChapter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LoadedChapter>) = size > MAX_CACHED_CHAPTERS
    }
    private val windowLock = Mutex()
    private var nextJumpId = 1L

    private var pageCountJob: Job? = null
    private var pageCountKey: String? = null

    fun load() {
        if (isArticle) {
            loadArticle()
            return
        }
        if (document != null) return
        viewModelScope.launch {
            val saved = repo.getBook(bookId)
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
                listenedMs = saved?.listenedMs ?: 0,
                listenedChars = saved?.listenedChars ?: 0,
            )
            opened.unreadableReason?.let { reason ->
                _ui.value = _ui.value.copy(isLoading = false, error = reason)
                return@launch
            }
            source = BookContentSource(opened)
            if (opened is PdfDocument) _ui.value = _ui.value.copy(pdfSectionPages = opened.sectionPageCounts())
            // Character counts drive the time estimates; computing them walks the whole book, so
            // it runs off the critical path — but only once the book is open, since opening is
            // what clears counts left over from an older way of splitting the book.
            launch {
                val counts = runCatching { repo.chapterCharCounts(bookId) }.getOrDefault(emptyList())
                if (counts.isNotEmpty()) _ui.value = _ui.value.copy(chapterCharCounts = counts)
            }
            val chapter = (saved?.chapterIndex ?: 0).coerceIn(0, opened.sectionCount - 1)
            recenter(chapter, jumpToBlock = saved?.blockIndex ?: 0)
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
                        window = listOf(LoadedChapter(0, null, body.blocks)),
                        anchorChapter = 0,
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

    // ---- the window of chapters ----

    /** Table of contents: go to the start of [index]. */
    fun showChapter(index: Int) {
        if (isArticle) return
        viewModelScope.launch { recenter(index, jumpToBlock = 0) }
    }

    /** « and »: the chapter after or before [visibleChapter], skipping any with nothing in it. */
    fun stepChapter(visibleChapter: Int, forward: Boolean) {
        if (isArticle) return
        viewModelScope.launch {
            val target = readableFrom(visibleChapter + if (forward) 1 else -1, if (forward) 1 else -1)
                ?: return@launch
            recenter(target.index, jumpToBlock = 0)
        }
    }

    /**
     * The page the reader settled on is in [chapterIndex]. If that is a neighbour, the window
     * moves to centre on it — the pager keeps the same page on screen throughout, because
     * pages are keyed by chapter and page number.
     */
    fun onVisibleChapter(chapterIndex: Int) {
        if (isArticle || chapterIndex == _ui.value.anchorChapter) return
        viewModelScope.launch { recenter(chapterIndex, jumpToBlock = null) }
    }

    /** The voice moved on; make sure its chapter is in the window so the page can follow. */
    fun syncToChapter(chapterIndex: Int) {
        if (isArticle || _ui.value.window.any { it.index == chapterIndex }) return
        viewModelScope.launch { recenter(chapterIndex, jumpToBlock = null) }
    }

    /** Moves both views to [blockIndex] of [chapterIndex]: switching between pages and text keeps the place. */
    fun jumpTo(chapterIndex: Int, blockIndex: Int) {
        if (isArticle) return
        viewModelScope.launch { recenter(chapterIndex, jumpToBlock = blockIndex) }
    }

    fun consumeJump(id: Long) {
        if (_ui.value.jump?.id == id) _ui.value = _ui.value.copy(jump = null)
    }

    private suspend fun recenter(requested: Int, jumpToBlock: Int?) = windowLock.withLock {
        val count = document?.sectionCount ?: return
        val start = requested.coerceIn(0, count - 1)
        // Land on something readable: forwards first, as a reader moving through the book would.
        val current = readableFrom(start, 1) ?: readableFrom(start, -1) ?: loadChapter(start)
        if (current == null) {
            _ui.value = _ui.value.copy(isLoading = false, error = "Could not load this chapter.")
            return
        }
        val previous = readableFrom(current.index - 1, -1)
        val next = readableFrom(current.index + 1, 1)
        _ui.value = _ui.value.copy(
            window = listOfNotNull(previous, current, next),
            anchorChapter = current.index,
            isLoading = false,
            error = null,
            jump = if (jumpToBlock != null) {
                JumpRequest(current.index, if (current.index == start) jumpToBlock else 0, nextJumpId++)
            } else {
                _ui.value.jump
            },
        )
    }

    /** The first chapter from [start] stepping by [step] that has anything to show. */
    private suspend fun readableFrom(start: Int, step: Int): LoadedChapter? {
        val count = document?.sectionCount ?: return null
        var index = start
        var looked = 0
        while (index in 0 until count && looked < MAX_EMPTY_SKIP) {
            val chapter = loadChapter(index)
            if (chapter != null && chapter.blocks.hasReadableContent()) return chapter
            index += step
            looked++
        }
        return null
    }

    private suspend fun loadChapter(index: Int): LoadedChapter? {
        chapters[index]?.let { return it }
        val chapter = runCatching { source?.chapter(index) }.getOrNull() ?: return null
        return LoadedChapter(index, chapter.title, chapter.blocks).also { chapters[index] = it }
    }

    // ---- whole-book page counts ----

    /**
     * Counts every chapter's pages at [geometry], the same way the pager paginates, so the
     * footer can number pages across the whole book. Cached per layout in the database; a
     * rotation or font-size change starts a fresh count and abandons the stale one.
     */
    fun ensurePageCounts(geometry: PageGeometry, measurer: TextMeasurer, density: Density) {
        if (isArticle || document == null) return
        val key = geometry.key
        if (key == pageCountKey) return
        pageCountKey = key
        pageCountJob?.cancel()
        _ui.value = _ui.value.copy(pageCounts = null)
        pageCountJob = viewModelScope.launch {
            repo.pageCounts(bookId, key)?.let { cached ->
                _ui.value = _ui.value.copy(pageCounts = cached)
                return@launch
            }
            // A separate instance, so counting never contends with the pages on screen.
            val counts = repo.openBook(bookId)?.use { book ->
                withContext(Dispatchers.Default) {
                    (0 until book.sectionCount).map { index ->
                        ensureActive()
                        val blocks = runCatching { book.loadSection(index).blocks }.getOrDefault(emptyList())
                        if (!blocks.hasReadableContent()) {
                            0
                        } else {
                            paginateChapter(
                                blocks,
                                geometry.widthPx,
                                geometry.heightPx,
                                measurer,
                                density,
                                geometry.fontScale,
                            ).size
                        }
                    }
                }
            } ?: return@launch
            repo.savePageCounts(bookId, key, counts)
            if (pageCountKey == key) _ui.value = _ui.value.copy(pageCounts = counts)
        }
    }

    // ---- PDF pages ----

    /** The open PDF, for the page view to draw; null for anything else or an unreadable PDF. */
    val pdf: PdfDocument?
        get() = (document as? PdfDocument)?.takeIf { it.unreadableReason == null }

    /**
     * Where reading would start for a tap at ([x], [y]) points on [page]: the sentence under
     * it, or null when the tap is nowhere near any text. Also returns the character tapped,
     * which a flashcard needs.
     */
    suspend fun pdfPositionAt(page: Int, x: Float, y: Float): Pair<ReadingPosition, Int>? {
        val pdf = pdf ?: return null
        val chapter = pdf.sectionOfPage(page)
        val hit = pdf.geometry(chapter).hit(page, x, y) ?: return null
        val block = loadChapter(chapter)?.blocks?.getOrNull(hit.blockIndex) as? Block.Text ?: return null
        return ReadingPosition(chapter, hit.blockIndex, block.sentenceIndexAt(hit.offset)) to hit.offset
    }

    /**
     * Bytes for an image block. Book images come out of the book file; article images are
     * absolute URLs, downloaded once and then served from disk.
     */
    suspend fun imageBytes(key: String): ByteArray? =
        if (isArticle) articleImages.bytes(key) else document?.readImage(key)

    // ---- Anki cards ----

    /** The language the book declares; the user's per-book override is applied on top. */
    fun bookLocale(): Locale = articleLocale ?: document?.locale() ?: Locale.getDefault()

    /** Language of the article being read, resolved from its feed. */
    private var articleLocale: Locale? = null

    /**
     * Builds an empty card for the sentence containing [offsetInBlock] — the character the
     * user long-pressed. Returns null if that block has no speakable text.
     */
    fun draftFor(chapterIndex: Int, blockIndex: Int, offsetInBlock: Int): CardDraft? {
        val blocks = (_ui.value.window.firstOrNull { it.index == chapterIndex } ?: chapters[chapterIndex])?.blocks
            ?: return null
        val block = blocks.getOrNull(blockIndex) as? Block.Text ?: return null
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
            cardAudio.speak(draft.sentence, locale, current.voiceFor(locale.language))
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
        /** Blank spine items skipped in a row before giving up looking for text. */
        private const val MAX_EMPTY_SKIP = 40
        private const val MAX_CACHED_CHAPTERS = 8

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
