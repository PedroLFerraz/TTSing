package com.pedrolopes.ttsing.data.pdf

import java.text.Normalizer
import kotlin.math.abs

/**
 * One character's box on its page, in points from the page's top-left corner as the page is
 * displayed — after its crop and its rotation, so it lines up with the rendered page.
 */
data class Glyph(val char: Char, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        /**
         * The characters one drawn glyph stands for, each with its share of the glyph's width:
         * a ligature "ﬁ" is two characters, "f" over its left half and "i" over its right.
         */
        fun split(unicode: String, left: Float, top: Float, right: Float, bottom: Float): List<Glyph> {
            val form = if (unicode.any { it in 'ﬀ'..'ﬆ' }) Normalizer.Form.NFKC else Normalizer.Form.NFC
            val chars = Normalizer.normalize(unicode, form)
            if (chars.isEmpty()) return emptyList()
            val step = (right - left) / chars.length
            return chars.mapIndexed { i, c -> Glyph(c, left + step * i, top, left + step * (i + 1), bottom) }
        }
    }
}

/** A glyph and the page it is on, as a paragraph collects them across a page break. */
data class PagedGlyph(val page: Int, val glyph: Glyph)

/** A rectangle on a page, in the same top-left points as [Glyph]. */
data class PageRect(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Where each character of a block's text sits on the page, so the reader can mark a spoken
 * range on the page itself. Characters the reflow added (the space between two joined lines)
 * have no box; everything the page actually prints does.
 *
 * Kept as flat arrays: a long section holds tens of thousands of characters.
 */
class TextGeometry private constructor(
    /** Page per character of the text, or -1 where the character has no box. */
    private val pages: IntArray,
    /** left, top, right, bottom per character. */
    private val boxes: FloatArray,
) {
    val length: Int get() = pages.size

    /** The pages this text is printed on, in order. */
    val pagesUsed: List<Int> by lazy { pages.filter { it >= 0 }.distinct() }

    fun pageAt(offset: Int): Int = pages.getOrElse(offset) { -1 }

    /**
     * The rectangles covering [range] of the text, one per line it runs over: consecutive
     * characters on the same page and line are merged, and a new rectangle starts where the
     * text wraps, moves to another column or turns the page. A line on a page turned
     * sideways runs down the screen rather than across, and merges the same way.
     */
    fun rects(range: IntRange): List<PageRect> {
        val out = mutableListOf<PageRect>()
        var page = -1
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        // Which way the current line runs, once its second character says: across or down.
        var across = false
        var down = false
        fun emit() {
            if (page >= 0) out.add(PageRect(page, left, top, right, bottom))
        }
        val first = range.first.coerceAtLeast(0)
        val last = range.last.coerceAtMost(length - 1)
        for (i in first..last) {
            val p = pages[i]
            if (p < 0) continue
            val l = boxes[i * 4]
            val t = boxes[i * 4 + 1]
            val r = boxes[i * 4 + 2]
            val b = boxes[i * 4 + 3]
            val height = (bottom - top).coerceAtLeast(b - t)
            val width = (right - left).coerceAtLeast(r - l)
            val sameRow = !down && p == page &&
                abs((t + b) / 2 - (top + bottom) / 2) < height * 0.5f && l >= right - height
            val sameColumn = !across && !sameRow && p == page &&
                abs((l + r) / 2 - (left + right) / 2) < width * 0.5f && (t >= bottom - width || b <= top + width)
            if (sameRow || sameColumn) {
                across = sameRow
                down = sameColumn
                left = minOf(left, l)
                right = maxOf(right, r)
                top = minOf(top, t)
                bottom = maxOf(bottom, b)
            } else {
                emit()
                page = p
                left = l
                top = t
                right = r
                bottom = b
                across = false
                down = false
            }
        }
        emit()
        return out
    }

    /** The first character printed on [page], or null if none of this text is there. */
    fun firstOffsetOn(page: Int): Int? = pages.indexOf(page).takeIf { it >= 0 }

    /**
     * The character on [page] closest to the point ([x], [y]), with its distance in points: 0
     * when the point is inside its box.
     */
    fun nearest(page: Int, x: Float, y: Float): Pair<Int, Float>? {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in pages.indices) {
            if (pages[i] != page) continue
            val dx = maxOf(boxes[i * 4] - x, 0f, x - boxes[i * 4 + 2])
            val dy = maxOf(boxes[i * 4 + 1] - y, 0f, y - boxes[i * 4 + 3])
            // Lines are close together and words far apart: being on the right line counts more.
            val distance = dx + dy * 3
            if (distance < bestDistance) {
                best = i
                bestDistance = distance
            }
        }
        return if (best < 0) null else best to bestDistance
    }

    companion object {
        /**
         * Lines up [text] — a paragraph as the reflow wrote it — with the [glyphs] it was built
         * from, in the order they were read. The reflow only ever drops characters (a hyphen
         * mended at a wrap, a footnote marker, a zero-width space) or adds spaces, and never
         * reorders them, so each printed character of the text is the next matching glyph, a
         * few dropped glyphs further on at most.
         */
        fun align(text: String, glyphs: List<PagedGlyph>): TextGeometry {
            val pages = IntArray(text.length) { -1 }
            val boxes = FloatArray(text.length * 4)
            var cursor = 0
            for (i in text.indices) {
                val c = text[i]
                if (c.isWhitespace()) continue
                var j = cursor
                val limit = minOf(glyphs.size, cursor + LOOKAHEAD)
                // Case aside: small caps are evened out ("BasiC" is read "Basic").
                while (j < limit && !normal(glyphs[j].glyph.char).equals(c, ignoreCase = true)) j++
                if (j >= limit) continue
                val g = glyphs[j]
                pages[i] = g.page
                boxes[i * 4] = g.glyph.left
                boxes[i * 4 + 1] = g.glyph.top
                boxes[i * 4 + 2] = g.glyph.right
                boxes[i * 4 + 3] = g.glyph.bottom
                cursor = j + 1
            }
            return TextGeometry(pages, boxes)
        }

        /** How many glyphs the reflow may have dropped in a row: a three-digit note marker and its hyphen. */
        private const val LOOKAHEAD = 8

        /** A glyph's character as the reflow writes it (see PdfReflow.clean). */
        private fun normal(c: Char): Char = when (c) {
            '‐', '‑' -> '-'
            else -> c
        }
    }
}

