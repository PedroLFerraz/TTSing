package com.pedrolopes.ttsing.desktop.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.pdf.PageRect
import com.pedrolopes.ttsing.data.pdf.PdfDocument
import com.pedrolopes.ttsing.data.pdf.SectionGeometry
import com.pedrolopes.ttsing.desktop.tts.Narration
import com.pedrolopes.ttsing.desktop.ui.MonoText
import com.pedrolopes.ttsing.desktop.ui.theme.Ink
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Highlighter yellow, multiplied onto the paper so the print under it stays black: a light
 * wash over the sentence being read, a strong one over the word. The same as on Android.
 */
private val SentenceMark = Ink.Live.copy(alpha = 0.3f)
private val WordMark = Ink.Live.copy(alpha = 0.85f)

/** The widest a page is drawn at 100%: a page about as wide as a book held at reading distance. */
private val PageMaxWidth = 900.dp
private val PageGap = 12.dp

/**
 * A PDF as its own pages, top to bottom, with the sentence and word being read marked on the
 * page itself, as the Android app shows it. Everything about reading (positions, the voice,
 * the footer) still works on the reflowed blocks the text view shows; this only maps their
 * characters to where they are printed (see [com.pedrolopes.ttsing.data.pdf.TextGeometry]).
 *
 * The page at the top third of the screen is where the reader is: scrolling reports it to
 * [model], and the model moving elsewhere (the contents, opening the book, the voice crossing
 * into another section) scrolls here. A double click reads from the sentence under it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PdfPagesView(
    pdf: PdfDocument,
    model: ReaderModel,
    narration: Narration,
    zoom: Float,
    listState: LazyListState,
    background: Color,
    onReadFrom: (ReadingPosition) -> Unit,
    /** Whether the sentence the voice is on can be seen: play resumes it, rather than the page top. */
    onVoiceOnScreen: (Boolean) -> Unit,
    /** Ctrl and the wheel: pages a step bigger (positive) or smaller. */
    onZoom: (Float) -> Unit,
) {
    val pageCount = remember(pdf) { pdf.pageCount }
    var landed by remember(pdf) { mutableStateOf(false) }

    // What the voice is reading, as rectangles on the pages; tagged with its section, so the old
    // section's geometry never places a new section's text.
    val voiceSection = narration.sentence?.position?.chapterIndex
    val loaded by produceState<Pair<Int, SectionGeometry>?>(null, pdf, voiceSection) {
        value = voiceSection?.let { it to pdf.geometry(it) }
    }
    val geometry = loaded?.takeIf { it.first == voiceSection }?.second
    val sentence = narration.sentence
    val sentenceRange = sentence?.let { it.startInBlock until it.startInBlock + it.text.length }
    val sentenceRects = remember(geometry, sentence) {
        if (geometry == null || sentence == null) emptyList() else geometry.rects(sentence.position.blockIndex, sentenceRange!!)
    }
    val wordRects = remember(geometry, sentence, narration.word) {
        val word = narration.word
        if (geometry == null || sentence == null || word == null) emptyList() else geometry.rects(sentence.position.blockIndex, word)
    }

    // The model points somewhere off screen: go there. This and the next are outside the
    // BoxWithConstraints below, whose content is composed while it is measured: a scroll
    // started from there would ask for a measure in the middle of one.
    LaunchedEffect(pdf, model.chapter, model.anchor) {
        val chapter = model.chapter ?: return@LaunchedEffect
        val anchor = model.anchor
        val target = if (anchor == Anchor.END) {
            pdf.firstPageOf(chapter.spineIndex) + pdf.sectionPageCounts().getOrElse(chapter.spineIndex) { 1 } - 1
        } else {
            pdf.geometry(chapter.spineIndex).pageOf(anchor.block, anchor.offset) ?: pdf.firstPageOf(chapter.spineIndex)
        }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val shown = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (!landed || target !in shown) listState.scrollToItem(target)
        landed = true
    }

    // The page in the top third is where the reader is; its first text is the place.
    val currentModel by rememberUpdatedState(model)
    LaunchedEffect(pdf, landed) {
        if (!landed) return@LaunchedEffect
        snapshotFlow {
            val viewportHeight = listState.layoutInfo.viewportSize.height
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.offset + it.size > viewportHeight / 3 }?.index
                ?: listState.firstVisibleItemIndex
        }.distinctUntilChanged().collect { page ->
            val section = pdf.sectionOfPage(page)
            val first = pdf.geometry(section).firstOn(page)
            val anchor = Anchor(first?.blockIndex ?: 0, first?.offset ?: 0)
            if (section != currentModel.chapterIndex) currentModel.showChapter(section, anchor, save = true)
            else currentModel.browse(anchor)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(background)) {
        val density = LocalDensity.current
        val room = maxWidth
        val pageWidth: Dp = min(PageMaxWidth, room - PageGap * 2) * zoom
        val widthPx = with(density) { pageWidth.roundToPx() }
        val viewportHeight = constraints.maxHeight
        fun pxPerPoint(page: Int) = widthPx / pdf.pageSize(page).width

        // Follow the voice: when the word being read leaves the screen, bring it a third of the way down.
        val follow = wordRects.firstOrNull() ?: sentenceRects.firstOrNull()
        val reportVoice by rememberUpdatedState(onVoiceOnScreen)
        LaunchedEffect(follow, narration.speaking) {
            val rect = follow
            val item = rect?.let { r -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == r.page } }
            val onScreen = rect != null && item != null &&
                item.offset + rect.top * pxPerPoint(rect.page) >= 0 &&
                item.offset + rect.bottom * pxPerPoint(rect.page) <= viewportHeight
            reportVoice(onScreen)
            if (rect == null || onScreen || !narration.speaking || listState.isScrollInProgress) return@LaunchedEffect
            // Not from within the measure pass this effect was started in (see above).
            withFrameNanos { }
            val offset = (rect.top * pxPerPoint(rect.page) - viewportHeight / 3f).toInt().coerceAtLeast(0)
            listState.animateScrollToItem(rect.page, offset)
            reportVoice(true)
        }

        // Wider than the window when zoomed in: then the pages scroll sideways too.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                    if (!event.keyboardModifiers.isCtrlPressed) return@onPointerEvent
                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f) onZoom(if (dy < 0) 0.1f else -0.1f)
                    event.changes.forEach { it.consume() }
                }
                .horizontalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(PageGap),
                contentPadding = PaddingValues(vertical = PageGap),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxHeight().width(maxOf(pageWidth + PageGap * 2, room)),
            ) {
                items(pageCount, key = { it }) { page ->
                    PdfPage(
                        pdf = pdf,
                        page = page,
                        width = pageWidth,
                        widthPx = widthPx,
                        sentence = sentenceRects.filter { it.page == page },
                        word = wordRects.filter { it.page == page },
                        onDoubleClick = { x, y ->
                            val section = pdf.sectionOfPage(page)
                            val hit = pdf.geometry(section).hit(page, x, y) ?: return@PdfPage
                            val block = model.textBlockIn(section, hit.blockIndex)
                            onReadFrom(ReadingPosition(section, hit.blockIndex, block?.sentenceIndexAt(hit.offset) ?: 0))
                        },
                    )
                }
            }
        }
        val top = listState.firstVisibleItemIndex
        MonoText(
            "Page ${top + 1} / $pageCount",
            color = Ink.Muted,
            size = 10f,
            // In the corner, clear of the page's own text.
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp).background(background).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** One page: its picture, white until drawn, with the reading marked over it. */
@Composable
private fun PdfPage(
    pdf: PdfDocument,
    page: Int,
    width: Dp,
    widthPx: Int,
    sentence: List<PageRect>,
    word: List<PageRect>,
    onDoubleClick: suspend (x: Float, y: Float) -> Unit,
) {
    val size = remember(pdf, page) { pdf.pageSize(page) }
    val pxPerPoint = widthPx / size.width
    val height = with(LocalDensity.current) { (size.height * pxPerPoint).toDp() }
    // Kept while a drawing at a new zoom is on its way, so zooming never blanks the page.
    var bitmap by remember(pdf, page) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pdf, page, widthPx) {
        pdf.renderPage(page, widthPx)?.let { bitmap = it.toComposeImageBitmap() }
    }
    val click by rememberUpdatedState(onDoubleClick)
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(Color.White)
            .pointerInput(page, pxPerPoint) {
                detectTapGestures(onDoubleTap = { p -> scope.launch { click(p.x / pxPerPoint, p.y / pxPerPoint) } })
            },
    ) {
        bitmap?.let { Image(it, contentDescription = "Page ${page + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        if (sentence.isNotEmpty() || word.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pad = 1.2f * pxPerPoint
                val corner = CornerRadius(2f * pxPerPoint)
                fun mark(rect: PageRect, color: Color) = drawRoundRect(
                    color = color,
                    topLeft = Offset(rect.left * pxPerPoint - pad, rect.top * pxPerPoint - pad),
                    size = Size((rect.right - rect.left) * pxPerPoint + pad * 2, (rect.bottom - rect.top) * pxPerPoint + pad * 2),
                    cornerRadius = corner,
                    blendMode = BlendMode.Multiply,
                )
                sentence.forEach { mark(it, SentenceMark) }
                word.forEach { mark(it, WordMark) }
            }
        }
    }
}
