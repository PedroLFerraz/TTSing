package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.data.epub.Block
import kotlin.math.roundToInt

/**
 * Time-to-finish estimates. Everything is derived from character counts and a measured
 * speaking speed (characters/second at rate 1.0), scaled by the current speech rate.
 */
object ReadingEstimate {

    /** Characters left in the current chapter from [fromBlockIndex]/[fromOffsetInBlock] onwards. */
    fun remainingCharsInChapter(
        blocks: List<Block>,
        fromBlockIndex: Int,
        fromOffsetInBlock: Int,
    ): Int {
        var total = 0
        blocks.forEachIndexed { index, block ->
            if (block !is Block.Text) return@forEachIndexed
            when {
                index > fromBlockIndex -> total += block.text.length
                index == fromBlockIndex ->
                    total += (block.text.length - fromOffsetInBlock).coerceAtLeast(0)
            }
        }
        return total
    }

    /** Characters left in the whole book: rest of this chapter plus every later chapter. */
    fun remainingCharsInBook(
        remainingInChapter: Int,
        chapterCharCounts: List<Int>,
        chapterIndex: Int,
    ): Int {
        val later = chapterCharCounts
            .drop(chapterIndex + 1)
            .sum()
        return remainingInChapter + later
    }

    fun secondsFor(chars: Int, charsPerSecond: Float, speechRate: Float): Int {
        val effective = (charsPerSecond * speechRate).coerceAtLeast(1f)
        return (chars / effective).roundToInt()
    }

    /**
     * The duration to *show*, given the one on screen ([shown]) and a fresh estimate
     * ([next]). The fresh one is taken only when it has clearly moved — by more than most of
     * a display step — so a figure sitting on a rounding boundary cannot flick between
     * "5 h 10 min" and "5 h 15 min" as the speed wobbles. Real movement (reading on, jumping
     * chapters) always gets through.
     */
    fun steady(shown: Int?, next: Int): Int {
        if (shown == null) return next
        val step = if (maxOf(shown, next) >= 3600) 3 * 60 else 45
        return when {
            // Reading on: the figure comes down as soon as it has clearly moved.
            next <= shown -> if (shown - next < step) shown else next
            // Going up while reading forward is almost always a slow passage nudging the
            // speed, and it reads as the estimate being broken. Only a real rise gets
            // through — going back a chapter, a slower voice — at 2% or more.
            else -> if (next - shown < maxOf(step, (shown * 0.02f).toInt())) shown else next
        }
    }

    /**
     * "3 h 20 min", "12 min", "< 1 min".
     *
     * Past an hour the figure is rounded to 5 minutes. Precision beyond that is false —
     * nobody plans around "4 h 17 min" versus "4 h 18" — and it is what made the book total
     * tick on every page turn even when the underlying speed was steady.
     */
    fun formatDuration(seconds: Int): String {
        if (seconds < 60) return "< 1 min"
        val exactMinutes = seconds / 60
        val totalMinutes = if (exactMinutes >= 60) ((exactMinutes + 2) / 5) * 5 else exactMinutes
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0 -> "$minutes min"
            minutes == 0 -> "$hours h"
            else -> "$hours h $minutes min"
        }
    }
}
