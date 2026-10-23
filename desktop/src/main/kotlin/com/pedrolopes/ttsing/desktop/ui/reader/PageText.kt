package com.pedrolopes.ttsing.desktop.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.Note
import com.pedrolopes.ttsing.desktop.ReaderTheme
import com.pedrolopes.ttsing.desktop.ui.theme.AppFonts
import com.pedrolopes.ttsing.desktop.ui.theme.Ink
import com.pedrolopes.ttsing.ui.reader.PageElement
import com.pedrolopes.ttsing.ui.reader.ReaderPage
import com.pedrolopes.ttsing.ui.reader.TextSlice

/*
 * The page as the Android reader sets it (ui/reader/Paginator.kt and Highlight.kt there): the
 * same sizes, spacing and colours, measured and drawn with the same styles so pages match.
 */

private const val LINE_HEIGHT_EM = 1.62f

fun blockFontSizeSp(kind: Block.Text.Kind): Float = when (kind) {
    Block.Text.Kind.HEADING_1 -> 26f
    Block.Text.Kind.HEADING_2 -> 22f
    Block.Text.Kind.HEADING_3 -> 19f
    Block.Text.Kind.QUOTE -> 17f
    Block.Text.Kind.PARAGRAPH -> 19f
    Block.Text.Kind.CODE -> 15f
}

fun blockVerticalPadding(kind: Block.Text.Kind): Dp = when (kind) {
    Block.Text.Kind.PARAGRAPH -> 8.dp
    else -> 12.dp
}

fun blockStartIndent(kind: Block.Text.Kind): Dp = when (kind) {
    Block.Text.Kind.QUOTE -> 12.dp
    else -> 0.dp
}

fun blockTextStyle(kind: Block.Text.Kind, fontScale: Float): TextStyle {
    val size = blockFontSizeSp(kind) * fontScale
    val isBody = kind == Block.Text.Kind.PARAGRAPH || kind == Block.Text.Kind.QUOTE || kind == Block.Text.Kind.CODE
    return TextStyle(
        fontSize = size.sp,
        lineHeight = (size * LINE_HEIGHT_EM).sp,
        fontWeight = if (isBody) FontWeight.Normal else FontWeight.SemiBold,
        fontFamily = if (kind == Block.Text.Kind.CODE) FontFamily.Monospace else AppFonts.Serif,
    )
}

val ImageDisplayHeight: Dp = 300.dp
private val ImageVerticalPadding: Dp = 12.dp

/** Splits a chapter's [blocks] into pages that fit [widthPx] x [heightPx], breaking text at line ends. */
fun paginateChapter(
    blocks: List<Block>,
    widthPx: Int,
    heightPx: Int,
    measurer: TextMeasurer,
    density: Density,
    fontScale: Float,
): List<ReaderPage> {
    if (widthPx <= 0 || heightPx <= 0) return listOf(emptyList())
    val pages = mutableListOf<ReaderPage>()
    var current = mutableListOf<PageElement>()
    var used = 0f

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
                val indent = with(density) { blockStartIndent(block.kind).roundToPx() }
                val layout = measurer.measure(block.text, style, constraints = Constraints(maxWidth = (widthPx - indent).coerceAtLeast(1)))
                var startLine = 0
                while (startLine < layout.lineCount) {
                    val firstLine = layout.getLineBottom(startLine) - layout.getLineTop(startLine)
                    if (heightPx - used - vPad < firstLine && used > 0f) {
                        flush()
                        continue
                    }
                    var endLine = startLine
                    while (endLine + 1 < layout.lineCount &&
                        layout.getLineBottom(endLine + 1) - layout.getLineTop(startLine) <= heightPx - used - vPad
                    ) {
                        endLine++
                    }
                    current.add(PageElement.TextEl(TextSlice(index, layout.getLineStart(startLine), layout.getLineEnd(endLine), block.kind)))
                    used += layout.getLineBottom(endLine) - layout.getLineTop(startLine) + vPad
                    startLine = endLine + 1
                    if (startLine < layout.lineCount) flush()
                }
            }
            is Block.Image -> {
                if (used > 0f && used + imageHeightPx > heightPx) flush()
                current.add(PageElement.ImageEl(index, block.zipPath, block.alt))
                used += imageHeightPx
                if (used >= heightPx) flush()
            }
        }
    }
    flush()
    return pages.ifEmpty { listOf(emptyList()) }
}

