package com.pedrolopes.ttsing.data.pdf

/**
 * Repairs to a single line's text that need the positions of its characters, kept apart from
 * PDFBox so they can be tested on plain data.
 */
object PdfText {

    /** One drawn character (or ligature): its text, and its left and right edge on the line. */
    data class Piece(val text: String, val left: Float, val right: Float)

    /**
     * A letter-spaced line — small caps set with wide tracking, as in "B A S I C  C O M M A N D S"
     * — rebuilt with spaces only between words, or null when [text] is not letter-spaced.
     * PDFBox reads the tracking as word gaps; the real word gaps are the ones clearly wider
     * than the rest. Small caps often come out in mixed case ("BasiC CommanDs"), which the
     * voice would spell, so each word keeps only its first letter's case.
     */
    fun respaced(text: String, pieces: List<Piece>, fontSize: Float): String? {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        val single = tokens.count { it.length == 1 && it[0].isLetter() }
        if (tokens.size < 4 || single < tokens.size * 0.6f) return spacedLabel(text)
        val ink = pieces.filter { it.text.isNotBlank() }
        if (ink.size < 4) return null
        val gaps = ink.zipWithNext { a, b -> b.left - a.right }
        val median = gaps.sorted()[gaps.size / 2]
        val wordGap = median + maxOf(fontSize, 1f) * 0.15f
        val out = StringBuilder()
        var previous: Piece? = null
        var blankBetween = false
        for (piece in pieces) {
            if (piece.text.isBlank()) {
                blankBetween = previous != null
                continue
            }
            val gap = previous?.let { piece.left - it.right }
            if (gap != null && (gap > wordGap || blankBetween && gap > median)) out.append(' ')
            out.append(piece.text)
            previous = piece
            blankBetween = false
        }
        return out.split(' ').joinToString(" ") { evenCase(it) }
    }

    /** A letter-spaced word opening a line of ordinary text: "n o t E  For more details…". */
    private val spacedPrefix = Regex("""^(\p{L}(?: \p{L}){2,})(?= {2}| \p{L}{2})""")

    private val labels = setOf("note", "tip", "warning", "caution", "important", "nota", "dica", "aviso", "atenção", "cuidado")

    /**
     * A line opening with a letter-spaced label — a note box's "n o t E" — with the label made
     * a word again, and a colon after it when it is one of the labels books set this way, so
     * the voice pauses as the eye does. Null when the line has no such label.
     */
    private fun spacedLabel(text: String): String? {
        val match = spacedPrefix.find(text) ?: return null
        val word = evenCase(match.groupValues[1].replace(" ", "")).lowercase()
        val rest = text.substring(match.range.last + 1).trimStart()
        return if (word in labels) word.replaceFirstChar { it.uppercase() } + ": " + rest else "$word $rest"
    }

    /** "BasiC" → "Basic", "notE" → "note"; an all-capitals word or an ordinary one is left alone. */
    private fun evenCase(word: String): String {
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty() || letters.all { it.isUpperCase() }) return word
        val mixed = letters.drop(1).any { it.isUpperCase() } && letters.any { it.isLowerCase() }
        return if (mixed) word.take(1) + word.drop(1).lowercase() else word
    }
}
