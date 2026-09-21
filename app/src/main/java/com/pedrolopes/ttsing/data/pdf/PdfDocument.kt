package com.pedrolopes.ttsing.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.TocEntry
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Writer
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
 * PDFBox is not thread-safe, so every call into it holds [lock].
 */
class PdfDocument private constructor(
    private val pdf: PDDocument,
    override val title: String,
    override val author: String?,
    override val language: String?,
    private val sections: List<Section>,
    override val unreadableReason: String?,
) : BookDocument {

    private data class Section(val title: String, val firstPage: Int, val endPage: Int)

    private val lock = Any()

    /** Running headers/footers and body text size across the whole book, sampled once. */
    private var bookSample: BookSample? = null

    private class BookSample(val furniture: Set<String>, val bodySize: Float?)

    override val sectionCount: Int get() = sections.size

    override val toc: List<TocEntry> = sections.mapIndexed { index, section -> TocEntry(section.title, index) }

    override suspend fun loadSection(index: Int, locale: Locale): Chapter = withContext(Dispatchers.IO) {
        val section = sections.getOrNull(index)
            ?: throw IllegalArgumentException("Section $index out of range")
        val blocks = synchronized(lock) {
            val pages = extract(pdf, section.firstPage, section.endPage)
            val book = sample()
            // The book's running headers, plus anything that repeats within this section only
            // (a chapter title used as its own running header).
            val furniture = book.furniture + PdfReflow.detectFurniture(pages)
            PdfReflow.toBlocks(pages, furniture, locale, book.bodySize)
        }
        Chapter(index, section.title, blocks)
    }

    /** PDFs are read as text; their figures are not carried over. */
    override suspend fun readImage(key: String): ByteArray? = null

    override fun close() = synchronized(lock) { pdf.close() }

    private fun sample(): BookSample {
        bookSample?.let { return it }
        val count = pdf.numberOfPages
        val step = maxOf(1, count / SAMPLE_PAGES)
        val pages = (0 until count step step).take(SAMPLE_PAGES).flatMap { extract(pdf, it, it + 1) }
        return BookSample(PdfReflow.detectFurniture(pages), PdfReflow.bodyFontSize(pages))
            .also { bookSample = it }
    }

    companion object {
        const val PAGES_PER_SECTION = 10
        private const val SAMPLE_PAGES = 40

        /** Fewer non-space characters than this per page, on average, means no text layer. */
        private const val SCANNED_CHARS_PER_PAGE = 20

        /** Opens [file]; [fallbackTitle] is used when the PDF's metadata has no title. */
        fun open(file: File, fallbackTitle: String): PdfDocument {
            val pdf = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
            try {
                val info = pdf.documentInformation
                val title = PdfMetadata.title(info?.title, fallbackTitle)
                val author = PdfMetadata.author(info?.author)
                val language = pdf.documentCatalog?.language?.trim()?.takeIf { it.isNotEmpty() }
                val sections = outlineSections(pdf) ?: pageRunSections(pdf.numberOfPages)
                return PdfDocument(pdf, title, author, language, sections, scannedReason(pdf))
            } catch (e: Exception) {
                pdf.close()
                throw e
            }
        }

        /**
         * Renders the first page as a cover image, with the platform renderer rather than
         * PDFBox: it is fast, and exactly what the page looks like.
         */
        fun renderCover(file: File, widthPx: Int = 600): ByteArray? = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val heightPx = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                        // PDF pages are transparent where nothing is drawn; paper is white.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            bitmap.recycle()
                            out.toByteArray()
                        }
                    }
                }
            }
        }.getOrNull()

        /** Top-level bookmarks as sections, or null when there are too few to be useful. */
        private fun outlineSections(pdf: PDDocument): List<Section>? {
            val outline = pdf.documentCatalog?.documentOutline ?: return null
            val marks = outline.children().mapNotNull { item ->
                val page = runCatching { item.findDestinationPage(pdf) }.getOrNull() ?: return@mapNotNull null
                val index = pdf.pages.indexOf(page)
                if (index < 0) null else (item.title?.trim().orEmpty()) to index
            }
                .sortedBy { it.second }
                .distinctBy { it.second }
            if (marks.size < 2) return null

            val starts = if (marks.first().second > 0) listOf("Start" to 0) + marks else marks
            return starts.mapIndexed { i, (title, first) ->
                val end = starts.getOrNull(i + 1)?.second ?: pdf.numberOfPages
                Section(title.ifEmpty { "Section ${i + 1}" }, first, end)
            }.filter { it.endPage > it.firstPage }
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

        /** Positioned lines for pages [first, end). */
        private fun extract(pdf: PDDocument, first: Int, end: Int): List<PdfPage> {
            if (end <= first) return emptyList()
            val collector = LineCollector()
            collector.startPage = first + 1
            collector.endPage = end
            collector.writeText(pdf, NullWriter)
            return collector.pages
        }
    }

    /**
     * Collects each line PDFBox finds, with where it sits and how big it is, instead of the
     * flat string [PDFTextStripper] normally produces. Content order rather than sorting by
     * position, because producers write columns in reading order and sorting interleaves them.
     */
    private class LineCollector : PDFTextStripper() {
        val pages = mutableListOf<PdfPage>()
        private var lines = mutableListOf<PdfLine>()
        private var text = StringBuilder()
        private var lineX = 0f
        private var lineY = 0f
        private var lineRight = 0f
        private val sizes = mutableListOf<Float>()
        private var pageWidth = 0f
        private var pageHeight = 0f

        init {
            sortByPosition = false
            suppressDuplicateOverlappingText = true
        }

        override fun startPage(page: PDPage) {
            super.startPage(page)
            lines = mutableListOf()
            resetLine()
            val box = page.cropBox
            val sideways = page.rotation % 180 != 0
            pageWidth = if (sideways) box.height else box.width
            pageHeight = if (sideways) box.width else box.height
        }

        override fun writeString(string: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isEmpty() || string.isEmpty()) return
            val first = textPositions.first()
            val last = textPositions.last()
            if (text.isEmpty()) {
                lineX = first.xDirAdj
                lineY = first.yDirAdj
            }
            text.append(string)
            lineRight = maxOf(lineRight, last.xDirAdj + last.widthDirAdj)
            textPositions.forEach { position ->
                val size = position.fontSizeInPt.takeIf { it > 1f } ?: position.heightDir
                if (size > 0f) sizes.add(size)
            }
        }

        override fun writeWordSeparator() {
            if (text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')
        }

        override fun writeLineSeparator() = finishLine()

        override fun endPage(page: PDPage) {
            finishLine()
            pages.add(PdfPage(currentPageNo - 1, pageWidth, pageHeight, lines))
            super.endPage(page)
        }

        private fun finishLine() {
            val content = text.toString().trim()
            if (content.isNotEmpty()) {
                val size = sizes.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
                lines.add(PdfLine(content, lineX, lineY, lineRight, size))
            }
            resetLine()
        }

        private fun resetLine() {
            text = StringBuilder()
            lineRight = 0f
            sizes.clear()
        }
    }

    private object NullWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }
}
