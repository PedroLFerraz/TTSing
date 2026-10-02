package com.pedrolopes.ttsing.data.pdf

import com.pedrolopes.ttsing.data.epub.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Layouts met in real books — O'Reilly technical books, No Starch's How Linux Works, an OCR'd
 * scan, a Brazilian policy document — each reduced to the few lines that showed the problem.
 */
class PdfBookLayoutsTest {

    // An O'Reilly page: 504 × 661 points, 10 pt body on a 12.6 pt grid, 72 pt left margin.
    private val width = 504f
    private val height = 661.5f
    private val left = 72f
    private val right = 432f
    private val body = 10f

    private fun line(
        text: String,
        y: Float,
        x: Float = left,
        end: Float = right,
        size: Float = body,
        bold: Boolean = false,
        mono: Boolean = false,
    ) = PdfLine(text, x, y, end, size, bold = bold, monospace = mono)

    private fun page(index: Int, vararg lines: PdfLine) = PdfPage(index, width, height, lines.toList())

    private fun blocks(vararg pages: PdfPage, titles: Set<String> = emptySet()): List<Block.Text> =
        PdfReflow.reflow(pages.toList(), emptySet(), Locale.ENGLISH, body, titles).blocks.filterIsInstance<Block.Text>()

    private fun texts(vararg pages: PdfPage, titles: Set<String> = emptySet()) = blocks(*pages, titles = titles).map { it.text }

    // ---- running heads ----

    @Test
    fun `O'Reilly folios with a title beside the page number are not read`() {
        val p = page(
            4,
            line("Data warehousing ushered in the first age of scalable analytics.", 100f, end = 400f),
            line("What Is Data Engineering? | 5", 618.8f, x = 300f, size = 9f, bold = true),
        )
        val q = page(
            5,
            line("The internet went mainstream around the mid-1990s.", 60f, end = 350f),
            line("6 | Chapter 1: Data Engineering Described", 618.8f, size = 9f, bold = true),
        )
        assertEquals(
            listOf("Data warehousing ushered in the first age of scalable analytics.", "The internet went mainstream around the mid-1990s."),
            texts(p, q),
        )
    }

    @Test
    fun `a running head that repeats a bookmark title with its page number is not read`() {
        val titles = setOf(PdfReflow.titleKey("Chapter 2: Basic Commands and Directory Hierarchy"))
        val p = page(
            0,
            line("Basic Commands and Directory Hierarchy 15", 20f, x = 300f, size = 7f),
            line("The ls command lists the contents of a directory.", 100f, end = 380f),
        )
        assertEquals(listOf("The ls command lists the contents of a directory."), texts(p, titles = titles))
    }

    @Test
    fun `a running head with no page number goes once it recurs, and the chapter's own title stays`() {
        val titles = setOf(PdfReflow.titleKey("Chapter 5 Probability"))
        val opener = page(0, line("Probability", 30f, size = 23f, end = 200f), line("One of the most crucial skills is thinking in odds.", 100f, end = 380f))
        val pages = (1..3).map { i ->
            page(i, line("CHAPTER 5 : PROBABILITY", 30f, x = 200f, size = 10f), line("Body text on page $i ends here.", 100f, end = 300f))
        }
        val read = texts(opener, *pages.toTypedArray(), titles = titles)
        assertEquals("Probability", read.first())
        assertFalse(read.toString(), read.any { it.contains("CHAPTER 5") })
    }

    @Test
    fun `bookmark titles are keyed the way running heads print them`() {
        assertEquals("data engineering described", PdfReflow.titleKey("Chapter 1. Data Engineering Described"))
        assertEquals("using the shell", PdfReflow.titleKey("2.2 Using the Shell"))
        assertEquals("probability", PdfReflow.titleKey("Chapter 5 Probability"))
        assertTrue(PdfReflow.isRunningHead("12 Chapter 2", emptySet()))
        assertFalse(PdfReflow.isRunningHead("In 2011 there were 12", setOf("something else")))
    }

