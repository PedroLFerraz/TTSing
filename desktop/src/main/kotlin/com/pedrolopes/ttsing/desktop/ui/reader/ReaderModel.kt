package com.pedrolopes.ttsing.desktop.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.epub.TocEntry
import com.pedrolopes.ttsing.data.pdf.PdfDocument
import com.pedrolopes.ttsing.desktop.App
import com.pedrolopes.ttsing.tts.BookContentSource
import com.pedrolopes.ttsing.tts.SentenceRef
import com.pedrolopes.ttsing.tts.piper.PiperCatalog
import com.pedrolopes.ttsing.tts.piper.PiperVoice
import com.pedrolopes.ttsing.ui.reader.browseFraction
import com.pedrolopes.ttsing.ui.reader.browsePosition
import com.pedrolopes.ttsing.ui.reader.hasReadableContent
import com.pedrolopes.ttsing.ui.reader.sentenceStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.util.Locale

/** Where in a chapter the page on screen begins: a block and a character in it. */
data class Anchor(val block: Int, val offset: Int) {
    companion object {
        /** Past every block: the chapter's last page, for paging back into it. */
        val END = Anchor(Int.MAX_VALUE, 0)
    }
}

/**
 * One open book: its chapters, the page on screen (as an [Anchor] in a chapter, so it survives
 * any re-layout), and the voice reading it. Pages themselves are laid out by the screen, which
 * knows the window's size.
 */
