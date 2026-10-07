package com.pedrolopes.ttsing.ui.reader

import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedrolopes.ttsing.data.settings.AppSettings
import com.pedrolopes.ttsing.data.settings.PdfView
import com.pedrolopes.ttsing.data.settings.ReaderTheme
import com.pedrolopes.ttsing.tts.LanguageOption
import com.pedrolopes.ttsing.tts.SpeedSteps
import com.pedrolopes.ttsing.tts.needsDownload
import com.pedrolopes.ttsing.tts.piper.CatalogVoice
import com.pedrolopes.ttsing.tts.piper.DownloadProgress
import com.pedrolopes.ttsing.tts.piper.PiperCatalog
import com.pedrolopes.ttsing.tts.piper.PiperVoice
import kotlinx.coroutines.delay
import com.pedrolopes.ttsing.ui.common.Hairline
import com.pedrolopes.ttsing.ui.common.ChoiceChip
import com.pedrolopes.ttsing.ui.common.MonoText
import com.pedrolopes.ttsing.ui.common.ThinProgress
import com.pedrolopes.ttsing.ui.theme.AppFonts
import com.pedrolopes.ttsing.ui.theme.Ink
import java.util.Locale

/** What [ReaderSettingsSheet]'s `sleepMinutesLeft` uses to mean "when the chapter ends". */
const val CHAPTER_END = -1

