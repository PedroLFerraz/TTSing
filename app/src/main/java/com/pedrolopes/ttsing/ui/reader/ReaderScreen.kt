package com.pedrolopes.ttsing.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.anki.CardDraft
import com.pedrolopes.ttsing.data.epub.Block
import com.pedrolopes.ttsing.data.epub.ReadingPosition
import com.pedrolopes.ttsing.data.settings.AppSettings
import com.pedrolopes.ttsing.data.settings.PdfView
import com.pedrolopes.ttsing.data.settings.ReaderTheme
import com.pedrolopes.ttsing.tts.ReadingController
import com.pedrolopes.ttsing.tts.SpeedSteps
import com.pedrolopes.ttsing.tts.piper.PiperCatalog
import com.pedrolopes.ttsing.ui.common.Hairline
import com.pedrolopes.ttsing.ui.common.LocalImage
import com.pedrolopes.ttsing.ui.common.MinTouchTarget
import com.pedrolopes.ttsing.ui.common.MonoText
import com.pedrolopes.ttsing.ui.common.ScreenPadding
import com.pedrolopes.ttsing.ui.common.ThinProgress
import com.pedrolopes.ttsing.ui.common.bookImageKey
import com.pedrolopes.ttsing.ui.common.simpleFactory
import com.pedrolopes.ttsing.ui.theme.AppFonts
import com.pedrolopes.ttsing.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private val DefaultSettings = AppSettings(
    libraryFolderUri = null,
    speechRate = 1f,
    fontScale = 1f,
    readerTheme = ReaderTheme.DARK,
)

/** How long a page must stay put before it counts as where the reader is. */
private const val BROWSE_SAVE_MS = 1_000L

/**
 * Bookkeeping for the browsing save: plain fields rather than state, since none of it is drawn.
 * [layoutEpoch] counts layout changes; a page reported after one moved because the text was
 * re-laid out, not because the reader turned to it.
 */
private class BrowseState {
    var reported: VisiblePage? = null
    var navigated = false
    var layoutEpoch = 0
    var seenEpoch = 0
    var layoutChanged = false
    /** Where the voice was last parked by the reader. */
    var parked: ReadingPosition? = null
    /** The save waiting out its delay, for when the screen is left before it fires. */
    var pending: (() -> Unit)? = null

    fun noteReport(page: VisiblePage) {
        // A report identical to the last changes nothing, so a layout change it follows moved no page.
        layoutChanged = (layoutChanged || layoutEpoch != seenEpoch) && page != reported
        seenEpoch = layoutEpoch
    }
}