    // ---- footnotes ----

    @Test
    fun `footnotes under a small-type quotation are found, skipped, and their markers taken out`() {
        val p = page(
            0,
            line("1 “Data Engineering and Its Main Concepts,” AlexSoft, 2021.", 537.7f, x = 73f, size = 8f),
            line("Data engineering is a set of operations aimed at creating", 61.7f),
            line("interfaces for the flow of information.1", 74.3f, end = 260f),
            line("Data engineering is the development of systems that take", 435f, x = 90f, size = 9f),
            line("in raw data and produce consistent information.", 446f, x = 90f, end = 300f, size = 9f),
        )
        assertEquals(
            listOf(
                "Data engineering is a set of operations aimed at creating interfaces for the flow of information.",
                "Data engineering is the development of systems that take in raw data and produce consistent information.",
            ),
            texts(p),
        )
    }

    // ---- lists and headings ----

    @Test
    fun `bulleted items with hanging lines become one block each, without the bullet`() {
        val bullet = { text: String, y: Float ->
            PdfLine(text, 80.7f, y, right, body, glyphs = listOf(Glyph('•', 80.7f, y - 8f, 85f, y + 2f), Glyph(text[2], 90f, y - 8f, 95f, y + 2f)))
        }
        val p = page(
            0,
            line("To set the stage, in this chapter we’ll cover:", 106f, end = 250f),
            bullet("• What we mean by platforms, and the terms we’ll use through-", 129f),
            line("out the book", 141.6f, x = 90f, end = 140f),
            bullet("• How system complexity has gotten worse in the era of cloud computing,", 158f),
            line("Leaving us in an “over-general swamp”", 170.6f, x = 90f, end = 300f),
        )
        assertEquals(
            listOf(
                "To set the stage, in this chapter we’ll cover:",
                "What we mean by platforms, and the terms we’ll use throughout the book",
                "How system complexity has gotten worse in the era of cloud computing, Leaving us in an “over-general swamp”",
            ),
            texts(p),
        )
    }

    @Test
    fun `a bold line on its own is a heading, not the start of the paragraph under it`() {
        val p = page(
            0,
            line("The early days: 1980 to 2000, from data warehousing to the web", 182.5f, end = 316f, size = 11f, bold = true),
            line("The birth of the data engineer arguably has its roots in data", 199.6f),
            line("warehousing.", 212.2f, end = 130f),
        )
        val read = blocks(p)
        assertEquals(Block.Text.Kind.HEADING_3, read[0].kind)
        assertEquals("The early days: 1980 to 2000, from data warehousing to the web", read[0].text)
        assertEquals("The birth of the data engineer arguably has its roots in data warehousing.", read[1].text)
    }

    @Test
    fun `a numbered heading stands alone, a number that merely starts a wrapped line does not`() {
        val p = page(
            0,
            line("Now let’s look at the commands.", 100f, end = 200f),
            line("2.3.1 The ls command", 126f, end = 180f),
            line("The default is the current directory, and in all about", 142f),
            line("1.5 million files were listed in our test this way", 154.6f),
            line("before it gave up.", 167.2f, end = 150f),
        )
        val read = blocks(p)
        assertEquals("2.3.1 The ls command", read[1].text)
        assertEquals(Block.Text.Kind.HEADING_3, read[1].kind)
        assertEquals(
            "The default is the current directory, and in all about 1.5 million files were listed in our test this way before it gave up.",
            read[2].text,
        )
    }

    @Test
    fun `a sentence that has not ended carries on past a note label's indent`() {
        val p = page(
            0,
            line("Note: For more details about Unix, consider read-", 53.5f, x = 78f),
            line("ing The Linux Command Line (No Starch Press, 2012).", 65.5f, x = 120f, end = 380f),
        )
        assertEquals(listOf("Note: For more details about Unix, consider reading The Linux Command Line (No Starch Press, 2012)."), texts(p))
    }