/** Every text block's [TextGeometry] in one section, null for pictures. */
class SectionGeometry(val blocks: List<TextGeometry?>) {

    /** Where to draw [range] of block [blockIndex]. */
    fun rects(blockIndex: Int, range: IntRange): List<PageRect> =
        blocks.getOrNull(blockIndex)?.rects(range).orEmpty()

    /** The first page block [blockIndex] is printed on. */
    fun pageOf(blockIndex: Int, offset: Int = 0): Int? {
        val geometry = blocks.getOrNull(blockIndex) ?: return null
        geometry.pageAt(offset).takeIf { it >= 0 }?.let { return it }
        // The offset itself has no box (a joining space): the nearest printed character after it.
        return (offset until geometry.length).firstNotNullOfOrNull { i -> geometry.pageAt(i).takeIf { it >= 0 } }
            ?: geometry.pagesUsed.firstOrNull()
    }

    /** The block and offset of the text nearest a tap at ([x], [y]) on [page], if any text is close. */
    fun hit(page: Int, x: Float, y: Float, maxDistance: Float = 40f): TextHit? =
        blocks.withIndex()
            .mapNotNull { (index, geometry) -> geometry?.nearest(page, x, y)?.let { (offset, d) -> Triple(index, offset, d) } }
            .minByOrNull { it.third }
            ?.takeIf { it.third <= maxDistance }
            ?.let { TextHit(it.first, it.second) }

    /** The first text printed on [page]: where reading from the top of that page starts. */
    fun firstOn(page: Int): TextHit? =
        blocks.withIndex().firstNotNullOfOrNull { (index, geometry) ->
            geometry?.firstOffsetOn(page)?.let { TextHit(index, it) }
        }
}

/** A character in a section: which block, and the offset into its text. */
data class TextHit(val blockIndex: Int, val offset: Int)
