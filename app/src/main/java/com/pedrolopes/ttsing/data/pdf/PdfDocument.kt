package com.pedrolopes.ttsing.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pedrolopes.ttsing.data.book.BookDocument
import com.pedrolopes.ttsing.data.pdf.PdfExtract.FigureKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A PDF on Android: its text through [PdfBook], and its pages and figures drawn by the
 * platform renderer — a figure looks exactly as it does in the PDF, masks and all, whatever
 * format it is stored in. The renderer has its own lock; [PdfBook] has another.
 */
class PdfDocument private constructor(
    private val file: File,
    private val book: PdfBook,
) : BookDocument by book {

    // ---- pages, for the page view ----

    val pageCount: Int get() = book.pageCount

    fun pageSize(page: Int): PdfBook.PageSize = book.pageSize(page)

    fun sectionOfPage(page: Int): Int = book.sectionOfPage(page)

    fun firstPageOf(section: Int): Int = book.firstPageOf(section)

    fun sectionPageCounts(): List<Int> = book.sectionPageCounts()

    suspend fun geometry(section: Int): SectionGeometry = book.geometry(section)

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
        book.close()
        synchronized(renderLock) {
            runCatching { renderer?.close() }
            runCatching { rendererFile?.close() }
            renderer = null
            rendererFile = null
        }
    }

    companion object {
        /** A few phone-width pages: enough to scroll back and forth without redrawing. */
        private const val PAGE_CACHE_BYTES = 48 * 1024 * 1024

        /** Opens [file]; [fallbackTitle] is used when the PDF's metadata has no title. */
        fun open(file: File, fallbackTitle: String): PdfDocument = PdfDocument(file, PdfBook.open(file, fallbackTitle))

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

        /** Figures are drawn about this wide: sharp on a phone, light enough to keep a few. */
        private const val FIGURE_WIDTH_PX = 1200f
    }
}
