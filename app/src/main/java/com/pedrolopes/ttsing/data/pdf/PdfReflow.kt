package com.pedrolopes.ttsing.data.pdf

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.SentenceSplitter
import java.util.Locale

/**
 * One line of text as it sits on a PDF page. Coordinates are in points with y growing
 * downwards (PDFBox's "direction-adjusted" space), [y] being the baseline.
 */
data class PdfLine(
    val text: String,
    val x: Float,
    val y: Float,
    val right: Float,
    val fontSize: Float,
)

/**
 * A picture drawn on a page, in the same top-down points as [PdfLine]. [key] is what the
 * document renders it from later, so the reflow never has to hold image bytes.
 */
data class PdfImage(
    val key: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val bottom: Float get() = top + height
}

data class PdfPage(
    val index: Int,
    val width: Float,
    val height: Float,
    val lines: List<PdfLine>,
    val images: List<PdfImage> = emptyList(),
)

/**
 * Turns positioned PDF lines back into the paragraphs a person would read — the step that
 * decides whether a PDF sounds like a book or like someone reading out a photocopy.
 *
 * A PDF has no paragraphs, only glyphs at coordinates, and it repeats things a reader skips:
 * running headers, page numbers, words hyphenated across a line break. Pure JVM on purpose, so
 * every heuristic here is covered by `PdfReflowTest` without a device.
 */
object PdfReflow {

    /** Share of the page height at the top and at the bottom where headers/footers live. */
    private const val MARGIN_BAND = 0.09f

    /**
     * Signatures of running headers and footers: lines in the top or bottom band that recur
     * on several pages once digits are masked, so "Chapter 3 · 41" and "Chapter 3 · 43" count
     * as one. A chapter title printed once at the top of its first page recurs nowhere and
     * survives.
     */
    fun detectFurniture(pages: List<PdfPage>, minShare: Float = 0.25f, minPages: Int = 3): Set<String> {
        if (pages.isEmpty()) return emptySet()
        val seenOn = HashMap<String, MutableSet<Int>>()
        for (page in pages) {
            for (line in page.lines) {
                if (!inMarginBand(line, page)) continue
                val signature = signature(line.text)
                if (signature.isEmpty() || signature.length > 90) continue
                seenOn.getOrPut(signature) { HashSet() }.add(page.index)
            }
        }
        val needed = maxOf(minPages, (pages.size * minShare).toInt())
        return seenOn.filterValues { it.size >= needed }.keys
    }

    /**
     * The body text size: the size most of the *characters* are set in. Worth measuring over
     * a spread of the book, since a single chapter-opening page can hold more title than text.
     */
    fun bodyFontSize(pages: List<PdfPage>): Float? =
        weightedMedian(pages.flatMap { page -> page.lines.map { it.fontSize to it.text.length } })

