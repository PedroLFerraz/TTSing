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
     * Selects the word at [start, end), or extends the current selection to cover it, so
     * multi-word expressions ("deu de ombros", "look forward to") can be picked as one
     * target. Tapping the only selected word again clears the selection.
     */
    fun withWordAt(start: Int, end: Int): CardDraft = when {
        !hasTarget -> copy(targetStart = start, targetEnd = end)
        start == targetStart && end == targetEnd -> copy(targetStart = null, targetEnd = null)
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