/** The page on screen, as the pager reports it: where it is in its chapter, and where it starts. */
internal data class VisiblePage(
    val chapterIndex: Int,
    val pageInChapter: Int,
    val pagesInChapter: Int,
    /** First text on the page — the reading position when the voice isn't playing. */
    val firstBlockIndex: Int,
    val firstOffset: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    controller: ReadingController,
    /** Called with the next story's id when the voice plays on into it from this one. */
    onFollowPlayback: (String) -> Unit = {},
    viewModel: ReaderViewModel = viewModel(factory = simpleFactory { ReaderViewModel.create(bookId) }),
) {
    val app = TTSingApp.instance
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by controller.state.collectAsStateWithLifecycle()
    val connected by controller.connected.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = DefaultSettings)
    val palette = readerPalette(settings.readerTheme)

    // The activity draws the status and navigation bars' icons light, for the app's black. Over
    // a light or sepia page they would vanish, so they follow the page while it is open.
    val activity = LocalActivity.current
    val pageIsLight = palette.isLight
    DisposableEffect(activity, pageIsLight) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val wasStatus = bars?.isAppearanceLightStatusBars
        val wasNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = pageIsLight
        bars?.isAppearanceLightNavigationBars = pageIsLight
        onDispose {
            if (bars != null && wasStatus != null) bars.isAppearanceLightStatusBars = wasStatus
            if (bars != null && wasNavigation != null) bars.isAppearanceLightNavigationBars = wasNavigation
        }
    }

    // Minutes left on the sleep timer, ticking down for the footer and the settings sheet.
    // CHAPTER_END stands for a timer that runs to the end of the chapter instead of a clock.
    var sleepMinutesLeft by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(playback.sleepAtElapsedMs, playback.sleepAtChapterEnd) {
        while (true) {
            sleepMinutesLeft = when {
                playback.sleepAtChapterEnd -> CHAPTER_END
                playback.sleepAtElapsedMs == null -> null
                else -> {
                    val left = playback.sleepAtElapsedMs!! - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) null else ((left + 59_999) / 60_000).toInt()
                }
            }
            if (sleepMinutesLeft == null || sleepMinutesLeft == CHAPTER_END) break
            delay(10_000)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf<VisiblePage?>(null) }
    /** The page the voice is on, when it is in the chapters the pager holds. */
    var voicePage by remember { mutableStateOf<VisiblePage?>(null) }
    val cardDraft by viewModel.cardDraft.collectAsStateWithLifecycle()
    var narratorWasPlaying by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    // ---- chrome: the header and footer can be put away to leave the page the whole screen ----
    // A phone on its side starts with a slimmer header and a one-row footer; hiding both is one
    // tap on the page's margin (anywhere that isn't text) or the header's fullscreen button.
    val compactChrome = isCompactHeight(LocalConfiguration.current.screenHeightDp)
    var chromeShown by rememberSaveable { mutableStateOf(true) }
    var chromeHintShown by rememberSaveable { mutableStateOf(false) }
    val setChromeShown: (Boolean) -> Unit = { show ->
        chromeShown = show
        if (!show && !chromeHintShown) {
            chromeHintShown = true
            scope.launch { snackbarHost.showSnackbar("Tap outside the text to bring the controls back") }
        }
    }
    val toggleChrome: () -> Unit = { setChromeShown(!chromeShown) }

    // ---- notifications: asked for the first time Play is pressed, never in the way of playing ----
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val askForNotifications: () -> Unit = {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (notificationAsk.shouldAskNow(Build.VERSION.SDK_INT, granted)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    /** Starts the voice (at [position], or where it left off), then asks about notifications. */
    val startReading: (ReadingPosition?) -> Unit = { position ->
        controller.play(bookId, position)
        askForNotifications()
    }
    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)

    // Counting pages happens off the main thread, so it gets its own measurer: the one the
    // composition uses keeps a cache that is not safe to share across threads.
    val fontResolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val countingMeasurer = remember(fontResolver, density, layoutDirection) {
        TextMeasurer(fontResolver, density, layoutDirection, cacheSize = 0)
    }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(connected) { if (connected) controller.prepare(bookId) }

    val isThisBook = playback.isActive && playback.bookId == bookId

    // Pause narration when the card sheet opens; resume on close if it was playing.
    LaunchedEffect(cardDraft != null) {
        if (cardDraft != null && isThisBook && playback.isSpeaking) {
            narratorWasPlaying = true
            controller.pause()
        } else if (cardDraft == null && narratorWasPlaying) {
            controller.togglePlayPause(bookId)
            narratorWasPlaying = false
        }
    }

    // Stories play on into the next one by themselves; a screen left showing the finished
    // one would sit there with no highlight while the voice reads something else. Only a
    // screen that was itself playing the story follows: reopening that story later from the
    // list must show it, even though the service still remembers moving on from it.
    var playedHere by remember { mutableStateOf(false) }
    LaunchedEffect(isThisBook, playback.isSpeaking) {
        if (isThisBook && playback.isSpeaking) playedHere = true
    }
    LaunchedEffect(playback.bookId, playback.continuedFrom, playedHere) {
        val next = playback.bookId
        if (playedHere && playback.continuedFrom == bookId && next != null && next != bookId) onFollowPlayback(next)
    }

    LaunchedEffect(playback.position.chapterIndex, isThisBook) {
        if (isThisBook) viewModel.syncToChapter(playback.position.chapterIndex)
    }

    // ---- browsing: remembering the place, and parking the voice there ----
    // Where the reader settles is where they are in the book. It is saved a moment after the
    // page stops changing; if the voice is paused in this book it is parked there too, so Play
    // reads what is on screen. While the voice speaks, the service owns the position.
    val browse = remember { BrowseState() }
    val latestPlayback by rememberUpdatedState(playback)
    // Back from installing voice data in the system's settings: the engine has to be asked again
    // whether the language is there now, or the "no voice" banner stays until the book is reopened.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = latestPlayback
        if (now.isActive && now.bookId == bookId && !now.languageAvailable) {
            controller.activeLocale()?.let { controller.selectLanguage(it.toLanguageTag()) }
        }
    }
    val latestWindow by rememberUpdatedState(ui.window)
    val latestVoicePage by rememberUpdatedState(voicePage)
    val speakingHere = isThisBook && playback.isSpeaking
    LaunchedEffect(visible, speakingHere) {
        browse.pending = null
        val page = visible ?: return@LaunchedEffect
        // Pausing restarts this for the same page: that is not a new report.
        if (browse.reported != page) {
            browse.navigated = isNavigation(browse.reported, page, browse.layoutChanged, browse.navigated)
            browse.layoutChanged = false
            browse.reported = page
        }
        if (!browse.navigated) return@LaunchedEffect
        val commit = {
            val now = latestPlayback
            val serviceHasBook = now.isActive && now.bookId == bookId
            val voiceOnPage = latestVoicePage?.let {
                it.chapterIndex == page.chapterIndex && it.pageInChapter == page.pageInChapter
            } == true
            val action = browseAction(true, serviceHasBook && now.isSpeaking, serviceHasBook, voiceOnPage)
            if (action != BrowseAction.NONE) {
                val blocks = latestWindow.firstOrNull { it.index == page.chapterIndex }?.blocks
                val position = browsePosition(page.chapterIndex, blocks, page.firstBlockIndex, page.firstOffset)
                viewModel.saveBrowsedPosition(position)
                if (action == BrowseAction.SAVE_AND_PARK) {
                    browse.parked = position
                    controller.seekTo(position, alsoPlay = false, bookId = bookId)
                }
            }
        }
        browse.pending = commit
        delay(BROWSE_SAVE_MS)
        browse.pending = null
        commit()
    }
    // Leaving within the delay must not lose the last swipe.
    DisposableEffect(Unit) {
        onDispose {
            browse.pending?.invoke()
            browse.pending = null
        }
    }
    /** The voice goes where the reader went: along with it when speaking, parked there when not. */
    val seekVoiceToChapter: (Int) -> Unit = { chapter ->
        val now = latestPlayback
        if (now.isActive && now.bookId == bookId) {
            val position = ReadingPosition(chapter, 0, 0)
            browse.parked = position
            // A speaking voice saves its own place as it goes; a parked one is the reader's to save.
            if (!now.isSpeaking) viewModel.saveBrowsedPosition(position)
            controller.seekTo(position, alsoPlay = now.isSpeaking, bookId = bookId)
        }
    }
    // What the page follows: the spoken word, kept through a pause (which clears it) so that
    // pausing in a sentence that began on the previous page does not turn the page back.
    val lastWord = remember { arrayOfNulls<WordAt>(1) }
    val followAt = followOffset(playback.wordRange, playback.position, playback.sentenceRange, lastWord[0])
    SideEffect {
        playback.wordRange?.let { lastWord[0] = WordAt(playback.position, playback.sentenceRange, it.first) }
    }
    // Not turned to while it sits exactly where the reader parked it.
    val followVoice = !(isThisBook && playback.position == browse.parked)

    // A PDF shows its own pages unless this book was switched to reflowed text.
    val pdf = viewModel.pdf
    val pdfView = if (pdf != null) settings.pdfViewFor(bookId) else null
    val showsPdfPages = pdf != null && pdfView == PdfView.PAGES

    val visibleChapter = visible?.chapterIndex ?: ui.anchorChapter
    val visibleTitle = ui.window.firstOrNull { it.index == visibleChapter }?.title

    // ---- where the reader is, for the footer ----

    // Stored counts for the book, with the chapter on screen corrected to what the pager
    // actually laid out. A single-section article needs no counting: its pages are all here.
    // A PDF's own pages need no counting either.
    val pageCounts: List<Int>? = (if (showsPdfPages) ui.pdfSectionPages.takeIf { it.isNotEmpty() } else ui.pageCounts)
        ?.let { counts ->
            counts.toMutableList().also { m ->
                visible?.let { v -> if (v.chapterIndex in m.indices) m[v.chapterIndex] = v.pagesInChapter }
            }
        }
        ?: visible?.takeIf { ui.chapterCount == 1 }?.let { listOf(it.pagesInChapter) }

    // ---- time left, KOReader's way (see TimeLeft) ----
    // Where from: the voice's page while it is speaking, the page on screen otherwise.
    val voiceChapter = playback.position.chapterIndex
    val trackPlayback = isThisBook && playback.isSpeaking && voicePage != null
    val at = if (trackPlayback) voicePage else visible
    val fromChapter = at?.chapterIndex ?: visibleChapter
    // At what pace: this book's own listening — live from the service while it has the book,
    // as saved otherwise — starting from the voice's speed while the book is new.
    val listenedMs = if (isThisBook) playback.bookListenedMs else ui.listenedMs
    val listenedChars = if (isThisBook) playback.bookListenedChars else ui.listenedChars
    val voiceCps = playback.charsPerSecond.takeIf { isThisBook } ?: settings.charsPerSecond
    val charCounts = ui.chapterCharCounts
    val bookChars = charCounts.sum()
    val pagesKnown = pageCounts != null
    // The pace is taken when a chapter starts (and again when the counts arrive or the speech
    // rate changes), the way KOReader refreshes on page turns: within a chapter, the only
    // thing that moves the figures is reading on.
    val secondsPerChar = remember(fromChapter, bookChars > 0, settings.speechRate, isThisBook) {
        TimeLeft.secondsPerChar(listenedMs, listenedChars, voiceCps) / settings.speechRate.coerceAtLeast(0.1f)
    }
    // Also refreshed when a PDF switches between its own pages and reflowed ones: a page
    // holds a different amount of text in each.
    val secondsPerPage = remember(fromChapter, pagesKnown, bookChars, settings.speechRate, isThisBook, showsPdfPages) {
        pageCounts?.let { counts ->
            TimeLeft.secondsPerPage(listenedMs, listenedChars, voiceCps, bookChars, BookPages.total(counts), settings.speechRate)
        }
    }
    val estimate = if (pageCounts != null && secondsPerPage != null && at != null) {
        TimeLeft.byPages(pageCounts, fromChapter, at.pageInChapter, secondsPerPage)
    } else {
        // Pages still being counted: characters left, from the voice or the top of the page.
        val fromBlocks = ui.window.firstOrNull { it.index == fromChapter }?.blocks.orEmpty()
        val fromBlock = if (trackPlayback) playback.position.blockIndex else (visible?.firstBlockIndex ?: 0)
        val fromOffset = if (trackPlayback) (playback.sentenceRange?.first ?: 0) else (visible?.firstOffset ?: 0)
        TimeLeft.byCharacters(
            ReadingEstimate.remainingCharsInChapter(fromBlocks, fromBlock, fromOffset),
            charCounts.drop(fromChapter + 1).sum(),
            secondsPerChar,
        )
    }
    val chapterTimeText = ReadingEstimate.formatDuration(estimate.chapterSeconds)
    val bookTimeText = if (charCounts.isNotEmpty()) ReadingEstimate.formatMinutes(estimate.bookMinutes) else null
    val remainingBookChars = charCounts.takeIf { it.isNotEmpty() }?.let {
        val fromBlocks = ui.window.firstOrNull { c -> c.index == visibleChapter }?.blocks.orEmpty()
        ReadingEstimate.remainingCharsInBook(
            ReadingEstimate.remainingCharsInChapter(fromBlocks, visible?.firstBlockIndex ?: 0, visible?.firstOffset ?: 0),
            it,
            visibleChapter,
        )
    }

    val pageInChapter = visible?.pageInChapter ?: 0
    val progress = when {
        pageCounts != null -> BookPages.fraction(pageCounts, visibleChapter, pageInChapter)
        remainingBookChars != null && charCounts.sum() > 0 -> 1f - remainingBookChars.toFloat() / charCounts.sum()
        else -> 0f
    }
    val pageLabel = when {
        pageCounts != null ->
            "Page ${BookPages.bookPage(pageCounts, visibleChapter, pageInChapter)} / ${BookPages.total(pageCounts)}"
        else -> "Counting pages"
    }
    val ticks = remember(pageCounts, ui.toc) {
        pageCounts?.let { counts -> BookPages.chapterTicks(counts, ui.toc.map { it.spineIndex }) }.orEmpty()
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
                val tocState = rememberLazyListState()
                val currentEntry = currentTocIndex(ui.toc.map { it.spineIndex }, visibleChapter)
                // Opening the contents shows where the reader is in them, not the top.
                LaunchedEffect(drawerState.targetValue) {
                    if (drawerState.targetValue == androidx.compose.material3.DrawerValue.Open && currentEntry >= 0) {
                        tocState.scrollToItem((currentEntry - 2).coerceAtLeast(0))
                    }
                }
                LazyColumn(state = tocState) {
                    itemsIndexed(ui.toc) { index, entry ->
                        val current = index == currentEntry
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.showChapter(entry.spineIndex, onLanded = seekVoiceToChapter)
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
                if (chromeShown) ReaderTopBar(
                    title = ui.title,
                    chapterLabel = chapterLabel(visibleChapter, ui.chapterCount, visibleTitle),
                    palette = palette,
                    articleLink = ui.articleLink,
                    compact = compactChrome,
                    onBack = onBack,
                    onOpenLink = { link -> openInBrowser(context, link) },
                    onOpenContents = { scope.launch { drawerState.open() } },
                    onOpenSettings = { showSettings = true },
                    // Room for one more glyph: always for a book, and on its side for an article.
                    onHideChrome = { setChromeShown(false) }.takeIf { compactChrome || ui.articleLink == null },
                )
            },
            bottomBar = {
                if (chromeShown) ReaderFooter(
                    compact = compactChrome,
                    // A book that could not be opened has no pages to count or time to run.
                    showPosition = ui.error == null && ui.window.isNotEmpty(),
                    pageLabel = pageLabel,
                    percent = (progress * 100).toInt(),
                    progress = progress,
                    ticks = ticks,
                    chapterTimeText = chapterTimeText,
                    bookTimeText = bookTimeText,
                    canGoBack = ui.window.firstOrNull()?.index?.let { it < visibleChapter } ?: false,
                    canGoForward = ui.window.lastOrNull()?.index?.let { it > visibleChapter } ?: false,
                    isSpeaking = isThisBook && playback.isSpeaking,
                    speechRate = settings.speechRate,
                    sleepMinutesLeft = sleepMinutesLeft,
                    palette = palette,
                    onSelectSpeed = { next ->
                        scope.launch { app.settings.setSpeechRate(next) }
                        controller.setSpeechRate(next)
                    },
                    onPrevChapter = { viewModel.stepChapter(visibleChapter, forward = false, onLanded = seekVoiceToChapter) },
                    onNextChapter = { viewModel.stepChapter(visibleChapter, forward = true, onLanded = seekVoiceToChapter) },
                    onPlayPause = {
                        val wasSpeaking = isThisBook && playback.isSpeaking
                        controller.togglePlayPause(bookId)
                        if (!wasSpeaking) askForNotifications()
                    },
                    onNextSentence = { controller.next() },
                    onPrevSentence = { controller.previous() },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    ui.error != null ->
                        Text(
                            ui.error!!,
                            color = palette.text,
                            fontFamily = AppFonts.Grotesk,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                            textAlign = TextAlign.Center,
                        )
                    ui.window.isEmpty() ->
                        CircularProgressIndicator(color = palette.accent, modifier = Modifier.align(Alignment.Center))
                    showsPdfPages && pdf != null -> PdfPageView(
                        pdf = pdf,
                        jump = ui.jump,
                        background = palette.background,
                        voiceChapter = voiceChapter.takeIf { isThisBook },
                        activeBlockIndex = playback.position.blockIndex.takeIf { isThisBook },
                        sentenceRange = playback.sentenceRange.takeIf { isThisBook },
                        wordRange = playback.wordRange.takeIf { isThisBook },
                        followVoice = followVoice,
                        onTap = { page, x, y ->
                            scope.launch {
                                // A tap that is on no text is a tap on the margin: it toggles the chrome.
                                val hit = viewModel.pdfPositionAt(page, x, y)
                                if (hit != null) startReading(hit.first) else toggleChrome()
                            }
                        },
                        onLongPress = { page, x, y ->
                            scope.launch {
                                viewModel.pdfPositionAt(page, x, y)?.let { (position, offset) ->
                                    viewModel.updateCardDraft(viewModel.draftFor(position.chapterIndex, position.blockIndex, offset))
                                }
                            }
                        },
                        onVisiblePage = { page -> browse.noteReport(page); visible = page },
                        onVoicePage = { page -> voicePage = page },
                        onSettledChapter = viewModel::onVisibleChapter,
                    )
                    else -> PagedBook(
                        bookId = bookId,
                        window = ui.window,
                        jump = ui.jump,
                        fontScale = settings.fontScale,
                        palette = palette,
                        voiceChapter = voiceChapter.takeIf { isThisBook },
                        activeBlockIndex = playback.position.blockIndex.takeIf { isThisBook },
                        // Follow the spoken WORD, so a sentence spanning a page boundary
                        // flips the page exactly when the highlight crosses it.
                        activeOffset = followAt,
                        sentenceRange = playback.sentenceRange,
                        wordRange = playback.wordRange,
                        followVoice = followVoice,
                        onTapStart = { position -> startReading(position) },
                        onToggleChrome = toggleChrome,
                        onMakeCard = { chapter, blockIndex, offset ->
                            viewModel.updateCardDraft(viewModel.draftFor(chapter, blockIndex, offset))
                        },
                        loadImage = viewModel::imageBytes,
                        onGeometry = { geometry ->
                            browse.layoutEpoch++
                            viewModel.ensurePageCounts(geometry, countingMeasurer, density)
                        },
                        onVisiblePage = { page -> browse.noteReport(page); visible = page },
                        onVoicePage = { page -> voicePage = page },
                        onSettledChapter = viewModel::onVisibleChapter,
                    )
                }

                val playbackError = playback.error.takeIf { isThisBook }
                val finishedHere = playback.finished && playback.bookId == bookId
                // Each banner can be sent away; it comes back if what it says happens again.
                var errorDismissed by rememberSaveable(playbackError) { mutableStateOf(false) }
                var voiceDismissed by rememberSaveable(playback.languageAvailable) { mutableStateOf(false) }
                var finishedDismissed by rememberSaveable(finishedHere) { mutableStateOf(false) }
                var summaryDismissed by rememberSaveable { mutableStateOf(false) }
                var retrying by remember { mutableStateOf(false) }
                when {
                    // An engine failure is the most urgent thing to say: without it, a voice
                    // that cannot speak just looks like a reader that stopped working.
                    playbackError != null && ui.error == null && !errorDismissed -> EngineErrorBanner(
                        message = playbackError,
                        onOpenSettings = { showSettings = true },
                        onDismiss = { errorDismissed = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    )
                    isThisBook && !playback.languageAvailable && !voiceDismissed ->
                        MissingVoiceBanner(
                            onDismiss = { voiceDismissed = true },
                            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                        )
                    finishedHere && !finishedDismissed -> FinishedBanner(
                        onStartOver = { startReading(null) },
                        onDismiss = { finishedDismissed = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    )
                    ui.isTruncated && !summaryDismissed -> TruncatedArticleBanner(
                        message = summaryBannerMessage(ui.articleIssue),
                        retrying = retrying,
                        onRetry = {
                            retrying = true
                            viewModel.retryFullText { ok ->
                                retrying = false
                                if (ok) {
                                    // The voice holds the old, short text: it has to read the new.
                                    if (latestPlayback.isActive && latestPlayback.bookId == bookId) controller.reloadBook(bookId)
                                } else {
                                    scope.launch { snackbarHost.showSnackbar("Still couldn't get the full article") }
                                }
                            }
                        },
                        onDismiss = { summaryDismissed = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    )
                }
            }
        }
    }

    cardDraft?.let { draft ->
        // Use effective locale (with user's language override applied) for consistency with card audio.
        val declaredLocale = ui.languageTag
            ?.let { Locale.forLanguageTag(it) }
            ?.takeIf { it.language.isNotEmpty() }
        val effectiveLocale = settings.localeFor(bookId, declaredLocale ?: viewModel.bookLocale())

        val cardStatus by viewModel.cardStatus.collectAsStateWithLifecycle()
        CardSheet(
            draft = draft,
            locale = effectiveLocale,
            isSubmitting = ui.isSavingCard,
            error = cardStatus.error,
            offerWithoutAudio = cardStatus.offerWithoutAudio,
            isAnkiInstalled = viewModel::isAnkiInstalled,
            onDraftChange = { viewModel.updateCardDraft(it) },
            onPreviewAudio = { viewModel.previewCardAudio(it) },
            // The sheet has already asked before discarding typed work.
            onDismiss = { viewModel.updateCardDraft(null) },
            onSubmit = { toAdd ->
                viewModel.submitCard(toAdd) { message -> scope.launch { snackbarHost.showSnackbar(message) } }
            },
            onSubmitWithoutAudio = { toAdd ->
                viewModel.submitCard(toAdd, withAudio = false) { message ->
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
        // Downloads belong to the app, not to this sheet, so closing it neither loses one nor
        // lets a second start into the same folder.
        val downloads = app.piperDownloads
        val downloadProgress by downloads.progress.collectAsStateWithLifecycle()
        val installedNeural by downloads.installed.collectAsStateWithLifecycle()
        // Coming back from Android's "Install voice data" screen: look at the engine again.
        var resumes by remember { mutableIntStateOf(0) }
        LifecycleResumeEffect(Unit) {
            resumes++
            downloads.refresh()
            onPauseOrDispose { }
        }
        // The voice lists are read from the engine and the disk, off the main thread, and
        // again whenever something they depend on changes: the voice in use, a download or
        // delete finishing, the language, or the app coming back to the front.
        var voiceLists by remember { mutableStateOf(VoiceLists()) }
        LaunchedEffect(activeLocale, connected, playback.voiceName, installedNeural, resumes) {
            voiceLists = withContext(Dispatchers.IO) {
                VoiceLists(
                    voices = controller.voicesFor(activeLocale),
                    defaultVoiceName = controller.defaultVoiceNameFor(activeLocale),
                    languages = controller.availableLanguages(),
                )
            }
        }
        ReaderSettingsSheet(
            settings = settings,
            voices = voiceLists.voices,
            currentVoiceName = playback.voiceName,
            speaking = isThisBook && playback.isSpeaking,
            defaultVoiceName = voiceLists.defaultVoiceName,
            activeLocale = activeLocale,
            declaredLanguageTag = ui.languageTag,
            languageOverridden = bookId in settings.bookLanguages,
            availableLanguages = voiceLists.languages,
            onSelectLanguage = { locale ->
                val tag = locale.toLanguageTag()
                // Persist first: settings drive the UI, so the picker updates even if the
                // service is not bound yet. The variant is remembered for every book in that
                // language; only a different language is this book's own exception.
                scope.launch {
                    val ownLanguage = locale.language == (declaredLocale ?: Locale.getDefault()).language
                    app.settings.setBookLanguage(bookId, if (ownLanguage) null else tag)
                    app.settings.setVariant(tag)
                }
                controller.selectLanguage(tag)
            },
            onUseBookLanguage = {
                val restored = settings.copy(bookLanguages = settings.bookLanguages - bookId)
                    .localeFor(bookId, declaredLocale ?: Locale.getDefault())
                scope.launch { app.settings.setBookLanguage(bookId, null) }
                controller.selectLanguage(restored.toLanguageTag())
            },
            onInstallVoiceData = { openTtsDataInstaller(context) },
            onDismiss = { showSettings = false },
            onSpeechRate = { rate ->
                scope.launch { app.settings.setSpeechRate(rate) }
                controller.setSpeechRate(rate)
            },
            onFontScale = { scale ->
                // Snapped to exact 5% steps, so the same size is always the same layout key.
                scope.launch { app.settings.setFontScale((scale * 20).roundToInt() / 20f) }
            },
            onTheme = { theme -> scope.launch { app.settings.setReaderTheme(theme) } },
            pdfView = pdfView,
            onPdfView = { view ->
                scope.launch { app.settings.setPdfView(bookId, view) }
                // The other view opens where this one was.
                visible?.let { viewModel.jumpTo(it.chapterIndex, it.firstBlockIndex) }
            },
            onSelectDefaultVoice = {
                scope.launch { app.settings.setVoice(languageCode, null) }
                controller.selectVoice(null)
            },
            onSelectVoice = { name ->
                scope.launch { app.settings.setVoice(languageCode, name) }
                controller.selectVoice(name)
            },
            onToggleFavorite = { name -> scope.launch { app.settings.toggleFavoriteVoice(name) } },
            neuralVoices = PiperCatalog.forLanguage(activeLocale),
            otherNeuralVoices = PiperCatalog.otherLanguages(activeLocale),
            installedNeuralIds = installedNeural,
            downloading = downloadProgress,
            sleepMinutesLeft = sleepMinutesLeft,
            sleepStartedMinutes = playback.sleepMinutes,
            onSleepTimer = { minutes, atChapterEnd -> controller.setSleepTimer(minutes, atChapterEnd) },
            onDeleteVoice = { voice ->
                scope.launch {
                    downloads.delete(voice.id)
                    // Every language that had picked it, not just this book's: a stored choice
                    // pointing at a deleted voice would fall back silently the next time.
                    val used = VoiceRules.languagesToClear(settings.voices, voice.id)
                    used.forEach { app.settings.setVoice(it, null) }
                    if (languageCode in used || playback.voiceName == voice.id) controller.selectVoice(null)
                }
            },
            onDownloadVoice = { voice -> downloads.start(voice) },
            onCancelDownload = downloads::cancel,
            isMetered = { isMeteredConnection(context) },
        )
    }
}


/** One page of the continuous book: which chapter it belongs to, and what is on it. */
private data class BookPage(
    val chapter: LoadedChapter,
    val pageInChapter: Int,
    val pagesInChapter: Int,
    val content: ReaderPage,
) {
    /** Stable across window changes, which is what keeps the page on screen while it moves. */
    val key: String get() = "${chapter.index}:$pageInChapter"
}

/** The page area and the font pages are laid out for; a change in any of them lays every page out again. */
private data class PageLayout(val widthPx: Int, val heightPx: Int, val fontScale: Float)

/** The chapters of [window] laid out at [layout], their pages numbered on from one another. */
private class PagedWindow(val window: List<LoadedChapter>, val layout: PageLayout, val pages: List<BookPage>)

/**
 * The book as one run of pages. The pager spans the chapters in [window] back to back, so
 * swiping off a chapter's last page lands on the next chapter's first; when the settled page
 * is in a neighbour, [onSettledChapter] lets the window recentre on it. Pages are keyed by
 * chapter and page number, so the pager keeps the same page on screen while the list shifts
 * around it. When the layout itself changes (font size, rotation) the numbers no longer mean
 * the same text, so the page is found again by the text it started with.
 *
 * Laying pages out takes a while for a long chapter, so it happens off the main thread: the
 * pages on screen stay as they are until the new ones are ready.
 */
@Composable
private fun PagedBook(
    /** Whose images these are: the same path in two books is two pictures. */
    bookId: String,
    window: List<LoadedChapter>,
    jump: JumpRequest?,
    fontScale: Float,
    palette: ReaderPalette,
    voiceChapter: Int?,
    activeBlockIndex: Int?,
    activeOffset: Int?,
    sentenceRange: IntRange?,
    wordRange: IntRange?,
    /** False while the voice sits where the reader parked it: turning to it would undo their browsing. */
    followVoice: Boolean,
    onTapStart: (ReadingPosition) -> Unit,
    /** A tap on the page that is not on any text: show or hide the header and footer. */
    onToggleChrome: () -> Unit,
    onMakeCard: (chapterIndex: Int, blockIndex: Int, offsetInBlock: Int) -> Unit,
    loadImage: suspend (String) -> ByteArray?,
    onGeometry: (PageGeometry) -> Unit,
    onVisiblePage: (VisiblePage) -> Unit,
    onVoicePage: (VisiblePage?) -> Unit,
    onSettledChapter: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = pageWidthPx(
            constraints.maxWidth.toFloat(),
            with(density) { ReaderColumnMaxWidth.toPx() },
            with(density) { PageHorizontalPadding.toPx() },
        )
        val heightPx = (constraints.maxHeight - with(density) { (PageVerticalPadding * 2 + PageBottomSlack).toPx() })
            .toInt().coerceAtLeast(1)
        // A line of text across a tablet, or a phone on its side, is too long to follow: the
        // column stops at a readable width and sits in the middle, the rest being margin.
        val sideInset = with(density) { columnInsetPx(constraints.maxWidth.toFloat(), ReaderColumnMaxWidth.toPx()).toDp() }
        val geometry = PageGeometry(widthPx, heightPx, fontScale, density.density)
        LaunchedEffect(geometry) { onGeometry(geometry) }

        // A measurer of its own: the composition's keeps a cache that is not safe to share
        // with a background thread. Each chapter is laid out once per layout and reused as the
        // window slides along.
        val fontResolver = LocalFontFamilyResolver.current
        val layoutDirection = LocalLayoutDirection.current
        val measurer = remember(fontResolver, density, layoutDirection) {
            TextMeasurer(fontResolver, density, layoutDirection, cacheSize = 0)
        }
        val layout = PageLayout(widthPx, heightPx, fontScale)
        val paginated = remember(layout) { ConcurrentHashMap<Int, List<ReaderPage>>() }
        val paged by produceState<PagedWindow?>(null, window, layout) {
            value = withContext(Dispatchers.Default) {
                PagedWindow(
                    window,
                    layout,
                    window.flatMap { chapter ->
                        val chapterPages = paginated.getOrPut(chapter.index) {
                            paginateChapter(chapter.blocks, widthPx, heightPx, measurer, density, fontScale)
                        }
                        chapterPages.mapIndexed { i, content -> BookPage(chapter, i, chapterPages.size, content) }
                    },
                )
            }
        }
        val current = paged
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }
            return@BoxWithConstraints
        }
        val pages = current.pages

        // A jump (opening the book, the contents, « ») builds a fresh pager already on the
        // right page, rather than scrolling an old one there and flashing what was in between.
        // It waits for the pages it lands on: a jump that arrives with a new window would
        // otherwise be placed on the old one.
        val gate = remember { JumpGate() }
        if (jump != null && jump.id != gate.applied?.id && current.layout == layout &&
            current.window.any { it.index == jump.chapterIndex }
        ) {
            gate.applied = jump
        }
        val appliedJump = gate.applied
        key(appliedJump?.id) {
            val startPage = remember(appliedJump?.id) {
                appliedJump?.let { target ->
                    indexOfBlock(pages, target.chapterIndex, target.blockIndex, target.offsetInBlock)
                } ?: 0
            }
            val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) {
                pages.size
            }

            // The pager positions by index, and recentring the window renumbers every page. So
            // when the list changes, put back whichever page was on screen, by its key, in the
            // same frame — otherwise moving into the next chapter jumps to whatever page now
            // sits at the old index. After a re-layout the keys mean different text, so the
            // page is found by the text it began with instead.
            val shown = remember { PagesOnScreen(current.layout) }
            if (shown.pages !== pages) {
                val onScreen = shown.pages?.getOrNull(pagerState.currentPage)
                val relaid = shown.layout != current.layout
                shown.pages = pages
                shown.layout = current.layout
                val index = when {
                    onScreen == null -> -1
                    !relaid -> pages.indexOfFirst { it.key == onScreen.key }
                    else -> onScreen.anchor()
                        ?.let { (block, offset) -> indexOfBlock(pages, onScreen.chapter.index, block, offset) } ?: -1
                }
                if (index >= 0 && index != pagerState.currentPage) pagerState.requestScrollToPage(index)
            }
            val currentPages by rememberUpdatedState(pages)
            val currentLayout by rememberUpdatedState(current.layout)
            val following by rememberUpdatedState(followVoice)

            // The voice's page, by key. Reading along turns to it when the voice *moves* — not
            // when its page merely reappears in a recentred window or under a new layout, which
            // would drag a reader who had swiped ahead back to where the voice was parked.
            val voicePageKey = if (voiceChapter != null && activeBlockIndex != null) {
                indexOfBlock(pages, voiceChapter, activeBlockIndex, activeOffset ?: 0)?.let { pages[it].key }
            } else {
                null
            }
            LaunchedEffect(voicePageKey, pages) {
                val page = pages.firstOrNull { it.key == voicePageKey }
                onVoicePage(
                    page?.let {
                        VisiblePage(it.chapter.index, it.pageInChapter, it.pagesInChapter, 0, 0)
                    },
                )
            }
            LaunchedEffect(voicePageKey) {
                if (voicePageKey == null || voicePageKey == shown.lastVoiceKey) return@LaunchedEffect
                val firstSighting = shown.lastVoiceKey == null
                val relaid = shown.voiceLayout != currentLayout
                shown.lastVoiceKey = voicePageKey
                shown.voiceLayout = currentLayout
                // On opening, the jump already put the reader where the voice is parked; after
                // a re-layout the same voice sits under a new page number, not somewhere new;
                // and a voice the reader parked themselves is already where they are looking.
                if (firstSighting || relaid || !following) return@LaunchedEffect
                val target = currentPages.indexOfFirst { it.key == voicePageKey }.takeIf { it >= 0 } ?: return@LaunchedEffect
                if (target != pagerState.currentPage) pagerState.animateScrollToPage(target)
            }

            LaunchedEffect(pagerState.currentPage, pages) {
                val page = pages.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
                val firstText = page.content.firstNotNullOfOrNull { it as? PageElement.TextEl }
                onVisiblePage(
                    VisiblePage(
                        chapterIndex = page.chapter.index,
                        pageInChapter = page.pageInChapter,
                        pagesInChapter = page.pagesInChapter,
                        firstBlockIndex = firstText?.slice?.blockIndex ?: 0,
                        firstOffset = firstText?.slice?.start ?: 0,
                    ),
                )
            }
            // Only a page the reader settled on moves the window — deliberately not keyed on the
            // page list, whose changes are the window's own doing.
            LaunchedEffect(pagerState.settledPage) {
                currentPages.getOrNull(pagerState.settledPage)?.let { onSettledChapter(it.chapter.index) }
            }

            val toggleChrome by rememberUpdatedState(onToggleChrome)
            HorizontalPager(
                state = pagerState,
                key = { index -> pages.getOrNull(index)?.key ?: "gap:$index" },
                // Text takes its own taps (read from here, make a card); what is left over —
                // the margins, the gaps between paragraphs, the space around a narrow column —
                // comes up to here.
                modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { toggleChrome() }) },
            ) { index ->
                val page = pages.getOrNull(index) ?: return@HorizontalPager
                val isVoiceChapter = page.chapter.index == voiceChapter
                PageView(
                    page = page.content,
                    blocks = page.chapter.blocks,
                    bookId = bookId,
                    sideInset = sideInset,
                    chapterIndex = page.chapter.index,
                    // The font the pages were laid out in, not the one just chosen: the two only
                    // differ for the moment before the new pages arrive.
                    fontScale = current.layout.fontScale,
                    palette = palette,
                    activeBlockIndex = activeBlockIndex.takeIf { isVoiceChapter },
                    sentenceRange = sentenceRange.takeIf { isVoiceChapter },
                    wordRange = wordRange.takeIf { isVoiceChapter },
                    onTapStart = onTapStart,
                    onMakeCard = { blockIndex, offset -> onMakeCard(page.chapter.index, blockIndex, offset) },
                    loadImage = loadImage,
                )
            }
        }
    }
}

