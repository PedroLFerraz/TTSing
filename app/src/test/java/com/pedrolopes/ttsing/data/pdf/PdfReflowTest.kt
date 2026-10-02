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

    // ---- figures ----

    private fun figure(key: String, top: Float, height: Float = 200f, width: Float = 300f, left: Float = 150f) =
        PdfImage(key, left, top, width, height)

    private fun pageWith(index: Int, lines: List<PdfLine>, images: List<PdfImage>) =
        PdfPage(index, width, height, lines, images)

    /** Blocks as short labels: the first words of each paragraph, or "[key]" for a figure. */
    private fun shape(blocks: List<Block>) = blocks.map {
        when (it) {
            is Block.Image -> "[${it.zipPath}]"
            is Block.Text -> it.text.split(' ').take(2).joinToString(" ")
        }
    }

    @Test
    fun `a figure between two paragraphs is placed between them`() {
        val p = pageWith(
            0,
            listOf(
                line("First paragraph ends before the picture.", 100f, end = 330f),
                line("Second paragraph starts under the picture.", 350f, x = left + 18f, end = 330f),
            ),
            listOf(figure("fig", top = 120f)),
        )
        assertEquals(
            listOf("First paragraph", "[fig]", "Second paragraph"),
            shape(PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH)),
        )
    }

    @Test
    fun `a figure in the middle of a paragraph waits for the paragraph to end`() {
        // The text wraps around the picture: one paragraph above and below it.
        val p = pageWith(
            0,
            listOf(
                line("This paragraph runs above the picture and does not", 100f),
                line("stop when the picture appears, carrying on beneath", 330f),
                line("it until it is done.", 344f, end = 200f),
                line("Next paragraph.", 380f, x = left + 18f, end = 200f),
            ),
            listOf(figure("fig", top = 110f)),
        )
        val blocks = PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH)
        assertEquals(listOf("This paragraph", "[fig]", "Next paragraph."), shape(blocks))
        // And the sentence it interrupted on the page reads whole.
        assertEquals(
            "This paragraph runs above the picture and does not stop when the picture appears, " +
                "carrying on beneath it until it is done.",
            (blocks.first() as Block.Text).text,
        )
    }

    @Test
    fun `a figure at the foot of a page follows the paragraph that carries on overleaf`() {
        val pages = listOf(
            pageWith(0, listOf(line("A paragraph that has not finished at the foot", 500f)), listOf(figure("fig", top = 520f, height = 150f))),
            pageWith(1, listOf(line("of the page, and ends here.", 100f, end = 260f), line("Then another.", 130f, x = left + 18f, end = 200f)), emptyList()),
        )
        assertEquals(
            listOf("A paragraph", "[fig]", "Then another."),
            shape(PdfReflow.toBlocks(pages, emptySet(), Locale.ENGLISH)),
        )
    }

    @Test
    fun `a heading after a figure keeps the figure before it`() {
        val p = pageWith(
            0,
            listOf(
                line("The end of a chapter.", 100f, end = 250f),
                line("Chapter Two", 420f, size = 18f, end = 220f),
                line("It begins.", 450f, end = 150f),
            ),
            listOf(figure("fig", top = 150f)),
        )
        assertEquals(
            listOf("The end", "[fig]", "Chapter Two", "It begins."),
            shape(PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH, bodySize = body)),
        )
    }

    @Test
    fun `a page of plates with no text still gives its pictures`() {
        val plates = pageWith(0, emptyList(), listOf(figure("a", top = 80f), figure("b", top = 400f)))
        assertEquals(listOf("[a]", "[b]"), shape(PdfReflow.toBlocks(listOf(plates), emptySet(), Locale.ENGLISH)))
    }

    @Test
    fun `bullets, rules and backgrounds are not figures`() {
        // A full page of body text, as a real page over a tint would have.
        val text = (0 until 40).map { line("Body line $it over a tinted page background here", 100f + it * 14f) }
        val p = pageWith(
            0,
            text,
            listOf(
                figure("bullet", top = 100f, width = 8f, height = 8f),
                figure("rule", top = 300f, width = 400f, height = 1f),
                figure("background", top = 0f, left = 0f, width = width, height = height),
                figure("photo", top = 400f),
                figure("photo-again", top = 400f),
            ),
        )
        assertEquals(listOf("photo"), PdfReflow.figuresOn(p).map { it.key })
    }

    @Test
    fun `a full-page illustration with no text over it is kept`() {
        val plate = pageWith(0, emptyList(), listOf(figure("plate", top = 0f, left = 0f, width = width, height = height)))
        assertEquals(listOf("plate"), PdfReflow.figuresOn(plate).map { it.key })
    }

    // ---- found in a real O'Reilly PDF ----

    @Test
    fun `a typeset hyphen at the break is mended like an ordinary one`() {
        val p = page(
            0,
            line("The later chapters are writ‐", 100f),
            line("ten with the assumption that you have one.", 114f, end = 400f),
        )
        assertEquals(
            listOf("The later chapters are written with the assumption that you have one."),
            texts(PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH)),
        )
    }

    @Test
    fun `a two-line title set with wide leading stays one title`() {
        val p = page(
            0,
            line("Why Platform Engineering Is", 100f, size = 20f, end = 400f),
            line("Becoming Essential", 128f, size = 20f, end = 300f),
            line("Body text begins here and runs on across the line.", 170f),
        )
        val blocks = PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH, bodySize = body)
            .filterIsInstance<Block.Text>()
        assertEquals("Why Platform Engineering Is Becoming Essential", blocks.first().text)
        assertEquals(2, blocks.size)
    }

    @Test
    fun `an epigraph set in from the margin is one paragraph, not one per line`() {
        val inset = left + 30f
        val p = page(
            0,
            line("She swallowed the cat to catch the bird, she", 100f, x = inset),
            line("swallowed the bird to catch the spider, she swal-", 114f, x = inset),
            line("lowed the spider to catch the fly.", 128f, x = inset, end = 330f),
        )
        assertEquals(
            listOf("She swallowed the cat to catch the bird, she swallowed the bird to catch the spider, she swallowed the spider to catch the fly."),
            texts(PdfReflow.toBlocks(listOf(p), emptySet(), Locale.ENGLISH, bodySize = body)),
        )
    }

    @Test
    fun `a cover picture with its title printed over it is kept`() {
        val title = listOf(
            line("Platform", 120f, size = 40f, end = 400f),
            line("Engineering", 170f, size = 40f, end = 450f),
            line("A Guide for Technical, Product,", 210f, size = 16f, end = 380f),
            line("and People Leaders", 230f, size = 16f, end = 300f),
            line("Camille Fournier", 700f, size = 12f, end = 540f),
            line("& Ian Nowland", 716f, size = 12f, end = 540f),
        )
        val cover = PdfPage(0, width, height, title, listOf(PdfImage("cover", 0f, 0f, width, height)))
        assertEquals(listOf("cover"), PdfReflow.figuresOn(cover).map { it.key })
    }

    /** What the reader gets, in order: text as it reads, pictures as "[key]". */
    private fun flow(blocks: List<Block>) = blocks.map {
        when (it) {
            is Block.Text -> it.text
            is Block.Image -> "[${it.zipPath}]"
        }
    }

    @Test
    fun `footnotes drawn first neither pull the figure up nor break into the text, and are not read`() {
        // As an O'Reilly page is drawn: the footnote first, then the body, then the caption.
        val p = pageWith(
            0,
            listOf(
                line("4 This is literally what they were called.", 700f, size = 8f, end = 300f),
                line("building blocks that are not integrated with one", 100f),
                line("another.4 To function, they need glue. The type of", 114f),
                line("architecture seen in Figure 1-1.", 128f, end = 300f),
                line("Figure 1-1. The over-general swamp, held together by glue", 400f, end = 400f),
            ),
            listOf(figure("fig", top = 150f)),
        )
        val next = page(1, line("The problem with the swamp is how hard it is to change.", 60f, end = 400f))
        assertEquals(
            listOf(
                "building blocks that are not integrated with one another. To function, they need glue. " +
                    "The type of architecture seen in Figure 1-1.",
                "[fig]",
                "Figure 1-1. The over-general swamp, held together by glue",
                "The problem with the swamp is how hard it is to change.",
            ),
            flow(PdfReflow.toBlocks(listOf(p, next), emptySet(), Locale.ENGLISH, body)),
        )
    }

    @Test
    fun `small type at the foot that is not a numbered note stays text`() {
        val p = page(
            0,
            line("A sentence of body text that ends here.", 100f, end = 400f),
            line("Printed in the USA.", 700f, size = 8f, end = 200f),
        )
        assertTrue(PdfReflow.footnotesOn(p.lines, p, body).isEmpty())
    }

    @Test
    fun `only the page's own note numbers are taken out of the text`() {
        assertEquals(
            "another. To function, Web 2 and 1984.",
            PdfReflow.stripNoteMarkers("another.4 To function, Web 2 and 1984.", setOf("4", "2")),
        )
    }

    @Test
    fun `the digits under a barcode are not read aloud`() {
        assertTrue(PdfReflow.isBarcode("9 7 8 1 0 9 8 1 5 3 6 4 9 5 5 9 9 9"))
        assertTrue(PdfReflow.isBarcode("5 1 5 9 9 9>"))
        assertFalse(PdfReflow.isBarcode("ISBN: 978-1-098-15364-9"))
        assertFalse(PdfReflow.isBarcode("In 1984, 12 of them came back."))
        assertFalse(PdfReflow.isBarcode("12"))
    }
}
