package com.pedrolopes.ttsing.data.pdf

import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.pdf.PdfExtract.FigureKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * A PDF on the desktop: its text through [PdfBook], built from the Android app's own file, and
 * its pages, figures and cover drawn by Apache PDFBox's renderer, so the page view shows the
 * PDF as it is and a figure looks as it does in the PDF whatever format it is stored in.
 * Rendering reads its own copy of the file under its own lock; [PdfBook] has another.
 */
class PdfDocument private constructor(
    private val file: File,
    private val book: PdfBook,
) : BookDocument by book {

    private val renderLock = Any()
    private var renderDoc: PDDocument? = null
    private var renderer: PDFRenderer? = null

    // ---- pages, for the page view ----

    val pageCount: Int get() = book.pageCount

    fun pageSize(page: Int): PdfBook.PageSize = book.pageSize(page)

    fun sectionOfPage(page: Int): Int = book.sectionOfPage(page)

    fun firstPageOf(section: Int): Int = book.firstPageOf(section)

    fun sectionPageCounts(): List<Int> = book.sectionPageCounts()

    suspend fun geometry(section: Int): SectionGeometry = book.geometry(section)

    /** Pages drawn lately, by page and width; a few screens' worth. */
    private val pages = object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>) = size > PAGE_CACHE
    }

    /** Draws [page] [widthPx] wide, on white, exactly as the PDF looks. */
    suspend fun renderPage(page: Int, widthPx: Int): BufferedImage? = withContext(Dispatchers.IO) {
        val key = "$page@$widthPx"
        synchronized(renderLock) {
            pages[key]?.let { return@withContext it }
            runCatching {
                val pdf = renderDoc ?: load(file).also { renderDoc = it }
                if (page !in 0 until pdf.numberOfPages) return@runCatching null
                val drawer = renderer ?: PDFRenderer(pdf).also { renderer = it }
                drawer.renderImage(page, widthPx / pageSize(page).width.coerceAtLeast(1f), ImageType.RGB)
            }.getOrNull()?.also { pages[key] = it }
        }
    }

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

    /** Draws just [figure]'s rectangle of its page, sharp at reading size. */
    private fun renderFigure(figure: FigureKey): ByteArray? = runCatching {
        val pdf = renderDoc ?: load(file).also { renderDoc = it }
        if (figure.page !in 0 until pdf.numberOfPages) return null
        val scale = (FIGURE_WIDTH_PX / figure.width).coerceIn(1f, 4f)
        val page = (renderer ?: PDFRenderer(pdf).also { renderer = it }).renderImage(figure.page, scale, ImageType.RGB)
        val left = (figure.left * scale).toInt().coerceIn(0, page.width - 1)
        val top = (figure.top * scale).toInt().coerceIn(0, page.height - 1)
        val width = (figure.width * scale).toInt().coerceIn(1, page.width - left)
        val height = (figure.height * scale).toInt().coerceIn(1, page.height - top)
        page.getSubimage(left, top, width, height).encode("jpg")
    }.getOrNull()

    override fun close() {
        book.close()
        synchronized(renderLock) {
            runCatching { renderDoc?.close() }
            renderDoc = null
            renderer = null
            pages.clear()
        }
    }

    companion object {
        /** Figures are drawn about this wide: sharp in a reading column, light enough to keep a few. */
        private const val FIGURE_WIDTH_PX = 1200f

        /** Rendered pages kept: a couple of screens either way at any zoom. */
        private const val PAGE_CACHE = 16

        /** Opens [file]; [fallbackTitle] is used when the PDF's metadata has no title. */
        fun open(file: File, fallbackTitle: String): PdfDocument = PdfDocument(file, PdfBook.open(file, fallbackTitle))

        /** The first page, [widthPx] wide, as a PNG for the library's cover. */
        fun renderCover(file: File, widthPx: Int = 600): ByteArray? = runCatching {
            load(file).use { pdf ->
                if (pdf.numberOfPages == 0) return null
                val box = pdf.getPage(0).cropBox
                val scale = widthPx / box.width.coerceAtLeast(1f)
                PDFRenderer(pdf).renderImage(0, scale, ImageType.RGB).encode("png")
            }
        }.getOrNull()

        private fun load(file: File): PDDocument = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

        private fun BufferedImage.encode(format: String): ByteArray =
            ByteArrayOutputStream().use { out ->
                ImageIO.write(this, format, out)
                out.toByteArray()
            }
    }
}