/**
 * What the pager last had on screen. A plain holder rather than state: it is bookkeeping for
 * keeping the page steady across window changes, and must not itself cause recomposition.
 */
private class PagesOnScreen(layout: PageLayout) {
    var pages: List<BookPage>? = null
    var layout: PageLayout = layout
    var voiceLayout: PageLayout = layout
    var lastVoiceKey: String? = null
}

/** The jump the pager was last built for (see [PagedBook]). */
private class JumpGate {
    var applied: JumpRequest? = null
}

/** Block and offset of the first thing on the page: what to look for in a new layout. */
private fun BookPage.anchor(): Pair<Int, Int>? = content.firstOrNull()?.let { element ->
    when (element) {
        is PageElement.TextEl -> element.slice.blockIndex to element.slice.start
        is PageElement.ImageEl -> element.blockIndex to 0
    }
}

/** Index in [pages] of the page showing [offset] of block [blockIndex] in [chapterIndex]. */
private fun indexOfBlock(pages: List<BookPage>, chapterIndex: Int, blockIndex: Int, offset: Int): Int? {
    val start = pages.indexOfFirst { it.chapter.index == chapterIndex }.takeIf { it >= 0 } ?: return null
    val chapterPages = pages.filter { it.chapter.index == chapterIndex }.map { it.content }
    return start + pageForAnchor(chapterPages, blockIndex, offset)
}

