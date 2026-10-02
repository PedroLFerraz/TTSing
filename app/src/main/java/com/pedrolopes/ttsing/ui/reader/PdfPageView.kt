package com.pedrolopes.ttsing.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pedrolopes.ttsing.data.pdf.PageRect
import com.pedrolopes.ttsing.data.pdf.PdfDocument
import com.pedrolopes.ttsing.data.pdf.SectionGeometry
import com.pedrolopes.ttsing.ui.theme.Ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How far a page can be pinched up: enough to read a footnote on a phone. */
private const val MAX_ZOOM = 4f

/** Above this zoom, pages are drawn at twice the width so they stay sharp. */
private const val SHARPER_ABOVE = 1.4f

/**
 * Highlighter yellow, multiplied onto the paper so the print under it stays black: a light
 * wash over the sentence being read, a strong one over the word.
 */
private val SentenceMark = Ink.Live.copy(alpha = 0.3f)
private val WordMark = Ink.Live.copy(alpha = 0.85f)

/**
 * A PDF as its own pages, top to bottom, with the sentence and word being read marked on the
 * page itself. Everything about reading — positions, the voice, flashcards — still works on
 * the reflowed blocks the text view shows; the page view only maps those blocks' characters
 * to where they are printed (see [com.pedrolopes.ttsing.data.pdf.TextGeometry]).
 *
 * Pinch to zoom; once zoomed, one finger pans. A tap starts reading at the sentence under it
 * and a long press makes a flashcard of it, as in the text view.
 */