/** The order the design lists themes in: the app's own first, the system's last. */
private val ThemeOrder = listOf(ReaderTheme.DARK, ReaderTheme.LIGHT, ReaderTheme.SEPIA, ReaderTheme.SYSTEM)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(
    settings: AppSettings,
    voices: List<Voice>,
    currentVoiceName: String?,
    /** True while the voice is actually reading; paused, the current voice is "selected", not "speaking". */
    speaking: Boolean,
    defaultVoiceName: String?,
    /** The language the book is actually read in — the override if set, else `dc:language`. */
    activeLocale: Locale,
    /** What the EPUB itself declares, shown so a wrong declaration is visible. */
    declaredLanguageTag: String?,
    /** Whether this book is read in a language other than the one it declares, by choice. */
    languageOverridden: Boolean,
    availableLanguages: List<LanguageOption>,
    /** The app's own neural voices for this language, installed or not. */
    neuralVoices: List<CatalogVoice>,
    /** Neural voices for the languages this book is not in, to fetch before they're needed. */
    otherNeuralVoices: List<CatalogVoice>,
    installedNeuralIds: Set<String>,
    downloading: DownloadProgress?,
    onDownloadVoice: (CatalogVoice) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteVoice: (CatalogVoice) -> Unit,
    /** Whether the connection in use is metered, asked when a download is tapped. */
    isMetered: () -> Boolean,
    /** Minutes left on the sleep timer, or null; -1 means "until the chapter ends". */
    sleepMinutesLeft: Int?,
    /** What the running sleep timer was set to, so its chip stays lit while it counts down. */
    sleepStartedMinutes: Int?,
    onSleepTimer: (minutes: Int?, atChapterEnd: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSpeechRate: (Float) -> Unit,
    onFontScale: (Float) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    /** How this PDF is shown, or null when the book isn't a PDF. */
    pdfView: PdfView?,
    onPdfView: (PdfView) -> Unit,
    onSelectLanguage: (Locale) -> Unit,
    onUseBookLanguage: () -> Unit,
    onInstallVoiceData: () -> Unit,
    onSelectDefaultVoice: () -> Unit,
    onSelectVoice: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val sheetState = // Opens at half height so a font or theme change can be seen behind the sheet;
    // drag it up for the voice list.
    rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val storedVoice = settings.voiceFor(activeLocale.language)
    val favoriteVoices = settings.favoriteVoices
    val languageName = activeLocale.displayName()
    var languageMenuOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CatalogVoice?>(null) }
    var askMetered by remember { mutableStateOf<CatalogVoice?>(null) }
    // Said when a tap can't do what it asks, e.g. a second download while one is running.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000)
            notice = null
        }
    }
    val declaredLocale = declaredLanguageTag
        ?.let { Locale.forLanguageTag(it) }
        ?.takeIf { it.language.isNotEmpty() }
    val isOverridden = declaredLocale != null && languageOverridden

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ink.Surface,
        dragHandle = {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(width = 44.dp, height = 4.dp).background(Ink.Handle))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            MonoText("Reading settings", size = 13f, tracking = 0.2f, color = Ink.Text, weight = FontWeight.Bold)

            // Speeds you would actually choose, not a slider: each step is a fresh synthesis
            // of the sentence being spoken, so sliding through the range restarted the reading
            // at every hair of movement.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MonoText("Speed", size = 11f, tracking = 0.16f)
                    MonoText(SpeedSteps.format(settings.speechRate), size = 11f, tracking = 0.16f, color = Ink.Live)
                }
                // A speed stored from the old slider may sit between steps: the nearest one shows.
                val currentStep = SpeedSteps.nearest(settings.speechRate)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SpeedSteps.ALL.forEach { step ->
                        ChoiceChip(
                            label = SpeedSteps.format(step),
                            selected = step == currentStep,
                            onClick = { onSpeechRate(step) },
                        )
                    }
                }
            }
            if (pdfView != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MonoText("Show PDF as", size = 11f, tracking = 0.16f)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("Pages", selected = pdfView == PdfView.PAGES, onClick = { onPdfView(PdfView.PAGES) })
                        ChoiceChip("Text", selected = pdfView == PdfView.TEXT, onClick = { onPdfView(PdfView.TEXT) })
                    }
                    Text(
                        text = if (pdfView == PdfView.PAGES) {
                            "The pages as printed, with what's being read marked on them."
                        } else {
                            "Reflowed like a book, so font size and theme apply."
                        },
                        fontFamily = AppFonts.Grotesk,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Ink.Muted,
                    )
                }
            }

            // In 5% steps: every font size is its own page layout, counted across the whole
            // book, so a continuous slider would recount it for every hair of movement.
            // A PDF's own pages have their own type, so there it has nothing to change.
            if (pdfView != PdfView.PAGES) {
                HardSlider(
                    "Font size",
                    settings.fontScale,
                    "%.0f%%".format(settings.fontScale * 100),
                    0.8f..1.8f,
                    onFontScale,
                    steps = 19,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonoText("Theme", size = 11f, tracking = 0.16f)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeOrder.forEach { theme ->
                        ChoiceChip(
                            label = theme.displayName(),
                            selected = settings.readerTheme == theme,
                            onClick = { onTheme(theme) },
                        )
                    }
                }
            }

            Hairline(inset = 0.dp)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonoText("Language", size = 11f, tracking = 0.16f)
                Text(
                    text = when {
                        isOverridden -> "Set by you. This book declares ${declaredLocale!!.displayName()}."
                        declaredLocale != null ->
                            "From the book's own metadata. The variant you pick is used for every " +
                                "book in ${activeLocale.displayLanguage}."
                        else -> "This book doesn't say what language it's in."
                    },
                    fontFamily = AppFonts.Grotesk,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Ink.Muted,
                )

                Box {
                    ChoiceRow(
                        title = languageName,
                        subtitle = "Tap to change the reading language",
                        selected = true,
                        isCurrent = false,
                        onClick = { languageMenuOpen = true },
                    )
                    DropdownMenu(
                        expanded = languageMenuOpen,
                        onDismissRequest = { languageMenuOpen = false },
                        containerColor = Ink.Raised,
                        modifier = Modifier.heightIn(max = 400.dp),
                    ) {
                        if (availableLanguages.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No languages available yet", color = Ink.Muted) },
                                onClick = { languageMenuOpen = false },
                            )
                        }
                        availableLanguages.forEach { option ->
                            val current = option.locale.language == activeLocale.language &&
                                option.locale.country == activeLocale.country
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            option.locale.displayName(),
                                            fontFamily = AppFonts.Grotesk,
                                            fontSize = 14.sp,
                                            color = if (current) Ink.Live else Ink.Text,
                                        )
                                        if (!option.downloaded) {
                                            MonoText("Downloads on first use", size = 9f, color = Ink.Dim)
                                        }
                                    }
                                },
                                leadingIcon = {
                                    if (current) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Current language",
                                            tint = Ink.Live,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                },
                                onClick = {
                                    languageMenuOpen = false
                                    onSelectLanguage(option.locale)
                                },
                            )
                        }
                        Hairline(inset = 8.dp)
                        if (isOverridden) {
                            DropdownMenuItem(
                                text = {
                                    MonoText(
                                        "Use the book's language (${declaredLocale!!.displayLanguage})",
                                        size = 11f,
                                        color = Ink.Live,
                                    )
                                },
                                onClick = {
                                    languageMenuOpen = false
                                    onUseBookLanguage()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { MonoText("Install voice data…", size = 11f, color = Ink.Live) },
                            onClick = {
                                languageMenuOpen = false
                                onInstallVoiceData()
                            },
                        )
                    }
                }
            }

            Hairline(inset = 0.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MonoText("Sleep timer", size = 11f, tracking = 0.16f)
                SleepTimerUi.remaining(sleepMinutesLeft)?.let { MonoText(it, size = 10f, tracking = 0.12f, color = Ink.Live) }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val options = listOf<Pair<String, Pair<Int?, Boolean>>>(
                    "OFF" to (null to false),
                    "15" to (15 to false),
                    "30" to (30 to false),
                    "60" to (60 to false),
                    "CHAPTER" to (null to true),
                )
                options.forEach { (label, setting) ->
                    val (minutes, chapterEnd) = setting
                    val selected = SleepTimerUi.chipSelected(minutes, chapterEnd, sleepStartedMinutes, sleepMinutesLeft)
                    ChoiceChip(label = label, selected = selected) { onSleepTimer(minutes, chapterEnd) }
                }
            }

            Hairline(inset = 0.dp)

            val deviceVoices = remember(voices) {
                voices.filter { PiperVoice.FEATURE !in it.features.orEmpty() }.map {
                    DeviceVoice(it.name, it.locale, it.quality, it.isNetworkConnectionRequired, it.needsDownload())
                }
            }
            val neuralName = { id: String -> PiperCatalog.find(id)?.displayName ?: PiperCatalog.nameFor(id) }
            val activeLabel = currentVoiceName?.let { name ->
                if (name in installedNeuralIds) neuralName(name)
                else deviceVoices.firstOrNull { it.name == name }?.let { VoiceLabels.line(it, deviceVoices).full } ?: name
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                MonoText("Voice — $languageName", size = 11f, tracking = 0.16f)
                // Paused, the voice is still the one chosen, but nothing is speaking with it.
                if (activeLabel != null) {
                    MonoText(if (speaking) "Now speaking" else "Selected", size = 10f, tracking = 0.12f, color = Ink.Live)
                }
            }
            notice?.let { MonoText(it, size = 10f, tracking = 0.1f, color = Ink.Live, maxLines = 3) }

            val requestDownload: (CatalogVoice) -> Unit = { voice ->
                when (VoiceRules.downloadTap(voice, downloading, isMetered())) {
                    VoiceRules.DownloadTap.START -> onDownloadVoice(voice)
                    VoiceRules.DownloadTap.ASK_METERED -> askMetered = voice
                    VoiceRules.DownloadTap.BUSY ->
                        notice = "Already downloading ${downloading?.id?.let(neuralName)} — cancel it or wait, then try again."
                    VoiceRules.DownloadTap.IGNORE -> Unit
                }
            }

            // The voices you starred, plus the one in use, are all there is to see; the many
            // others an engine offers — a dozen regions, each in several variants — wait in a
            // folder. Until anything is starred the folder starts open, so nothing is hidden.
            val pinnedNeural = neuralVoices.filter { it.id in favoriteVoices || it.id == storedVoice }
            val pinnedDevice = deviceVoices.filter { it.name in favoriteVoices || it.name == storedVoice }
            val anyStarred = (pinnedNeural.map { it.id } + pinnedDevice.map { it.name }).any { it in favoriteVoices }
            var showMore by remember(activeLocale.language) { mutableStateOf(!anyStarred) }
            var showOnline by remember(activeLocale.language) { mutableStateOf(false) }
            // The chosen, current and starred voices stay in view even when they need a connection.
            val keep = { voice: DeviceVoice ->
                voice.name in favoriteVoices || voice.name == storedVoice || voice.name == currentVoiceName
            }

            val effectiveDefault = VoiceLabels.effectiveDefault(activeLocale, deviceVoices, defaultVoiceName)
            Column {
                ChoiceRow(
                    title = "Device default",
                    subtitle = effectiveDefault?.let { VoiceLabels.line(it, deviceVoices).full }
                        ?: "The engine's built-in voice for this language",
                    selected = storedVoice == null,
                    isCurrent = storedVoice == null && currentVoiceName != null,
                    speaking = speaking,
                    onClick = onSelectDefaultVoice,
                )
                pinnedNeural.forEach { voice ->
                    NeuralVoiceRow(
                        voice = voice,
                        installedNeuralIds = installedNeuralIds,
                        downloading = downloading,
                        storedVoice = storedVoice,
                        currentVoiceName = currentVoiceName,
                        speaking = speaking,
                        starred = voice.id in favoriteVoices,
                        onToggleFavorite = { onToggleFavorite(voice.id) },
                        onSelectVoice = onSelectVoice,
                        onDownloadVoice = requestDownload,
                        onCancelDownload = onCancelDownload,
                        onDeleteVoice = { deleting = it },
                    )
                }
                pinnedDevice.forEach { voice ->
                    val line = VoiceLabels.line(voice, deviceVoices)
                    ChoiceRow(
                        title = line.full,
                        subtitle = line.detail,
                        selected = storedVoice == voice.name,
                        isCurrent = voice.name == currentVoiceName,
                        speaking = speaking,
                        onClick = { onSelectVoice(voice.name) },
                        starred = voice.name in favoriteVoices,
                        onToggleStar = { onToggleFavorite(voice.name) },
                    )
                }
            }

            val moreNeural = neuralVoices - pinnedNeural.toSet()
            val moreDevice = deviceVoices - pinnedDevice.toSet()
            val deviceGroups = VoiceLabels.groups(moreDevice, deviceVoices, showOnline, keep)
            val onlineHeld = VoiceLabels.hiddenOnline(moreDevice, keep)
            val moreCount = moreNeural.size + deviceGroups.sumOf { it.voices.size } + otherNeuralVoices.size
            if (moreCount > 0) {
                MonoText(
                    text = if (showMore) "More voices — tap to hide" else "More voices · $moreCount — tap to show",
                    size = 10f,
                    tracking = 0.14f,
                    color = Ink.Live,
                    modifier = Modifier
                        .clickable { showMore = !showMore }
                        .padding(top = 4.dp, bottom = 4.dp),
                )
            }
            if (!anyStarred) {
                MonoText(
                    "Star the voices you use and the rest fold away.",
                    size = 10f,
                    tracking = 0.1f,
                    color = Ink.Dim,
                )
            }

            if (showMore) {
                if (moreNeural.isNotEmpty()) {
                    MonoText(
                        "In this app · neural",
                        size = 10f,
                        tracking = 0.14f,
                        color = Ink.Dim,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Column {
                        moreNeural.forEach { voice ->
                            NeuralVoiceRow(
                                voice = voice,
                                installedNeuralIds = installedNeuralIds,
                                downloading = downloading,
                                storedVoice = storedVoice,
                                currentVoiceName = currentVoiceName,
                                speaking = speaking,
                                starred = false,
                                onToggleFavorite = { onToggleFavorite(voice.id) },
                                onSelectVoice = onSelectVoice,
                                onDownloadVoice = requestDownload,
                                onCancelDownload = onCancelDownload,
                                onDeleteVoice = { deleting = it },
                            )
                        }
                    }
                }

                // The device's own voices come before the other languages' neural ones, so the
                // long list sits under a heading of its own rather than under a collapsed one.
                MonoText(
                    "On this device",
                    size = 10f,
                    tracking = 0.14f,
                    color = Ink.Dim,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                when (VoiceRules.noVoices(deviceVoices.size, neuralVoices.size)) {
                    VoiceRules.NoVoices.ALL -> Text(
                        "This engine has no voices for $languageName. Install voice data in Android's " +
                            "Text-to-speech settings, or pick another language above.",
                        fontFamily = AppFonts.Grotesk,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Ink.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    VoiceRules.NoVoices.DEVICE_ONLY -> Text(
                        "The device's engine has no voices for $languageName; the neural voices above work offline.",
                        fontFamily = AppFonts.Grotesk,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Ink.Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    VoiceRules.NoVoices.NONE -> Column {
                        deviceGroups.forEach { group ->
                            MonoText(
                                group.region,
                                size = 10f,
                                tracking = 0.14f,
                                color = Ink.Dim,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                            group.voices.forEach { line ->
                                ChoiceRow(
                                    title = line.short,
                                    subtitle = line.detail,
                                    selected = storedVoice == line.name,
                                    isCurrent = line.name == currentVoiceName,
                                    speaking = speaking,
                                    onClick = { onSelectVoice(line.name) },
                                    starred = false,
                                    onToggleStar = { onToggleFavorite(line.name) },
                                )
                            }
                        }
                        if (moreDevice.any { it.online }) {
                            MonoText(
                                text = if (showOnline) "Online voices — tap to hide" else "Online voices · $onlineHeld — tap to show",
                                size = 10f,
                                tracking = 0.14f,
                                color = Ink.Live,
                                modifier = Modifier
                                    .clickable { showOnline = !showOnline }
                                    .padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                }

                if (otherNeuralVoices.isNotEmpty()) {
                    var showOthers by remember { mutableStateOf(false) }
                    MonoText(
                        text = if (showOthers) {
                            "Other languages — tap to hide"
                        } else {
                            "Other languages · ${otherNeuralVoices.size} voices — tap to show"
                        },
                        size = 10f,
                        tracking = 0.14f,
                        color = Ink.Live,
                        modifier = Modifier
                            .clickable { showOthers = !showOthers }
                            .padding(top = 14.dp, bottom = 4.dp),
                    )
                    if (showOthers) {
                        otherNeuralVoices.forEach { voice ->
                            NeuralVoiceRow(
                                voice = voice,
                                installedNeuralIds = installedNeuralIds,
                                downloading = downloading,
                                storedVoice = storedVoice,
                                currentVoiceName = currentVoiceName,
                                speaking = speaking,
                                starred = false,
                                onToggleFavorite = {},
                                onSelectVoice = onSelectVoice,
                                onDownloadVoice = requestDownload,
                                onCancelDownload = onCancelDownload,
                                onDeleteVoice = { deleting = it },
                                otherLanguage = true,
                            )
                        }
                    }
                }
            }
        }
    }

    deleting?.let { voice ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Ink.Raised,
            text = { Text(VoiceRules.deletePrompt(voice), color = Ink.Text, fontFamily = AppFonts.Grotesk) },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDeleteVoice(voice) }) { Text("Delete", color = Ink.Live) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = Ink.Muted) } },
        )
    }
    askMetered?.let { voice ->
        AlertDialog(
            onDismissRequest = { askMetered = null },
            containerColor = Ink.Raised,
            text = { Text(VoiceRules.meteredPrompt(voice), color = Ink.Text, fontFamily = AppFonts.Grotesk) },
            confirmButton = {
                TextButton(onClick = { askMetered = null; onDownloadVoice(voice) }) { Text("Download", color = Ink.Live) }
            },
            dismissButton = { TextButton(onClick = { askMetered = null }) { Text("Cancel", color = Ink.Muted) } },
        )
    }
}

