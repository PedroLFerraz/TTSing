package com.pedrolopes.ttsing.data.epub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Conservative clean-up for badly formatted EPUBs. It only removes markup that is
 * unambiguously navigational clutter — footnote reference markers and their note bodies,
 * page-break anchors, and stray superscript reference numbers — so that read-aloud does
 * not pronounce things like "palavra um" for a footnote marker glued to a word.
 *
 * On a well-formed book none of these patterns match and the document is left untouched.
 */
internal object EpubSanitizer {

    /** epub:type values whose *content* is a note body, not part of the reading flow. */
    private val NOTE_BODY_TYPES = setOf(
        "footnote", "footnotes", "endnote", "endnotes", "rearnote", "rearnotes", "note", "notes",
    )

    /** epub:type values that mark an inline *reference* to a note. */
    private val NOTE_REF_TYPES = setOf("noteref", "backlink")

    private val NOTE_BODY_ROLES = setOf("doc-footnote", "doc-endnote", "doc-rearnote")

    /**
     * Marker inside a <sup>: "1", "[12]", "(3)", "*", "†", "a", "iv". Superscripts are almost
     * always call-outs, so letters and roman numerals are safe to treat as markers here.
     * Capped at 3 digits so years ("1500") are never mistaken for a note number.
     */
    private val SUP_MARKER = Regex(
        """^[\[({<]?\s*(?:\d{1,3}|[*†‡§¶]{1,3}|[a-z]|[ivxlcdm]{1,5})\s*[])}>]?[.)]?$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Marker as an anchor's whole text. Stricter than [SUP_MARKER]: bare letters are real
     * words ("a", "i", "o" in Portuguese), so only digits and footnote symbols qualify.
     */
    private val LINK_MARKER = Regex("""^[\[({<]?\s*(?:\d{1,3}|[*†‡§¶]{1,3})\s*[])}>]?[.)]?$""")

    fun sanitize(doc: Document) {
        removeByTypeAndRole(doc)
        removeMarkerSuperscripts(doc)
        removeNumericNoteLinks(doc)
    }

    private fun removeByTypeAndRole(doc: Document) {
        val doomed = mutableListOf<Element>()
        for (element in doc.getAllElements()) {
            val epubTypes = element.attr("epub:type")
                .ifEmpty { element.attr("type") }
                .lowercase()
                .split(' ', '\t')
                .filter { it.isNotEmpty() }
                .toSet()
            val role = element.attr("role").lowercase()

            val isNoteBody = epubTypes.any { it in NOTE_BODY_TYPES } || role in NOTE_BODY_ROLES
            val isNoteRef = epubTypes.any { it in NOTE_REF_TYPES } || role == "doc-noteref"
            val isPageBreak = epubTypes.contains("pagebreak") || role == "doc-pagebreak"

            // Only strip a note *body* when it is a container (aside/div/section/ol/li/p),
            // never a whole chapter body that happens to be tagged.
            if ((isNoteBody && element.tagName().lowercase() != "body") || isNoteRef || isPageBreak) {
                doomed.add(element)
            }
        }
        doomed.forEach { if (it.parentNode() != null) it.remove() }
    }

    /**
     * Only <sup> is considered — <sub> carries meaning (H₂O, chemical/math notation) and is
     * never a footnote call-out.
     */
    private fun removeMarkerSuperscripts(doc: Document) {
        for (element in doc.select("sup")) {
            if (element.parentNode() == null) continue
            if (matchesMarker(element.text(), SUP_MARKER)) element.remove()
        }
    }

    /**
     * Anchors whose entire text is a bare number AND which point at an internal fragment
     * are footnote call-outs in practice ("texto<a href="#nota3">3</a>").
     */
    private fun removeNumericNoteLinks(doc: Document) {
        for (anchor in doc.select("a[href]")) {
            if (anchor.parentNode() == null) continue
            if (!anchor.attr("href").contains('#')) continue
            if (matchesMarker(anchor.text(), LINK_MARKER)) anchor.remove()
        }
    }

    private fun matchesMarker(raw: String, pattern: Regex): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 8) return false
        return pattern.matches(text)
    }
}