    @Test
    fun `a heading set large that runs on for lines is read as a paragraph`() {
        val long = "This chapter is a guide to the Unix commands and utilities that will be referenced " +
            "throughout this book. This is preliminary material, and you may already know a lot of it."
        val p = page(0, line(long.take(90), 100f, size = 13f), line(long.drop(90), 116f, size = 13f, end = 300f))
        assertEquals(Block.Text.Kind.PARAGRAPH, blocks(p).single().kind)
    }

    @Test
    fun `a chapter label over its title is two headings`() {
        val p = page(
            0,
            line("PREFÁCIO", 95f, size = 16f, end = 150f, bold = true),
            line("O futuro é glorioso", 118f, size = 24f, end = 300f, bold = true),
            line("Body text follows here and ends.", 160f, end = 300f),
        )
        assertEquals(listOf("PREFÁCIO", "O futuro é glorioso", "Body text follows here and ends."), texts(p))
    }

    // ---- code ----

    @Test
    fun `a listing in a fixed-pitch face is shown as code and never read`() {
        val p = page(
            0,
            line("Here is a sample long listing:", 557f, x = 120f, end = 250f),
            line("$ ls -l", 579.8f, x = 120f, size = 8f, mono = true, bold = true),
            line("total 3616", 590.3f, x = 120f, size = 8f, mono = true),
            line("-rw-r--r--   1 juser    users   3804 Apr 30  2011 abusive.c", 600.8f, x = 120f, size = 8f, mono = true),
        )
        val q = page(
            1,
            line("You’ll learn more about the d in column 1 later.", 52f, x = 120f, end = 380f),
            line("The cp command copies files, and in its simplest form it", 80f, x = 120f),
            line("takes two arguments, the file to copy and where to put", 92.6f, x = 120f),
            line("the copy.", 105.2f, x = 120f, end = 170f),
        )
        val read = blocks(p, q)
        assertEquals(4, read.size)
        val code = read[1]
        assertEquals(Block.Text.Kind.CODE, code.kind)
        assertTrue(code.sentences.isEmpty())
        assertEquals("$ ls -l\ntotal 3616\n-rw-r--r--   1 juser    users   3804 Apr 30  2011 abusive.c", code.text)
        assertEquals("You’ll learn more about the d in column 1 later.", read[2].text)
    }

    @Test
    fun `a text set in a fixed-pitch face throughout, like an OCR layer, is read`() {
        val p = page(
            0,
            line("One of the most crucial skills a data scientist needs is the ability to", 366f, mono = true),
            line("think probabilistically.", 381f, end = 200f, mono = true),
        )
        val read = blocks(p).single()
        assertEquals(Block.Text.Kind.PARAGRAPH, read.kind)
        assertFalse(read.sentences.isEmpty())
    }

    @Test
    fun `identical code lines in a row are all kept`() {
        val p = page(
            0,
            line("    }", 100f, mono = true, end = 110f),
            line("}", 110f, mono = true, end = 100f),
            line("Prose after it, which runs on to the end of the measure and", 140f),
            line("on again to the next line, and to the next one too, before", 152.6f),
            line("it stops.", 165.2f, end = 150f),
        )
        assertEquals("}\n}", blocks(p).first().text)
    }

    // ---- maths and crumbs ----

    @Test
    fun `formulas and their OCR wreckage are left to the page`() {
        listOf(
            "Discrete: } fy (x) = 1, Continuous: { fy (x)dx =1",
            "f(x) = tb exp- (=u) 2n0 20°",
            "P(B|A)P(A)",
            "k}) k'n-k)! me(n-1l)*..*#n-k+1jy=",
            "n!",
        ).forEach { assertTrue(it, PdfReflow.isMath(it)) }
        listOf(
            "and its mean and variance are: p = np, o? = np(1 — p).",
            "The cumulative distribution function (CDF) is often used in practice.",
            "In 2011, 12 of them came back (out of 40).",
        ).forEach { assertFalse(it, PdfReflow.isMath(it)) }
    }