/**
 * One of the app's own neural voices: picked when installed, fetched when not. With
 * [otherLanguage] it belongs to a language this book isn't in, so it can be fetched or freed
 * but not picked.
 */
@Composable
private fun NeuralVoiceRow(
    voice: CatalogVoice,
    installedNeuralIds: Set<String>,
    downloading: DownloadProgress?,
    storedVoice: String?,
    currentVoiceName: String?,
    speaking: Boolean,
    starred: Boolean,
    onToggleFavorite: () -> Unit,
    onSelectVoice: (String) -> Unit,
    onDownloadVoice: (CatalogVoice) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteVoice: (CatalogVoice) -> Unit,
    otherLanguage: Boolean = false,
) {
    val installed = voice.id in installedNeuralIds
    val busy = downloading?.takeIf { it.id == voice.id && !it.failed }
    val region = VoiceLabels.region(voice.locale)
    ChoiceRow(
        title = if (otherLanguage) "${voice.displayName} · $region" else voice.displayName,
        subtitle = VoiceRules.neuralSubtitle(voice, installed, downloading, region, otherLanguage),
        selected = !otherLanguage && storedVoice == voice.id,
        isCurrent = !otherLanguage && voice.id == currentVoiceName,
        speaking = speaking,
        onClick = {
            if (installed) {
                if (!otherLanguage) onSelectVoice(voice.id)
            } else if (busy == null) {
                onDownloadVoice(voice)
            }
        },
        // A downloaded voice is ~67 MB: a trash button says so, and a long press still works.
        onDelete = { onDeleteVoice(voice) }.takeIf { installed && !voice.bundled },
        starred = starred,
        onToggleStar = onToggleFavorite.takeIf { !otherLanguage },
    )
    if (busy != null) {
        ThinProgress(
            fraction = busy.fraction,
            modifier = Modifier.padding(start = 3.dp, top = 2.dp),
        )
        MonoText(
            "Cancel download",
            size = 10f,
            tracking = 0.14f,
            color = Ink.Live,
            modifier = Modifier
                .clickable(onClick = onCancelDownload)
                .padding(start = 17.dp, top = 8.dp, bottom = 10.dp, end = 17.dp),
        )
    }
}

