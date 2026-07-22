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

    /** "3 h 20 min", "12 min", "< 1 min". */
    fun formatDuration(seconds: Int): String {
        if (seconds < 60) return "< 1 min"
        val totalMinutes = seconds / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0 -> "$minutes min"
            minutes == 0 -> "$hours h"
            else -> "$hours h $minutes min"
        }
    }
}
