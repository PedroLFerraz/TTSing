package com.pedrolopes.ttsing.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.Chapter
import com.pedrolopes.ttsing.data.epub.Note
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.desktop.App
import com.pedrolopes.ttsing.desktop.SettingsStore
import com.pedrolopes.ttsing.desktop.tts.Narration
import com.pedrolopes.ttsing.desktop.ui.Hairline
import com.pedrolopes.ttsing.desktop.ui.MonoText
import com.pedrolopes.ttsing.desktop.ui.rememberImage
import com.pedrolopes.ttsing.desktop.ui.theme.AppFonts
import com.pedrolopes.ttsing.desktop.ui.theme.Ink
import com.pedrolopes.ttsing.tts.SpeakingSpeed
import com.pedrolopes.ttsing.tts.SpeedSteps
import com.pedrolopes.ttsing.ui.reader.PageElement
import com.pedrolopes.ttsing.ui.reader.ReaderPage
import com.pedrolopes.ttsing.ui.reader.CHAPTER_END
import com.pedrolopes.ttsing.ui.reader.ReadingEstimate
import com.pedrolopes.ttsing.ui.reader.TimeLeft
import com.pedrolopes.ttsing.ui.reader.browseFraction
import com.pedrolopes.ttsing.ui.reader.browsePosition
import com.pedrolopes.ttsing.ui.reader.currentTocIndex
import com.pedrolopes.ttsing.ui.reader.pageForAnchor
import com.pedrolopes.ttsing.ui.reader.sliceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The pages of the chapter on screen, laid out for the window as it is, and how many show at once. */
private class Laid(val chapter: Chapter, val pages: List<ReaderPage>, val perView: Int)

/** Where a page begins, as an [Anchor]: its first piece of text, or its picture. */
private fun ReaderPage.anchor(): Anchor = when (val first = firstOrNull()) {
    is PageElement.TextEl -> Anchor(first.slice.blockIndex, first.slice.start)
    is PageElement.ImageEl -> Anchor(first.blockIndex, 0)
    null -> Anchor(0, 0)
}

/** First page of the view (one page, or a spread of two) holding [anchor]. */
private fun Laid.viewStart(anchor: Anchor): Int =
    pageForAnchor(pages, anchor.block, anchor.offset).let { it - it % perView }