    @Test
    fun `big type that is not a word is not a heading, and crumbs are dropped`() {
        val p = page(
            0,
            line("The binomial distribution gives the probability of k successes.", 100f, end = 400f),
            line("ual gal rn ?", 120f, x = 200f, size = 13f, end = 260f),
            line("x?", 140f, x = 200f, end = 210f),
            line("The most common applications are coin flips.", 160f, end = 380f),
        )
        assertEquals(
            listOf("The binomial distribution gives the probability of k successes.", "The most common applications are coin flips."),
            texts(p),
        )
        assertTrue(PdfReflow.looksLikeWords("2"))
        assertTrue(PdfReflow.looksLikeWords("SQL"))
        assertFalse(PdfReflow.looksLikeWords("er"))
    }

    // ---- contents pages and page order ----

    @Test
    fun `dot leaders and page numbers are taken off contents lines`() {
        val p = page(
            0,
            line("PREFÁCIO ....................................................03", 100f),
            line("Preface . . . . . . . . . . . . . xvii", 120f),
            line("1. Introduction……………………… 12", 140f),
        )
        assertEquals(listOf("PREFÁCIO", "Preface", "1. Introduction"), blocks(p).map { it.text })
    }

    @Test
    fun `a label at the head of the page drawn last is read first, and an overprint once`() {
        val p = page(
            0,
            line("CAPÍTULO I AJUSTE FISCAL", 50f, size = 8f, bold = true),
            line("O governo brasileiro hoje não tem dinheiro para gastar e o", 400f),
            line("ajuste é urgente.", 412.6f, end = 200f),
            line("CAPÍTULO I   AJUSTE FISCAL", 55f, size = 16f, bold = true),
        )
        val q = page(1, line("e continua na página seguinte.", 60f, end = 200f))
        assertEquals(
            listOf("CAPÍTULO I AJUSTE FISCAL", "O governo brasileiro hoje não tem dinheiro para gastar e o ajuste é urgente."),
            texts(p).take(2),
        )
        assertEquals(1, texts(p, q).count { it.startsWith("CAPÍTULO") })
    }

    // ---- small caps ----

    @Test
    fun `letter-spaced small caps are put back together, one case per word`() {
        val word = "BasiC CommanDs"
        var x = 0f
        val pieces = mutableListOf<PdfText.Piece>()
        for (c in word) {
            if (c == ' ') {
                x += 6f
                continue
            }
            pieces.add(PdfText.Piece(c.toString(), x, x + 5f))
            x += 7f
        }
        val spaced = word.filter { it != ' ' }.toList().joinToString(" ")
        assertEquals("Basic Commands", PdfText.respaced(spaced, pieces, 10f))
    }

    @Test
    fun `a letter-spaced label opening a line becomes a word with a colon`() {
        assertEquals("Note: For more details", PdfText.respaced("n o t E  For more details", emptyList(), 10f))
        assertNull(PdfText.respaced("An ordinary line of text", emptyList(), 10f))
        assertNull(PdfText.respaced("I t was", emptyList(), 10f))
    }

    // ---- drawing order ----

    @Test
    fun `a call-out drawn last is read where it sits, not in the sentence running overleaf`() {
        val p = page(
            0,
            line("A raiz jurídica do problema é antiga e conhecida de todos.", 300f, end = 380f),
            line("Esse arcabouço constitucional já tem se mostrado ineficaz, e o", 350f),
            line("crime se modernizou na velocidade da fibra óptica, enquanto o", 362.6f),
            line("A esse descompasso jurídico soma-se um descompasso operacional.", 327f, size = 14f, end = 400f),
        )
        val q = page(1, line("Estado ficou parado no tempo.", 60f, end = 200f))
        assertEquals(
            listOf(
                "A raiz jurídica do problema é antiga e conhecida de todos.",
                "A esse descompasso jurídico soma-se um descompasso operacional.",
                "Esse arcabouço constitucional já tem se mostrado ineficaz, e o crime se modernizou na " +
                    "velocidade da fibra óptica, enquanto o Estado ficou parado no tempo.",
            ),
            texts(p, q),
        )
    }

