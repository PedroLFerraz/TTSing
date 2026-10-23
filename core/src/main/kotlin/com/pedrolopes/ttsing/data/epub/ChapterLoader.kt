package com.pedrolopes.ttsing.data.epub

import com.pedrolopes.ttsing.data.epub.InlineNotes.Draft
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Converts a chapter XHTML document into a flat list of [Block]s: text blocks with
 * pre-computed sentence spans (for TTS + highlighting) and inline images. Footnotes come out
 * of the flow and onto the text that calls them out (see [Footnotes]).
 */
class ChapterLoader(
    private val locale: Locale,
    private val entryExists: (String) -> Boolean = { true },
    /** Another file of the book, by zip path, for notes kept outside the chapter. */
    private val loadDocument: (String) -> Document? = { null },
) {

    fun parse(input: InputStream, chapterZipPath: String): List<Block> {
        val doc = parseDocument(input.readBytes())
        val notes = LinkedNotes.extract(doc, chapterZipPath, loadDocument).toMutableList()
        EpubSanitizer.sanitize(doc)
        val baseDir = EpubPaths.parentDir(chapterZipPath)
        val drafts = mutableListOf<Draft>()
        walk(doc.body(), baseDir, drafts)
        return InlineNotes.extract(drafts, notes).mapNotNull { draft ->
            when (draft) {
                is Draft.Text -> textBlock(draft, notes)
                is Draft.Image -> draft.block
                Draft.Gap -> null
            }
        }
    }

    /** Puts each note's marker where its placeholder stands, and splits the text into sentences. */
    private fun textBlock(draft: Draft.Text, pending: List<PendingNote>): Block.Text? {
        val raw = draft.text
        val text: String
        val notes = mutableListOf<Note>()
        if (raw.none { Footnotes.indexOf(it) in pending.indices }) {
            text = raw
        } else {
            val out = StringBuilder(raw.length + 8)
            for (c in raw) {
                val note = pending.getOrNull(Footnotes.indexOf(c))
                if (note == null) {
                    out.append(c)
                } else {
                    notes.add(Note(out.length, out.length + note.label.length, note.text))
                    out.append(note.label)
                }
            }
            text = out.toString()
        }
        if (text.isBlank()) return null
        return Block.Text(text, draft.kind, sentences(text, notes), notes)
    }

    /**
     * Sentences of [text], each marker kept with the sentence it annotates. A break iterator
     * doesn't know "¹" or a call-out's "*": after a full stop it either runs straight on into
     * the next sentence or starts the next sentence with the marker. Split as if every marker
     * were a closing bracket — the same length, so every offset holds — it does neither.
     */
    private fun sentences(text: String, notes: List<Note>): List<SentenceSpan> {
        if (notes.isEmpty()) return SentenceSplitter.split(text, locale)
        val chars = text.toCharArray()
        for (note in notes) for (k in note.start until note.end) chars[k] = ')'
        return attachMarkers(text, SentenceSplitter.split(String(chars), locale), notes)
    }

    /**
     * A marker right after a full stop — "o fim.¹ Depois" — can be taken for the start of the
     * next sentence; it belongs to the end of the one it annotates.
     */
    private fun attachMarkers(text: String, spans: List<SentenceSpan>, notes: List<Note>): List<SentenceSpan> {
        if (notes.isEmpty() || spans.size < 2) return spans
        val out = spans.toMutableList()
        for (i in 1 until out.size) {
            var start = out[i].start
            var end = out[i - 1].end
            while (true) {
                val note = notes.firstOrNull { it.start == start } ?: break
                end = note.end
                start = note.end
                while (start < out[i].end && text[start].isWhitespace()) start++
            }
            if (start == out[i].start || start >= out[i].end) continue
            out[i - 1] = SentenceSpan(out[i - 1].start, end)
            out[i] = SentenceSpan(start, out[i].end)
        }
        return out
    }

    private fun walk(element: Element, baseDir: String, out: MutableList<Draft>) {
        for (child in element.children()) {
            val tag = child.tagName().lowercase()
            when {
                tag in skippedTags -> {}
                tag == "img" -> addImage(child.attr("src"), child.attr("alt"), baseDir, out)
                tag == "image" -> addImage(
                    child.attr("xlink:href").ifEmpty { child.attr("href") },
                    null, baseDir, out,
                )
                tag in headingKinds -> addText(child, headingKinds.getValue(tag), baseDir, out)
                tag == "blockquote" -> {
                    if (child.children().any { it.tagName().lowercase() in nestableInQuote }) {
                        walk(child, baseDir, out)
                    } else {
                        addText(child, Block.Text.Kind.QUOTE, baseDir, out)
                    }
                }
                tag in paragraphTags || tag == "table" ->
                    addText(child, Block.Text.Kind.PARAGRAPH, baseDir, out)
                else -> {
                    // Generic container (div, section, figure, ol, ul, ...): treat as a
                    // paragraph when it only holds inline content, otherwise recurse.
                    val onlyInlineChildren = child.children().all { it.tagName().lowercase() in inlineTags }
                    if (onlyInlineChildren && child.text().isNotBlank()) {
                        addText(child, Block.Text.Kind.PARAGRAPH, baseDir, out)
                    } else {
                        walk(child, baseDir, out)
                    }
                }
            }
        }
    }

    private fun addText(element: Element, kind: Block.Text.Kind, baseDir: String, out: MutableList<Draft>) {
        var images = false
        for (img in element.select("img")) {
            images = addImage(img.attr("src"), img.attr("alt"), baseDir, out) || images
        }
        for (img in element.select("image")) {
            images = addImage(img.attr("xlink:href").ifEmpty { img.attr("href") }, null, baseDir, out) || images
        }
        val text = element.text().trim()
        if (text.isEmpty()) {
            if (!images) out.add(Draft.Gap)
            return
        }
        out.add(Draft.Text(text, kind))
    }

    private fun addImage(src: String, alt: String?, baseDir: String, out: MutableList<Draft>): Boolean {
        if (src.isEmpty()) return false
        val path = EpubPaths.resolve(baseDir, src)
        if (!entryExists(path)) return false
        out.add(Draft.Image(Block.Image(path, alt?.takeIf { it.isNotBlank() })))
        return true
    }

    internal companion object {
        /**
         * Parses a chapter, preferring UTF-8 over whatever the document declares.
         *
         * Converted EPUBs frequently ship UTF-8 bytes under a `charset=iso-8859-1` meta tag; obeying
         * the declaration turns every curly quote into `â€™`, both on screen and in the voice. Bytes
         * that decode cleanly as UTF-8 are almost never really Latin-1, so a strict decode is a
         * reliable test: if it succeeds, trust it; if it fails, this is a genuine single-byte
         * encoding and Jsoup's own sniffing (which reads the meta tag) is right.
         */
        fun parseDocument(bytes: ByteArray): Document {
            val utf8 = strictUtf8(bytes)
            return if (utf8 != null) Jsoup.parse(utf8, "") else Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        }

        private fun strictUtf8(bytes: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

        val headingKinds = mapOf(
            "h1" to Block.Text.Kind.HEADING_1,
            "h2" to Block.Text.Kind.HEADING_2,
            "h3" to Block.Text.Kind.HEADING_3,
            "h4" to Block.Text.Kind.HEADING_3,
            "h5" to Block.Text.Kind.HEADING_3,
            "h6" to Block.Text.Kind.HEADING_3,
        )
        val paragraphTags = setOf("p", "li", "dd", "dt", "figcaption", "caption", "pre", "address")
        val skippedTags = setOf("script", "style", "template", "audio", "video", "svg")
        val nestableInQuote = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "div")
        val inlineTags = setOf(
            "a", "span", "em", "strong", "i", "b", "u", "s", "small", "sub", "sup", "code",
            "br", "abbr", "cite", "q", "dfn", "kbd", "mark", "samp", "time", "var", "wbr",
            "big", "tt", "font", "ins", "del", "ruby", "rt", "rp",
        )
    }
}