private val PageHorizontalPadding = 24.dp

/** The widest the column of text gets; past it the page has margins of its own. */
private val ReaderColumnMaxWidth = 640.dp
private val PageVerticalPadding = 16.dp

/** A little room under the last line, so descenders never meet the footer. */
private val PageBottomSlack = 8.dp

@Composable
private fun PageView(
    page: ReaderPage,
    blocks: List<Block>,
    bookId: String,
    /** Margin on both sides beyond [PageHorizontalPadding], for a page wider than the text column. */
    sideInset: Dp,
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
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = PageHorizontalPadding + sideInset, vertical = PageVerticalPadding),
    ) {
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
                            .padding(start = blockStartIndent(slice.kind))
                            // Innermost, so pointer coordinates line up with the glyphs.
                            .pointerInput(slice, full, chapterIndex) {
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
                            key = bookImageKey(bookId, element.zipPath),
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
/** Whether the active network is one a data plan is charged for. */
private fun isMeteredConnection(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
        ?.isActiveNetworkMetered == true

private fun openTtsDataInstaller(context: android.content.Context) {
    val install = android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = android.content.Intent("com.android.settings.TTS_SETTINGS")
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(install) }
        .recoverCatching { context.startActivity(fallback) }
}

/**
 * "CH 4/12 · A SHADOW ON THE WALL" — the mono subtitle under the book's title. The count
 * rides here because the bottom bar has only enough room for the page.
 */
private fun chapterLabel(chapterIndex: Int, chapterCount: Int, chapterTitle: String?): String? {
    // A news article is one section; "CH 1/1" under its title would say nothing.
    if (chapterCount <= 1) return null
    val number = "Ch ${chapterIndex + 1}/$chapterCount"
    return if (chapterTitle.isNullOrBlank()) number else "$number · $chapterTitle"
}

/**
 * The reader's own header: title in the interface face, chapter in mono underneath, and the
 * three ways out of the page (contents, settings, the original article) as muted glyphs.
 */
@Composable
private fun ReaderTopBar(
    title: String,
    chapterLabel: String?,
    palette: ReaderPalette,
    articleLink: String?,
    /** On its side the header gives up its padding: every dp of height is a line of text. */
    compact: Boolean,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenContents: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Puts the header and footer away; null when there is no room for the button. */
    onHideChrome: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .background(palette.background)
            .fillMaxWidth()
            // Scaffold insets its content but not a custom top bar, so the header has to
            // keep clear of the status bar itself.
            .statusBarsPadding()
            .padding(start = 18.dp, end = 10.dp, top = if (compact) 0.dp else 8.dp, bottom = if (compact) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(MinTouchTarget).clickable(role = Role.Button, onClick = onBack),
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
            chapterLabel?.let { label ->
                MonoText(
                    text = label,
                    size = 10f,
                    tracking = 0.16f,
                    color = palette.secondaryText,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        articleLink?.let { link ->
            ReaderAction(Icons.AutoMirrored.Filled.OpenInNew, "Open original article", palette) { onOpenLink(link) }
        }
        ReaderAction(Icons.AutoMirrored.Filled.List, "Table of contents", palette, onClick = onOpenContents)
        ReaderAction(Icons.Filled.Tune, "Reading settings", palette, onClick = onOpenSettings)
        onHideChrome?.let { ReaderAction(Icons.Filled.Fullscreen, "Hide controls", palette, onClick = it) }
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
        modifier = Modifier.size(MinTouchTarget).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = palette.secondaryText, modifier = Modifier.size(20.dp))
    }
}

/**
 * Every message that overlays the page looks the same: a black block with a live-coloured
 * spine. Black regardless of the reader's theme, because it is chrome, not page. It can be
 * sent away with the cross ([onDismiss]); [actionLabel] adds a button under the message.
 */
@Composable
private fun ReaderBanner(
    label: String,
    message: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Ink.Raised)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(Ink.Live))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, top = 12.dp, bottom = if (actionLabel != null && onAction != null) 0.dp else 12.dp),
        ) {
            MonoText(label, size = 10f, tracking = 0.18f, color = Ink.Live, weight = FontWeight.Bold)
            Text(
                message,
                fontFamily = AppFonts.Grotesk,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Ink.Muted,
                modifier = Modifier.padding(top = 5.dp),
            )
            if (actionLabel != null && onAction != null) {
                Box(
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .clickable(role = Role.Button, onClick = onAction),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    MonoText(actionLabel, size = 11f, tracking = 0.16f, color = Ink.Live, weight = FontWeight.Bold)
                }
            }
        }
        if (onDismiss != null) {
            Box(
                modifier = Modifier.size(MinTouchTarget).clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Ink.Muted, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(14.dp))
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
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderBanner(
        label = "Voice failed",
        message = "$message\nTap to open reading settings.",
        modifier = modifier,
        onClick = onOpenSettings,
        onDismiss = onDismiss,
    )
}