    /**
     * Rebuilds [pages] (consecutive, in reading order) as blocks. [furniture] comes from
     * [detectFurniture] and [bodySize] from [bodyFontSize], both usually measured over more
     * pages than these, so a short section still loses the book's running header and a
     * sparse opening page still recognises its title as a title.
     */
    fun toBlocks(
        pages: List<PdfPage>,
        furniture: Set<String>,
        locale: Locale,
        bodySize: Float? = null,
    ): List<Block> {
        val body = pages.map { page -> page to page.lines.filterNot { isFurniture(it, page, furniture) } }
        val allLines = body.flatMap { it.second }
        if (allLines.isEmpty()) {
            // A run of plates: pictures and no text.
            return pages.flatMap { page -> figuresOn(page).map { Block.Image(it.key, null) } }
        }

        val bodySize = bodySize ?: weightedMedian(allLines.map { it.fontSize to it.text.length }) ?: 10f
        val lineGap = medianLineGap(body.map { it.second }) ?: (bodySize * 1.2f)
        val bodyLeft = percentile(allLines.filter { it.fontSize < bodySize * 1.2f }.map { it.x }, 0.2f) ?: 0f
        val bodyRight = percentile(allLines.map { it.right }, 0.9f) ?: Float.MAX_VALUE

        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        var headingKind: Block.Text.Kind? = null
        var previous: PdfLine? = null

        // Pictures the reading has already passed, waiting for the paragraph they interrupt
        // to end: a figure goes *between* paragraphs, never through the middle of a sentence.
        val passedFigures = mutableListOf<PdfImage>()
        // Footnotes wait the same way, and are read once the paragraph that cites them is done.
        val passedNotes = mutableListOf<String>()

        fun flush() {
            val text = paragraph.toString().replace(Regex("\\s+"), " ").trim()
            paragraph.setLength(0)
            if (text.isNotEmpty()) {
                val kind = headingKind ?: Block.Text.Kind.PARAGRAPH
                blocks.add(Block.Text(text, kind, SentenceSplitter.split(text, locale)))
            }
            headingKind = null
            passedNotes.forEach { note ->
                blocks.add(Block.Text(note, Block.Text.Kind.QUOTE, SentenceSplitter.split(note, locale)))
            }
            passedNotes.clear()
            passedFigures.forEach { blocks.add(Block.Image(it.key, null)) }
            passedFigures.clear()
        }

        for ((pageNumber, pageAndLines) in body.withIndex()) {
            val (page, pageLines) = pageAndLines
            val figures = ArrayDeque(figuresOn(page))
            val notes = footnotesOn(pageLines, page, bodySize)
            val noteNumbers = notes.mapNotNull { noteNumber.find(clean(it.text))?.groupValues?.get(1) }.toSet()
            val lines = pageLines - notes.toSet()
            for ((lineNumber, line) in lines.withIndex()) {
                val text = stripNoteMarkers(clean(line.text), noteNumbers)
                if (text.isEmpty()) continue
                // Reaching a line below a figure's top edge means the reader has got to it. The
                // space the figure takes up is not paragraph spacing: text that resumes under a
                // picture is often the same paragraph, carrying on.
                var figureSpace = 0f
                while (figures.isNotEmpty() && figures.first().top <= line.y) {
                    val figure = figures.removeFirst()
                    passedFigures.add(figure)
                    figureSpace += figure.height + lineGap * 2
                }
                val kind = headingKindFor(line, text, bodySize)
                val prev = previous

                val startsNew = when {
                    prev == null -> true
                    // Headings stand alone, and a run of heading lines at one size is one heading.
                    // Measured against the heading's own size: titles are set with more
                    // leading than body text, and "Why Platform Engineering Is" / "Becoming
                    // Essential" is one title.
                    // A caption stands alone: it neither joins the text above it nor runs on
                    // into the next page's first paragraph.
                    isCaption(text) || isCaption(paragraph) &&
                        (lineNumber == 0 || line.y < prev.y || gapBetween(prev, line) > lineGap * 1.3f) -> true
                    kind != null || headingKind != null ->
                        kind != headingKind || gapBetween(prev, line) > maxOf(lineGap * 1.6f, line.fontSize * 1.9f)
                    // A page break, or a jump back up the page (the next column), only ends the
                    // paragraph if the text there reads as ended.
                    lineNumber == 0 && pageNumber > 0 -> endsSentence(paragraph) || isIndented(line, bodyLeft, bodySize)
                    line.y < prev.y -> endsSentence(paragraph) || isIndented(line, bodyLeft, bodySize)
                    gapBetween(prev, line) - figureSpace > lineGap * 1.45f -> true
                    // A first-line indent is an indent relative to the line above: an epigraph
                    // or a block quote set in from the margin keeps all its lines together.
                    isIndentedFrom(line, prev, bodySize) -> true
                    // A short last line that finished a sentence ends its paragraph.
                    endsSentence(paragraph) && prev.right < bodyRight - bodySize * 4 -> true
                    else -> false
                }

                if (startsNew) {
                    flush()
                    headingKind = kind
                    paragraph.append(text)
                } else {
                    appendLine(paragraph, text)
                }
                previous = line
            }
            // Whatever sits below the page's last line comes after it.
            passedFigures.addAll(figures)
            passedNotes.addAll(joinNotes(notes))
        }
        flush()
        return blocks
    }

