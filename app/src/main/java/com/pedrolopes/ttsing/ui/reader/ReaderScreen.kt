package com.pedrolopes.ttsing.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.anki.CardDraft
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.settings.AppSettings
import com.pedrolopes.ttsing.data.settings.ReaderTheme
import com.pedrolopes.ttsing.tts.ReadingController
import com.pedrolopes.ttsing.ui.common.LocalImage
import com.pedrolopes.ttsing.ui.common.simpleFactory
import kotlinx.coroutines.launch
import java.util.Locale

private val DefaultSettings = AppSettings(
    libraryFolderUri = null,
    speechRate = 1f,
    pitch = 1f,
    fontScale = 1f,
    readerTheme = ReaderTheme.SYSTEM,
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
            ModalDrawerSheet {
                Text("Contents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                LazyColumn {
                    itemsIndexed(ui.toc) { _, entry ->
                        NavigationDrawerItem(
                            label = { Text(entry.title, maxLines = 2) },
                            selected = entry.spineIndex == ui.chapterIndex,
                            onClick = {
                                viewModel.showChapter(entry.spineIndex)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            containerColor = palette.background,
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(ui.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            ui.chapterTitle?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of contents")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Reading settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = palette.background,
                        titleContentColor = palette.text,
                    ),
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

                if (isThisBook && !playback.languageAvailable) {
                    MissingVoiceBanner(modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
                } else if (ui.isTruncated) {
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
        // Re-read on every language change so the voice list follows the picker.
        val activeLocale = remember(showSettings, settings.bookLanguages[bookId]) {
            controller.activeLocale()
        } ?: settings.localeFor(bookId, ui.languageTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault())
        val languageCode = activeLocale.language
        ReaderSettingsSheet(
            settings = settings,
            voices = remember(showSettings, languageCode) { controller.voicesForCurrentBook() },
            currentVoiceName = remember(showSettings, languageCode) { controller.currentVoiceName() },
            defaultVoiceName = remember(showSettings, languageCode) { controller.defaultVoiceName() },
            activeLocale = activeLocale,
            declaredLanguageTag = ui.languageTag,
            availableLanguages = remember(showSettings) { controller.availableLanguages() },
            onSelectLanguage = { locale -> controller.selectLanguage(locale.toLanguageTag()) },
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
        Text(
            text = indicator,
            style = MaterialTheme.typography.labelSmall,
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
            Text("End of chapter", style = MaterialTheme.typography.titleMedium, color = palette.text)
            Text(
                "Keep swiping for the next chapter",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondaryText,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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

/** Shown when the article's page couldn't be reached, so only the feed's teaser is available. */
@Composable
private fun TruncatedArticleBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            "Only the feed's summary is available — the full article couldn't be downloaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun MissingVoiceBanner(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().clickable {
            runCatching {
                context.startActivity(
                    android.content.Intent("com.android.settings.TTS_SETTINGS")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    ) {
        Text(
            "The voice for this book's language isn't installed. Tap to open Text-to-speech settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
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
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPlayPause: () -> Unit,
    onNextSentence: () -> Unit,
    onPrevSentence: () -> Unit,
) {
    Surface(color = palette.background, tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onPrevChapter, enabled = chapterIndex > 0) {
                    Icon(Icons.Filled.KeyboardDoubleArrowLeft, contentDescription = "Previous chapter", tint = palette.text)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Chapter ${chapterIndex + 1} of $chapterCount  ·  page $pageCurrent/$pageTotal",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.secondaryText,
                    )
                    Text(
                        text = buildString {
                            append("$chapterTimeText left in chapter")
                            if (bookTimeText != null) append("  ·  $bookTimeText left in book")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.secondaryText,
                    )
                }
                IconButton(onClick = onNextChapter, enabled = chapterIndex < chapterCount - 1) {
                    Icon(Icons.Filled.KeyboardDoubleArrowRight, contentDescription = "Next chapter", tint = palette.text)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevSentence) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous sentence", tint = palette.text, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.size(24.dp))
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(60.dp)) {
                    Icon(
                        if (isSpeaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isSpeaking) "Pause" else "Play",
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.size(24.dp))
                IconButton(onClick = onNextSentence) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next sentence", tint = palette.text, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