/**
 * Shown when only the feed's teaser could be had for an article: [message] says why, and
 * Retry goes for the page again.
 */
@Composable
private fun TruncatedArticleBanner(
    message: String,
    retrying: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderBanner(
        label = "Summary only",
        message = message,
        modifier = modifier,
        actionLabel = if (retrying) "Retrying…" else "Retry",
        onAction = if (retrying) ({}) else onRetry,
        onDismiss = onDismiss,
    )
}

/** Shown when the book has been read to its end: Play would start it over anyway. */
@Composable
private fun FinishedBanner(onStartOver: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    ReaderBanner(
        label = "Finished",
        message = "You've reached the end of this book. Start over from the beginning?",
        modifier = modifier,
        actionLabel = "Start over",
        onAction = onStartOver,
        onDismiss = onDismiss,
    )
}

@Composable
private fun MissingVoiceBanner(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
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
        onDismiss = onDismiss,
    )
}

/**
 * KOReader's footer, in this app's clothes: where you are in the *book* — page of pages,
 * percentage, a progress bar with a tick where each chapter starts — then how long is left,
 * and the clock; the transport controls sit underneath.
 */
@Composable
private fun ReaderFooter(
    /** One row of controls instead of three rows of figures and a big button: for a phone on its side. */
    compact: Boolean,
    showPosition: Boolean,
    pageLabel: String,
    percent: Int,
    progress: Float,
    ticks: List<Float>,
    chapterTimeText: String,
    bookTimeText: String?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isSpeaking: Boolean,
    speechRate: Float,
    sleepMinutesLeft: Int?,
    palette: ReaderPalette,
    onSelectSpeed: (Float) -> Unit,
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
            .navigationBarsPadding()
            .padding(horizontal = ScreenPadding)
            .padding(top = if (compact) 2.dp else 8.dp, bottom = if (compact) 2.dp else 10.dp),
    ) {
        if (compact) {
            if (showPosition) {
                BookProgressBar(
                    progress = progress,
                    ticks = ticks,
                    color = palette.accent,
                    track = palette.hairline,
                    tickColor = palette.secondaryText,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (showPosition) {
                    MonoText(
                        text = "$pageLabel · $percent%",
                        size = 10f,
                        tracking = 0.16f,
                        color = palette.secondaryText,
                        maxLines = 2,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TransportControls(
                    palette = palette,
                    playSize = 52.dp,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    isSpeaking = isSpeaking,
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter,
                    onPlayPause = onPlayPause,
                    onNextSentence = onNextSentence,
                    onPrevSentence = onPrevSentence,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sleepMinutesLeft != null) SleepLabel(sleepMinutesLeft, palette)
                    SpeedControl(speechRate, palette, onSelectSpeed)
                }
            }
            return@Column
        }
        if (showPosition) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Takes what the figures on the right leave and wraps inside it, so a large font
                // size makes two short lines rather than a cut-off one.
                MonoText(
                    text = "$pageLabel · $percent%",
                    size = 10f,
                    tracking = 0.16f,
                    color = palette.secondaryText,
                    maxLines = 2,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                // Reading speed is the setting that gets changed most, and it used to be three
                // taps deep in the settings sheet. Here it opens the list of steps.
                SpeedControl(speechRate, palette, onSelectSpeed)
                if (sleepMinutesLeft != null) SleepLabel(sleepMinutesLeft, palette)
                Spacer(Modifier.width(8.dp))
                MonoText(text = rememberClock(), size = 10f, tracking = 0.16f, color = palette.secondaryText)
            }
            BookProgressBar(
                progress = progress,
                ticks = ticks,
                color = palette.accent,
                track = palette.hairline,
                tickColor = palette.secondaryText,
                modifier = Modifier.padding(bottom = 7.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                // The chapter's time left is the one figure worth colouring: it is what moves
                // while the voice is speaking. Each side wraps within its own half.
                MonoText(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = palette.accent)) { append(chapterTimeText.uppercase()) }
                        append(" LEFT IN CHAPTER")
                    },
                    size = 10f,
                    tracking = 0.16f,
                    color = palette.secondaryText,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (bookTimeText != null) {
                    Spacer(Modifier.width(12.dp))
                    MonoText(
                        text = "$bookTimeText in book",
                        size = 10f,
                        tracking = 0.16f,
                        color = palette.secondaryText,
                        maxLines = 2,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        TransportControls(
            palette = palette,
            playSize = 68.dp,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            isSpeaking = isSpeaking,
            onPrevChapter = onPrevChapter,
            onNextChapter = onNextChapter,
            onPlayPause = onPlayPause,
            onNextSentence = onNextSentence,
            onPrevSentence = onPrevSentence,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun SleepLabel(minutesLeft: Int, palette: ReaderPalette) {
    MonoText(
        text = if (minutesLeft == CHAPTER_END) "SLEEP CH" else "SLEEP $minutesLeft",
        size = 10f,
        tracking = 0.16f,
        color = palette.accent,
        modifier = Modifier.padding(start = 6.dp),
    )
}

/**
 * The reading speed: a bordered chip with a caret, in a 48dp touch target, that opens the list
 * of speeds ([SpeedSteps.ALL]) with the current one marked.
 */
@Composable
private fun SpeedControl(speechRate: Float, palette: ReaderPalette, onSelect: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val tint = if (speechRate == 1f) palette.secondaryText else palette.accent
    val current = SpeedSteps.selected(speechRate)
    Box {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                .semantics { contentDescription = "Reading speed ${SpeedSteps.format(speechRate)}" }
                .clickable(role = Role.Button, onClickLabel = "Choose reading speed") { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                    .padding(start = 9.dp, end = 3.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText(text = SpeedSteps.format(speechRate), size = 12f, tracking = 0.08f, color = tint, weight = FontWeight.Bold)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ink.Raised) {
            SpeedSteps.ALL.forEach { step ->
                val isCurrent = step == current
                DropdownMenuItem(
                    text = {
                        MonoText(
                            text = SpeedSteps.format(step),
                            size = 13f,
                            tracking = 0.1f,
                            color = if (isCurrent) Ink.Live else Ink.Text,
                            weight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = if (isCurrent) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = Ink.Live, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSelect(step)
                    },
                    modifier = Modifier.semantics { selected = isCurrent },
                )
            }
        }
    }
}

/** « ‹ play › »: the five transport buttons, [playSize] being the round one in the middle. */
@Composable
private fun TransportControls(
    palette: ReaderPalette,
    playSize: Dp,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isSpeaking: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPlayPause: () -> Unit,
    onNextSentence: () -> Unit,
    onPrevSentence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(
            icon = Icons.Filled.KeyboardDoubleArrowLeft,
            contentDescription = "Previous chapter",
            tint = palette.secondaryText,
            enabled = canGoBack,
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
                .size(playSize)
                .clip(CircleShape)
                .background(palette.accent)
                .clickable(role = Role.Button, onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isSpeaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isSpeaking) "Pause" else "Play",
                tint = palette.background,
                modifier = Modifier.size(playSize / 2),
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
            enabled = canGoForward,
            onClick = onNextChapter,
        )
    }
}

/**
 * The book's progress: a thin bar, filled to [progress], with a short mark at each chapter
 * start so you can see how far the current chapter has to run.
 */
@Composable
private fun BookProgressBar(
    progress: Float,
    ticks: List<Float>,
    color: Color,
    track: Color,
    tickColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(9.dp)) {
        val barHeight = 3.dp.toPx()
        val top = (size.height - barHeight) / 2
        drawRect(track, topLeft = Offset(0f, top), size = Size(size.width, barHeight))
        drawRect(color, topLeft = Offset(0f, top), size = Size(size.width * progress.coerceIn(0f, 1f), barHeight))
        val tickWidth = 1.dp.toPx()
        ticks.forEach { fraction ->
            val x = size.width * fraction
            drawRect(tickColor, topLeft = Offset(x - tickWidth / 2, 0f), size = Size(tickWidth, size.height))
        }
    }
}

/** The time, updated on the minute, in the device's own 12/24-hour format. */
@Composable
private fun rememberClock(): String {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val time by produceState(initialValue = format.format(java.util.Date())) {
        while (true) {
            value = format.format(java.util.Date())
            val now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000 - now % 60_000 + 50)
        }
    }
    return time
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
        modifier = Modifier.size(MinTouchTarget).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
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
