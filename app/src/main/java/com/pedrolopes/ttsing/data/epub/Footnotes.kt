package com.pedrolopes.ttsing.data.epub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Footnotes, lifted out of the reading flow and pinned to their call-outs the way an e-reader
 * shows them: a small marker on the page, the note in a popup when the marker is tapped.
 *
 * While a chapter is being read out of its document, each call-out is held in the text as a
 * single private-use character ([placeholder]) that indexes the chapter's list of
 * [PendingNote]s; [ChapterLoader] swaps it for the visible marker once the block's text is final.
 * That way the marker survives every step in between — the sanitizer, whitespace folding,
 * paragraphs being joined back together — without any offsets to keep up to date.
 */
internal data class PendingNote(
    /** What the page shows: "¹", "²³", "*". */
    val label: String,
    val text: String,
)

internal object Footnotes {

    private const val FIRST_PLACEHOLDER = ''

    /** U+E000 to U+F7FF: far more notes than any chapter has. */
    const val MAX_NOTES = 0x1800

    fun placeholder(index: Int): Char = FIRST_PLACEHOLDER + index

    /** The note index [c] stands for, or -1 when it is not a placeholder. */
    fun indexOf(c: Char): Int = if (c >= FIRST_PLACEHOLDER && c < FIRST_PLACEHOLDER + MAX_NOTES) c - FIRST_PLACEHOLDER else -1

    /** "12" as "¹²": the size and lift of a call-out, and no wider than the page measured it. */
    fun superscript(digits: String): String = digits.map { SUPERSCRIPT_DIGITS[it - '0'] }.joinToString("")

    /**
     * The marker for a call-out written as [raw]: a number keeps its number, a symbol keeps its
     * symbol, and anything else — a letter, a roman numeral, a whole linked word — takes the
     * note's place in the chapter, [ordinal], counting from 1.
     */
    fun label(raw: String, ordinal: Int): String {
        val core = raw.trim().trim('[', ']', '(', ')', '{', '}', '<', '>', '.', ' ')
        return when {
            core.isNotEmpty() && core.length <= 3 && core.all { it in '0'..'9' } -> superscript(core)
            core.isNotEmpty() && core.length <= 3 && core.all { it in NOTE_SYMBOLS } -> core
            else -> superscript(ordinal.toString())
        }
    }

    /**
     * The number or symbol a note's own text opens with — "1. ", "[3] ", "* " — which the popup
     * has no use for: it is already showing which note this is.
     */
    fun stripLeadingMarker(text: String): String = LEADING_MARKER.replaceFirst(text.trim(), "").trim()

    const val NOTE_SYMBOLS = "*†‡§¶"

    private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    private val LEADING_MARKER = Regex("""^[\[(]?\s*(?:\d{1,3}|[*†‡§¶]{1,3})\s*[\])]?[.:)]?\s+""")
}

/**
 * Call-outs that link to their note, as a well-made EPUB writes them:
 * `<a epub:type="noteref" href="notes.xhtml#n3">3</a>`, or just a bare number linking to an
 * anchor. The link is followed — into this document or another one in the book — and the
 * note's text taken from wherever it lives.
 */
internal object LinkedNotes {

