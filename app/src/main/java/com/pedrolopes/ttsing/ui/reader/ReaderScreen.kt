package com.pedrolopes.ttsing.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.anki.CardDraft
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.settings.AppSettings
import com.pedrolopes.ttsing.data.settings.ReaderTheme
import com.pedrolopes.ttsing.tts.ReadingController
import com.pedrolopes.ttsing.ui.common.Hairline
import com.pedrolopes.ttsing.ui.common.LocalImage
import com.pedrolopes.ttsing.ui.common.MonoText
import com.pedrolopes.ttsing.ui.common.ScreenPadding
import com.pedrolopes.ttsing.ui.common.ThinProgress
import com.pedrolopes.ttsing.ui.common.simpleFactory
import com.pedrolopes.ttsing.ui.theme.AppFonts
import com.pedrolopes.ttsing.ui.theme.Ink
import kotlinx.coroutines.launch
import java.util.Locale

private val DefaultSettings = AppSettings(
    libraryFolderUri = null,
    speechRate = 1f,
    pitch = 1f,
    fontScale = 1f,
    readerTheme = ReaderTheme.DARK,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    controller: ReadingController,
    viewModel: ReaderViewModel = viewModel(factory = simpleFactory { ReaderViewModel.create(bookId) }),
) {
    val app = TTSingApp.instance
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by controller.state.collectAsStateWithLifecycle()
    val connected by controller.connected.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = DefaultSettings)
    val palette = readerPalette(settings.readerTheme)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var pageInfo by remember { mutableStateOf(PageInfo()) }
    var cardDraft by remember { mutableStateOf<CardDraft?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(connected) { if (connected) controller.prepare(bookId) }

    val isThisBook = playback.isActive && playback.bookId == bookId

    LaunchedEffect(playback.position.chapterIndex, isThisBook) {
        if (isThisBook) viewModel.syncToChapter(playback.position.chapterIndex)
    }

    val activeBlockIndex = if (isThisBook && playback.position.chapterIndex == ui.chapterIndex) {
        playback.position.blockIndex
    } else {
        null
    }

    // Time to finish: measured from where the voice is when playing, otherwise from the
    // top of the visible page.
    val trackPlayback = isThisBook && playback.isSpeaking && activeBlockIndex != null
    val fromBlock = if (trackPlayback) playback.position.blockIndex else pageInfo.firstBlockIndex
    val fromOffset = if (trackPlayback) (playback.sentenceRange?.first ?: 0) else pageInfo.firstOffset
    val remainingChapterChars = ReadingEstimate.remainingCharsInChapter(ui.blocks, fromBlock, fromOffset)
    val chapterTimeText = ReadingEstimate.formatDuration(
        ReadingEstimate.secondsFor(remainingChapterChars, settings.charsPerSecond, settings.speechRate),
    )
    val bookTimeText = ui.chapterCharCounts.takeIf { it.isNotEmpty() }?.let { counts ->
        ReadingEstimate.formatDuration(
            ReadingEstimate.secondsFor(
                ReadingEstimate.remainingCharsInBook(remainingChapterChars, counts, ui.chapterIndex),
                settings.charsPerSecond,
                settings.speechRate,
            ),
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Ink.Surface) {
                MonoText(
                    "Contents",
                    size = 13f,
                    tracking = 0.2f,
                    color = Ink.Text,
                    weight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 8.dp),
                )
                Hairline(modifier = Modifier.padding(vertical = 10.dp), inset = 24.dp)
                LazyColumn {
                    itemsIndexed(ui.toc) { _, entry ->
                        val current = entry.spineIndex == ui.chapterIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.showChapter(entry.spineIndex)
                                    scope.launch { drawerState.close() }
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 3.dp, height = 18.dp)
                                    .background(if (current) Ink.Live else Color.Transparent),
                            )
                            Text(
                                entry.title,
                                maxLines = 2,
                                fontFamily = AppFonts.Grotesk,
                                fontSize = 14.sp,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (current) Ink.Text else Ink.Muted,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            containerColor = palette.background,
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                ReaderTopBar(
                    title = ui.title,
                    chapterLabel = chapterLabel(ui.chapterIndex, ui.chapterTitle),
                    palette = palette,
                    articleLink = ui.articleLink,
                    onBack = onBack,
                    onOpenLink = { link -> openInBrowser(context, link) },
                    onOpenContents = { scope.launch { drawerState.open() } },
                    onOpenSettings = { showSettings = true },
                )
            },
            bottomBar = {
                ReaderBottomBar(
                    chapterIndex = ui.chapterIndex,
                    chapterCount = ui.chapterCount,
                    pageCurrent = pageInfo.current,
                    pageTotal = pageInfo.total,
                    chapterTimeText = chapterTimeText,
                    bookTimeText = bookTimeText,
                    isSpeaking = isThisBook && playback.isSpeaking,
                    palette = palette,
                    progress = if (pageInfo.total > 0) pageInfo.current.toFloat() / pageInfo.total else 0f,
                    onPrevChapter = { viewModel.previousChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    onPlayPause = { controller.togglePlayPause(bookId) },
                    onNextSentence = { controller.next() },
                    onPrevSentence = { controller.previous() },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    ui.isLoading && ui.blocks.isEmpty() ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    ui.error != null ->
                        Text(
                            ui.error!!,
                            color = palette.text,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            textAlign = TextAlign.Center,
                        )
                    else -> PagedChapter(
                        blocks = ui.blocks,
                        chapterIndex = ui.chapterIndex,
                        hasNextChapter = ui.chapterIndex < ui.chapterCount - 1,
                        fontScale = settings.fontScale,
                        palette = palette,
                        activeBlockIndex = activeBlockIndex,
                        // Follow the spoken WORD, so a sentence spanning a page boundary
                        // flips the page exactly when the highlight crosses it.
                        activeOffset = playback.wordRange?.first ?: playback.sentenceRange?.first,
                        sentenceRange = playback.sentenceRange,
                        wordRange = playback.wordRange,
                        onTapStart = { position -> controller.play(bookId, position) },
                        onMakeCard = { blockIndex, offset ->
                            cardDraft = viewModel.draftFor(blockIndex, offset)
                        },
                        onRequestNextChapter = { viewModel.nextChapter() },
                        loadImage = viewModel::imageBytes,
                        onPageInfo = { info -> pageInfo = info },
                    )
                }

                val playbackError = playback.error.takeIf { isThisBook }
                when {
                    // An engine failure is the most urgent thing to say: without it, a voice
                    // that cannot speak just looks like a reader that stopped working.
                    playbackError != null -> EngineErrorBanner(
                        message = playbackError,
                        onOpenSettings = { showSettings = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    )
                    isThisBook && !playback.languageAvailable ->
                        MissingVoiceBanner(modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
                    ui.isTruncated ->
                        TruncatedArticleBanner(modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
                }
            }
        }
    }

    cardDraft?.let { draft ->
        CardSheet(
            draft = draft,
            locale = viewModel.bookLocale(),
            isSubmitting = ui.isSavingCard,
            onDraftChange = { cardDraft = it },
            onPreviewAudio = { viewModel.previewCardAudio(it) },
            onDismiss = { cardDraft = null },
            onSubmit = { toAdd ->
                viewModel.submitCard(toAdd) { message ->
                    cardDraft = null
                    scope.launch { snackbarHost.showSnackbar(message) }
                }
            },
        )
    }

    if (showSettings) {
        // The language is whatever the user last chose for this book, falling back to what the
        // book declares. Derived straight from settings — which is DataStore-backed and so
        // recomposes on change — rather than asking the service, whose copy is a plain var the
        // UI cannot observe. Reading that var through `remember` is what previously left the
        // picker stuck on a language the user was trying to move away from.
        val declaredLocale = ui.languageTag
            ?.let { Locale.forLanguageTag(it) }
            ?.takeIf { it.language.isNotEmpty() }
        val activeLocale = settings.localeFor(bookId, declaredLocale ?: Locale.getDefault())
        val languageCode = activeLocale.language
        ReaderSettingsSheet(
            settings = settings,
            voices = remember(languageCode, connected) { controller.voicesFor(activeLocale) },
            currentVoiceName = remember(languageCode, connected) { controller.currentVoiceName() },
            defaultVoiceName = remember(languageCode, connected) { controller.defaultVoiceNameFor(activeLocale) },
            activeLocale = activeLocale,
            declaredLanguageTag = ui.languageTag,
            availableLanguages = remember(connected) { controller.availableLanguages() },
            onSelectLanguage = { locale ->
                // Persist first: settings drive the UI, so the picker updates even if the
                // service is not bound yet.
                scope.launch { app.settings.setBookLanguage(bookId, locale.toLanguageTag()) }
                controller.selectLanguage(locale.toLanguageTag())
            },
            onInstallVoiceData = { openTtsDataInstaller(context) },
            onDismiss = { showSettings = false },
            onSpeechRate = { rate ->
                scope.launch { app.settings.setSpeechRate(rate) }
                controller.applySpeechSettings(rate, settings.pitch)
            },
            onPitch = { pitch ->
                scope.launch { app.settings.setPitch(pitch) }
                controller.applySpeechSettings(settings.speechRate, pitch)
            },
            onFontScale = { scale -> scope.launch { app.settings.setFontScale(scale) } },
            onTheme = { theme -> scope.launch { app.settings.setReaderTheme(theme) } },
            onSelectDefaultVoice = {
                scope.launch { app.settings.setVoice(languageCode, null) }
                controller.selectVoice(null)
            },
            onSelectVoice = { voice ->
                scope.launch { app.settings.setVoice(languageCode, voice.name) }
                controller.selectVoice(voice.name)
            },
        )
    }
}

@Composable
private fun PagedChapter(
    blocks: List<Block>,
    chapterIndex: Int,
    hasNextChapter: Boolean,
    fontScale: Float,
    palette: ReaderPalette,
    activeBlockIndex: Int?,
    activeOffset: Int?,
    sentenceRange: IntRange?,
    wordRange: IntRange?,
    onTapStart: (ReadingPosition) -> Unit,
    onMakeCard: (blockIndex: Int, offsetInBlock: Int) -> Unit,
    onRequestNextChapter: () -> Unit,
    loadImage: suspend (String) -> ByteArray?,
    onPageInfo: (PageInfo) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val hPadPx = with(density) { 48.dp.toPx() }.toInt()
        val vReservePx = with(density) { (32.dp + 30.dp).toPx() }.toInt() // vertical padding + indicator
        val widthPx = (constraints.maxWidth - hPadPx).coerceAtLeast(1)
        val heightPx = (constraints.maxHeight - vReservePx).coerceAtLeast(1)

        val pages = remember(blocks, widthPx, heightPx, fontScale) {
            paginateChapter(blocks, widthPx, heightPx, measurer, density, fontScale)
        }
        val textPageCount = pages.size
        val pageCount = textPageCount + if (hasNextChapter) 1 else 0
        val pagerState = rememberPagerState(pageCount = { pageCount })

        val targetPage = if (activeBlockIndex != null) {
            pageIndexForOffset(pages, activeBlockIndex, activeOffset ?: 0)
        } else {
            null
        }

        LaunchedEffect(chapterIndex, textPageCount) {
            val initial = (targetPage ?: 0).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            pagerState.scrollToPage(initial)
        }
        LaunchedEffect(targetPage) {
            val t = targetPage ?: return@LaunchedEffect
            if (t != pagerState.currentPage) pagerState.animateScrollToPage(t)
        }
        LaunchedEffect(pagerState.currentPage, textPageCount, pages) {
            val visible = pagerState.currentPage.coerceIn(0, textPageCount - 1)
            val firstText = pages.getOrNull(visible)?.firstNotNullOfOrNull { it as? PageElement.TextEl }
            onPageInfo(
                PageInfo(
                    current = (pagerState.currentPage + 1).coerceAtMost(textPageCount),
                    total = textPageCount,
                    firstBlockIndex = firstText?.slice?.blockIndex ?: 0,
                    firstOffset = firstText?.slice?.start ?: 0,
                ),
            )
        }
        LaunchedEffect(pagerState.settledPage) {
            if (hasNextChapter && pagerState.settledPage >= textPageCount) onRequestNextChapter()
        }

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
            if (pageIndex >= textPageCount) {
                EndOfChapterPage(palette)
            } else {
                PageView(
                    page = pages[pageIndex],
                    blocks = blocks,
                    chapterIndex = chapterIndex,
                    fontScale = fontScale,
                    palette = palette,
                    activeBlockIndex = activeBlockIndex,
                    sentenceRange = sentenceRange,
                    wordRange = wordRange,
                    onTapStart = onTapStart,
                    onMakeCard = onMakeCard,
                    loadImage = loadImage,
                )
            }
        }

        val indicator = if (pagerState.currentPage >= textPageCount) {
            "Next chapter »"
        } else {
            "${(pagerState.currentPage + 1).coerceAtMost(textPageCount)} / $textPageCount"
        }
        MonoText(
            text = indicator,
            size = 9.5f,
            tracking = 0.18f,
            color = palette.secondaryText,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun PageView(
    page: ReaderPage,
    blocks: List<Block>,
    chapterIndex: Int,
    fontScale: Float,
    palette: ReaderPalette,
    activeBlockIndex: Int?,
    sentenceRange: IntRange?,
    wordRange: IntRange?,
    onTapStart: (ReadingPosition) -> Unit,
    onMakeCard: (blockIndex: Int, offsetInBlock: Int) -> Unit,
    loadImage: suspend (String) -> ByteArray?,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        page.forEach { element ->
            when (element) {
                is PageElement.TextEl -> {
                    val slice = element.slice
                    val full = blocks[slice.blockIndex] as Block.Text
                    val text = full.text.substring(slice.start, slice.end)
                    val isActive = slice.blockIndex == activeBlockIndex
                    val sLocal = if (isActive) sentenceRange?.toSliceLocal(slice.start, slice.end) else null
                    val wLocal = if (isActive) wordRange?.toSliceLocal(slice.start, slice.end) else null
                    val annotated = highlightedText(
                        text = text,
                        sentenceRange = sLocal,
                        wordRange = wLocal,
                        sentenceColor = palette.sentenceHighlight,
                        sentenceTextColor = palette.sentenceText,
                        wordColor = palette.wordHighlight,
                        wordTextColor = palette.wordText,
                    )
                    // Captured so a touch can be resolved to the exact character under the
                    // finger, rather than to the start of the page slice.
                    var layout by remember(slice) { mutableStateOf<TextLayoutResult?>(null) }
                    Text(
                        text = annotated,
                        style = blockTextStyle(slice.kind, fontScale),
                        color = palette.text,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = blockVerticalPadding(slice.kind))
                            .then(
                                if (slice.kind == Block.Text.Kind.QUOTE) Modifier.padding(start = 12.dp) else Modifier,
                            )
                            // Innermost, so pointer coordinates line up with the glyphs.
                            .pointerInput(slice, full) {
                                fun offsetInBlock(point: Offset): Int? =
                                    layout?.getOffsetForPosition(point)?.plus(slice.start)

                                detectTapGestures(
                                    onTap = { point ->
                                        val offset = offsetInBlock(point) ?: slice.start
                                        onTapStart(
                                            ReadingPosition(
                                                chapterIndex,
                                                slice.blockIndex,
                                                full.sentenceIndexAt(offset),
                                            ),
                                        )
                                    },
                                    onLongPress = { point ->
                                        val offset = offsetInBlock(point) ?: slice.start
                                        onMakeCard(slice.blockIndex, offset)
                                    },
                                )
                            },
                    )
                }

                is PageElement.ImageEl -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LocalImage(
                            key = element.zipPath,
                            contentDescription = element.alt,
                            modifier = Modifier.fillMaxWidth().height(ImageDisplayHeight),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            loadBytes = { loadImage(element.zipPath) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EndOfChapterPage(palette: ReaderPalette) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MonoText("End of chapter", size = 12f, tracking = 0.2f, color = palette.accent)
            Text(
                "Keep swiping for the next chapter",
                fontFamily = AppFonts.Grotesk,
                fontSize = 14.sp,
                color = palette.secondaryText,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Opens an article's source page in whatever browser the device has. */
private fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Opens the TTS engine's own voice-data download screen, falling back to Android's
 * Text-to-speech settings if the engine doesn't offer one.
 */
private fun openTtsDataInstaller(context: android.content.Context) {
    val install = android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = android.content.Intent("com.android.settings.TTS_SETTINGS")
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(install) }
        .recoverCatching { context.startActivity(fallback) }
}

/** "CH 4 · A SHADOW ON THE WALL" — the mono subtitle under the book's title. */
private fun chapterLabel(chapterIndex: Int, chapterTitle: String?): String {
    val number = "Ch ${chapterIndex + 1}"
    return if (chapterTitle.isNullOrBlank()) number else "$number · $chapterTitle"
}

/**
 * The reader's own header: title in the interface face, chapter in mono underneath, and the
 * three ways out of the page (contents, settings, the original article) as muted glyphs.
 */
@Composable
private fun ReaderTopBar(
    title: String,
    chapterLabel: String,
    palette: ReaderPalette,
    articleLink: String?,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenContents: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(palette.background)
            .fillMaxWidth()
            .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = palette.text,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
            Text(
                text = title,
                fontFamily = AppFonts.Grotesk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            MonoText(
                text = chapterLabel,
                size = 10f,
                tracking = 0.16f,
                color = palette.secondaryText,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        articleLink?.let { link ->
            ReaderAction(Icons.AutoMirrored.Filled.OpenInNew, "Open original article", palette) { onOpenLink(link) }
        }
        ReaderAction(Icons.AutoMirrored.Filled.List, "Table of contents", palette, onClick = onOpenContents)
        ReaderAction(Icons.Filled.Tune, "Reading settings", palette, onClick = onOpenSettings)
    }
}

@Composable
private fun ReaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    palette: ReaderPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(42.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = palette.secondaryText, modifier = Modifier.size(20.dp))
    }
}

/**
 * Every message that overlays the page looks the same: a black block with a live-coloured
 * spine. Black regardless of the reader's theme, because it is chrome, not page.
 */
@Composable
private fun ReaderBanner(
    label: String,
    message: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Ink.Raised)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(Ink.Live))
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            MonoText(label, size = 10f, tracking = 0.18f, color = Ink.Live, weight = FontWeight.Bold)
            Text(
                message,
                fontFamily = AppFonts.Grotesk,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Ink.Muted,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/**
 * Shown when the speech engine itself failed. Tapping opens the reading settings, since the
 * fix is almost always picking a different language or voice.
 */
@Composable
private fun EngineErrorBanner(
    message: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderBanner(
        label = "Voice failed",
        message = "$message\nTap to open reading settings.",
        modifier = modifier,
        onClick = onOpenSettings,
    )
}

/** Shown when the article's page couldn't be reached, so only the feed's teaser is available. */
@Composable
private fun TruncatedArticleBanner(modifier: Modifier = Modifier) {
    ReaderBanner(
        label = "Summary only",
        message = "The full article couldn't be downloaded — this is the feed's own summary.",
        modifier = modifier,
    )
}

@Composable
private fun MissingVoiceBanner(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ReaderBanner(
        label = "No voice installed",
        message = "There's no voice for this book's language. Tap to open Text-to-speech settings.",
        modifier = modifier,
        onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent("com.android.settings.TTS_SETTINGS")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

@Composable
private fun ReaderBottomBar(
    chapterIndex: Int,
    chapterCount: Int,
    pageCurrent: Int,
    pageTotal: Int,
    chapterTimeText: String,
    bookTimeText: String?,
    isSpeaking: Boolean,
    palette: ReaderPalette,
    progress: Float,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPlayPause: () -> Unit,
    onNextSentence: () -> Unit,
    onPrevSentence: () -> Unit,
) {
    Column(
        modifier = Modifier
            .background(palette.background)
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .padding(top = 10.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            MonoText(
                text = "Page $pageCurrent / $pageTotal  ·  ch ${chapterIndex + 1}/$chapterCount",
                size = 10f,
                tracking = 0.16f,
                color = palette.secondaryText,
            )
            // The time still to run is the one number worth colouring: it is what changes
            // while the voice is speaking.
            MonoText(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = palette.accent)) { append(chapterTimeText.uppercase()) }
                    append(" LEFT")
                    if (bookTimeText != null) append("  ·  ${bookTimeText.uppercase()}")
                },
                size = 10f,
                tracking = 0.16f,
                color = palette.secondaryText,
            )
        }
        ThinProgress(
            fraction = progress,
            color = palette.accent,
            track = palette.hairline,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportIcon(
                icon = Icons.Filled.KeyboardDoubleArrowLeft,
                contentDescription = "Previous chapter",
                tint = palette.secondaryText,
                enabled = chapterIndex > 0,
                onClick = onPrevChapter,
            )
            TransportIcon(
                icon = Icons.Filled.SkipPrevious,
                contentDescription = "Previous sentence",
                tint = palette.text,
                size = 26.dp,
                onClick = onPrevSentence,
            )
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(palette.accent)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isSpeaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isSpeaking) "Pause" else "Play",
                    tint = palette.background,
                    modifier = Modifier.size(32.dp),
                )
            }
            TransportIcon(
                icon = Icons.Filled.SkipNext,
                contentDescription = "Next sentence",
                tint = palette.text,
                size = 26.dp,
                onClick = onNextSentence,
            )
            TransportIcon(
                icon = Icons.Filled.KeyboardDoubleArrowRight,
                contentDescription = "Next chapter",
                tint = palette.secondaryText,
                enabled = chapterIndex < chapterCount - 1,
                onClick = onNextChapter,
            )
        }
    }
}

@Composable
private fun TransportIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 24.dp,
) {
    Box(
        modifier = Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(size),
        )
    }
}
