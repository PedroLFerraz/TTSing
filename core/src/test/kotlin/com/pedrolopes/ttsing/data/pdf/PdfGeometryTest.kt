package com.pedrolopes.ttsing.data.pdf

import com.pedrolopes.ttsing.data.epub.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class PdfGeometryTest {

    private val body = 11f
    private val leading = 14f
    private val advance = 6f

    /**
     * A line whose glyphs are laid out one [advance] apart from [x], on baseline [y], the way
     * the line collector reports them.
     */
    private fun line(text: String, y: Float, x: Float = 72f): PdfLine {
        val glyphs = text.mapIndexedNotNull { i, c ->
            if (c == ' ') null else Glyph(c, x + i * advance, y - body * 0.8f, x + (i + 1) * advance, y + body * 0.22f)
        }
        return PdfLine(text, x, y, x + text.length * advance, body, glyphs)
    }

    private fun page(index: Int, vararg lines: PdfLine) = PdfPage(index, 612f, 792f, lines.toList())

    private fun paged(page: Int, line: PdfLine) = line.glyphs.map { PagedGlyph(page, it) }

    @Test
    fun `a word mended across a wrap maps back to both lines`() {
        val first = line("look impor-", 100f)
        val second = line("tant now.", 114f)
        val geometry = TextGeometry.align("look important now.", paged(0, first) + paged(0, second))
        // "impor" is on the first line, "tant" on the second; the hyphen is in neither.
        val rects = geometry.rects(5..13)
        assertEquals(2, rects.size)
        assertEquals(72f + 5 * advance, rects[0].left)
        assertEquals(72f + 10 * advance, rects[0].right) // ends at the "r", not the hyphen
        assertEquals(72f, rects[1].left)
        assertEquals(72f + 4 * advance, rects[1].right)
    }

    @Test
    fun `a dropped footnote marker does not shift the characters after it`() {
        val printed = line("another.4 To be", 100f)
        val geometry = TextGeometry.align("another. To be", paged(0, printed))
        val t = geometry.rects(9..9).single()
        // "T" is the 11th printed character: after "another." (8), "4" and the space.
        assertEquals(72f + 10 * advance, t.left)
    }

    @Test
    fun `a ligature's characters split its box`() {
        val glyphs = Glyph.split("ﬁ", 100f, 0f, 110f, 10f)
        assertEquals(listOf('f', 'i'), glyphs.map { it.char })
        assertEquals(105f, glyphs[0].right)
        assertEquals(105f, glyphs[1].left)
        val n = Glyph('n', 110f, 0f, 116f, 10f)
        val geometry = TextGeometry.align("fin", (glyphs + n).map { PagedGlyph(3, it) })
        assertEquals(listOf(PageRect(3, 100f, 0f, 116f, 10f)), geometry.rects(0..2))
    }

    @Test
    fun `typeset hyphens match the plain hyphen the reflow writes`() {
        val glyphs = "well‐known".mapIndexed { i, c -> PagedGlyph(0, Glyph(c, i * 6f, 0f, i * 6f + 6, 10f)) }
        val geometry = TextGeometry.align("well-known", glyphs)
        assertEquals(listOf(PageRect(0, 0f, 0f, 60f, 10f)), geometry.rects(0..9))
    }

    @Test
    fun `a line on a page turned sideways merges down the screen`() {
        // Two words running downwards, as on a page with /Rotate 90; the second line is to its left.
        fun down(text: String, x: Float) = text.mapIndexed { i, c ->
            PagedGlyph(0, Glyph(c, x, 100f + i * 6, x + 11, 106f + i * 6))
        }
        val geometry = TextGeometry.align("wall stood", down("wall", 500f) + down("stood", 480f))
        assertEquals(
            listOf(PageRect(0, 500f, 100f, 511f, 124f), PageRect(0, 480f, 100f, 491f, 130f)),
            geometry.rects(0..9),
        )
    }

    @Test
    fun `a paragraph running over a page break marks both pages`() {
        val reflowed = PdfReflow.reflow(
            listOf(
                page(0, line("The wall was old and it", 700f)),
                page(1, line("had always been there.", 100f)),
            ),
            emptySet(),
            Locale.ENGLISH,
        )
        val text = reflowed.blocks.single() as Block.Text
        assertEquals("The wall was old and it had always been there.", text.text)
        val rects = reflowed.geometry.rects(0, text.text.indices)
        assertEquals(listOf(0, 1), rects.map { it.page })
        assertEquals(1, reflowed.geometry.pageOf(0, text.text.indexOf("had")))
        // Reading from the top of page 1 starts mid-paragraph, at "had".
        assertEquals(TextHit(0, text.text.indexOf("had")), reflowed.geometry.firstOn(1))
    }

    @Test
    fun `a tap finds the character under it, and nothing far from any text`() {
        val reflowed = PdfReflow.reflow(
            listOf(page(0, line("First paragraph here.", 100f), line("Second one.", 140f, x = 90f))),
            emptySet(),
            Locale.ENGLISH,
        )
        assertEquals(2, reflowed.blocks.size)
        // The "o" of "one", on the second line: "Second " is 7 characters in.
        assertEquals(TextHit(1, 7), reflowed.geometry.hit(0, 90f + 7.5f * advance, 137f))
        assertNull(reflowed.geometry.hit(0, 500f, 600f))
    }

    @Test
    fun `footnotes stay on the page, unread and unmarked`() {
        val reflowed = PdfReflow.reflow(
            listOf(
                page(
                    0,
                    line("Body text that cites a note.1", 100f),
                    line("Body text goes on for a while.", 114f),
                    PdfLine(
                        "1 The note itself.",
                        72f,
                        700f,
                        200f,
                        8f,
                        "1 The note itself.".mapIndexed { i, c -> Glyph(c, 72f + i * 4, 693f, 76f + i * 4, 702f) },
                    ),
                ),
            ),
            emptySet(),
            Locale.ENGLISH,
            bodySize = body,
        )
        val texts = reflowed.blocks.filterIsInstance<Block.Text>().map { it.text }
        assertTrue(texts.toString(), texts.none { it.contains("The note itself") })
        // Nothing read is marked down where the note is printed.
        val marked = reflowed.blocks.indices.flatMap { reflowed.geometry.rects(it, 0..Int.MAX_VALUE - 1) }
        assertTrue(marked.toString(), marked.none { it.top >= 690f })
    }
}
