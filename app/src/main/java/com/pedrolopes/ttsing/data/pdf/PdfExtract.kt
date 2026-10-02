package com.pedrolopes.ttsing.data.pdf

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
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Writer

/**
 * Pulls positioned lines and pictures out of a PDF with PDFBox, for [PdfReflow]. Apart from
 * [PdfDocument] so the corpus test can run the same extraction on real books without a device.
 * Not thread-safe: callers hold the document's lock.
 */
internal object PdfExtract {

    /** Positioned lines for pages [first, end), and with [withImages] where pictures sit. */
    fun lines(pdf: PDDocument, first: Int, end: Int, withImages: Boolean = false): List<PdfPage> {
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

        /** Each drawn character's text and its left and right on the upright line. */
        private val pieces = mutableListOf<PdfText.Piece>()

        /** Characters set in a bold face, and in a fixed-pitch (code) face, on this line. */
        private var boldChars = 0
        private var monoChars = 0
        private var inkChars = 0
        private val faces = HashMap<PDFont, Face>()
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
                val unicode = position.unicode ?: return@forEach
                pieces.add(PdfText.Piece(unicode, position.xDirAdj, position.xDirAdj + position.widthDirAdj))
                if (unicode.isBlank()) return@forEach
                inkChars++
                val face = position.font?.let { font -> faces.getOrPut(font) { Face.of(font) } } ?: return@forEach
                if (face.bold) boldChars++
                if (face.mono) monoChars++
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
            val raw = text.toString().trim()
            if (raw.isNotEmpty()) {
                val size = sizes.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
                val content = PdfText.respaced(raw, pieces, size) ?: raw
                val bold = inkChars > 0 && boldChars >= inkChars * 0.8f
                val mono = inkChars > 0 && monoChars >= inkChars * 0.8f
                lines.add(PdfLine(content, lineX, lineY, lineRight, size, glyphs, bold, mono))
            }
            resetLine()
        }

        private fun resetLine() {
            text = StringBuilder()
            lineRight = 0f
            pieces.clear()
            boldChars = 0
            monoChars = 0
            inkChars = 0
            sizes.clear()
            glyphs = mutableListOf()
        }
    }

    /** What a font says about how its text is set: bold, and fixed-pitch as code is. */
    private class Face(val bold: Boolean, val mono: Boolean) {
        companion object {
            private val boldName = Regex("(?i)bold|black|heavy|semibold|demi")
            private val monoName = Regex("(?i)mono|courier|consol|typewriter|code|cmtt|inconsolata|menlo|monaco")

            fun of(font: PDFont): Face {
                // A subset font is named "ABCDEF+RealName".
                val name = font.name.orEmpty().substringAfter('+')
                val descriptor = runCatching { font.fontDescriptor }.getOrNull()
                val bold = boldName.containsMatchIn(name) ||
                    (descriptor?.fontWeight ?: 0f) >= 600f || descriptor?.isForceBold == true
                val mono = monoName.containsMatchIn(name) || descriptor?.isFixedPitch == true
                return Face(bold, mono)
            }
        }
    }

    /**
     * A figure's page and rectangle, in top-down points from the page's top-left corner —
     * all the renderer needs, so the key can travel through the reader as a plain string.
     */
    internal data class FigureKey(val page: Int, val left: Float, val top: Float, val width: Float, val height: Float) {
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
