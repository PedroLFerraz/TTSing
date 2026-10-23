package com.pedrolopes.ttsing.data.pdf

import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.TocEntry
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * A PDF as a [BookDocument], read as reflowed text: PDFBox pulls out every line with its
 * position and size, and [PdfReflow] turns those back into paragraphs and headings, so a PDF
 * gets the same font size, themes, tap-to-read and highlighting as an EPUB.
 *
 * Sections follow the PDF's own bookmarks when it has them; otherwise they are runs of
 * [PAGES_PER_SECTION] pages. Each section is extracted only when first asked for, so opening a
 * long PDF does not wait for all of it.
 *
 * Pictures come back as image blocks between paragraphs, keyed by where PDFBox says each one
 * is drawn; drawing them, and the pages themselves, is the platform's job — see [PdfDocument].
 * Each block also remembers where its characters are printed ([SectionGeometry]), so a page
 * view can mark the sentence being read on the page itself.
 *
 * Only PDFBox is used here, nothing of the platform: the Linux app builds this same file
 * against Apache PDFBox, whose API PDFBox-Android shares under another package name.
 *
 * PDFBox is not thread-safe, so every call into it holds [lock].
 */
class PdfBook private constructor(
    private val pdf: PDDocument,
    override val title: String,
    override val author: String?,
    override val language: String?,
    private val sections: List<Section>,
    /** Every page's size in points as displayed: cropped, and turned by its rotation. */
    private val pageSizes: List<PageSize>,
    override val unreadableReason: String?,
    /** Bookmark titles, for telling running heads from text ([PdfReflow.titleKey]). */
    private val titles: Set<String>,
) : BookDocument {

    internal data class Section(val title: String, val firstPage: Int, val endPage: Int)

    private val lock = Any()

    /** Set under [lock] by [close], so a load queued behind it doesn't read a closed file. */
    private var closed = false

    /** Running headers/footers and body text size across the whole book, sampled once. */
    private var bookSample: BookSample? = null

    private class BookSample(val furniture: Set<String>, val bodySize: Float?)

    override val sectionCount: Int get() = sections.size

    override val toc: List<TocEntry> = sections.mapIndexed { index, section -> TocEntry(section.title, index) }

    override fun skipsWhenPlayingOn(section: Int): Boolean =
        sections.getOrNull(section)?.let { PdfMetadata.isApparatus(it.title) } == true

    override suspend fun loadSection(index: Int, locale: Locale): Chapter = withContext(Dispatchers.IO) {
        val section = sections.getOrNull(index)
            ?: throw IllegalArgumentException("Section $index out of range")
        val reflowed = synchronized(lock) {
            // Leaving a book closes it while a chapter may still be waiting here; that load is
            // as good as cancelled, and must not crash the app reading a closed file.
            if (closed) throw CancellationException("The PDF was closed")
            val pages = extract(pdf, section.firstPage, section.endPage, withImages = true)
            val book = sample()
            // The book's running headers, plus anything that repeats within this section only
            // (a chapter title used as its own running header).
            val furniture = book.furniture + PdfReflow.detectFurniture(pages)
            PdfReflow.reflow(pages, furniture, locale, book.bodySize, titles)
        }
        synchronized(geometries) { geometries[index] = reflowed.geometry }
        Chapter(index, section.title, reflowed.blocks)
    }

    // ---- pages, for the page view ----

    /** Where each section's text sits on its pages, for the last few sections loaded. */
    private val geometries = object : LinkedHashMap<Int, SectionGeometry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SectionGeometry>) = size > 8
    }

    // Page sizes are read when the file opens, not here: the page view asks for them on the
    // main thread, which must never wait on [lock] while a section is being extracted.
    val pageCount: Int get() = pageSizes.size

    fun pageSize(page: Int): PageSize = pageSizes.getOrElse(page) { PageSize(612f, 792f) }

    /** The section [page] belongs to: sections are runs of whole pages. */
    fun sectionOfPage(page: Int): Int = sections.indexOfLast { it.firstPage <= page }.coerceAtLeast(0)

    fun firstPageOf(section: Int): Int = sections.getOrNull(section)?.firstPage ?: 0

    fun sectionPageCounts(): List<Int> = sections.map { it.endPage - it.firstPage }

    /** [section]'s text positions, reading the section if it hasn't been read lately. */
    suspend fun geometry(section: Int): SectionGeometry {
        synchronized(geometries) { geometries[section] }?.let { return it }
        loadSection(section, locale())
        return synchronized(geometries) { geometries[section] } ?: SectionGeometry(emptyList())
    }

    /** Text only: figures are drawn by each platform's [PdfDocument], which wraps this. */
    override suspend fun readImage(key: String): ByteArray? = null

    override fun close() {
        synchronized(lock) {
            closed = true
            pdf.close()
        }
    }

    private fun sample(): BookSample {
        bookSample?.let { return it }
        val count = pdf.numberOfPages
        val step = maxOf(1, count / SAMPLE_PAGES)
        val pages = (0 until count step step).take(SAMPLE_PAGES).flatMap { extract(pdf, it, it + 1) }
        return BookSample(PdfReflow.detectFurniture(pages), PdfReflow.bodyFontSize(pages))
            .also { bookSample = it }
    }

    /** A page's size in points, as displayed. */
    data class PageSize(val width: Float, val height: Float)

    companion object {
        const val PAGES_PER_SECTION = 10
        private const val SAMPLE_PAGES = 40

        /** Fewer non-space characters than this per page, on average, means no text layer. */
        private const val SCANNED_CHARS_PER_PAGE = 20

        /** Opens [file]; [fallbackTitle] is used when the PDF's metadata has no title. */
        fun open(file: File, fallbackTitle: String): PdfBook {
            val pdf = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
            try {
                val info = pdf.documentInformation
                val title = PdfMetadata.title(info?.title, fallbackTitle)
                val author = PdfMetadata.author(info?.author)
                val language = PdfMetadata.language(pdf.documentCatalog?.language)
                val sections = sections(pdf)
                val pageSizes = pdf.pages.map { page ->
                    val box = page.cropBox
                    if (page.rotation % 180 != 0) PageSize(box.height, box.width) else PageSize(box.width, box.height)
                }
                return PdfBook(pdf, title, author, language, sections, pageSizes, scannedReason(pdf), outlineTitles(pdf))
            } catch (e: Exception) {
                pdf.close()
                throw e
            }
        }

        /** The PDF's sections: its bookmarks, or runs of pages when it has too few. */
        internal fun sections(pdf: PDDocument): List<Section> =
            outlineSections(pdf) ?: pageRunSections(pdf.numberOfPages)

        /**
         * Bookmarks as sections, or null when there are too few to be useful. A section should
         * be a chapter: long enough to settle into, short enough to find your way in.
         */
        private fun outlineSections(pdf: PDDocument): List<Section>? {
            val outline = pdf.documentCatalog?.documentOutline ?: return null
            val pageCount = pdf.numberOfPages
            fun mark(item: PDOutlineItem): Pair<String, Int>? {
                val page = runCatching { item.findDestinationPage(pdf) }.getOrNull() ?: return null
                val index = pdf.pages.indexOf(page)
                return if (index < 0) null else (item.title?.trim().orEmpty()) to index
            }
            val top = outline.children().map { item -> mark(item) to item.children().mapNotNull { mark(it) }.sortedBy { it.second } }
                .filter { (own, children) -> own != null || children.isNotEmpty() }
            val starts = top.map { (own, children) -> own?.second ?: children.first().second }
            // A book's top level is often its Parts, each a hundred pages, with the chapters
            // one level down; then each chapter is a section and the part keeps only its own
            // opening pages. But when the top level already is the chapters, the level below
            // is their sub-headings, a page or two each, and the chapter stays whole.
            val marks = top.flatMapIndexed { i, (own, children) ->
                val next = starts.drop(i + 1).firstOrNull { it > starts[i] } ?: pageCount
                val childPages = if (children.isEmpty()) 0f else (next - children.first().second).toFloat() / children.size
                val descend = children.isNotEmpty() &&
                    (own == null || partTitle.containsMatchIn(own.first) || childPages >= MIN_CHAPTER_PAGES)
                listOfNotNull(own) + if (descend) children else emptyList()
            }
                .sortedBy { it.second }
                // Where a part and its first chapter open on the same page, the chapter names it.
                .groupBy { it.second }
                .map { (_, same) -> same.last() }
            if (marks.size < 2) return null

            val sections = if (marks.first().second > 0) listOf("Start" to 0) + marks else marks
            return sections.mapIndexed { i, (title, first) ->
                val end = sections.getOrNull(i + 1)?.second ?: pageCount
                Section(title.ifEmpty { "Section ${i + 1}" }, first, end)
            }.filter { it.endPage > it.firstPage }
        }

        private val partTitle = Regex("^(part|parte|book|livro|volume|tomo)\\b", RegexOption.IGNORE_CASE)

        /** Sub-entries shorter than this, on average, are a chapter's headings, not chapters. */
        private const val MIN_CHAPTER_PAGES = 8f

        /**
         * Every bookmark title, at any depth, as [PdfReflow.titleKey] writes it: running heads
         * repeat these, and that is how they are told from the text.
         */
        internal fun outlineTitles(pdf: PDDocument): Set<String> {
            val outline = pdf.documentCatalog?.documentOutline ?: return emptySet()
            val titles = HashSet<String>()
            fun walk(node: PDOutlineNode, depth: Int) {
                for (item in node.children()) {
                    item.title?.let { PdfReflow.titleKey(it) }?.takeIf { it.length >= 3 }?.let { titles.add(it) }
                    if (depth < 4) walk(item, depth + 1)
                }
            }
            runCatching { walk(outline, 0) }
            return titles
        }

        private fun pageRunSections(pageCount: Int): List<Section> =
            (0 until maxOf(pageCount, 1) step PAGES_PER_SECTION).map { first ->
                val end = minOf(first + PAGES_PER_SECTION, pageCount)
                val title = if (end - first <= 1) "Page ${first + 1}" else "Pages ${first + 1}–$end"
                Section(title, first, maxOf(end, first + 1))
            }

        private fun scannedReason(pdf: PDDocument): String? {
            val checked = minOf(pdf.numberOfPages, 5)
            if (checked == 0) return "This PDF has no pages."
            val chars = extract(pdf, 0, checked).sumOf { page -> page.lines.sumOf { line -> line.text.count { !it.isWhitespace() } } }
            return if (chars < checked * SCANNED_CHARS_PER_PAGE) {
                "This PDF is scanned pages with no text layer, so there is nothing to read aloud."
            } else {
                null
            }
        }

        private fun extract(pdf: PDDocument, first: Int, end: Int, withImages: Boolean = false): List<PdfPage> =
            PdfExtract.lines(pdf, first, end, withImages)
    }
}