    /**
     * Replaces every call-out in [doc] that leads to a note with a placeholder and returns the
     * notes, in placeholder order. A call-out that leads nowhere is left alone, for
     * [EpubSanitizer] to strip as before. Notes kept in this same document are removed from it,
     * so they are not read a second time where they stand.
     *
     * [loadDocument] opens another file of the book by its zip path, or returns null.
     */
    fun extract(
        doc: Document,
        chapterZipPath: String,
        loadDocument: (String) -> Document?,
    ): List<PendingNote> {
        val baseDir = EpubPaths.parentDir(chapterZipPath)
        val notes = mutableListOf<PendingNote>()
        val lifted = mutableListOf<Element>()
        for (anchor in doc.select("a[href]")) {
            if (notes.size >= Footnotes.MAX_NOTES) break
            if (anchor.parentNode() == null || insideNoteBody(anchor)) continue
            val href = anchor.attr("href")
            if (!href.contains('#')) continue
            val raw = anchor.text().trim()
            val declared = isNoteRef(anchor)
            if (!declared && (!looksLikeMarker(anchor, raw) || isBacklink(anchor) || startsItsBlock(anchor))) continue

            val path = href.substringBefore('#')
            val fragment = EpubPaths.percentDecode(href.substringAfter('#'))
            if (fragment.isEmpty()) continue
            val targetPath = if (path.isEmpty()) chapterZipPath else EpubPaths.resolve(baseDir, path)
            val sameDocument = targetPath == chapterZipPath
            val targetDoc = if (sameDocument) doc else loadDocument(targetPath) ?: continue
            val target = targetDoc.getElementById(fragment)
                ?: targetDoc.getElementsByAttributeValue("name", fragment).firstOrNull()
                ?: continue
            val container = noteContainer(target, declared) ?: continue
            if (!declared && !looksLikeNote(container, target, raw)) continue
            // A link to the paragraph it sits in is no footnote.
            if (sameDocument && (container === anchor || anchor.parents().any { it === container })) continue
            val text = noteText(container) ?: continue

            val label = Footnotes.label(raw, notes.size + 1)
            // A note pinned to a whole word keeps the word on the page, with the marker after it.
            val keptWord = if (looksLikeMarker(anchor, raw)) "" else raw
            notes.add(PendingNote(label, text))
            outermostMarker(anchor).replaceWith(TextNode(keptWord + Footnotes.placeholder(notes.lastIndex)))
            if (sameDocument) lifted.add(container)
        }
        lifted.forEach { if (it.parentNode() != null && it !== doc.body()) it.remove() }
        liftNotesCalledElsewhere(doc, chapterZipPath, loadDocument)
        return notes
    }

