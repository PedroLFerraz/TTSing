package com.pedrolopes.ttsing.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.TocEntry
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
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
 * Pictures come back as image blocks between paragraphs: PDFBox says where each one is drawn,
 * and the platform renderer draws that rectangle of the page when the reader asks for it — so
 * a figure looks exactly as it does in the PDF, masks and all, whatever format it is stored in.
 *
 * The same reflow also feeds the page view, which shows the pages themselves: each block
 * remembers where its characters are printed ([SectionGeometry]), so the sentence being read
 * can be marked on the page while positions, the voice and flashcards still work on blocks.
 *
 * PDFBox is not thread-safe, so every call into it holds [lock]; the renderer has its own.
 */
class PdfDocument private constructor(
    private val file: File,
    private val pdf: PDDocument,
    override val title: String,
    override val author: String?,
    override val language: String?,
    private val sections: List<Section>,
    /** Every page's size in points as displayed: cropped, and turned by its rotation. */
    private val pageSizes: List<PageSize>,
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
        val reflowed = synchronized(lock) {
            val pages = extract(pdf, section.firstPage, section.endPage, withImages = true)
            val book = sample()
            // The book's running headers, plus anything that repeats within this section only
            // (a chapter title used as its own running header).
            val furniture = book.furniture + PdfReflow.detectFurniture(pages)
            PdfReflow.reflow(pages, furniture, locale, book.bodySize)
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

    /** Pages drawn lately, by page and width; bounded by memory rather than count. */
    private val pageBitmaps = object : android.util.LruCache<String, Bitmap>(PAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Draws [page] [widthPx] wide, on white, exactly as the PDF looks. */
    suspend fun renderPage(page: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$page@$widthPx"
        pageBitmaps.get(key)?.let { return@withContext it }
        synchronized(renderLock) {
            runCatching {
                val pages = openRenderer()
                if (page !in 0 until pages.pageCount) return@runCatching null
                pages.openPage(page).use { p ->
                    val heightPx = (widthPx.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.getOrNull()
        }?.also { pageBitmaps.put(key, it) }
    }

    private fun openRenderer(): PdfRenderer = renderer ?: run {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        rendererFile = fd
        PdfRenderer(fd).also { renderer = it }
    }

    // ---- figures ----

    private val renderLock = Any()
    private var renderer: PdfRenderer? = null
    private var rendererFile: ParcelFileDescriptor? = null

    /** The last few figures drawn, so paging back and forth doesn't redraw them. */
    private val rendered = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 12
    }

    override suspend fun readImage(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val figure = FigureKey.parse(key) ?: return@withContext null
        synchronized(renderLock) {
            rendered[key]?.let { return@withContext it }
            renderFigure(figure)?.also { rendered[key] = it }
        }
    }

    /** Draws just [figure]'s rectangle of its page, at a size that stays sharp on a phone. */
    private fun renderFigure(figure: FigureKey): ByteArray? = runCatching {
        val pages = openRenderer()
        if (figure.page !in 0 until pages.pageCount) return null
        pages.openPage(figure.page).use { page ->
            val scale = (FIGURE_WIDTH_PX / figure.width).coerceIn(1f, 4f)
            val widthPx = (figure.width * scale).toInt().coerceAtLeast(1)
            val heightPx = (figure.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val transform = android.graphics.Matrix().apply {
                setTranslate(-figure.left, -figure.top)
                postScale(scale, scale)
            }
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }.getOrNull()

    override fun close() {
        synchronized(lock) { pdf.close() }
        synchronized(renderLock) {
            runCatching { renderer?.close() }
            runCatching { rendererFile?.close() }
            renderer = null
            rendererFile = null
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

        /** A few phone-width pages: enough to scroll back and forth without redrawing. */
        private const val PAGE_CACHE_BYTES = 48 * 1024 * 1024

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
                val pageSizes = pdf.pages.map { page ->
                    val box = page.cropBox
                    if (page.rotation % 180 != 0) PageSize(box.height, box.width) else PageSize(box.width, box.height)
                }
                return PdfDocument(file, pdf, title, author, language, sections, pageSizes, scannedReason(pdf))
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
            fun mark(item: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem): Pair<String, Int>? {
                val page = runCatching { item.findDestinationPage(pdf) }.getOrNull() ?: return null
                val index = pdf.pages.indexOf(page)
                return if (index < 0) null else (item.title?.trim().orEmpty()) to index
            }
            // A book's top level is often its Parts, each a hundred pages; its chapters are the
            // next level down. So a top-level entry with children gives way to them: the part
            // keeps only its own opening pages, and each chapter is a section.
            val marks = outline.children().flatMap { item ->
                listOfNotNull(mark(item)) + item.children().mapNotNull { mark(it) }
            }
                .sortedBy { it.second }
                // Where a part and its first chapter open on the same page, the chapter names it.
                .groupBy { it.second }
                .map { (_, same) -> same.last() }
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

        /** Figures are drawn about this wide: sharp on a phone, light enough to keep a few. */
        private const val FIGURE_WIDTH_PX = 1200f

        /** Positioned lines for pages [first, end), and with [withImages] where pictures sit. */
        private fun extract(pdf: PDDocument, first: Int, end: Int, withImages: Boolean = false): List<PdfPage> {
            if (end <= first) return emptyList()
            val collector = LineCollector()
            collector.startPage = first + 1
            collector.endPage = end
            collector.writeText(pdf, NullWriter)
            if (!withImages) return collector.pages
            return collector.pages.map { page ->
                val images = runCatching { ImageLocator(page.index).locate(pdf.getPage(page.index)) }
                    .getOrDefault(emptyList())
                page.copy(images = images)
            }
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
        private var glyphs = mutableListOf<Glyph>()
        private var pageWidth = 0f
        private var pageHeight = 0f

        /** The page's crop box size before its rotation, and the rotation it is displayed with. */
        private var storedWidth = 0f
        private var storedHeight = 0f
        private var rotation = 0

        init {
            sortByPosition = false
            suppressDuplicateOverlappingText = true
        }

        /**
         * Reads a page turned by /Rotate as if it were upright. PDFBox lays a turned page's
         * text out along the turned axes, which splits every line into single letters; read
         * upright, its lines come out whole, and only the glyph boxes are turned to match the
         * page as displayed.
         */
        override fun processPage(page: PDPage) {
            rotation = ((page.rotation % 360) + 360) % 360
            if (rotation == 0) return super.processPage(page)
            page.rotation = 0
            try {
                super.processPage(page)
            } finally {
                page.rotation = rotation
            }
        }

        override fun startPage(page: PDPage) {
            super.startPage(page)
            lines = mutableListOf()
            resetLine()
            // Lines are measured on the upright page (see processPage), so the page is too.
            val box = page.cropBox
            storedWidth = box.width
            storedHeight = box.height
            pageWidth = box.width
            pageHeight = box.height
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
                addGlyphs(position, size)
            }
        }

        /**
         * The position's characters with their boxes as displayed. PDFBox measures from the
         * crop box's top-left before the page's rotation, so a rotated page's boxes are turned
         * with it. The box spans the line's ascent and descent rather than the ink, so a marked
         * word reads as one band rather than letters of different heights.
         */
        private fun addGlyphs(position: TextPosition, size: Float) {
            val height = if (size > 0f) size else return
            val left = position.xDirAdj
            val right = left + position.widthDirAdj
            val top = position.yDirAdj - height * 0.8f
            val bottom = position.yDirAdj + height * 0.22f
            val (l, t, r, b) = when (rotation) {
                90 -> listOf(storedHeight - bottom, left, storedHeight - top, right)
                180 -> listOf(storedWidth - right, storedHeight - bottom, storedWidth - left, storedHeight - top)
                270 -> listOf(top, storedWidth - right, bottom, storedWidth - left)
                else -> listOf(left, top, right, bottom)
            }
            glyphs.addAll(Glyph.split(position.unicode ?: return, l, t, r, b))
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
                lines.add(PdfLine(content, lineX, lineY, lineRight, size, glyphs))
            }
            resetLine()
        }

        private fun resetLine() {
            text = StringBuilder()
            lineRight = 0f
            sizes.clear()
            glyphs = mutableListOf()
        }
    }

    /**
     * A figure's page and rectangle, in top-down points from the page's top-left corner —
     * all the renderer needs, so the key can travel through the reader as a plain string.
     */
    private data class FigureKey(val page: Int, val left: Float, val top: Float, val width: Float, val height: Float) {
        fun encode(): String = "$PREFIX$page:$left:$top:$width:$height"

        companion object {
            private const val PREFIX = "pdf-figure:"

            fun parse(key: String): FigureKey? {
                if (!key.startsWith(PREFIX)) return null
                val parts = key.removePrefix(PREFIX).split(':')
                if (parts.size != 5) return null
                val page = parts[0].toIntOrNull() ?: return null
                val numbers = parts.drop(1).map { it.toFloatOrNull() ?: return null }
                return FigureKey(page, numbers[0], numbers[1], numbers[2], numbers[3])
            }
        }
    }

    /**
     * Walks a page's drawing instructions and notes where each picture lands. A picture is
     * drawn as a unit square stretched by the current transformation, so its rectangle on the
     * page is that square's four corners after the transform; pictures inside form objects
     * are followed in.
     */
    private class ImageLocator(private val pageIndex: Int) : PDFStreamEngine() {
        private val found = mutableListOf<PdfImage>()
        private var cropLeft = 0f
        private var cropTop = 0f

        init {
            addOperator(Concatenate())
            addOperator(DrawObject())
            addOperator(SetGraphicsStateParameters())
            addOperator(Save())
            addOperator(Restore())
            addOperator(SetMatrix())
        }

        fun locate(page: PDPage): List<PdfImage> {
            val crop = page.cropBox
            cropLeft = crop.lowerLeftX
            cropTop = crop.upperRightY
            processPage(page)
            return found
        }

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (operator.name != "Do") {
                super.processOperator(operator, operands)
                return
            }
            val name = operands.firstOrNull() as? COSName ?: return
            when (val xObject = resources?.getXObject(name)) {
                is PDImageXObject -> {
                    val m = graphicsState.currentTransformationMatrix
                    val xs = listOf(0f, m.scaleX, m.shearX, m.scaleX + m.shearX).map { it + m.translateX }
                    val ys = listOf(0f, m.shearY, m.scaleY, m.shearY + m.scaleY).map { it + m.translateY }
                    val left = xs.min() - cropLeft
                    val top = cropTop - ys.max()
                    val width = xs.max() - xs.min()
                    val height = ys.max() - ys.min()
                    val key = FigureKey(pageIndex, left, top, width, height).encode()
                    found.add(PdfImage(key, left, top, width, height))
                }
                is PDFormXObject -> showForm(xObject)
                else -> Unit
            }
        }
    }

    private object NullWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }
}