    // ---- figures ----

    /** Smaller than this on either side, a picture is a bullet, a rule or an icon. */
    private const val MIN_FIGURE_POINTS = 36f

    /**
     * The pictures on [page] worth showing, top to bottom. Drops decoration — bullets,
     * rules, small icons — and a picture covering most of the page with text printed over
     * it, which is a background (a tint, a watermark, a paper texture), not a figure. The
     * same picture drawn twice in the same place counts once.
     */
    fun figuresOn(page: PdfPage): List<PdfImage> {
        val pageArea = page.width * page.height
        return page.images
            .filter { image ->
                val area = image.width * image.height
                // A page of body text over a tint is a background; a cover — one big picture
                // with a title and the authors' names over it — is not, and must stay.
                val isBackground = pageArea > 0 && area >= pageArea * 0.8f &&
                    page.lines.count { it.y in image.top..image.bottom && it.x in image.left..(image.left + image.width) } >= 12
                image.width >= MIN_FIGURE_POINTS && image.height >= MIN_FIGURE_POINTS && !isBackground
            }
            .distinctBy { listOf(it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt()) }
            .sortedBy { it.top }
    }

    // ---- footnotes ----

    private val noteNumber = Regex("""^([0-9]{1,3}|[*†‡§])\s""")

    /**
     * The footnotes at the foot of [page]: the run of lines in smaller type below all of the
     * page's body text, starting with a note number. Producers often draw them before the
     * body, so they are picked out by position rather than by where they come in the stream.
     */
    fun footnotesOn(lines: List<PdfLine>, page: PdfPage, bodySize: Float): List<PdfLine> {
        val lowestBody = lines.filter { it.fontSize >= bodySize * 0.95f }.maxOfOrNull { it.y } ?: return emptyList()
        val below = lines
            .filter { it.y > lowestBody && it.fontSize <= bodySize * 0.9f && it.y > page.height * 0.5f }
            .sortedBy { it.y }
        if (below.isEmpty() || !noteNumber.containsMatchIn(clean(below.first().text))) return emptyList()
        return below
    }

    /** Footnote lines, one string per note. */
    private fun joinNotes(lines: List<PdfLine>): List<String> {
        val notes = mutableListOf<StringBuilder>()
        for (line in lines) {
            val text = clean(line.text)
            if (text.isEmpty()) continue
            if (notes.isEmpty() || noteNumber.containsMatchIn(text)) notes.add(StringBuilder(text))
            else appendLine(notes.last(), text)
        }
        return notes.map { it.toString() }
    }

    /**
     * Takes the page's note markers out of its text — "another.4 To function" is read
     * "another. To function", not "another. four" — only for numbers that have a note on the
     * page, and only where they're stuck to the word or punctuation before them.
     */
    fun stripNoteMarkers(text: String, numbers: Set<String>): String {
        if (numbers.isEmpty()) return text
        return numbers.fold(text) { acc, n ->
            acc.replace(Regex("""(?<=[\p{L}.,;:!?”’"')\]])${Regex.escape(n)}(?=[\s,.;:!?)]|$)"""), "")
        }
    }

    // ---- line classification ----

    private val captionPattern = Regex("""^(Figure|Fig\.|Table|Example|Listing|Figura|Tabela|Quadro)\s+[0-9]+([-.–][0-9]+)*\.?\s""")

    private fun isCaption(text: CharSequence): Boolean = captionPattern.containsMatchIn(text)


    private fun isFurniture(line: PdfLine, page: PdfPage, furniture: Set<String>): Boolean {
        if (!inMarginBand(line, page)) return false
        return isPageNumber(line.text) || signature(line.text) in furniture
    }

    private fun inMarginBand(line: PdfLine, page: PdfPage): Boolean =
        page.height > 0 && (line.y < page.height * MARGIN_BAND || line.y > page.height * (1 - MARGIN_BAND))

