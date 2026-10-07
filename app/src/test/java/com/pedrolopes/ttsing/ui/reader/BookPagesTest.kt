package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.data.epub.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookPagesTest {

    // A cover with nothing on it, then chapters of 10, 5 and 25 pages.
    private val counts = listOf(0, 10, 5, 25)

    @Test
    fun `pages number continuously through the whole book`() {
        assertEquals(40, BookPages.total(counts))
        assertEquals(1, BookPages.bookPage(counts, chapter = 1, pageInChapter = 0))
        assertEquals(10, BookPages.bookPage(counts, chapter = 1, pageInChapter = 9))
        assertEquals(11, BookPages.bookPage(counts, chapter = 2, pageInChapter = 0))
        assertEquals(40, BookPages.bookPage(counts, chapter = 3, pageInChapter = 24))
    }

    @Test
    fun `progress is the pages before this one over the total`() {
        assertEquals(0f, BookPages.fraction(counts, 1, 0), 0f)
        assertEquals(0.225f, BookPages.fraction(counts, 1, 9), 0.0001f)
        assertEquals(0.25f, BookPages.fraction(counts, 2, 0), 0.0001f)
        assertEquals(0.5f, BookPages.fraction(counts, 3, 5), 0.0001f)
        assertEquals(0.975f, BookPages.fraction(counts, 3, 24), 0.0001f)
        assertEquals(0f, BookPages.fraction(emptyList(), 0, 0), 0f)
    }

    @Test
    fun `a fresh two page book reads 0 percent, not 50`() {
        assertEquals(0f, BookPages.fraction(listOf(2), 0, 0), 0f)
        assertEquals(0.5f, BookPages.fraction(listOf(2), 0, 1), 0.0001f)
    }

    @Test
    fun `chapter ticks sit where each chapter starts`() {
        assertEquals(listOf(0.25f, 0.375f), BookPages.chapterTicks(counts, listOf(1, 2, 3)))
    }

    @Test
    fun `a book with too many chapters draws no ticks rather than a comb`() {
        val many = List(200) { 3 }
        assertTrue(BookPages.chapterTicks(many, (0 until 200).toList(), max = 60).isEmpty())
        assertEquals(59, BookPages.chapterTicks(many, (0 until 60).toList(), max = 60).size)
    }

    @Test
    fun `blank spine items have nothing readable`() {
        val empty = listOf(Block.Text("  ", Block.Text.Kind.PARAGRAPH, emptyList()))
        val picture = listOf(Block.Image("cover.jpg", null))
        val prose = listOf(Block.Text("Hello.", Block.Text.Kind.PARAGRAPH, emptyList()))
        assertFalse(emptyList<Block>().hasReadableContent())
        assertFalse(empty.hasReadableContent())
        assertTrue(picture.hasReadableContent())
        assertTrue(prose.hasReadableContent())
    }

    @Test
    fun `the layout key changes with anything that moves text between pages`() {
        val base = PageGeometry(1000, 1800, 1f, 2.625f)
        assertEquals(base.key, base.copy().key)
        assertFalse(base.key == base.copy(fontScale = 1.1f).key)
        assertFalse(base.key == base.copy(widthPx = 1800, heightPx = 1000).key)
        // Float noise from a slider does not make a new layout.
        assertEquals(base.key, base.copy(fontScale = 1.0000001f).key)
    }
}