    @Test
    fun `a title page's title drawn after its authors is read first, and columns stay apart`() {
        val title = page(
            0,
            line("Alice Zheng and Amanda Casari", 412f, end = 300f, size = 14f),
            line("Feature Engineering for Machine Learning", 174f, end = 400f, size = 31f, bold = true),
        )
        assertEquals(listOf("Feature Engineering for Machine Learning", "Alice Zheng and Amanda Casari"), texts(title))

        val left = line("The left column goes all the way down the page.", 500f, x = 72f, end = 240f)
        val right = line("The right column starts at the top.", 100f, x = 260f, end = 432f)
        assertEquals(listOf(left, right), PdfReflow.readingOrder(listOf(left, right), page(1)))
    }

    @Test
    fun `a footer drawn first does not shuffle the page`() {
        // How Linux Works draws the page's footer before its text, at the right-hand side only.
        val footer = line("The Big Picture 5", 642f, x = 399f, end = 456f, size = 6f)
        val full = line("are fairly straightforward, but describing how a process uses", 168f, x = 123f, end = 451f)
        val short = line("normal course of operation is a bit more complex.", 180f, x = 123f, end = 342f)
        val heading = line("1.3.1 Process Management", 128f, x = 123f, end = 248f, size = 12f, bold = true)
        val note = line("Andrew S. Tanenbaum and Herbert Bos (Prentice Hall, 2014).", 89f, x = 123f, end = 369f)
        val ordered = PdfReflow.readingOrder(listOf(footer, note, heading, full, short), page(0))
        assertEquals(listOf(note, heading, full, short), ordered - footer)
    }

    @Test
    fun `a drop cap's sunken first letter does not reorder its lines`() {
        val first = line("E sse capítulo trata do maior problema do Brasil de hoje: a", 414.1f, x = 46f)
        val second = line("violência, que cresce a cada ano que passa sem resposta do", 401.5f, x = 81f)
        assertEquals(listOf(first, second), PdfReflow.readingOrder(listOf(first, second), page(0)))
    }

    // ---- captions and chapter numbers ----

    @Test
    fun `captions are shown but not read, a sentence naming a figure is read`() {
        val p = page(
            0,
            line("The lifecycle has five stages, as the next figure shows.", 100f, end = 380f),
            line("Figure 1-1. The data engineering lifecycle", 300f, end = 300f, size = 9f),
            line("Figure 1-2 shows a snapshot of Google Trends for big data.", 340f, end = 380f),
        )
        val read = blocks(p)
        assertEquals("Figure 1-1. The data engineering lifecycle", read[1].text)
        assertTrue(read[1].sentences.isEmpty())
        assertFalse(read[2].sentences.isEmpty())
        assertTrue(PdfReflow.isCaptionLine("Tabela 2: Gastos por estado", 10f, 10f))
        assertFalse(PdfReflow.isCaptionLine("Figure 1-2 shows a snapshot", 10f, 10f))
    }

    @Test
    fun `a chapter number set alone at the chapter's head is read as Chapter N`() {
        val p = page(
            0,
            line("2", 80f, size = 40f, end = 100f),
            line("Basic Commands and Directory Hierarchy", 140f, size = 20f, end = 400f),
            line("This chapter is a guide to the Unix commands.", 200f, end = 300f),
        )
        assertEquals(listOf("Chapter 2", "Basic Commands and Directory Hierarchy"), texts(p).take(2))
        val pt = PdfReflow.reflow(listOf(p), emptySet(), Locale.forLanguageTag("pt-BR"), body).blocks
        assertEquals("Capítulo 2", (pt.first() as Block.Text).text)
        // Further in, a big number is just a number.
        val later = page(0, line("Some text first.", 60f, end = 200f), line("1", 120f, size = 40f, end = 100f))
        assertFalse(texts(later).any { it.startsWith("Chapter") })
    }
}
