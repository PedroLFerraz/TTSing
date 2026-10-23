package com.pedrolopes.ttsing.data.epub

import java.text.BreakIterator
import java.util.Locale

/**
 * Splits text into the sentences the voice reads one at a time.
 *
 * [BreakIterator] ends a sentence at every full stop followed by a capital, so "Dr. Silva",
 * "W. H. D. Rouse", "Book IV. Socrates" and "(see pp. 481–482)" all came apart: the voice
 * read "Dr." on its own with a sentence's pause after it, and the highlight broke mid-name.
 * After it runs, [mergeFalseBreaks] joins back every break that was not a sentence end.
 * Merging only ever joins, so the result is the same whichever BreakIterator the platform has
 * (the JDK's in tests, ICU's on Android).
 */
object SentenceSplitter {

    fun split(text: String, locale: Locale): List<SentenceSpan> {
        if (text.isBlank()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(text)
        val spans = mutableListOf<SentenceSpan>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            var s = start
            var e = end
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) spans.add(SentenceSpan(s, e))
            start = end
            end = iterator.next()
        }
        return mergeFalseBreaks(text, spans)
    }

    /** Joins each span to the one before it when the break between them was not a sentence end. */
    internal fun mergeFalseBreaks(text: String, spans: List<SentenceSpan>): List<SentenceSpan> {
        val merged = mutableListOf<SentenceSpan>()
        for (span in carryListMarkers(text, spans)) {
            val previous = merged.lastOrNull()
            if (previous != null &&
                isFalseBreak(text.substring(previous.start, previous.end), text.substring(span.start, span.end))
            ) {
                merged[merged.lastIndex] = SentenceSpan(previous.start, span.end)
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    /**
     * Some break iterators leave the next item's number stuck to the end of the sentence before
     * it — "…the garden. 2." then "The wall itself." — so the voice would say "two" at the end
     * of the wrong sentence. When a span ends in a bare "N." after a sentence end, the number
     * moves forward onto the span it numbers.
     */
    private fun carryListMarkers(text: String, spans: List<SentenceSpan>): List<SentenceSpan> {
        val out = spans.toMutableList()
        for (i in 0 until out.size - 1) {
            val span = out[i]
            val body = text.substring(span.start, span.end)
            val marker = TRAILING_LIST_MARKER.find(body) ?: continue
            val markerStart = span.start + marker.range.first + marker.value.indexOfFirst { it.isDigit() }
            var keepEnd = span.start + marker.range.first
            while (keepEnd > span.start && text[keepEnd - 1].isWhitespace()) keepEnd--
            if (keepEnd <= span.start) continue
            out[i] = SentenceSpan(span.start, keepEnd)
            out[i + 1] = SentenceSpan(markerStart, out[i + 1].end)
        }
        return out
    }

    /** A sentence end, then a lone number and its dot, at the very end of a span. */
    private val TRAILING_LIST_MARKER = Regex("(?<=[.!?])\\s+\\d{1,3}\\.$")

    /** A span that opens with an item number: "2. The wall itself." */
    private val LEADING_LIST_MARKER = Regex("^\\d{1,3}\\.\\s")

    private fun isFalseBreak(before: String, after: String): Boolean {
        // Punctuation on its own — a stray full stop, a footnote's "*" — is not something to
        // say; it belongs to the sentence it follows.
        val firstOfNext = after.firstOrNull { it.isLetterOrDigit() } ?: return true

        // A sentence almost never starts in lower case or with a digit, so whatever made the
        // break — an abbreviation not on the list, "p.m.", a dialogue tag after "?" in
        // "— Não? — disse ele." — the text carries on. Only while the result stays a sentence
        // the voice can say in one breath: Guimarães Rosa chains exclamations in lower case
        // across whole paragraphs, and one 1,300-character utterance means a long silence
        // while it is synthesized.
        if (before.length + after.length < MAX_CONTINUED_LENGTH) {
            if (firstOfNext.isLowerCase()) return true
            // A digit continues "No. 5" or "Jan. 12" — unless it is the next item's own number.
            if (firstOfNext.isDigit() && !LEADING_LIST_MARKER.containsMatchIn(after)) return true
        }

        if (!before.endsWith('.')) return false
        val words = before.trim().split(WHITESPACE)
        val token = words.last().trimStart(*OPENING_PUNCTUATION)
        val core = token.removeSuffix(".")
        return when {
            core.isEmpty() -> false
            core.lowercase() in ABBREVIATIONS -> true
            // "I." is the pronoun far more often than the numeral — "MENON: Not I. But look
            // here" — so it counts as a numeral only as a heading on its own or after a word
            // that numbers things: "Part I.", "Livro I.".
            core == "I" -> words.size == 1 ||
                words[words.size - 2].lowercase().trimEnd(',', ':') in NUMBERING_WORDS
            // An initial: "W." "H." "D." (D. for Dona). Upper case only — Portuguese "é." ends
            // sentences.
            core.length == 1 && core[0].isUpperCase() -> true
            // A Roman numeral: "II." "IV." "XIV." — "Livro IV. O rio".
            core.all { it in "IVXLCDM" } && ROMAN_NUMERAL.matches(core) -> true
            // A list marker or chapter number standing alone: "1." "12."
            words.size == 1 && core.all { it.isDigit() } -> true
            else -> false
        }
    }

    /** Past this, a lower-case continuation starts a new utterance instead of growing this one. */
    private const val MAX_CONTINUED_LENGTH = 400

    private val WHITESPACE = Regex("\\s+")

    /** Words after which "I." is a number: "Part I.", "Livro I.", "Capítulo I.". */
    private val NUMBERING_WORDS = setOf(
        "part", "book", "chapter", "act", "scene", "canto", "vol", "volume", "section", "lesson",
        "appendix", "livro", "parte", "capítulo", "cap", "ato", "cena", "tomo", "seção", "secção",
        "lição", "apêndice", "anexo", "tome", "partie", "chapitre", "libro", "teil", "kapitel", "band",
    )

    internal val OPENING_PUNCTUATION = charArrayOf('(', '[', '"', '\'', '«', '“', '‘', '—', '–', '¿', '¡')

    internal val ROMAN_NUMERAL = Regex("^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")

    /**
     * Abbreviations whose full stop does not end a sentence, lower-case and without the final
     * dot. For the languages this app reads — English and Portuguese first, a few Spanish,
     * French and German. Deliberately absent: words that are also whole sentences ("No.") and
     * abbreviations that usually do end one ("etc.", "Ltda.", "S.A.", "Inc."); when they don't,
     * the lower-case or digit that follows catches them.
     */
    internal val ABBREVIATIONS = setOf(
        // Titles and forms of address
        "mr", "mrs", "ms", "messrs", "dr", "dra", "drs", "dras", "sr", "sra", "srs", "sras",
        "srta", "srtas", "prof", "profa", "profs", "rev", "st", "sto", "sta", "jr", "gen", "col",
        "capt", "lt", "sgt", "gov", "sen", "dep", "eng", "arq", "exmo", "exma", "ilmo", "ilma",
        "pe", "fr", "mons", "mme", "mlle", "ud", "uds",
        // References
        "p", "pp", "pág", "págs", "pag", "pags", "cap", "caps", "ch", "chap", "vol", "vols",
        "fig", "figs", "art", "arts", "ed", "eds", "trans", "sec", "séc", "cf", "vs", "ref",
        "refs", "tab", "op", "cit", "ibid", "par", "núm", "nr", "vgl", "bzw", "ca", "ff",
        // Latin and the like, with their inner dots
        "e.g", "i.e", "a.m", "p.m", "viz", "a.c", "d.c", "z.b", "d.h", "u.a",
        // Everything else
        "approx", "dept", "av", "obs", "ex", "tel", "aprox", "evtl",
    )
}
