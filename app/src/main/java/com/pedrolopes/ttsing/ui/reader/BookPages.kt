package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.data.epub.Block

/**
 * The exact area pages are laid out in, and the font scale — everything a page count depends
 * on. Its [key] is what cached page counts are stored against, so a rotation or a font-size
 * change recounts and nothing else does.
 */
data class PageGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val fontScale: Float,
    val density: Float,
) {
    /** Bump [LAYOUT_VERSION] whenever pagination itself changes, so stale counts are dropped. */
    val key: String
        get() = "v$LAYOUT_VERSION:${widthPx}x$heightPx@${"%.2f".format(java.util.Locale.ROOT, fontScale)}" +
            "d${"%.3f".format(java.util.Locale.ROOT, density)}"

    private companion object {
        const val LAYOUT_VERSION = 1
    }
}

/**
 * True when a chapter has something to show. EPUBs are full of spine items that don't: a
 * blank page before a part title, an empty wrapper. Those count as zero pages and the reader
 * flows past them.
 */
fun List<Block>.hasReadableContent(): Boolean =
    any { (it is Block.Text && it.text.isNotBlank()) || it is Block.Image }

/**
 * Whole-book page arithmetic over per-chapter page counts, the way KOReader numbers pages:
 * one continuous run from the first page of the book to the last.
 */
object BookPages {

    fun total(counts: List<Int>): Int = counts.sum()

    /** Book page (0-based) that [chapter] starts on. */
    fun firstPageOf(counts: List<Int>, chapter: Int): Int = counts.take(chapter.coerceAtLeast(0)).sum()

    /** 1-based page number in the whole book. */
    fun bookPage(counts: List<Int>, chapter: Int, pageInChapter: Int): Int =
        firstPageOf(counts, chapter) + pageInChapter.coerceAtLeast(0) + 1

    /** How far through the book that page is: 1.0 on the last page. */
    fun fraction(counts: List<Int>, chapter: Int, pageInChapter: Int): Float {
        val total = total(counts)
        if (total <= 0) return 0f
        return (bookPage(counts, chapter, pageInChapter).toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * Where each chapter in [chapterStarts] begins along the book, as fractions for tick marks
     * on the progress bar. More than [max] and they would merge into a solid comb, so none are
     * drawn — the bar still works, it just stops pretending to mark chapters.
     */
    fun chapterTicks(counts: List<Int>, chapterStarts: List<Int>, max: Int = 60): List<Float> {
        val total = total(counts).takeIf { it > 0 } ?: return emptyList()
        val ticks = chapterStarts
            .filter { it in 1 until counts.size }
            .map { firstPageOf(counts, it).toFloat() / total }
            .filter { it > 0f && it < 1f }
            .distinct()
            .sorted()
        return if (ticks.size > max) emptyList() else ticks
    }
}
