package com.pedrolopes.ttsing.tts.piper

/**
 * Cuts a long sentence into clause-sized pieces before it is synthesized.
 *
 * onnxruntime sizes its working memory for the longest input it has seen and never gives it
 * back, so one Guimarães Rosa sentence — they run past a thousand characters — costs the app
 * hundreds of megabytes for the rest of the session. Measured on a desktop CPU, synthesizing
 * in pieces of 100 characters peaked at 247 MB, 220 characters at 447 MB, and a whole
 * 1200-character sentence at 841 MB.
 *
 * Cutting at a comma or a dash costs nothing in speed (the same audio comes out in the same
 * time) and only puts a breath where the writing already has one.
 */
object PiperChunks {

    /** About a clause: long enough to carry the phrasing, short enough to stay cheap. */
    const val LIMIT = 160

    /** Where a sentence may be broken, best first. */
    private val BREAKS = listOf("; ", ", ", " — ", " – ", ": ", " e ", " que ")

    fun split(text: String, limit: Int = LIMIT): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= limit) return listOf(trimmed)
        val pieces = mutableListOf<String>()
        var rest = trimmed
        while (rest.length > limit) {
            val window = rest.take(limit)
            // A break too near the start would leave a scrap of a phrase on its own.
            val cut = BREAKS.firstNotNullOfOrNull { mark ->
                window.lastIndexOf(mark).takeIf { it >= limit / 3 }?.plus(mark.length)
            }
                ?: window.lastIndexOf(' ').takeIf { it >= limit / 3 }?.plus(1)
                ?: limit
            pieces.add(rest.take(cut).trim())
            rest = rest.drop(cut).trim()
        }
        if (rest.isNotEmpty()) pieces.add(rest)
        return pieces.filter { it.isNotEmpty() }
    }
}