    /**
     * Takes out of [doc] the notes whose call-outs are in other files — a converter that splits a
     * long chapter into several files gathers the notes of all of them after the last one:
     *
     * `<p><a id="n2" href="part3.html#r2">2</a> Escolas de jurisprudência…</p>`
     *
     * The call-out's own file shows each of them in a popup already; left here they would be read
     * out as stray paragraphs at the end of the chapter. A note counts only when the link goes
     * both ways — its number leads to the call-out, and the call-out leads back to it — so a list
     * of contents is never mistaken for one. A file with nothing else in it is a page of notes
     * someone may turn to on purpose, and is left as it is.
     */
    private fun liftNotesCalledElsewhere(doc: Document, chapterZipPath: String, loadDocument: (String) -> Document?) {
        val baseDir = EpubPaths.parentDir(chapterZipPath)
        // Everything that opens like a note, and those of them whose call-out was found.
        val noteLike = mutableListOf<Element>()
        val found = mutableListOf<Element>()
        for (anchor in doc.select("a[href]")) {
            if (noteLike.any { note -> anchor.parents().any { it === note } }) continue
            if (!isBacklink(anchor) && !(looksLikeMarker(anchor, anchor.text().trim()) && startsItsBlock(anchor))) continue
            val href = anchor.attr("href")
            val path = href.substringBefore('#')
            val fragment = EpubPaths.percentDecode(href.substringAfter('#', ""))
            if (path.isEmpty() || fragment.isEmpty()) continue
            val callOutPath = EpubPaths.resolve(baseDir, path)
            if (callOutPath == chapterZipPath) continue
            val note = noteContainer(anchor, declared = true) ?: continue
            noteLike.add(note)
            // Only a note the call-out's file can show: anything else would be lost from the book.
            noteText(note) ?: continue
            val ids = (listOf(anchor) + anchor.parents().takeWhile { it !== note } + note)
                .map { it.id() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (ids.isEmpty()) continue
            val callOutDoc = loadDocument(callOutPath) ?: continue
            val callOut = callOutDoc.getElementById(fragment) ?: continue
            val callOutDir = EpubPaths.parentDir(callOutPath)
            val linksBack = callOut.select("a[href]").any { link ->
                val back = link.attr("href")
                back.substringBefore('#').isNotEmpty() &&
                    EpubPaths.resolve(callOutDir, back) == chapterZipPath &&
                    EpubPaths.percentDecode(back.substringAfter('#', "")) in ids
            }
            if (linksBack) found.add(note)
        }
        if (found.isNotEmpty() && hasTextBesides(doc, noteLike)) found.forEach { it.remove() }
    }

    /** Whether [doc] has words of its own outside [notes] and its headings. */
    private fun hasTextBesides(doc: Document, notes: List<Element>): Boolean {
        var own = false
        doc.body().traverse { node, _ ->
            if (own || node !is TextNode || node.isBlank) return@traverse
            var parent = node.parent()
            while (parent != null) {
                if (notes.any { it === parent }) return@traverse
                if (parent is Element && parent.tagName().lowercase() in HEADINGS) return@traverse
                parent = parent.parent()
            }
            own = true
        }
        return own
    }

    private fun isNoteRef(anchor: Element): Boolean =
        "noteref" in epubTypes(anchor) || anchor.attr("role").equals("doc-noteref", ignoreCase = true)

    private fun isBacklink(anchor: Element): Boolean =
        "backlink" in epubTypes(anchor) || anchor.attr("role").equals("doc-backlink", ignoreCase = true)

    /** "3", "[12]", "*" as the link's text — or a letter inside a superscript, "<sup>a</sup>". */
    private fun looksLikeMarker(anchor: Element, raw: String): Boolean {
        if (raw.isEmpty() || raw.length > 8) return false
        if (LINK_MARKER.matches(raw)) return true
        val superscript = anchor.parent()?.tagName().equals("sup", ignoreCase = true) ||
            anchor.children().any { it.tagName().equals("sup", ignoreCase = true) }
        return superscript && SUP_MARKER.matches(raw)
    }

    /**
     * Whether the link is the first thing in its paragraph. A note opens with its number
     * linking back to the text — "<p><a href="ch1.xhtml#r3">3</a> The note.</p>" — while a
     * call-out comes after the words it annotates; the two otherwise look exactly alike.
     */
    private fun startsItsBlock(anchor: Element): Boolean {
        val block = anchor.parents().firstOrNull { it.tagName().lowercase() in BLOCK_TAGS } ?: return false
        return opens(anchor, block)
    }

    /** Whether nothing but whitespace comes before [element] inside [block]. */
    private fun opens(element: Element, block: Element): Boolean {
        val before = StringBuilder()
        var reached = false
        block.traverse { node, _ ->
            if (node === element) reached = true
            if (!reached && node is TextNode) before.append(node.text())
        }
        return before.isBlank()
    }

    private fun insideNoteBody(element: Element): Boolean = element.parents().any { parent ->
        epubTypes(parent).any { it in NOTE_BODY_TYPES } || parent.attr("role").lowercase() in NOTE_BODY_ROLES
    }

    /**
     * The element holding the whole note [target] marks. An id on the note itself (an
     * `<aside>`, a list item) is the note; an id on an anchor inside one is the paragraph
     * around it — and only when the anchor opens that paragraph, unless the link [declared]
     * itself a note reference: an undeclared link into the middle of a paragraph is a link
     * back to the text, not to a note.
     */
    private fun noteContainer(target: Element, declared: Boolean): Element? {
        val tag = target.tagName().lowercase()
        if (tag == "body" || tag == "html") return null
        if (tag in BLOCK_TAGS) return target
        val block = target.parents().firstOrNull { it.tagName().lowercase() in BLOCK_TAGS } ?: return null
        if (block.tagName().equals("body", ignoreCase = true)) return null
        if (!declared && !opens(target, block)) return null
        return block
    }

    /**
     * Whether an undeclared link landed on a note rather than on some other part of the book
     * — "see section <a href="#s2">2</a>". A note is short, has no headings, and says it is a
     * note: it opens with the call-out's own number, or is named like one ("fn3", "nota").
     */
    private fun looksLikeNote(container: Element, target: Element, raw: String): Boolean {
        val tag = container.tagName().lowercase()
        if (tag in NOT_NOTES || container.select("h1, h2, h3, h4, h5, h6").isNotEmpty()) return false
        val number = raw.trim('[', ']', '(', ')', '{', '}', '<', '>', '.', ' ')
        if (number.isNotEmpty() && container.text().trim().trimStart('[', '(').startsWith(number)) return true
        return sequenceOf(target, container).any { element ->
            NOTE_NAME.containsMatchIn(element.id()) || NOTE_NAME.containsMatchIn(element.className())
        }
    }

    /**
     * The note as plain text, paragraphs separated by a blank line, without its own number and
     * the link back to the text. Null when there is nothing to show, or when the "note" is so
     * long it must be some other part of the book the link happens to land on.
     */
    private fun noteText(container: Element): String? {
        val note = container.clone()
        for (anchor in note.select("a[href]")) {
            if (isBacklink(anchor) || looksLikeMarker(anchor, anchor.text().trim())) anchor.remove()
        }
        val leaves = note.select(BLOCK_TAGS.joinToString(",")).filter { element ->
            element !== note && element.select(BLOCK_TAGS.joinToString(",")).size == 1
        }
        val paragraphs = (if (leaves.isEmpty()) listOf(note.text()) else leaves.map { it.text() })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val text = Footnotes.stripLeadingMarker(paragraphs.joinToString("\n\n"))
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_NOTE_LENGTH }
    }

