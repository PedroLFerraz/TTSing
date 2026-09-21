package com.pedrolopes.ttsing.ui.reader

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.ui.theme.AppFonts

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

// ---- Shared typography (used for BOTH measuring and rendering so pages match) ----

private const val LINE_HEIGHT_EM = 1.62f

fun blockFontSizeSp(kind: Block.Text.Kind): Float = when (kind) {
    Block.Text.Kind.HEADING_1 -> 26f
    Block.Text.Kind.HEADING_2 -> 22f
    Block.Text.Kind.HEADING_3 -> 19f
    Block.Text.Kind.QUOTE -> 17f
    Block.Text.Kind.PARAGRAPH -> 19f
}

fun blockVerticalPadding(kind: Block.Text.Kind): Dp = when (kind) {
    Block.Text.Kind.PARAGRAPH -> 8.dp
    else -> 12.dp
}

fun blockTextStyle(kind: Block.Text.Kind, fontScale: Float): TextStyle {
    val sizeValue = blockFontSizeSp(kind) * fontScale
    val isBody = kind == Block.Text.Kind.PARAGRAPH || kind == Block.Text.Kind.QUOTE
    return TextStyle(
        fontSize = sizeValue.sp,
        lineHeight = (sizeValue * LINE_HEIGHT_EM).sp,
        fontWeight = if (isBody) FontWeight.Normal else FontWeight.SemiBold,
        // The whole page is the book's own voice, headings included, so it is all serif.
        fontFamily = AppFonts.Serif,
    )
}

/** Fixed display height for an inline image (kept identical in measure + render). */
val ImageDisplayHeight: Dp = 260.dp
private val ImageVerticalPadding: Dp = 12.dp

/**
 * Splits a chapter's [blocks] into pages that fit [contentWidthPx] x [contentHeightPx],
 * breaking long text blocks at line boundaries. Pure function of its inputs, so it can be
 * safely cached with `remember`.
 */
fun paginateChapter(
    blocks: List<Block>,
    contentWidthPx: Int,
    contentHeightPx: Int,
    measurer: TextMeasurer,
    density: Density,
    fontScale: Float,
): List<ReaderPage> {
    if (contentWidthPx <= 0 || contentHeightPx <= 0) return listOf(emptyList())

    val pages = mutableListOf<ReaderPage>()
    var current = mutableListOf<PageElement>()
    var used = 0f
    val widthConstraints = Constraints(maxWidth = contentWidthPx)

    fun flush() {
        if (current.isNotEmpty()) {
            pages.add(current)
            current = mutableListOf()
            used = 0f
        }
    }

    val imageHeightPx = with(density) { (ImageDisplayHeight + ImageVerticalPadding * 2).toPx() }

    blocks.forEachIndexed { index, block ->
        when (block) {
            is Block.Text -> {
                if (block.text.isBlank()) return@forEachIndexed
                val style = blockTextStyle(block.kind, fontScale)
                val vPad = with(density) { blockVerticalPadding(block.kind).toPx() } * 2
                val layout = measurer.measure(text = block.text, style = style, constraints = widthConstraints)
                val lineCount = layout.lineCount
                var startLine = 0
                while (startLine < lineCount) {
                    val avail = contentHeightPx - used - vPad
                    val firstLineHeight = layout.getLineBottom(startLine) - layout.getLineTop(startLine)
                    if (avail < firstLineHeight && used > 0f) {
                        flush()
                        continue
                    }
                    var endLine = startLine
                    while (endLine + 1 < lineCount &&
                        (layout.getLineBottom(endLine + 1) - layout.getLineTop(startLine)) <=
                        (contentHeightPx - used - vPad)
                    ) {
                        endLine++
                    }
                    val sliceStart = layout.getLineStart(startLine)
                    val sliceEnd = layout.getLineEnd(endLine)
                    current.add(PageElement.TextEl(TextSlice(index, sliceStart, sliceEnd, block.kind)))
                    used += (layout.getLineBottom(endLine) - layout.getLineTop(startLine)) + vPad
                    startLine = endLine + 1
                    if (startLine < lineCount) flush()
                }
            }

            is Block.Image -> {
                if (used > 0f && used + imageHeightPx > contentHeightPx) flush()
                current.add(PageElement.ImageEl(index, block.zipPath, block.alt))
                used += imageHeightPx
                if (used >= contentHeightPx) flush()
            }
        }
    }
    flush()
    return pages.ifEmpty { listOf(emptyList()) }
}

/** Page index that contains [offset] within [blockIndex]'s text, else the block's first page. */
fun pageIndexForOffset(pages: List<ReaderPage>, blockIndex: Int, offset: Int): Int? {
    pages.forEachIndexed { i, page ->
        for (el in page) {
            if (el is PageElement.TextEl && el.slice.blockIndex == blockIndex &&
                offset >= el.slice.start && offset < el.slice.end
            ) {
                return i
            }
        }
    }
    pages.forEachIndexed { i, page ->
        for (el in page) {
            val b = when (el) {
                is PageElement.TextEl -> el.slice.blockIndex
                is PageElement.ImageEl -> el.blockIndex
            }
            if (b == blockIndex) return i
        }
    }
    return null
}

/** Intersects [range] (offsets into the full block) with a slice and shifts to slice-local. */
fun IntRange.toSliceLocal(sliceStart: Int, sliceEnd: Int): IntRange? {
    val a = maxOf(first, sliceStart)
    val b = minOf(last, sliceEnd - 1)
    if (a > b) return null
    return (a - sliceStart)..(b - sliceStart)
}