/** The page of [pages] holding [offset] of [blockIndex], if any does. */
fun pageHolding(pages: List<ReaderPage>, blockIndex: Int, offset: Int): Int? =
    pages.indexOfFirst { page ->
        page.any { it is PageElement.TextEl && it.slice.blockIndex == blockIndex && offset >= it.slice.start && offset < it.slice.end }
    }.takeIf { it >= 0 }

/** [this] (offsets into a whole block) cut to a slice, in the slice's own offsets. */
fun IntRange.toSliceLocal(sliceStart: Int, sliceEnd: Int): IntRange? {
    val a = maxOf(first, sliceStart)
    val b = minOf(last, sliceEnd - 1)
    return if (a > b) null else (a - sliceStart)..(b - sliceStart)
}

data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val sentenceHighlight: Color,
    val sentenceText: Color?,
    val wordHighlight: Color,
    val wordText: Color,
    val accent: Color,
    val hairline: Color,
)

fun paletteFor(theme: ReaderTheme): ReaderPalette = when (theme) {
    ReaderTheme.SEPIA -> ReaderPalette(
        background = Color(0xFFF4ECD8),
        text = Color(0xFF2A2018),
        secondaryText = Color(0xFF6E5F4C),
        sentenceHighlight = Color(0xFFE9DCB4),
        sentenceText = null,
        wordHighlight = Ink.LiveOnPaper,
        wordText = Color(0xFFFFFBEF),
        accent = Ink.LiveOnPaper,
        hairline = Color(0x33000000),
    )
    ReaderTheme.LIGHT -> ReaderPalette(
        background = Color(0xFFFFFFFF),
        text = Color(0xFF111111),
        secondaryText = Color(0xFF6B6B66),
        sentenceHighlight = Color(0xFFFFF0B3),
        sentenceText = null,
        wordHighlight = Ink.LiveOnPaper,
        wordText = Color(0xFFFFFFFF),
        accent = Ink.LiveOnPaper,
        hairline = Color(0x22000000),
    )
    // The black theme marks the sentence by colouring it, not by painting behind it.
    ReaderTheme.DARK -> ReaderPalette(
        background = Ink.Surface,
        text = Ink.ReaderText,
        secondaryText = Ink.Muted,
        sentenceHighlight = Color.Transparent,
        sentenceText = Ink.Live,
        wordHighlight = Ink.Live,
        wordText = Ink.Surface,
        accent = Ink.Live,
        hairline = Ink.Hairline,
    )
}

/** [text] with the sentence being read marked, the word inverted on top, and note markers dimmed. */
fun highlightedText(
    text: String,
    sentence: IntRange?,
    word: IntRange?,
    palette: ReaderPalette,
    markers: List<IntRange>,
): AnnotatedString = buildAnnotatedString {
    append(text)
    fun style(range: IntRange, span: SpanStyle) {
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(span, start, end)
    }
    markers.forEach { style(it, SpanStyle(color = palette.secondaryText)) }
    sentence?.let { style(it, SpanStyle(background = palette.sentenceHighlight, color = palette.sentenceText ?: Color.Unspecified)) }
    // Colour only: a heavier weight is wider and would make the line re-wrap.
    word?.let { style(it, SpanStyle(background = palette.wordHighlight, color = palette.wordText)) }
}

/** The note whose marker a click at [point] was meant for: within [reachPx] of it, the nearest. */
fun noteAt(notes: List<Note>, sliceStart: Int, sliceEnd: Int, layout: TextLayoutResult, point: Offset, reachPx: Float): Note? = notes
    .filter { it.start >= sliceStart && it.end <= sliceEnd && it.end > it.start }
    .map { note ->
        val first = layout.getBoundingBox(note.start - sliceStart)
        val last = layout.getBoundingBox(note.end - 1 - sliceStart)
        note to Rect(minOf(first.left, last.left), minOf(first.top, last.top), maxOf(first.right, last.right), maxOf(first.bottom, last.bottom))
    }
    .filter { (_, box) -> box.inflate(reachPx).contains(point) }
    .minByOrNull { (_, box) -> (box.center - point).getDistance() }
    ?.first