@Composable
fun ReaderScreen(app: App, bookId: String, onBack: () -> Unit) {
    val model = remember(bookId) { ReaderModel(app, bookId) }
    DisposableEffect(model) { onDispose { model.close() } }
    val settings by app.settings.state.collectAsState()
    val narration by app.narrator.state.collectAsState()
    val books by app.library.books.collectAsState()
    val record = books[bookId]
    val palette = paletteFor(settings.theme)

    var tocOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var openNote by remember { mutableStateOf<Note?>(null) }
    var laid by remember { mutableStateOf<Laid?>(null) }
    val current = laid?.takeIf { it.chapter === model.chapter }
    // A PDF shows its own pages unless this book was switched to text, as on Android.
    val pdf = model.pdf
    val showsPages = pdf != null && bookId !in settings.pdfAsText
    val pdfList = remember(bookId) { LazyListState() }
    var voiceOnScreen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(model.needsVoice) { if (model.needsVoice) settingsOpen = true }

    fun zoomPages(by: Float) = app.settings.update {
        it.copy(pdfZoom = (Math.round((it.pdfZoom + by) * 10) / 10f).coerceIn(MIN_PDF_ZOOM, MAX_PDF_ZOOM))
    }

    fun turn(forward: Boolean) {
        if (showsPages) {
            // A screenful, less a little so the last line stays in view.
            val by = pdfList.layoutInfo.viewportSize.height * 0.9f
            scope.launch { pdfList.animateScrollBy(if (forward) by else -by) }
            return
        }
        val view = current ?: return
        val start = view.viewStart(model.anchor)
        val target = start + if (forward) view.perView else -view.perView
        if (target in view.pages.indices) model.browse(view.pages[target].anchor()) else model.turnChapter(forward)
    }

    /** Play from the sentence the voice is on, if it is on screen; else from the top of the page. */
    fun playHere() {
        if (showsPages) {
            val chapter = model.chapter ?: return
            val sentence = narration.sentence
            val anchor = model.anchor
            model.play(
                if (voiceOnScreen && sentence != null) sentence.position
                else browsePosition(model.chapterIndex, chapter.blocks, anchor.block.coerceAtMost(chapter.blocks.size), anchor.offset),
            )
            return
        }
        val view = current ?: return
        val start = view.viewStart(model.anchor)
        val shown = start until start + view.perView
        val sentence = narration.sentence
        val voiceOnScreen = sentence != null && sentence.position.chapterIndex == model.chapterIndex &&
            pageHolding(view.pages, sentence.position.blockIndex, sentence.startInBlock) in shown
        val from = if (voiceOnScreen) sentence!!.position else {
            val top = view.pages.getOrNull(start)?.anchor() ?: Anchor(0, 0)
            browsePosition(model.chapterIndex, view.chapter.blocks, top.block, top.offset)
        }
        model.play(from)
    }

    fun togglePlay() = if (narration.speaking) model.pause() else playHere()

    // The page follows the voice: when the word being read leaves the pages on screen, turn.
    LaunchedEffect(narration.sentence, narration.word, current) {
        val view = current ?: return@LaunchedEffect
        val sentence = narration.sentence ?: return@LaunchedEffect
        if (!narration.speaking || sentence.position.chapterIndex != model.chapterIndex) return@LaunchedEffect
        val offset = narration.word?.first ?: sentence.startInBlock
        val page = pageHolding(view.pages, sentence.position.blockIndex, offset) ?: return@LaunchedEffect
        val start = view.viewStart(model.anchor)
        if (page !in start until start + view.perView) model.browse(view.pages[page].anchor())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionRight || event.key == Key.PageDown -> turn(true)
                    event.key == Key.DirectionLeft || event.key == Key.PageUp -> turn(false)
                    event.key == Key.Spacebar -> togglePlay()
                    event.key == Key.DirectionDown && narration.sentence != null -> model.skip(true)
                    event.key == Key.DirectionUp && narration.sentence != null -> model.skip(false)
                    event.isCtrlPressed && (event.key == Key.Equals || event.key == Key.Plus) ->
                        if (showsPages) zoomPages(0.1f) else app.settings.update { it.copy(fontScale = SettingsStore.fontStep(it.fontScale + 0.05f)) }
                    event.isCtrlPressed && event.key == Key.Minus ->
                        if (showsPages) zoomPages(-0.1f) else app.settings.update { it.copy(fontScale = SettingsStore.fontStep(it.fontScale - 0.05f)) }
                    event.key == Key.Escape -> when {
                        openNote != null -> openNote = null
                        tocOpen || settingsOpen -> {
                            tocOpen = false
                            settingsOpen = false
                        }
                        else -> onBack()
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        TopBar(
            title = model.title,
            chapterTitle = model.toc.getOrNull(currentTocIndex(model.toc.map { it.spineIndex }, model.chapterIndex))?.title,
            palette = palette,
            onBack = onBack,
            onToc = { tocOpen = !tocOpen },
            onSettings = { settingsOpen = !settingsOpen },
        )
        Hairline(color = palette.hairline)
        (model.error ?: narration.error)?.let { message ->
            Banner(message, palette, onDismiss = if (model.error == null) ({ app.narrator.clearError() }) else null)
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (tocOpen) {
                TocPanel(
                    toc = model.toc,
                    current = currentTocIndex(model.toc.map { it.spineIndex }, model.chapterIndex),
                    palette = palette,
                    onPick = { model.showChapter(it, Anchor(0, 0), save = true) },
                )
                Box(Modifier.fillMaxHeight().width(1.dp).background(palette.hairline))
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val chapter = model.chapter
                if (chapter == null) {
                    if (model.error == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette.accent) }
                    }
                } else if (showsPages && pdf != null) {
                    PdfPagesView(
                        pdf = pdf,
                        model = model,
                        narration = narration,
                        zoom = settings.pdfZoom,
                        listState = pdfList,
                        background = palette.background,
                        onReadFrom = { model.play(it) },
                        onVoiceOnScreen = { voiceOnScreen = it },
                        onZoom = ::zoomPages,
                    )
                } else {
                    PageArea(
                        model = model,
                        chapter = chapter,
                        fontScale = settings.fontScale,
                        palette = palette,
                        narration = narration,
                        laid = current,
                        onLaid = { laid = it },
                        onTurn = ::turn,
                        onNote = { openNote = it },
                        onReadFrom = { model.play(it) },
                    )
                }
            }
            if (settingsOpen) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(Ink.Hairline))
                SettingsPanel(app = app, model = model, narration = narration)
            }
        }
        Hairline(color = palette.hairline)
        Footer(
            model = model,
            laid = current,
            palette = palette,
            narration = narration,
            speechRate = settings.speechRate,
            listenedMs = record?.listenedMs ?: 0,
            listenedChars = record?.listenedChars ?: 0,
            onPlay = ::togglePlay,
            onSkip = { model.skip(it) },
            onRate = { rate ->
                app.settings.update { it.copy(speechRate = rate) }
                model.restartVoice()
            },
        )
    }

    openNote?.let { note -> NotePopup(note, settings.fontScale, palette) { openNote = null } }
}

