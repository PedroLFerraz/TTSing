package com.pedrolopes.ttsing.anki

/**
 * A flashcard being composed in the reader: one sentence from the book, the span of the
 * word the reader didn't know, and what it means.
 *
 * Maps onto the note type created by [AnkiExporter]:
 * `Frente` = [frontHtml], `Audio` = synthesized sentence, `Verso` = [meaning].
 */
data class CardDraft(
    val sentence: String,
    /** Half-open [start, end) into [sentence]; null until the user picks a word. */
    val targetStart: Int? = null,
    val targetEnd: Int? = null,
    val meaning: String = "",
    val bookTitle: String = "",
    val languageTag: String? = null,
) {

    val hasTarget: Boolean get() = targetStart != null && targetEnd != null && targetEnd > targetStart

    /** The selected word(s), or an empty string before a selection is made. */
    val targetWord: String
        get() = if (hasTarget) sentence.substring(targetStart!!, targetEnd!!) else ""

    /**
     * Selects the word at [start, end), or extends/shrinks the current selection:
     * - Tapping the same word clears the selection.
     * - Tapping the first or last word removes that word from that end (skipping inter-word spaces).
     * - Tapping a word strictly inside shrinks the selection to end at that word.
     * - Multi-word expressions ("deu de ombros", "look forward to") can be picked as one target.
     */
    fun withWordAt(start: Int, end: Int): CardDraft = when {
        !hasTarget -> copy(targetStart = start, targetEnd = end)
        start == targetStart && end == targetEnd -> copy(targetStart = null, targetEnd = null)
        // Tapping the first word of a multi-word selection: remove it from the start,
        // advancing past the word and any trailing space(s).
        start == targetStart && end < targetEnd!! -> {
            var newStart = end
            while (newStart < sentence.length && sentence[newStart].isWhitespace()) newStart++
            if (newStart >= targetEnd!!) copy(targetStart = null, targetEnd = null) else copy(targetStart = newStart)
        }
        // Tapping the last word of a multi-word selection: remove it from the end,
        // backing up past the word and any preceding space(s).
        start > targetStart!! && end == targetEnd -> {
            var newEnd = start
            while (newEnd > targetStart!! && sentence[newEnd - 1].isWhitespace()) newEnd--
            if (newEnd <= targetStart!!) copy(targetStart = null, targetEnd = null) else copy(targetEnd = newEnd)
        }
        // Tapping a word strictly inside: shrink to end at it.
        start > targetStart!! && end < targetEnd!! -> copy(targetEnd = end)
        // Tapping a word before the selection: extend backwards.
        start < targetStart!! -> copy(targetStart = start)
        // Tapping a word after the selection: extend forwards.
        end > targetEnd!! -> copy(targetEnd = end)
        // Shouldn't reach here, but extend to encompass both as a fallback.
        else -> copy(
            targetStart = minOf(targetStart!!, start),
            targetEnd = maxOf(targetEnd!!, end),
        )
    }

    /** The sentence as Anki HTML, with the unknown word in bold. */
    fun frontHtml(): String {
        if (!hasTarget) return escapeHtml(sentence)
        val start = targetStart!!.coerceIn(0, sentence.length)
        val end = targetEnd!!.coerceIn(start, sentence.length)
        return buildString {
            append(escapeHtml(sentence.substring(0, start)))
            append("<b>")
            append(escapeHtml(sentence.substring(start, end)))
            append("</b>")
            append(escapeHtml(sentence.substring(end)))
        }
    }

    /** Anki tags: a fixed marker plus a slug of the book, so cards stay filterable by source. */
    fun tags(): Set<String> = setOfNotNull(SOURCE_TAG, slugify(bookTitle))

    companion object {
        const val SOURCE_TAG = "ttsing"

        fun escapeHtml(text: String): String = buildString(text.length) {
            for (char in text) {
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(char)
                }
            }
        }

        /** Anki tags cannot contain spaces, so book titles become `the-little-garden`. */
        fun slugify(title: String): String? {
            val slug = title.trim().lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .trim('-')
                .replace(Regex("-+"), "-")
            return slug.ifEmpty { null }
        }
    }
}
