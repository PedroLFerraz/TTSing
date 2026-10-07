package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.ReadingPosition

/*
 * Decisions about where the reader is in the text, kept apart from the screen so they can be
 * tested: which page keeps the same text on screen after a re-layout, what position a page
 * stands for, and when browsing moves the saved place or the parked voice.
 */

/**
 * The page of one chapter's [pages] to show so the text at [offset] of [blockIndex] stays on
 * screen — the page that now contains it, wherever a different font size or screen shape has
 * moved it. A block with no page of its own (blank, so never laid out) resolves to the page
 * it would have sat on; an anchor past the end resolves to the last page.
 */
internal fun pageForAnchor(pages: List<ReaderPage>, blockIndex: Int, offset: Int): Int {
    fun blockOf(element: PageElement) = when (element) {
        is PageElement.TextEl -> element.slice.blockIndex
        is PageElement.ImageEl -> element.blockIndex
    }
    pages.forEachIndexed { i, page ->
        if (page.any { it is PageElement.TextEl && it.slice.blockIndex == blockIndex && offset >= it.slice.start && offset < it.slice.end }) {
            return i
        }
    }
    // The block is there but the offset is not (an image, or text that has since shortened).
    pages.indexOfLast { page -> page.any { blockOf(it) == blockIndex } }.let { if (it >= 0) return it }
    return pages.indexOfLast { page -> page.firstOrNull()?.let { blockOf(it) <= blockIndex } == true }.coerceAtLeast(0)
}

/**
 * Where the voice would start to read what is on a page: its first sentence that *starts*
 * on the page. The page's first text may be the tail of a sentence begun on the page before;
 * reading from that sentence's start would repeat what the reader has already turned past.
 * When no sentence in the block starts at or after [firstOffset], reading starts at the next
 * block.
 */
internal fun browsePosition(
    chapterIndex: Int,
    blocks: List<Block>?,
    firstBlockIndex: Int,
    firstOffset: Int,
): ReadingPosition {
    val text = blocks?.getOrNull(firstBlockIndex) as? Block.Text ?: return ReadingPosition(chapterIndex, firstBlockIndex, 0)
    val sentence = text.sentences.indexOfFirst { it.start >= firstOffset }
    return if (sentence >= 0) {
        ReadingPosition(chapterIndex, firstBlockIndex, sentence)
    } else {
        ReadingPosition(chapterIndex, firstBlockIndex + 1, 0)
    }
}

/** Character offset in its block at which [position]'s sentence begins; 0 when unknown. */
internal fun sentenceStart(blocks: List<Block>, position: ReadingPosition): Int =
    (blocks.getOrNull(position.blockIndex) as? Block.Text)?.sentences?.getOrNull(position.sentenceIndex)?.start ?: 0

/**
 * How far through the book [position] is, by characters — the measure the voice's own saves
 * use, so the library shows one figure whichever of the two saved the place. Falls back to a
 * section-based guess before the book's character counts exist.
 */
internal fun browseFraction(
    chapterCharCounts: List<Int>,
    blocks: List<Block>?,
    position: ReadingPosition,
    sectionCount: Int,
): Float {
    val total = chapterCharCounts.sum()
    if (total > 0 && blocks != null) {
        val before = chapterCharCounts.take(position.chapterIndex).sum() +
            blocks.take(position.blockIndex).filterIsInstance<Block.Text>().sumOf { it.text.length }
        return (before.toFloat() / total).coerceIn(0f, 1f)
    }
    val count = (blocks?.size ?: 0).coerceAtLeast(1)
    return ((position.chapterIndex + position.blockIndex.toFloat() / count) / sectionCount.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** What settling on a page does to the saved place and to the voice. */
internal enum class BrowseAction { NONE, SAVE, SAVE_AND_PARK }

/**
 * Browsing the text moves the place that is remembered, but never the voice's while it is
 * speaking: the service owns the position then. When the voice is paused in this book it is
 * parked at the page on screen too, so Play reads what is in front of the reader — unless it
 * is already on that page. A reader not looking at the book the service holds just saves.
 *
 * [navigated] is false for the page a book opens on and for pages that only moved because the
 * layout changed: neither is the reader going anywhere.
 */
internal fun browseAction(
    navigated: Boolean,
    speakingHere: Boolean,
    serviceHasBook: Boolean,
    voiceOnPage: Boolean,
): BrowseAction = when {
    !navigated || speakingHere -> BrowseAction.NONE
    !serviceHasBook -> BrowseAction.SAVE
    voiceOnPage -> BrowseAction.NONE
    else -> BrowseAction.SAVE_AND_PARK
}

/**
 * Whether a newly reported page is the reader moving. The same page reported again (the window
 * recentred, a PDF's text finished loading) leaves things as they were, [wasNavigation]; the
 * first page reported, and any page that shows up because the layout changed, are not the
 * reader going anywhere.
 */
internal fun isNavigation(
    previous: VisiblePage?,
    now: VisiblePage,
    layoutChanged: Boolean,
    wasNavigation: Boolean = false,
): Boolean = when {
    previous == null || layoutChanged -> false
    previous.chapterIndex == now.chapterIndex && previous.pageInChapter == now.pageInChapter -> wasNavigation
    else -> true
}

/**
 * The contents entry the reader is in: among the entries starting at or before [chapter], the
 * one that starts latest (the last of them when several start together). Entries rarely line
 * up with every spine item, so an exact match would leave most chapters with nothing lit.
 */
internal fun currentTocIndex(spineIndexes: List<Int>, chapter: Int): Int {
    var best = -1
    spineIndexes.forEachIndexed { i, spine ->
        if (spine <= chapter && (best < 0 || spine >= spineIndexes[best])) best = i
    }
    return best
}

/** The last word spoken, and the sentence (in the block at [position]) it was in. */
internal data class WordAt(val position: ReadingPosition, val sentence: IntRange?, val offset: Int)

/**
 * Where the page should follow the voice to. The word being spoken moves the page exactly when
 * the highlight crosses a boundary; pausing clears the word, and falling back to the start of
 * its sentence would flip back a page when the sentence began on the previous one. So while the
 * sentence is unchanged the last word's offset is kept.
 */
internal fun followOffset(
    word: IntRange?,
    position: ReadingPosition,
    sentence: IntRange?,
    last: WordAt?,
): Int? = when {
    word != null -> word.first
    last != null && last.position == position && last.sentence == sentence -> last.offset
    else -> sentence?.first
}