    private val pageNumberPattern = Regex(
        "^(page\\s*)?[-–—.(\\[]?\\s*(\\d{1,4}|[ivxlcdm]{1,7})\\s*[-–—.)\\]]?(\\s*(/|of)\\s*\\d{1,4})?$",
        RegexOption.IGNORE_CASE,
    )

    fun isPageNumber(text: String): Boolean = pageNumberPattern.matches(text.trim())

    /** Lowercased, digits masked, whitespace collapsed — what "the same header" means. */
    fun signature(text: String): String =
        text.lowercase()
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun headingKindFor(line: PdfLine, text: String, bodySize: Float): Block.Text.Kind? {
        // Long runs of big type are pull quotes or a large-print book, not headings.
        if (text.length > 120) return null
        return when {
            line.fontSize >= bodySize * 1.6f -> Block.Text.Kind.HEADING_1
            line.fontSize >= bodySize * 1.25f -> Block.Text.Kind.HEADING_2
            else -> null
        }
    }

    private fun isIndented(line: PdfLine, bodyLeft: Float, bodySize: Float): Boolean =
        line.x > bodyLeft + bodySize * 0.8f && line.x < bodyLeft + bodySize * 6f

    private fun isIndentedFrom(line: PdfLine, previous: PdfLine, bodySize: Float): Boolean =
        line.x - previous.x in (bodySize * 0.8f)..(bodySize * 6f)

    private fun gapBetween(a: PdfLine, b: PdfLine): Float = b.y - a.y

    // ---- joining ----

    private val terminal = Regex("[.!?…:;][\"”’')»\\]]*$")

    private fun endsSentence(text: CharSequence): Boolean = terminal.containsMatchIn(text.trimEnd())

    /**
     * Joins a wrapped line onto its paragraph. A word broken across the wrap ("exam-" then
     * "ple") is mended; a real hyphenated compound broken at its own hyphen ("well-" then
     * "Known") keeps it, since the second half is capitalised.
     */
    fun appendLine(paragraph: StringBuilder, line: String) {
        val end = paragraph.length
        if (end == 0) {
            paragraph.append(line)
            return
        }
        val last = paragraph[end - 1]
        val first = line.first()
        when {
            last == '­' -> {
                paragraph.setLength(end - 1)
                paragraph.append(line)
            }
            last == '-' && end >= 2 && paragraph[end - 2].isLetter() && first.isLowerCase() -> {
                paragraph.setLength(end - 1)
                paragraph.append(line)
            }
            last == '-' || last.isWhitespace() -> paragraph.append(line)
            else -> paragraph.append(' ').append(line)
        }
    }

    /** Strips what PDF text layers carry that nobody wants spoken. */
    private fun clean(text: String): String =
        text.replace(' ', ' ')
            // Typeset hyphens (U+2010, U+2011) are hyphens; left as they are they would neither
            // be mended at a line break nor read sensibly.
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace(Regex("[​-‍﻿]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---- statistics ----

    private fun weightedMedian(values: List<Pair<Float, Int>>): Float? {
        val sorted = values.filter { it.second > 0 }.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        if (total == 0) return null
        var seen = 0
        for ((value, weight) in sorted) {
            seen += weight
            if (seen * 2 >= total) return value
        }
        return sorted.last().first
    }

    /** Typical baseline-to-baseline distance between consecutive body lines. */
    private fun medianLineGap(pages: List<List<PdfLine>>): Float? {
        val gaps = pages.flatMap { lines ->
            lines.zipWithNext { a, b -> b.y - a.y }.filter { it > 0f && it < largestFont(lines) * 4 }
        }
        return percentile(gaps, 0.5f)
    }

    private fun largestFont(lines: List<PdfLine>): Float = lines.maxOfOrNull { it.fontSize }?.coerceAtLeast(1f) ?: 12f

    private fun percentile(values: List<Float>, p: Float): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
    }
}