/**
 * A row you pick from — voice, language. The selected one is marked with a live spine and a
 * raised fill rather than a tick, so the current choice is legible at a glance down the list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    /** This is the voice the engine is set up with. */
    isCurrent: Boolean,
    onClick: () -> Unit,
    /** Whether the current voice is actually reading, which decides how it is marked. */
    speaking: Boolean = true,
    /** Shows a trash button at the row's end, and a long press does the same. */
    onDelete: (() -> Unit)? = null,
    starred: Boolean = false,
    /** Shows a star at the row's end when set; voices only. */
    onToggleStar: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (selected) Ink.Well else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDelete,
                onLongClickLabel = if (onDelete != null) "Delete voice" else null,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The spine runs the full height of whatever the row's text works out to.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) Ink.Live else Color.Transparent),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                title,
                fontFamily = AppFonts.Grotesk,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Ink.Text else Color(0xFFC9C9C4),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MonoText(
                subtitle,
                size = 10f,
                tracking = 0.1f,
                color = if (selected) Ink.Muted else Ink.Dim,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 2,
            )
        }
        if (isCurrent) {
            Icon(
                if (speaking) Icons.Filled.GraphicEq else Icons.Filled.Check,
                contentDescription = if (speaking) "Currently speaking" else "Selected voice",
                tint = Ink.Live,
                modifier = Modifier
                    .padding(end = if (onToggleStar != null || onDelete != null) 0.dp else 14.dp)
                    .size(18.dp),
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete voice",
                    tint = Ink.Dim,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onToggleStar != null) {
            IconButton(onClick = onToggleStar) {
                Icon(
                    if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (starred) "Remove from favourites" else "Add to favourites",
                    tint = if (starred) Ink.Live else Ink.Dim,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Mono label on the left, live-coloured value on the right, and a flat track underneath —
 * the design's slider. The Material slider still does the dragging and the accessibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardSlider(
    label: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText(label, size = 11f, tracking = 0.16f)
            MonoText(valueLabel, size = 11f, tracking = 0.16f, color = Ink.Live)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Ink.Live,
                activeTrackColor = Ink.Live,
                inactiveTrackColor = Ink.Track,
            ),
            thumb = { Box(modifier = Modifier.size(14.dp).background(Ink.Live)) },
            track = {
                ThinProgress(
                    fraction = (value - range.start) / (range.endInclusive - range.start),
                    color = Ink.Live,
                    track = Ink.Track,
                )
            },
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

private fun ReaderTheme.displayName(): String = when (this) {
    ReaderTheme.SYSTEM -> "System"
    ReaderTheme.LIGHT -> "Light"
    ReaderTheme.SEPIA -> "Sepia"
    // "Dark" undersells it — the theme is the app's own black.
    ReaderTheme.DARK -> "Black"
}

/** "German", "Portuguese" — capitalised for the picker and section headings. */
/** "Portuguese (Brazil)": the variant is the choice, so it is always shown. */
private fun Locale.displayName(): String {
    val name = displayLanguage.replaceFirstChar { it.uppercase() }.ifBlank { language }
    return if (displayCountry.isBlank()) name else "$name ($displayCountry)"
}

/** What the voice section lists, read off the main thread by the screen and handed over whole. */
data class VoiceLists(
    val voices: List<Voice> = emptyList(),
    val defaultVoiceName: String? = null,
    val languages: List<LanguageOption> = emptyList(),
)
