package com.pedrolopes.ttsing.data.pdf

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.SentenceSplitter
import java.util.Locale
import kotlin.math.abs

/**
 * One line of text as it sits on a PDF page. Coordinates are in points with y growing
 * downwards (PDFBox's "direction-adjusted" space), [y] being the baseline. [glyphs] are its
 * characters' boxes, for marking spoken text on the page; empty when nobody needs them.
 * [bold] and [monospace] say the line is (almost) all set in such a face: a run-in heading,
 * or a line of code.
 */
data class PdfLine(
    val text: String,
    val x: Float,
    val y: Float,
    val right: Float,
    val fontSize: Float,
    val glyphs: List<Glyph> = emptyList(),
    val bold: Boolean = false,
    val monospace: Boolean = false,
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
        titles: Set<String> = emptySet(),
    ): List<Block> = reflow(pages, furniture, locale, bodySize, titles).blocks

    /** Blocks and, for each, where its text sits on the pages. */
    class Reflowed(val blocks: List<Block>, val geometry: SectionGeometry)

    /**
     * [toBlocks], plus each text block's characters lined up with the glyphs they came from,
     * so the page view can mark what is being read on the page itself. [titles] are the
     * book's outline titles ([titleKey]), which running headers repeat.
     */
    fun reflow(
        pages: List<PdfPage>,
        furniture: Set<String>,
        locale: Locale,
        bodySize: Float? = null,
        titles: Set<String> = emptySet(),
    ): Reflowed {
        val bodySize = bodySize
            ?: weightedMedian(pages.flatMap { page -> page.lines.map { it.fontSize to it.text.length } })
            ?: 10f
        val furniture = furniture + runningTitles(pages, titles, bodySize)
        val body = pages.map { page ->
            page to readingOrder(page.lines, page).filterNot { isFurniture(it, page, furniture, titles, bodySize) }
        }
        val allLines = body.flatMap { it.second }
        if (allLines.isEmpty()) {
            // A run of plates: pictures and no text.
            val plates = pages.flatMap { page -> figuresOn(page).map { Block.Image(it.key, null) } }
            return Reflowed(plates, SectionGeometry(plates.map { null }))
        }

        val lineGap = medianLineGap(body.map { it.second }) ?: (bodySize * 1.2f)
        val bodyLeft = percentile(allLines.filter { it.fontSize < bodySize * 1.2f }.map { it.x }, 0.2f) ?: 0f
        val bodyRight = percentile(allLines.map { it.right }, 0.9f) ?: Float.MAX_VALUE
        // A text set in a fixed-pitch face throughout — a typescript, or an OCR text layer —
        // is not code; code is the fixed-pitch minority among proportional text.
        val codeFace = allLines.count { it.monospace } < allLines.size * 0.5f
        // Likewise bold only stands out against text that isn't.
        val boldFace = allLines.count { it.bold } < allLines.size * 0.5f
        fun PdfLine.isBold() = boldFace && bold

        val blocks = mutableListOf<Block>()
        val geometry = mutableListOf<TextGeometry?>()
        val paragraph = StringBuilder()
        // The glyphs of every line in [paragraph], in the order they were joined.
        val paragraphGlyphs = mutableListOf<PagedGlyph>()
        var headingKind: Block.Text.Kind? = null
        var previous: PdfLine? = null
        // What the paragraph being built is: a listing of code, a bulleted item (and where
        // its text, past the bullet, starts), or all in bold so far.
        var inCode = false
        var itemTextX: Float? = null
        var allBold = true
        // A contents entry, which ran out to its page number: the next line is the next entry.
        var contentsEntry = false

        // Pictures the reading has already passed, waiting for the paragraph they interrupt
        // to end: a figure goes *between* paragraphs, never through the middle of a sentence.
        val passedFigures = mutableListOf<PdfImage>()

        fun flush() {
            if (inCode) {
                val code = paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                // Shown, but never read: no sentences, so the voice goes straight past it.
                if (code.isNotEmpty()) {
                    blocks.add(Block.Text(code, Block.Text.Kind.CODE, emptyList()))
                    geometry.add(TextGeometry.align(code, paragraphGlyphs.toList()))
                }
            } else {
                val text = paragraph.toString().replace(Regex("\\s+"), " ").trim()
                // A bold line standing alone, short and unpunctuated, is a run-in heading.
                val boldHeading = allBold && text.length <= 120 && !endsSentence(text)
                val kind = when {
                    // A "heading" that runs on for several lines is an introduction set large.
                    headingKind != null && text.length > 160 -> Block.Text.Kind.PARAGRAPH
                    headingKind != null -> headingKind!!
                    boldHeading -> Block.Text.Kind.HEADING_3
                    else -> Block.Text.Kind.PARAGRAPH
                }
                if (text.isNotEmpty()) {
                    blocks.add(Block.Text(text, kind, SentenceSplitter.split(text, locale)))
                    geometry.add(TextGeometry.align(text, paragraphGlyphs.toList()))
                }
            }
            paragraph.setLength(0)
            paragraphGlyphs.clear()
            headingKind = null
            inCode = false
            itemTextX = null
            allBold = true
            passedFigures.forEach {
                blocks.add(Block.Image(it.key, null))
                geometry.add(null)
            }
            passedFigures.clear()
        }

        for ((pageNumber, pageAndLines) in body.withIndex()) {
            val (page, pageLines) = pageAndLines
            val figures = ArrayDeque(figuresOn(page))
            // Footnotes are left to the page: the voice reads the text, not its apparatus.
            // Their numbers still tell which markers in the text to take out.
            val notes = footnotesOn(pageLines, page, bodySize)
            val noteNumbers = notes.mapNotNull { noteNumber.find(clean(it.text))?.groupValues?.get(1) }.toSet()
            val lines = pageLines - notes.toSet()
            for ((lineNumber, line) in lines.withIndex()) {
                // Code is set at text size; a cover title in a typewriter face is still a title.
                val code = codeFace && line.monospace && line.fontSize <= bodySize * 1.2f
                var text = if (code) line.text.trim() else stripNoteMarkers(clean(line.text), noteNumbers)
                if (text.isEmpty()) continue
                if (!code && isMath(text)) continue
                // The same words drawn twice over — a label printed at two sizes, fake bold —
                // are read once.
                if (!code && previous != null && sameWords(text, previous!!.text)) continue
                // Reaching a line below a figure's top edge means the reader has got to it. The
                // space the figure takes up is not paragraph spacing: text that resumes under a
                // picture is often the same paragraph, carrying on.
                var figureSpace = 0f
                while (figures.isNotEmpty() && figures.first().top <= line.y) {
                    val figure = figures.removeFirst()
                    passedFigures.add(figure)
                    figureSpace += figure.height + lineGap * 2
                }
                val prev = previous
                val gap = if (prev == null) 0f else gapBetween(prev, line) - figureSpace
                // A page break, or a jump back up the page to the next column.
                val turned = prev != null && (lineNumber == 0 && pageNumber > 0 || line.y < prev.y)

                if (code) {
                    if (!inCode) {
                        flush()
                        inCode = true
                    } else if (paragraph.isNotEmpty()) {
                        paragraph.append('\n')
                    }
                    paragraph.append(text)
                    line.glyphs.mapTo(paragraphGlyphs) { PagedGlyph(page.index, it) }
                    previous = line
                    continue
                }

                val bullet = bulletPattern.find(text)
                if (bullet != null) text = text.substring(bullet.range.last + 1)
                if (text.isEmpty()) continue
                var kind = headingKindFor(line, text, bodySize)
                // Big type that isn't words — a stray letter, a mangled formula — is not a title.
                if (kind != null && !looksLikeWords(text)) continue
                // Nor is a crumb of one at text size: "1", "x?", "n n" left over from a formula.
                if (kind == null && isCrumb(text)) continue
                // A numbered heading opens a paragraph and stops short of the right margin;
                // "1.5 million users…" wrapping onto a new line does neither.
                if (kind == null && isNumberedHeading(text) &&
                    (prev == null || endsSentence(paragraph) || gap > lineGap * 1.3f || turned) &&
                    line.right < bodyRight - bodySize * 2
                ) {
                    kind = Block.Text.Kind.HEADING_3
                }

                val entry = leaders.containsMatchIn(line.text)
                val startsNew = when {
                    prev == null || inCode -> true
                    bullet != null || entry || contentsEntry -> true
                    // Headings stand alone, and a run of heading lines at one size is one heading.
                    // Measured against the heading's own size: titles are set with more
                    // leading than body text, and "Why Platform Engineering Is" / "Becoming
                    // Essential" is one title.
                    // A caption stands alone: it neither joins the text above it nor runs on
                    // into the next page's first paragraph.
                    isCaption(text) || isCaption(paragraph) &&
                        (lineNumber == 0 || line.y < prev.y || gapBetween(prev, line) > lineGap * 1.3f) -> true
                    kind != null || headingKind != null ->
                        kind != headingKind || gapBetween(prev, line) > maxOf(lineGap * 1.6f, line.fontSize * 1.9f) ||
                            // A chapter label over its title: two sizes, two headings.
                            abs(line.fontSize - prev.fontSize) > prev.fontSize * 0.15f
                    // A bold line after plain text opens a heading; plain text after a short
                    // bold line closes one.
                    line.isBold() && !allBold && (endsSentence(paragraph) || gap > lineGap * 1.3f || turned) -> true
                    !line.isBold() && allBold && paragraph.length <= 120 && !endsSentence(paragraph) -> true
                    // A sentence that hasn't ended goes on, whatever the layout does: the rest
                    // of a word broken at a page turn, or a note's text set off from its label.
                    !endsSentence(paragraph) && text.first().isLowerCase() && (turned || gap < lineGap * 2.5f) -> false
                    // A bulleted item's wrapped lines hang under its text, not under the bullet.
                    itemTextX != null && abs(line.x - itemTextX!!) < bodySize * 0.6f && !turned && gap < lineGap * 1.45f -> false
                    // A page break, or a jump back up the page (the next column), only ends the
                    // paragraph if the text there reads as ended.
                    turned -> endsSentence(paragraph) || isIndented(line, bodyLeft, bodySize)
                    gap > lineGap * 1.45f -> true
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
                    if (bullet != null) {
                        itemTextX = line.glyphs.firstOrNull { it.left > line.x + 1f && !it.char.isWhitespace() && it.char !in BULLETS }?.left
                    }
                } else {
                    appendLine(paragraph, text)
                }
                allBold = allBold && line.isBold()
                contentsEntry = entry
                line.glyphs.mapTo(paragraphGlyphs) { PagedGlyph(page.index, it) }
                previous = line
            }
            // Whatever sits below the page's last line comes after it.
            passedFigures.addAll(figures)
        }
        flush()
        return Reflowed(blocks, SectionGeometry(geometry))
    }

    // ---- reading order ----

    /**
     * [lines] in the order they are read. Content order is almost always right, but a label
     * at the head of the page — a chapter tab, a "PREFÁCIO" box — is sometimes drawn last,
     * and would be read at the foot of the page, splitting the sentence that runs on to the
     * next. A line in the top band, above all the text drawn before it, goes first.
     */
    fun readingOrder(lines: List<PdfLine>, page: PdfPage): List<PdfLine> {
        if (page.height <= 0f || lines.size < 2) return lines
        val band = page.height * MARGIN_BAND
        val raised = lines.filterIndexed { i, line ->
            i > 0 && line.y < band &&
                lines.subList(0, i).all { it.y < band || it.y > line.y + line.fontSize * 0.5f }
        }
        return if (raised.isEmpty()) lines else raised + (lines - raised.toSet())
    }

    private fun sameWords(a: String, b: String): Boolean =
        signature(clean(a)).filterNot { it.isWhitespace() } == signature(clean(b)).filterNot { it.isWhitespace() }

    // ---- lists, headings, maths ----

    // "¢" is how OCR tends to read a round bullet.
    private const val BULLETS = "•●▪■◦‣▸►➢❖✓✔○∙¢"
    private val bulletPattern = Regex("^[$BULLETS]\\s*")

    /** "2.2.1 The Shell Window": a numbered section heading, set at body size. */
    private val numberedHeading = Regex("""^\d{1,2}(\.\d{1,2}){1,3}\.?\s+\p{L}""")

    private fun isNumberedHeading(text: String): Boolean =
        text.length <= 80 && numberedHeading.containsMatchIn(text) && !endsSentence(text)

    /** A few characters with no word of two letters in them. */
    fun isCrumb(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length <= 4 && trimmed.split(' ').none { token -> token.count { it.isLetter() } >= 2 }
    }

    /** Something a person would call a title: a real word, a chapter number, an acronym. */
    fun looksLikeWords(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.matches(Regex("""(?i)\d{1,3}|[ivxlcdm]{1,7}"""))) return true
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.any { token -> token.count { it.isLetter() } >= 4 }) return true
        return tokens.any { token -> token.length >= 2 && token.all { it.isLetter() && it.isUpperCase() } }
    }

    private const val MATH_SYMBOLS = "=+−×÷∑∏∫√∞∂∆∇≤≥≈≠±^|{}<>"

    /**
     * A formula, or what an OCR'd formula turns into: mostly symbols and fragments, with an
     * equals sign or barely a word in it. Read aloud it is noise, so it is left to the page.
     */
    fun isMath(text: String): Boolean {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val words = tokens.count { token ->
            val core = token.trim { !it.isLetter() }
            core.length >= 3 && core.all { it.isLetter() || it == '’' || it == '\'' || it == '-' }
        }
        val letters = text.count { it.isLetter() }
        val symbols = text.count { it in MATH_SYMBOLS }
        val digits = text.count { it.isDigit() }
        return when {
            words >= 4 -> false
            // Not one word in it, and a bracket or an operator: "P(B|A)P(A)", "n!".
            words == 0 && text.any { it in "()[]{}|!^=+*/" } -> true
            symbols > 0 && (text.contains('=') || symbols >= 2) && words <= 2 -> true
            text.length < 60 && symbols + digits > letters && symbols > 0 -> true
            else -> false
        }
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
        // Small type can sit between the text and its notes — a quotation set in 9 pt — so the
        // notes start at the first numbered line, and run on in type no bigger than it.
        val first = below.indexOfFirst { noteNumber.containsMatchIn(clean(it.text)) }
        if (first < 0) return emptyList()
        val noteSize = below[first].fontSize
        return below.drop(first).filter { it.fontSize <= noteSize + 0.5f }
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


    private fun isFurniture(
        line: PdfLine,
        page: PdfPage,
        furniture: Set<String>,
        titles: Set<String> = emptySet(),
        bodySize: Float = Float.MAX_VALUE,
    ): Boolean {
        if (isBarcode(line.text)) return true
        if (!inMarginBand(line, page)) return false
        if (isPageNumber(line.text) || signature(line.text) in furniture) return true
        // Headings are set larger than the running heads that repeat them.
        if (line.fontSize > bodySize * 1.15f) return false
        return isRunningHead(clean(line.text), titles)
    }

    // ---- running heads ----

    // Front matter is numbered in lower-case roman; upper case would take "CIVIC" for a folio.
    private val folioLead = Regex("""^(?:\d{1,4}|[ivxlcdm]{1,7})\s*[|·•]?\s+""")
    private val folioTail = Regex("""\s+[|·•]?\s*(?:\d{1,4}|[ivxlcdm]{1,7})$""")
    private val pipeFolio = Regex("""^\d{1,4}\s*\|\s*\S|\S\s*\|\s*\d{1,4}$""")
    private val chapterPrefix = Regex(
        """^(chapter|chap\.|cap[íi]tulo|cap\.|part|parte|appendix|ap[êe]ndice|section|se[çc][ãa]o)\s+[\divxlc]+\s*[:.\-–—]?\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val numbering = Regex("""^\d+(\.\d+)*\.?\s+""")

    /** "12 Chapter 2", "Chapter 2 13": a chapter number and a page number, nothing else. */
    private val chapterFolio = Regex(
        """^\d{1,4}\s+(chapter|cap[íi]tulo|part|parte)\s+[\divxlc]+|(chapter|cap[íi]tulo|part|parte)\s+[\divxlc]+\s+\d{1,4}""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * An outline title as a running head would print it: "Chapter 1. Data Engineering
     * Described" and "2.2 Using the Shell" become "data engineering described" and "using the
     * shell".
     */
    fun titleKey(title: String): String =
        title.trim().lowercase()
            .replace(chapterPrefix, "")
            .replace(numbering, "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ':')

    /** A running head's title, and whether a page number is printed beside it. */
    private fun runningKey(text: String): Pair<String, Boolean> {
        var rest = text.trim()
        var folio = false
        folioLead.find(rest)?.let {
            rest = rest.substring(it.range.last + 1)
            folio = true
        }
        folioTail.find(rest)?.let {
            rest = rest.substring(0, it.range.first)
            folio = true
        }
        return titleKey(rest.trim().trim('|', '·', '•').trim()) to folio
    }

    /**
     * A head or foot line that carries a page number with a title — "4 | Chapter 1: Data
     * Engineering Described", "What Is Data Engineering? | 5", "Basic Commands and Directory
     * Hierarchy 15", "12 Chapter 2". The title has to be one of the book's own ([titles]),
     * unless it is set off by the bar O'Reilly-style books use.
     */
    fun isRunningHead(text: String, titles: Set<String>): Boolean {
        if (pipeFolio.containsMatchIn(text)) return true
        if (chapterFolio.matches(text)) return true
        val (key, folio) = runningKey(text)
        return folio && key.isNotEmpty() && key in titles
    }

    /**
     * Running heads without a page number — "CHAPTER 5 : PROBABILITY" — told from the title
     * printed once where its chapter opens by recurring: the book's own titles, in the head or
     * foot band of two pages or more.
     */
    private fun runningTitles(pages: List<PdfPage>, titles: Set<String>, bodySize: Float): Set<String> {
        if (titles.isEmpty()) return emptySet()
        val seenOn = HashMap<String, MutableSet<Int>>()
        for (page in pages) for (line in page.lines) {
            if (!inMarginBand(line, page) || line.fontSize > bodySize * 1.3f) continue
            val key = runningKey(clean(line.text)).first
            if (key.isNotEmpty() && key in titles) seenOn.getOrPut(signature(line.text)) { HashSet() }.add(page.index)
        }
        return seenOn.filterValues { it.size >= 2 }.keys
    }

    /** A contents line's dot leaders and page number: "Preface ........ xvii". */
    private val leaders = Regex("""\s*(?:[.·…]\s*){3,}\s*(?:\d{1,4}|[ivxlcdm]{1,7})?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * The digits printed under a barcode — "9 7 8 1 0 9 8 1 5 3 6 4 9" on a back cover — which
     * the voice would otherwise read out one by one. Nothing but digits and spaces, and more
     * of them than any real sentence carries.
     */
    fun isBarcode(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.all { it.isDigit() || it.isWhitespace() || it in "-><" }) return false
        // A barcode's giveaway is digits printed one by one; a year or a page range is not.
        val loneDigits = trimmed.split(Regex("""\s+""")).count { token ->
            token.count { it.isDigit() } == 1
        }
        return loneDigits >= 5 || trimmed.count { it.isDigit() } >= 10
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
            .replace(leaders, "")
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
