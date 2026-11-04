package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.data.epub.Block

/** A contiguous run of one text block's characters that fits on a page. */
data class TextSlice(
    val blockIndex: Int,
    val start: Int,
    val end: Int,
    val kind: Block.Text.Kind,
)

sealed interface PageElement {
    data class TextEl(val slice: TextSlice) : PageElement
    data class ImageEl(val blockIndex: Int, val zipPath: String, val alt: String?) : PageElement
}

typealias ReaderPage = List<PageElement>

/** The page on screen, as the pager reports it: where it is in its chapter, and where it starts. */
data class VisiblePage(
    val chapterIndex: Int,
    val pageInChapter: Int,
    val pagesInChapter: Int,
    /** First text on the page — the reading position when the voice isn't playing. */
    val firstBlockIndex: Int,
    val firstOffset: Int,
)
