package com.pedrolopes.ttsing.ui.reader

import kotlin.math.roundToInt

/**
 * Time left, the way KOReader works it out (statistics.koplugin: `getTimeForPages(pages) =
 * pages * avg_time`), with its per-book average taken from listening rather than from page
 * turns:
 *
 * - The pace is **this book's**: total time the voice has spent reading it over the characters
 *   it has read, every sentence counted. It never decays and never mixes books, so it settles
 *   as the book goes on. A book not yet listened to starts from the voice's measured speed,
 *   which carries the weight of [PRIOR_MS] and is outweighed as the book's own history grows.
 * - Time left is **pages left × seconds per page**, pages being the real, counted pages of the
 *   book, so both figures agree with the page numbers in the footer.
 * - The book figure is **the chapter figure plus a whole number of minutes** for the chapters
 *   after this one. While a chapter is read, that second part stays put, so each minute off the
 *   chapter is exactly one minute off the book.
 */
object TimeLeft {

    /** How much the voice's own speed counts for before this book has a history: five minutes. */
    const val PRIOR_MS = 5 * 60_000.0

    /**
     * Seconds per character at rate 1.0 for this book: its own listening ([bookMs] over
     * [bookChars]) blended with [voiceCps] worth [PRIOR_MS] of listening.
     */
    fun secondsPerChar(bookMs: Long, bookChars: Long, voiceCps: Float): Double {
        val cps = voiceCps.coerceIn(1f, 200f).toDouble()
        val priorChars = cps * PRIOR_MS / 1000.0
        return (bookMs.coerceAtLeast(0) + PRIOR_MS) / 1000.0 / (bookChars.coerceAtLeast(0) + priorChars)
    }

    /**
     * Seconds per page at speech [rate] — KOReader's `avg_time` — from the book's pace and its
     * characters per page. Null until the book's characters and pages are both counted.
     */
    fun secondsPerPage(
        bookMs: Long,
        bookChars: Long,
        voiceCps: Float,
        bookTotalChars: Int,
        bookTotalPages: Int,
        rate: Float,
    ): Double? {
        if (bookTotalChars <= 0 || bookTotalPages <= 0) return null
        return secondsPerChar(bookMs, bookChars, voiceCps) * bookTotalChars / bookTotalPages / rate.coerceAtLeast(0.1f)
    }

    /** What the footer shows: the chapter's time left, and the book's in whole minutes. */
    data class Estimate(val chapterSeconds: Int, val bookMinutes: Int) {
        val chapterMinutes: Int get() = chapterSeconds / 60
    }

    /**
     * Pages left in [chapter] from [pageInChapter] (the page being read counts: it hasn't been
     * finished), and pages in every later chapter, at [secondsPerPage].
     */
    fun byPages(pageCounts: List<Int>, chapter: Int, pageInChapter: Int, secondsPerPage: Double): Estimate {
        val pagesLeftInChapter = (pageCounts.getOrElse(chapter) { 0 } - pageInChapter).coerceAtLeast(0)
        val pagesAfter = pageCounts.drop(chapter + 1).sum()
        return combine(pagesLeftInChapter * secondsPerPage, pagesAfter * secondsPerPage)
    }

    /** The same while pages are still being counted: characters left, at [secondsPerChar] (rate applied). */
    fun byCharacters(charsLeftInChapter: Int, charsAfterChapter: Int, secondsPerChar: Double): Estimate =
        combine(charsLeftInChapter * secondsPerChar, charsAfterChapter * secondsPerChar)

    private fun combine(chapterSeconds: Double, laterSeconds: Double): Estimate {
        val chapter = chapterSeconds.roundToInt().coerceAtLeast(0)
        // Later chapters as a whole number of minutes, added to the chapter's minutes: the
        // book then moves in step with the chapter instead of rounding on its own.
        val later = (laterSeconds / 60.0).roundToInt().coerceAtLeast(0)
        return Estimate(chapter, chapter / 60 + later)
    }
}