class ReaderModel(private val app: App, val bookId: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var document: BookDocument? = null
    private var source: BookContentSource? = null

    var title by mutableStateOf(app.library.book(bookId)?.title.orEmpty())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var toc by mutableStateOf<List<TocEntry>>(emptyList())
        private set
    var sectionCount by mutableStateOf(0)
        private set
    var chapter by mutableStateOf<Chapter?>(null)
        private set
    var anchor by mutableStateOf(Anchor(0, 0))
        private set
    var chapterChars by mutableStateOf<List<Int>>(emptyList())
        private set

    /** The book's own language, or the one picked for it when its file says the wrong one. */
    var locale by mutableStateOf(Locale.getDefault())
        private set

    /** The book, when it is a PDF that has text: it can then be shown as its own pages. */
    var pdf by mutableStateOf<PdfDocument?>(null)
        private set

    /** Shown in place of playing when no voice for the book's language is installed. */
    var needsVoice by mutableStateOf(false)

    val chapterIndex: Int get() = chapter?.spineIndex ?: 0

    private var loadJob: Job? = null

    init {
        app.narrator.pause()
        app.narrator.park(null)
        app.narrator.onSpoken = { ref, ms, rate -> app.library.addListening(bookId, (ms * rate).toLong(), ref.text.length.toLong()) }
        scope.launch { open() }
        scope.launch {
            app.narrator.state.distinctUntilChangedBy { it.sentence }.collect { narration ->
                val sentence = narration.sentence ?: return@collect
                if (narration.speaking) onSentence(sentence)
            }
        }
    }

    private suspend fun open() {
        val record = app.library.book(bookId)
        val opened = runCatching { app.library.open(bookId) }.getOrElse {
            error = it.message ?: "Couldn't open this book"
            return
        }
        document = opened
        source = BookContentSource(opened)
        app.library.markOpened(bookId)
        title = opened.title
        toc = opened.toc
        sectionCount = opened.sectionCount
        locale = app.settings.value.languageByBook[bookId]?.let(Locale::forLanguageTag) ?: opened.locale()
        opened.unreadableReason?.let {
            error = it
            return
        }
        pdf = opened as? PdfDocument
        val saved = record?.position ?: ReadingPosition.START
        val blocks = section(saved.chapterIndex)?.blocks
        showChapter(saved.chapterIndex, Anchor(saved.blockIndex, blocks?.let { sentenceStart(it, saved) } ?: 0))
        scope.launch { chapterChars = app.library.chapterChars(bookId, opened) }
    }

    private suspend fun section(index: Int): Chapter? =
        runCatching { source?.chapter(index) }.getOrNull()

    /**
     * Shows chapter [index] at [at]; a chapter that won't load leaves the page as it was. With
     * [save], it is the reader's own choice (the contents), so it becomes the place.
     */
    fun showChapter(index: Int, at: Anchor, save: Boolean = false) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = section(index) ?: return@launch
            chapter = loaded
            anchor = at
            if (save && !app.narrator.state.value.speaking) saveBrowsed(at)
        }
    }

    /** The next chapter with anything on it, forward or back; blank spine items are passed over. */
    fun turnChapter(forward: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            var index = chapterIndex + if (forward) 1 else -1
            while (index in 0 until sectionCount) {
                val loaded = section(index)
                if (loaded != null && loaded.blocks.hasReadableContent()) {
                    chapter = loaded
                    anchor = if (forward) Anchor(0, 0) else Anchor.END
                    if (!app.narrator.state.value.speaking) saveBrowsed(anchor)
                    return@launch
                }
                index += if (forward) 1 else -1
            }
        }
    }

    /** The reader settled on a page starting at [at]; unless the voice is reading, that is the place. */
    fun browse(at: Anchor) {
        anchor = at
        if (!app.narrator.state.value.speaking) saveBrowsed(at)
    }

    private fun saveBrowsed(at: Anchor) {
        val blocks = chapter?.blocks ?: return
        val position = browsePosition(chapterIndex, blocks, at.block.coerceAtMost(blocks.size), at.offset)
        app.library.savePosition(bookId, position, browseFraction(chapterChars, blocks, position, sectionCount))
    }

    /** The voice began [sentence]: save it, and bring its chapter on screen if it moved on. */
    private fun onSentence(sentence: SentenceRef) {
        val position = sentence.position
        if (position.chapterIndex != chapterIndex) {
            showChapter(position.chapterIndex, Anchor(position.blockIndex, sentence.startInBlock))
        }
        scope.launch {
            val blocks = section(position.chapterIndex)?.blocks
            app.library.savePosition(bookId, position, browseFraction(chapterChars, blocks, position, sectionCount))
        }
    }

    /** Text block [index] of section [section], if it is one. */
    suspend fun textBlockIn(section: Int, index: Int): Block.Text? = section(section)?.blocks?.getOrNull(index) as? Block.Text

    suspend fun readImage(key: String): ByteArray? = document?.readImage(key)

    // ---- the voice ----

    /** The voice the book is read in: the one chosen for its language, else the first installed. */
    fun voice(): PiperVoice? {
        val installed = app.voiceFolder.voices().filter { it.locale.language == locale.language }
        val chosen = app.settings.value.voiceByLanguage[locale.language]
        val order = PiperCatalog.forLanguage(locale).map { it.id }
        return installed.firstOrNull { it.id == chosen }
            ?: installed.minByOrNull { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /** Reads from [from]. With no voice installed for the book's language, asks for one instead. */
    fun play(from: ReadingPosition) {
        val src = source ?: return
        val voice = voice()
        if (voice == null) {
            needsVoice = true
            return
        }
        needsVoice = false
        app.narrator.play(src, from, voice, locale, app.settings.value.speechRate)
    }

    fun pause() = app.narrator.pause()

    /** Starts over from the sentence being read, e.g. with a new speed or voice. */
    fun restartVoice() {
        val narration = app.narrator.state.value
        if (narration.speaking) narration.sentence?.let { play(it.position) }
    }

    fun skip(forward: Boolean) {
        val src = source ?: return
        scope.launch {
            val target = app.narrator.skipTarget(src, forward) ?: return@launch
            if (app.narrator.state.value.speaking) play(target.position) else {
                app.narrator.park(target)
                if (target.position.chapterIndex != chapterIndex) {
                    showChapter(target.position.chapterIndex, Anchor(target.position.blockIndex, target.startInBlock))
                } else {
                    anchor = Anchor(target.position.blockIndex, target.startInBlock)
                }
            }
        }
    }

    fun selectVoice(voice: PiperVoice) {
        app.settings.update { it.copy(voiceByLanguage = it.voiceByLanguage + (voice.locale.language to voice.id)) }
        needsVoice = false
        restartVoice()
    }

    fun selectLanguage(tag: String) {
        app.settings.update { it.copy(languageByBook = it.languageByBook + (bookId to tag)) }
        locale = Locale.forLanguageTag(tag)
        restartVoice()
    }

    fun close() {
        app.narrator.pause()
        app.narrator.onSpoken = { _, _, _ -> }
        scope.cancel()
        val opened = document
        document = null
        // A section may still be loading on the IO pool; close once it's been let go of.
        CoroutineScope(Dispatchers.IO).launch { runCatching { opened?.close() } }
    }
}
