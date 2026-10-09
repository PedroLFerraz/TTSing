package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.epub.SentenceSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPlaceTest {

    private fun slice(block: Int, start: Int, end: Int): ReaderPage =
        listOf(PageElement.TextEl(TextSlice(block, start, end, Block.Text.Kind.PARAGRAPH)))

    // Block 0 is a short heading; block 1 is a long paragraph of 1000 characters.
    private val smallFont: List<ReaderPage> = listOf(
        slice(0, 0, 20) + slice(1, 0, 300),
        slice(1, 300, 700),
        slice(1, 700, 1000),
    )
    private val bigFont: List<ReaderPage> = listOf(
        slice(0, 0, 20) + slice(1, 0, 100),
        slice(1, 100, 300),
        slice(1, 300, 500),
        slice(1, 500, 700),
        slice(1, 700, 850),
        slice(1, 850, 1000),
    )

    @Test
    fun `a bigger font puts the same text on a later page`() {
        // The reader was on the second small-font page, which began at offset 300.
        assertEquals(1, pageForAnchor(smallFont, 1, 300))
        assertEquals(2, pageForAnchor(bigFont, 1, 300))
    }

    @Test
    fun `a smaller font puts the same text on an earlier page`() {
        // On the fourth big-font page, which began at offset 500.
        assertEquals(3, pageForAnchor(bigFont, 1, 500))
        assertEquals(1, pageForAnchor(smallFont, 1, 500))
    }

    @Test
    fun `an anchor at the start of the chapter stays on the first page`() {
        assertEquals(0, pageForAnchor(bigFont, 0, 0))
        assertEquals(0, pageForAnchor(smallFont, 0, 0))
    }

    @Test
    fun `an anchor past the end lands on the last page`() {
        assertEquals(smallFont.lastIndex, pageForAnchor(smallFont, 7, 0))
        assertEquals(bigFont.lastIndex, pageForAnchor(bigFont, 1, 5000))
    }

    @Test
    fun `a block that was never laid out resolves to the page it would have sat on`() {
        // Block 2 is blank so has no page; the text around it sits on page 0 and after.
        val pages = listOf(slice(1, 0, 100), slice(3, 0, 100), slice(4, 0, 100))
        assertEquals(0, pageForAnchor(pages, 2, 0))
    }

    @Test
    fun `a page begun mid-sentence starts reading at the next sentence`() {
        val text = "One. Two is long. Three."
        val block = Block.Text(
            text,
            Block.Text.Kind.PARAGRAPH,
            listOf(SentenceSpan(0, 4), SentenceSpan(5, 17), SentenceSpan(18, 24)),
        )
        val blocks = listOf<Block>(block)
        // Page begins in the middle of "Two is long.".
        assertEquals(ReadingPosition(2, 0, 2), browsePosition(2, blocks, 0, 9))
        // Page begins exactly on a sentence.
        assertEquals(ReadingPosition(2, 0, 1), browsePosition(2, blocks, 0, 5))
        assertEquals(ReadingPosition(2, 0, 0), browsePosition(2, blocks, 0, 0))
        // Page begins in the last sentence: reading carries on in the next block.
        assertEquals(ReadingPosition(2, 1, 0), browsePosition(2, blocks, 0, 20))
    }

    @Test
    fun `speaking leaves the position alone`() {
        assertEquals(BrowseAction.NONE, browseAction(true, speakingHere = true, serviceHasBook = true, voiceOnPage = false))
    }

    @Test
    fun `paused in this book saves and parks the voice on the page`() {
        assertEquals(BrowseAction.SAVE_AND_PARK, browseAction(true, false, serviceHasBook = true, voiceOnPage = false))
    }

    @Test
    fun `a voice already on the page is left where it is`() {
        assertEquals(BrowseAction.NONE, browseAction(true, false, serviceHasBook = true, voiceOnPage = true))
    }

    @Test
    fun `another book in the service means saving only`() {
        assertEquals(BrowseAction.SAVE, browseAction(true, false, serviceHasBook = false, voiceOnPage = false))
    }

    @Test
    fun `opening a book or re-laying it out is not browsing`() {
        assertEquals(BrowseAction.NONE, browseAction(false, false, serviceHasBook = false, voiceOnPage = false))
        val a = VisiblePage(1, 3, 10, 0, 0)
        assertEquals(false, isNavigation(null, a, layoutChanged = false))
        assertEquals(false, isNavigation(a, VisiblePage(1, 5, 12, 0, 0), layoutChanged = true))
        assertEquals(true, isNavigation(a, VisiblePage(1, 4, 10, 0, 0), layoutChanged = false))
        assertEquals(true, isNavigation(a, VisiblePage(2, 3, 10, 0, 0), layoutChanged = false))
    }

    @Test
    fun `the same page reported again keeps what was decided`() {
        val a = VisiblePage(1, 3, 10, 0, 0)
        val again = a.copy(firstBlockIndex = 4, firstOffset = 12)
        assertEquals(true, isNavigation(a, again, layoutChanged = false, wasNavigation = true))
        assertEquals(false, isNavigation(a, again, layoutChanged = false, wasNavigation = false))
    }

    @Test
    fun `contents highlight the last entry at or before the chapter`() {
        val toc = listOf(0, 3, 3, 8, 12)
        assertEquals(0, currentTocIndex(toc, 0))
        assertEquals(0, currentTocIndex(toc, 2))
        assertEquals(2, currentTocIndex(toc, 3))
        assertEquals(2, currentTocIndex(toc, 7))
        assertEquals(3, currentTocIndex(toc, 9))
        assertEquals(4, currentTocIndex(toc, 500))
        assertEquals(-1, currentTocIndex(listOf(2, 5), 1))
        assertEquals(-1, currentTocIndex(emptyList(), 4))
    }

    @Test
    fun `an out-of-order contents still lights the latest entry before the chapter`() {
        assertEquals(0, currentTocIndex(listOf(6, 2, 4), 7))
    }

    @Test
    fun `pausing keeps following the last word instead of the sentence start`() {
        val position = ReadingPosition(1, 4, 2)
        val sentence = 100..260
        val last = WordAt(position, sentence, 210)
        // Speaking: the word.
        assertEquals(230, followOffset(230..236, position, sentence, last))
        // Paused mid-sentence: the word is gone, but the page stays where the word was.
        assertEquals(210, followOffset(null, position, sentence, last))
        // Paused, then skipped to another sentence: that sentence's start.
        assertEquals(300, followOffset(null, ReadingPosition(1, 4, 3), 300..400, last))
        // Nothing remembered.
        assertEquals(100, followOffset(null, position, sentence, null))
        assertEquals(null, followOffset(null, position, null, null))
    }

    @Test
    fun `progress is by characters, matching the voice's own saves`() {
        val blocks = listOf<Block>(
            Block.Text("a".repeat(100), Block.Text.Kind.PARAGRAPH, emptyList()),
            Block.Text("b".repeat(100), Block.Text.Kind.PARAGRAPH, emptyList()),
        )
        // Chapter 1 of three chapters of 200 characters, at its second block.
        assertEquals(0.5f, browseFraction(listOf(200, 200, 200), blocks, ReadingPosition(1, 1, 0), 3), 0.0001f)
        // No counts yet: the section-based guess.
        assertEquals(0.5f, browseFraction(emptyList(), blocks, ReadingPosition(1, 0, 0), 2), 0.0001f)
    }

    @Test
    fun `a sentence's start is looked up in its block`() {
        val block = Block.Text("One. Two.", Block.Text.Kind.PARAGRAPH, listOf(SentenceSpan(0, 4), SentenceSpan(5, 9)))
        assertEquals(5, sentenceStart(listOf(block), ReadingPosition(0, 0, 1)))
        assertEquals(0, sentenceStart(listOf(block), ReadingPosition(0, 3, 0)))
    }

    @Test
    fun `a page opening on an image parks on the text the voice would resolve to`() {
        val para = Block.Text("One. Two.", Block.Text.Kind.PARAGRAPH, listOf(SentenceSpan(0, 4), SentenceSpan(5, 9)))
        val blocks = listOf(Block.Image("a.png", null), Block.Image("b.png", null), para)
        assertEquals(ReadingPosition(3, 2, 0), browsePosition(3, blocks, 0, 0))
        // Nothing left to read in the chapter: its end, which the voice carries on from.
        assertEquals(ReadingPosition(3, 2, 0), browsePosition(3, blocks, 2, 0))
        assertEquals(ReadingPosition(3, 3, 0), browsePosition(3, blocks, 2, 7))
    }

    @Test
    fun `the card sheet's pause never parks the voice`() {
        assertEquals(
            BrowseAction.NONE,
            browseAction(true, false, serviceHasBook = true, voiceOnPage = false, cardSheetOpen = true),
        )
        assertEquals(
            BrowseAction.SAVE_AND_PARK,
            browseAction(true, false, serviceHasBook = true, voiceOnPage = false, cardSheetOpen = false),
        )
    }

    @Test
    fun `play right after a swipe starts at the browsed page`() {
        val browsed = ReadingPosition(1, 7, 0)
        assertEquals(browsed, playStartAfterBrowse(true, serviceHasBook = true, voiceOnPage = false, browsed = browsed))
        // The service holds another book: the browse is only saved, so play from it all the same.
        assertEquals(browsed, playStartAfterBrowse(true, serviceHasBook = false, voiceOnPage = false, browsed = browsed))
        // Nothing browsed, or the voice is already on the page: play from where the voice is parked.
        assertEquals(null, playStartAfterBrowse(false, serviceHasBook = true, voiceOnPage = false, browsed = browsed))
        assertEquals(null, playStartAfterBrowse(true, serviceHasBook = true, voiceOnPage = true, browsed = browsed))
    }
}