@Composable
internal fun PdfPageView(
    pdf: PdfDocument,
    jump: JumpRequest?,
    background: Color,
    voiceChapter: Int?,
    activeBlockIndex: Int?,
    sentenceRange: IntRange?,
    wordRange: IntRange?,
    onTap: (page: Int, x: Float, y: Float) -> Unit,
    onLongPress: (page: Int, x: Float, y: Float) -> Unit,
    onVisiblePage: (VisiblePage) -> Unit,
    onVoicePage: (VisiblePage?) -> Unit,
    onSettledChapter: (Int) -> Unit,
) {
    val pageCount = remember(pdf) { pdf.pageCount }
    val sectionPages = remember(pdf) { pdf.sectionPageCounts() }
    val listState = rememberLazyListState()

    // What the voice is reading, as rectangles on the pages.
    // Tagged with its chapter: until a newly tapped chapter's geometry loads, the old one's
    // would place the new block on the old chapter's pages and scroll the reader back there.
    val loadedGeometry by produceState<Pair<Int, SectionGeometry>?>(null, pdf, voiceChapter) {
        value = voiceChapter?.let { it to pdf.geometry(it) }
    }
    val voiceGeometry = loadedGeometry?.takeIf { it.first == voiceChapter }?.second
    val sentenceRects = remember(voiceGeometry, activeBlockIndex, sentenceRange) {
        rectsFor(voiceGeometry, activeBlockIndex, sentenceRange)
    }
    val wordRects = remember(voiceGeometry, activeBlockIndex, wordRange) {
        rectsFor(voiceGeometry, activeBlockIndex, wordRange)
    }

    fun visiblePage(chapter: Int, page: Int, blockIndex: Int, offset: Int) = VisiblePage(
        chapterIndex = chapter,
        pageInChapter = page - pdf.firstPageOf(chapter),
        pagesInChapter = sectionPages.getOrElse(chapter) { 1 },
        firstBlockIndex = blockIndex,
        firstOffset = offset,
    )

    val voicePage = (wordRects.firstOrNull() ?: sentenceRects.firstOrNull())?.page
    LaunchedEffect(voicePage, voiceChapter) {
        val chapter = voiceChapter
        onVoicePage(
            if (voicePage == null || chapter == null) null else visiblePage(chapter, voicePage, activeBlockIndex ?: 0, 0),
        )
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth
        val viewportHeight = constraints.maxHeight
        val renderWidth = if (scale > SHARPER_ABOVE) widthPx * 2 else widthPx
        fun pxPerPoint(page: Int) = widthPx / pdf.pageSize(page).width

        // Opening the book, the contents, « », or switching from the text view.
        LaunchedEffect(jump?.id) {
            val target = jump ?: return@LaunchedEffect
            val page = if (target.blockIndex == 0) {
                pdf.firstPageOf(target.chapterIndex)
            } else {
                pdf.geometry(target.chapterIndex).pageOf(target.blockIndex) ?: pdf.firstPageOf(target.chapterIndex)
            }
            listState.scrollToItem(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        }

        FollowTheVoice(listState, wordRects.firstOrNull() ?: sentenceRects.firstOrNull(), viewportHeight, ::pxPerPoint)

        // The page at the top of the screen, and where its text starts: the footer's position
        // and the place reading resumes from when nothing is playing.
        val reportVisible by rememberUpdatedState(onVisiblePage)
        val reportSettled by rememberUpdatedState(onSettledChapter)
        LaunchedEffect(listState, pdf) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.offset + it.size > viewportHeight / 3 }?.index
                    ?: listState.firstVisibleItemIndex
            }
                .distinctUntilChanged()
                .collect { page ->
                    val chapter = pdf.sectionOfPage(page)
                    val first = pdf.geometry(chapter).firstOn(page)
                    reportVisible(visiblePage(chapter, page, first?.blockIndex ?: 0, first?.offset ?: 0))
                    reportSettled(chapter)
                }
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .pointerInput(listState) { zoomAndPan(listState, { scale }, { pan }) { s, p -> scale = s; pan = p } }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            items(pageCount, key = { it }) { page ->
                PdfPage(
                    pdf = pdf,
                    page = page,
                    widthPx = widthPx,
                    renderWidth = renderWidth,
                    sentence = sentenceRects.filter { it.page == page },
                    word = wordRects.filter { it.page == page },
                    onTap = onTap,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

private fun rectsFor(geometry: SectionGeometry?, blockIndex: Int?, range: IntRange?): List<PageRect> =
    if (geometry == null || blockIndex == null || range == null) emptyList() else geometry.rects(blockIndex, range)

/**
 * Keeps what is being read on screen. It scrolls when the voice turns to another page, or
 * moves off screen while the reader was following it; a reader who scrolled away to look at
 * something else on the same page is left alone until the voice turns the page.
 */
@Composable
private fun FollowTheVoice(
    listState: LazyListState,
    target: PageRect?,
    viewportHeight: Int,
    pxPerPoint: (Int) -> Float,
) {
    var previous by remember { mutableStateOf<PageRect?>(null) }
    // The scroll runs on its own: the next word arrives mid-animation, and restarting the
    // effect for it must not cancel the scroll halfway.
    val scope = rememberCoroutineScope()
    var scrolling by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(target) {
        val now = target ?: return@LaunchedEffect
        val before = previous
        previous = now
        if (scrolling?.isActive == true) return@LaunchedEffect
        fun onScreen(rect: PageRect): Boolean {
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == rect.page } ?: return false
            val scale = pxPerPoint(rect.page)
            return item.offset + rect.top * scale >= 0 && item.offset + rect.bottom * scale <= viewportHeight
        }
        val following = before == null || before.page != now.page || onScreen(before)
        if (!following || onScreen(now) || listState.isScrollInProgress) return@LaunchedEffect
        // A third of the way down, so the lines coming next are in view.
        val offset = (now.top * pxPerPoint(now.page) - viewportHeight / 3f).toInt().coerceAtLeast(0)
        scrolling = scope.launch { listState.animateScrollToItem(now.page, offset) }
    }
}

/**
 * Two fingers pinch and pan, around the point between them. Once zoomed, one finger pans
 * sideways and scrolls the pages; unzoomed, one finger is left to the list, which flings.
 * Watches the initial pass so a pinch never reaches the list as a scroll.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.zoomAndPan(
    listState: LazyListState,
    scale: () -> Float,
    pan: () -> Offset,
    update: (Float, Offset) -> Unit,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    fun clamp(offset: Offset, s: Float): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dragging = false
        var travel = Offset.Zero
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2) {
                val oldScale = scale()
                val newScale = (oldScale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                val centroid = event.calculateCentroid(useCurrent = true) - center
                val moved = centroid - (centroid - pan()) * (newScale / oldScale) + event.calculatePan()
                update(newScale, clamp(moved, newScale))
                event.changes.forEach { it.consume() }
            } else if (fingers == 1 && scale() > 1.01f) {
                val delta = event.calculatePan()
                if (!dragging) {
                    travel += delta
                    dragging = travel.getDistance() > viewConfiguration.touchSlop
                }
                if (dragging) {
                    update(scale(), clamp(Offset(pan().x + delta.x, pan().y), scale()))
                    listState.dispatchRawDelta(-delta.y / scale())
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        if (abs(scale() - 1f) < 0.05f) update(1f, Offset.Zero)
    }
}

/** One page: its picture, white until drawn, with the reading marked over it. */
@Composable
private fun PdfPage(
    pdf: PdfDocument,
    page: Int,
    widthPx: Int,
    renderWidth: Int,
    sentence: List<PageRect>,
    word: List<PageRect>,
    onTap: (page: Int, x: Float, y: Float) -> Unit,
    onLongPress: (page: Int, x: Float, y: Float) -> Unit,
) {
    val size = remember(pdf, page) { pdf.pageSize(page) }
    val pxPerPoint = widthPx / size.width
    val heightDp = with(LocalDensity.current) { (size.height * pxPerPoint).toDp() }
    // Kept while a sharper drawing is on its way, so zooming never blanks the page.
    var bitmap by remember(pdf, page) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pdf, page, renderWidth) {
        pdf.renderPage(page, renderWidth)?.let { bitmap = it.asImageBitmap() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            .background(Color.White)
            .pointerInput(page, pxPerPoint) {
                detectTapGestures(
                    onTap = { p -> onTap(page, p.x / pxPerPoint, p.y / pxPerPoint) },
                    onLongPress = { p -> onLongPress(page, p.x / pxPerPoint, p.y / pxPerPoint) },
                )
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