    /** The superscript around a linked number goes with it: "<sup><a>1</a></sup>". */
    private fun outermostMarker(anchor: Element): Element {
        val parent = anchor.parent() ?: return anchor
        return if (parent.tagName().equals("sup", ignoreCase = true) && parent.text().trim() == anchor.text().trim()) {
            parent
        } else {
            anchor
        }
    }

    private fun epubTypes(element: Element): Set<String> =
        element.attr("epub:type").ifEmpty { element.attr("type") }
            .lowercase()
            .split(' ', '\t')
            .filter { it.isNotEmpty() }
            .toSet()

    private const val MAX_NOTE_LENGTH = 8000

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private val NOT_NOTES = setOf("section", "article", "body") + HEADINGS

    private val NOTE_NAME = Regex("""(?i)note|nota|rodap|^fn|^n\d|_fn|-fn""")

    private val BLOCK_TAGS = setOf(
        "p", "div", "aside", "li", "section", "dd", "dt", "blockquote", "td", "footer", "article",
        "h1", "h2", "h3", "h4", "h5", "h6", "body",
    )

    private val NOTE_BODY_TYPES = setOf(
        "footnote", "footnotes", "endnote", "endnotes", "rearnote", "rearnotes", "note", "notes",
    )

    private val NOTE_BODY_ROLES = setOf("doc-footnote", "doc-endnote", "doc-rearnote", "doc-endnotes")

    private val LINK_MARKER = Regex("""^[\[({<]?\s*(?:\d{1,3}|[*†‡§¶]{1,3})\s*[])}>]?[.)]?$""")

