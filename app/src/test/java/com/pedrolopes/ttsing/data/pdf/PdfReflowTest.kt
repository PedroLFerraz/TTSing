package com.pedrolopes.ttsing.data.pdf

import com.pedrolopes.ttsing.data.epub.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PdfReflowTest {

    // A US-letter page set like a book: 11 pt body on a 14 pt grid, 72 pt margins.
    private val width = 612f
    private val height = 792f
    private val left = 72f
    private val right = 540f
    private val body = 11f
    private val leading = 14f

    /** A full-measure body line at baseline [y]. */
    private fun line(text: String, y: Float, x: Float = left, end: Float = right, size: Float = body) =
        PdfLine(text, x, y, end, size)

    private fun header(text: String) = line(text, y = 40f, size = 9f, end = 300f)
    private fun folio(number: Int) = line("$number", y = 760f, x = 300f, end = 310f, size = 9f)

    private fun page(index: Int, vararg lines: PdfLine) = PdfPage(index, width, height, lines.toList())

    private fun texts(blocks: List<Block>) = blocks.filterIsInstance<Block.Text>().map { it.text }

    @Test
    fun `wrapped lines join into one paragraph with the break hyphen mended`() {
        val p = page(
            0,
            line("There was a wall. It did not look impor-", 100f),
            line("tant. It was built of uncut rocks roughly", 114f),
            line("mortared.", 128f, end = 130f),
        )
        assertEquals(
            listOf("There was a wall. It did not look important. It was built of uncut rocks roughly mortared."),
            texts(PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH)),
        )
    }

    @Test
    fun `a hyphen before a capital is a real compound and is kept`() {
        val sb = StringBuilder("the well-")
        PdfReflow.appendLine(sb, "Known path")
        assertEquals("the well-Known path", sb.toString())
    }

    @Test
    fun `a soft hyphen at the wrap is removed`() {
        val sb = StringBuilder("exam­")
        PdfReflow.appendLine(sb, "ple")
        assertEquals("example", sb.toString())
    }

    @Test
    fun `a wider gap or an indent starts a new paragraph`() {
        val p = page(
            0,
            line("First paragraph runs across the whole measure of", 100f),
            line("the page and ends here.", 114f, end = 250f),
            // Blank-line gap.
            line("Second paragraph after a gap, also full measure", 142f),
            line("and ending.", 156f, end = 150f),
            // Indented first line, no gap.
            line("Third paragraph starts with an indent and keeps", 170f, x = left + 18f),
            line("going to the end of the line.", 184f, end = 300f),
        )
        assertEquals(
            listOf(
                "First paragraph runs across the whole measure of the page and ends here.",
                "Second paragraph after a gap, also full measure and ending.",
                "Third paragraph starts with an indent and keeps going to the end of the line.",
            ),
            texts(PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH)),
        )
    }

    @Test
    fun `running headers and folios are dropped, a one-off chapter title is kept`() {
        val pages = (0 until 6).map { i ->
            page(
                i,
                header("THE DISPOSSESSED · CHAPTER ONE"),
                line("Body text on page $i runs across the whole", 100f),
                line("measure and carries on to the next page", 114f),
                folio(i + 11),
            )
        }
        val furniture = PdfReflow.detectFurniture(pages)
        assertTrue(PdfReflow.signature("THE DISPOSSESSED · CHAPTER ONE") in furniture)

        val all = texts(PdfReflow.toBlocks(pages, furniture, Locale.ENGLISH)).joinToString(" ")
        assertFalse("header leaked: $all", all.contains("DISPOSSESSED"))
        assertFalse("folio leaked: $all", Regex("\\b1[1-6]\\b").containsMatchIn(all))

        // A title printed once, even inside the top band, repeats nowhere and survives — and
        // with the body size measured across the book, a sparse opening page still sees it as
        // a heading rather than as the body text.
        val titled = page(9, line("Chapter Two", 44f, size = 18f, end = 200f), line("It began.", 100f, end = 130f))
        val bookBody = PdfReflow.bodyFontSize(pages + titled)
        assertEquals(body, bookBody)
        val titleBlocks = PdfReflow.toBlocks(listOf(titled), furniture, Locale.ENGLISH, bookBody)
            .filterIsInstance<Block.Text>()
        assertEquals(Block.Text.Kind.HEADING_1 to "Chapter Two", titleBlocks.first().let { it.kind to it.text })
        assertEquals("It began.", titleBlocks[1].text)
    }

    @Test
    fun `digits are masked so numbered headers still count as the same header`() {
        assertEquals(PdfReflow.signature("Chapter 3 · page 41"), PdfReflow.signature("Chapter 3 · page 43"))
    }

    @Test
    fun `page numbers in their many costumes are recognised`() {
        listOf("12", "- 12 -", "Page 12", "xiv", "12 / 300", "12 of 300", "(12)").forEach {
            assertTrue(it, PdfReflow.isPageNumber(it))
        }
        listOf("12 Angry Men", "Chapter 12", "It was 1984.").forEach {
            assertFalse(it, PdfReflow.isPageNumber(it))
        }
    }

    @Test
    fun `a sentence cut by the page break continues on the next page`() {
        val pages = listOf(
            page(0, line("The paragraph starts on one page and it is not", 700f)),
            page(1, line("finished until the top of the next one.", 100f, end = 330f)),
        )
        assertEquals(
            listOf("The paragraph starts on one page and it is not finished until the top of the next one."),
            texts(PdfReflow.toBlocks(pages, emptySet(), Locale.ENGLISH)),
        )
    }

    @Test
    fun `a paragraph that ended at the page break stays ended`() {
        val pages = listOf(
            page(0, line("This one ends exactly at the foot of the page.", 700f)),
            page(1, line("And this is a new paragraph on the next page.", 100f)),
        )
        assertEquals(2, texts(PdfReflow.toBlocks(pages, emptySet(), Locale.ENGLISH)).size)
    }

    @Test
    fun `larger type becomes a heading, and a two-line heading stays one heading`() {
        val p = page(
            0,
            line("Part One", 80f, size = 20f, end = 200f),
            line("A Shadow on", 110f, size = 15f, end = 220f),
            line("the Wall", 128f, size = 15f, end = 180f),
            line("Body text follows the heading here and wraps", 160f),
            line("onto a second line.", 174f, end = 200f),
        )
        val blocks = PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH).filterIsInstance<Block.Text>()
        assertEquals(
            listOf(
                Block.Text.Kind.HEADING_1 to "Part One",
                Block.Text.Kind.HEADING_2 to "A Shadow on the Wall",
                Block.Text.Kind.PARAGRAPH to "Body text follows the heading here and wraps onto a second line.",
            ),
            blocks.map { it.kind to it.text },
        )
    }

    @Test
    fun `the next column continues the paragraph unless it had ended`() {
        // Two columns: the second starts back at the top of the page.
        val p = page(
            0,
            line("Left column text that runs to the bottom", 600f, end = 290f),
            line("of the column without finishing the", 614f, end = 290f),
            line("sentence, which goes on in the right column.", 100f, x = 320f, end = 540f),
        )
        assertEquals(
            listOf("Left column text that runs to the bottom of the column without finishing the sentence, which goes on in the right column."),
            texts(PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH)),
        )
    }

    @Test
    fun `blocks come with sentences split, ready for the voice`() {
        val p = page(0, line("One sentence. Another one follows.", 100f, end = 300f))
        val block = PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH).single() as Block.Text
        assertEquals(2, block.sentences.size)
    }

    @Test
    fun `a page with no text gives no blocks`() {
        assertTrue(PdfReflow.toBlocks(listOf(page(0)), emptySet(), Locale.ENGLISH).isEmpty())
    }
}