private const val MIN_PDF_ZOOM = 0.4f
private const val MAX_PDF_ZOOM = 2.5f

private val ColumnMaxWidth = 680.dp
private val Gutter = 48.dp
private val PageVerticalPadding = 28.dp

/** How far from a footnote marker a click still opens it: the marker itself is a sliver. */
private val NoteReach = 10.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PageArea(
    model: ReaderModel,
    chapter: Chapter,
    fontScale: Float,
    palette: ReaderPalette,
    narration: Narration,
    laid: Laid?,
    onLaid: (Laid) -> Unit,
    onTurn: (Boolean) -> Unit,
    onNote: (Note) -> Unit,
    onReadFrom: (ReadingPosition) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Two pages side by side when the window has room for two reading columns.
        val perView = if (maxWidth >= ColumnMaxWidth * 2 + Gutter * 3) 2 else 1
        val column: Dp = min(ColumnMaxWidth, (maxWidth - Gutter * (perView + 1)) / perView)
        val widthPx = with(density) { column.roundToPx() }
        val heightPx = with(density) { (maxHeight - PageVerticalPadding * 2).roundToPx() }
        val fontResolver = LocalFontFamilyResolver.current
        // A measurer of its own: the composition's caches aren't safe on a background thread.
        val measurer = remember(fontResolver, density) { TextMeasurer(fontResolver, density, LayoutDirection.Ltr, cacheSize = 0) }
        LaunchedEffect(chapter, widthPx, heightPx, fontScale, perView) {
            val pages = withContext(Dispatchers.Default) {
                paginateChapter(chapter.blocks, widthPx, heightPx, measurer, density, fontScale)
            }
            onLaid(Laid(chapter, pages, perView))
        }
        if (laid == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette.accent) }
            return@BoxWithConstraints
        }

        // The wheel turns pages, one per notch however fast it spins.
        var lastTurn by remember { mutableStateOf(0L) }
        val start = laid.viewStart(model.anchor)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    val now = System.currentTimeMillis()
                    if (dy != 0f && now - lastTurn > WHEEL_PAUSE_MS) {
                        lastTurn = now
                        onTurn(dy > 0)
                    }
                }
                // A click in the margins turns the page, left back and right on.
                .pointerInput(Unit) {
                    detectTapGestures { point ->
                        when {
                            point.x < size.width * 0.18f -> onTurn(false)
                            point.x > size.width * 0.82f -> onTurn(true)
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(Gutter, Alignment.CenterHorizontally),
        ) {
            repeat(laid.perView) { i ->
                val page = laid.pages.getOrNull(start + i)
                Box(Modifier.width(column).fillMaxHeight().padding(vertical = PageVerticalPadding)) {
                    if (page != null) {
                        PageView(
                            page = page,
                            chapter = chapter,
                            bookId = model.bookId,
                            fontScale = fontScale,
                            palette = palette,
                            narration = narration.takeIf { it.sentence?.position?.chapterIndex == chapter.spineIndex },
                            loadImage = model::readImage,
                            onNote = onNote,
                            onReadFrom = onReadFrom,
                        )
                    }
                }
            }
        }
        // Where in the chapter this is, quietly, at the foot of the column.
        MonoText(
            "${start + 1}${if (laid.perView == 2 && start + 1 < laid.pages.size) "–${start + 2}" else ""} / ${laid.pages.size}",
            color = palette.secondaryText,
            size = 10f,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

private const val WHEEL_PAUSE_MS = 180L

@Composable
private fun PageView(
    page: ReaderPage,
    chapter: Chapter,
    bookId: String,
    fontScale: Float,
    palette: ReaderPalette,
    narration: Narration?,
    loadImage: suspend (String) -> ByteArray?,
    onNote: (Note) -> Unit,
    onReadFrom: (ReadingPosition) -> Unit,
) {
    val sentence = narration?.sentence
    Column(Modifier.fillMaxSize()) {
        page.forEach { element ->
            when (element) {
                is PageElement.TextEl -> {
                    val slice = element.slice
                    val block = sliceText(chapter.blocks, slice) ?: return@forEach
                    val active = sentence?.position?.blockIndex == slice.blockIndex
                    val sentenceRange = if (active) sentence!!.let { s ->
                        block.sentences.getOrNull(s.position.sentenceIndex)?.let { it.start until it.end }
                    } else null
                    var layout by remember(slice) { mutableStateOf<TextLayoutResult?>(null) }
                    Text(
                        text = highlightedText(
                            text = block.text.substring(slice.start, slice.end),
                            sentence = sentenceRange?.toSliceLocal(slice.start, slice.end),
                            word = narration?.word?.takeIf { active }?.toSliceLocal(slice.start, slice.end),
                            palette = palette,
                            markers = block.notes.mapNotNull { (it.start until it.end).toSliceLocal(slice.start, slice.end) },
                        ),
                        style = blockTextStyle(slice.kind, fontScale),
                        color = palette.text,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = blockVerticalPadding(slice.kind))
                            .padding(start = blockStartIndent(slice.kind))
                            .pointerInput(slice, block) {
                                detectTapGestures(
                                    // A footnote's marker opens the note.
                                    onTap = { point ->
                                        layout?.let { noteAt(block.notes, slice.start, slice.end, it, point, NoteReach.toPx()) }?.let(onNote)
                                    },
                                    // A double click reads from that sentence.
                                    onDoubleTap = { point ->
                                        val offset = (layout?.getOffsetForPosition(point) ?: 0) + slice.start
                                        onReadFrom(ReadingPosition(chapter.spineIndex, slice.blockIndex, block.sentenceIndexAt(offset)))
                                    },
                                )
                            },
                    )
                }
                is PageElement.ImageEl -> {
                    val image = rememberImage("book:$bookId:${element.zipPath}") { loadImage(element.zipPath) }
                    Box(Modifier.fillMaxWidth().height(ImageDisplayHeight).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        // Shrunk to fit, never blown up: a small ornament stays sharp at its own size.
                        image?.let { Image(it, contentDescription = element.alt, contentScale = ContentScale.Inside, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    chapterTitle: String?,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to the library", tint = palette.secondaryText) }
        IconButton(onClick = onToc) { Icon(Icons.AutoMirrored.Outlined.List, "Contents", tint = palette.secondaryText) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = palette.text, fontFamily = AppFonts.Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            chapterTitle?.let { MonoText(it, color = palette.secondaryText, size = 10f) }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Tune, "Page and voice", tint = palette.secondaryText) }
    }
}

@Composable
private fun Banner(message: String, palette: ReaderPalette, onDismiss: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Ink.Raised).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Ink.Live, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(message, color = Ink.Text, fontSize = 13.sp, fontFamily = AppFonts.Grotesk, modifier = Modifier.weight(1f))
        onDismiss?.let { TextButton(onClick = it) { MonoText("Dismiss", color = Ink.Live) } }
    }
}

@Composable
private fun Footer(
    model: ReaderModel,
    laid: Laid?,
    palette: ReaderPalette,
    narration: Narration,
    speechRate: Float,
    listenedMs: Long,
    listenedChars: Long,
    onPlay: () -> Unit,
    onSkip: (Boolean) -> Unit,
    onRate: (Float) -> Unit,
) {
    val chapter = model.chapter
    val counts = model.chapterChars
    val anchor = model.anchor
    val position = chapter?.let {
        val top = laid?.let { l -> l.pages.getOrNull(l.viewStart(anchor))?.anchor() } ?: anchor
        browsePosition(model.chapterIndex, it.blocks, top.block.coerceAtMost(it.blocks.size), top.offset)
    }
    val fraction = if (chapter != null && position != null) browseFraction(counts, chapter.blocks, position, model.sectionCount) else 0f

    Column(Modifier.fillMaxWidth()) {
        BookProgress(fraction, counts, palette)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSkip(false) }, enabled = narration.sentence != null) {
                Icon(Icons.Filled.SkipPrevious, "Previous sentence", tint = palette.secondaryText)
            }
            Box(
                modifier = Modifier.size(44.dp).background(palette.accent, CircleShape).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                if (narration.preparing) {
                    CircularProgressIndicator(color = palette.background, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        if (narration.speaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (narration.speaking) "Pause" else "Read aloud",
                        tint = palette.background,
                    )
                }
            }
            IconButton(onClick = { onSkip(true) }, enabled = narration.sentence != null) {
                Icon(Icons.Filled.SkipNext, "Next sentence", tint = palette.secondaryText)
            }
            Spacer(Modifier.width(8.dp))
            SpeedChip(speechRate, palette, onRate)
            rememberSleepMinutesLeft(narration)?.let { left ->
                MonoText(if (left == CHAPTER_END) "Sleep ch" else "Sleep $left", color = palette.accent, size = 10f)
            }
            Spacer(Modifier.weight(1f))
            if (chapter != null && position != null) {
                val offset = (chapter.blocks.getOrNull(position.blockIndex) as? Block.Text)
                    ?.sentences?.getOrNull(position.sentenceIndex)?.start ?: 0
                val leftInChapter = ReadingEstimate.remainingCharsInChapter(chapter.blocks, position.blockIndex, offset)
                val perChar = TimeLeft.secondsPerChar(listenedMs, listenedChars, SpeakingSpeed.PRIOR_CPS) / speechRate.coerceAtLeast(0.1f)
                val estimate = TimeLeft.byCharacters(leftInChapter, counts.drop(model.chapterIndex + 1).sum(), perChar)
                MonoText(
                    "${ReadingEstimate.formatDuration(estimate.chapterSeconds)} in chapter · ${ReadingEstimate.formatMinutes(estimate.bookMinutes)} in book",
                    color = palette.secondaryText,
                    size = 10f,
                )
                Spacer(Modifier.width(20.dp))
            }
            MonoText("${(fraction * 100).roundToInt()}%", color = palette.accent, size = 11f, weight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            MonoText(rememberClock(), color = palette.secondaryText, size = 10f)
        }
    }
}

/** A line across the book, filled to [fraction], with a tick where each chapter begins. */
@Composable
private fun BookProgress(fraction: Float, counts: List<Int>, palette: ReaderPalette) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(3.dp).background(palette.hairline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(palette.accent))
        val total = counts.sum()
        if (total > 0 && counts.size <= MAX_TICKS) {
            var before = 0
            counts.forEach { chars ->
                if (before > 0) {
                    Box(Modifier.offset(x = maxWidth * (before.toFloat() / total)).width(1.dp).fillMaxHeight().background(palette.background))
                }
                before += chars
            }
        }
    }
}

private const val MAX_TICKS = 80

@Composable
private fun SpeedChip(rate: Float, palette: ReaderPalette, onRate: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { MonoText(SpeedSteps.format(rate), color = palette.text, size = 12f, weight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ink.Raised) {
            SpeedSteps.ALL.forEach { step ->
                DropdownMenuItem(
                    text = { MonoText(SpeedSteps.format(step), color = if (SpeedSteps.selected(rate) == step) Ink.Live else Ink.Text, size = 12f) },
                    onClick = {
                        open = false
                        onRate(step)
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberClock(): String {
    val format = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val now by produceState(LocalTime.now().format(format)) {
        while (true) {
            value = LocalTime.now().format(format)
            delay(15_000)
        }
    }
    return now
}

/** A footnote, opened from its marker: a panel along the foot of the page, in the page's colours. */
@Composable
private fun NotePopup(note: Note, fontScale: Float, palette: ReaderPalette, onDismiss: () -> Unit) {
    Popup(alignment = Alignment.BottomCenter, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .padding(bottom = 72.dp)
                .background(palette.background)
                .background(if (palette.background == Ink.Surface) Ink.Raised else Color.Transparent),
        ) {
            Hairline(color = palette.accent)
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
                MonoText("Note", color = palette.secondaryText, modifier = Modifier.padding(bottom = 8.dp))
                val size = 16f * fontScale
                Text(note.text, color = palette.text, fontFamily = AppFonts.Serif, fontSize = size.sp, lineHeight = (size * 1.5f).sp)
            }
        }
    }
}