    private val SUP_MARKER = Regex(
        """^[\[({<]?\s*(?:\d{1,3}|[*†‡§¶]{1,3}|[a-z]|[ivxlcdm]{1,5})\s*[])}>]?[.)]?$""",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * Notes that nothing links to: a printed book's footnotes, carried into the EPUB by a scan or
 * a conversion. The call-out is a bare asterisk after a word — "mãos de pardal,* que" — and
 * the note a paragraph opening with one — "* No original manos de gorrión…" — sitting wherever
 * the printed page ended, often in the middle of a sentence that carries on after it.
 *
 * Such a note is lifted out only when it can be matched to a call-out before it; the sentence
 * it interrupted is then joined back together. Anything that can't be matched is left exactly
 * where it was.
 */
internal object InlineNotes {

    /** What [ChapterLoader] collects from a chapter before its blocks are final. */
    sealed interface Draft {
        data class Text(val text: String, val kind: Block.Text.Kind) : Draft
        data class Image(val block: Block.Image) : Draft

        /** An empty paragraph: in a converted book, the gap the printed page's break left. */
        data object Gap : Draft
    }

    /**
     * Lifts the unlinked notes out of [drafts], replacing each matched call-out with a
     * placeholder numbered from [notes]'s current size and adding the note to [notes].
     * Gaps are dropped from the result.
     */
    fun extract(drafts: List<Draft>, notes: MutableList<PendingNote>): List<Draft> {
        val items = drafts.toMutableList()
        val bodies = findBodies(items)
        if (bodies.isEmpty()) return items.filter { it !is Draft.Gap }

        val callOuts = findCallOuts(items, bodies)
        val matches = match(bodies, callOuts, items)
        if (matches.isEmpty()) return items.filter { it !is Draft.Gap }

        // Call-outs become placeholders, right to left within a block so earlier offsets hold.
        for ((callOut, body) in matches.sortedByDescending { it.first.start }) {
            if (notes.size >= Footnotes.MAX_NOTES) break
            val draft = items[callOut.draft] as Draft.Text
            notes.add(PendingNote(callOut.marker, body.text))
            val text = draft.text.replaceRange(callOut.start, callOut.end, Footnotes.placeholder(notes.lastIndex).toString())
            items[callOut.draft] = draft.copy(text = text)
        }

        // The notes come out of the flow, and the sentence each one cut in two is joined again.
        val lifted = matches.associate { (_, body) -> body.first to body }
        val out = mutableListOf<Draft>()
        var i = 0
        while (i < items.size) {
            val body = lifted[i]
            if (body == null) {
                out.add(items[i])
                i++
                continue
            }
            i = body.last + 1
            // What the note interrupted: the rest of its own paragraph, or the next paragraph.
            var next = i
            while (next < items.size && items[next] is Draft.Gap) next++
            val after = body.remainder?.let { Draft.Text(it, Block.Text.Kind.PARAGRAPH) }
                ?: (items.getOrNull(next) as? Draft.Text)?.takeIf { next !in lifted }
            val beforeIndex = out.indexOfLast { it !is Draft.Gap }
            val before = out.getOrNull(beforeIndex) as? Draft.Text
            if (before != null && after != null && continues(before, after)) {
                while (out.lastIndex > beforeIndex) out.removeAt(out.lastIndex)
                out[beforeIndex] = before.copy(text = before.text.trimEnd() + " " + after.text.trimStart())
                if (body.remainder == null) i = next + 1
            } else if (body.remainder != null) {
                out.add(after!!)
            }
        }
        return out.filter { it !is Draft.Gap }
    }

    /** A note in [first]..[last] (indexes into the drafts), and anything after its end that was the book's own text. */
    private data class Body(val first: Int, val last: Int, val text: String, val remainder: String?)

    private data class CallOut(val draft: Int, val start: Int, val end: Int, val marker: String, val position: Long)

    private fun findBodies(items: List<Draft>): List<Body> {
        val bodies = mutableListOf<Body>()
        var i = 0
        while (i < items.size) {
            val draft = items[i]
            if (draft !is Draft.Text || !isBodyStart(draft)) {
                i++
                continue
            }
            val last = extent(items, i)
            // A note that ends "(N. T.)" ends there, even when the book's text runs on after it
            // in the same paragraph: nothing after the sign-off belongs to the note.
            val paragraphs = mutableListOf<String>()
            var endAt = last
            var remainder: String? = null
            for (k in i..last) {
                val paragraph = (items[k] as Draft.Text).text
                val close = NOTE_CLOSE.find(paragraph)
                if (close == null) {
                    paragraphs.add(paragraph)
                    continue
                }
                paragraphs.add(paragraph.substring(0, close.range.last + 1))
                remainder = paragraph.substring(close.range.last + 1).trim().takeIf { it.isNotEmpty() }
                endAt = k
                break
            }
            val text = Footnotes.stripLeadingMarker(paragraphs.joinToString("\n\n"))
            // Longer than the foot of a page holds, it has the book's own text run into it, and
            // lifting it out would hide that text in a popup; it stays where it is.
            if (text.isNotEmpty() && text.length <= MAX_NOTE_LENGTH) bodies.add(Body(i, endAt, text, remainder))
            i = endAt + 1
        }
        return bodies
    }

    private fun isBodyStart(draft: Draft.Text): Boolean =
        (draft.kind == Block.Text.Kind.PARAGRAPH || draft.kind == Block.Text.Kind.QUOTE) &&
            BODY_START.containsMatchIn(draft.text)

    /**
     * The last paragraph of the note starting at [start]. A note running over several
     * paragraphs is followed by the gap of the page break within a few of them; when no gap
     * comes that soon, the paragraphs after it are the book's own, and the note is one paragraph.
     */
    private fun extent(items: List<Draft>, start: Int): Int {
        var paragraphs = 0
        var k = start + 1
        while (k < items.size) {
            when (val draft = items[k]) {
                Draft.Gap -> return k - 1
                is Draft.Image -> return k - 1
                is Draft.Text -> {
                    if (isBodyStart(draft)) return k - 1
                    paragraphs++
                    if (paragraphs > MAX_CONTINUATION_PARAGRAPHS) return start
                }
            }
            k++
        }
        return items.lastIndex
    }

    private fun findCallOuts(items: List<Draft>, bodies: List<Body>): List<CallOut> {
        val inBody = BooleanArray(items.size)
        bodies.forEach { for (k in it.first..it.last) inBody[k] = true }
        val callOuts = mutableListOf<CallOut>()
        var position = 0L
        for ((index, draft) in items.withIndex()) {
            if (draft !is Draft.Text) continue
            if (!inBody[index]) {
                for (match in CALL_OUT.findAll(draft.text)) {
                    callOuts.add(CallOut(index, match.range.first, match.range.last + 1, match.value, position + match.range.first))
                }
            }
            position += draft.text.length + 1
        }
        return callOuts
    }

    /**
     * Pairs notes with call-outs. Notes printed together at the foot of one page are a run;
     * a run of n notes belongs to the last n call-outs before it, in order. A call-out passed
     * over is spent — its note was on a page this chapter does not have — so it can't be
     * claimed by a later note that is really someone else's.
     */
    private fun match(bodies: List<Body>, callOuts: List<CallOut>, items: List<Draft>): List<Pair<CallOut, Body>> {
        val start = LongArray(items.size)
        var position = 0L
        for ((index, draft) in items.withIndex()) {
            start[index] = position
            if (draft is Draft.Text) position += draft.text.length + 1
        }
        val runs = mutableListOf<MutableList<Body>>()
        for (body in bodies) {
            val run = runs.lastOrNull()
            val adjacent = run != null && (run.last().last + 1 until body.first).all { items[it] is Draft.Gap }
            if (adjacent) run!!.add(body) else runs.add(mutableListOf(body))
        }
        val matches = mutableListOf<Pair<CallOut, Body>>()
        var available = 0
        for (run in runs) {
            val first = run.first().first
            var end = available
            while (end < callOuts.size && callOuts[end].draft < first) end++
            val candidates = callOuts.subList(available, end)
                .filter { start[first] - it.position <= MAX_CALL_OUT_DISTANCE }
            val count = minOf(candidates.size, run.size)
            val claimed = candidates.takeLast(count)
            val notes = run.takeLast(count)
            claimed.zip(notes).forEach { matches.add(it) }
            available = end
        }
        return matches
    }

    /**
     * Whether [after] carries on the sentence [before] was in when a note cut it in two: the
     * first stops without a full stop, the second goes on in lower case.
     */
    private fun continues(before: Draft.Text, after: Draft.Text): Boolean {
        if (before.kind != Block.Text.Kind.PARAGRAPH || after.kind != Block.Text.Kind.PARAGRAPH) return false
        val last = before.text.trimEnd().lastOrNull() ?: return false
        val first = after.text.trimStart().firstOrNull() ?: return false
        return last !in SENTENCE_END && first.isLowerCase()
    }

    private const val MAX_CONTINUATION_PARAGRAPHS = 3

    private const val MAX_NOTE_LENGTH = 2500

    /** About three printed pages: a call-out further back than that is not this note's. */
    private const val MAX_CALL_OUT_DISTANCE = 8000L

    private const val SENTENCE_END = ".!?…"

    /** "* No original…": a symbol, a space, then the note. "***" alone is a scene break. */
    private val BODY_START = Regex("""^[*†‡]{1,3}\s+\S""")

    /** A symbol right after a word or its punctuation, then a space, punctuation or the end. */
    private val CALL_OUT = Regex("""(?<=[^\s*†‡])[*†‡]{1,3}(?=[\s\p{P}&&[^*†‡]]|$)""")

    /** "(N. T.)", "( N. do T.)", "(N. da E.)": translator's or editor's note, signed off. */
    private val NOTE_CLOSE = Regex("""\(\s*N\.\s*(?:d[oa]\s+)?[TEA]\.?\s*\)""")
}
