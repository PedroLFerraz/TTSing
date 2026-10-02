package com.pedrolopes.ttsing.ui.reader

import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.pedrolopes.ttsing.tts.needsDownload
import com.pedrolopes.ttsing.tts.piper.CatalogVoice
import com.pedrolopes.ttsing.tts.piper.DownloadProgress
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderSettingsSheet(
    settings: AppSettings,
    voices: List<Voice>,
    currentVoiceName: String?,
    defaultVoiceName: String?,
    /** The language the book is actually read in — the override if set, else `dc:language`. */
    activeLocale: Locale,
    /** What the EPUB itself declares, shown so a wrong declaration is visible. */
    declaredLanguageTag: String?,
    availableLanguages: List<LanguageOption>,
    /** The app's own neural voices for this language, installed or not. */
    neuralVoices: List<CatalogVoice>,
    /** Neural voices for the languages this book is not in, to fetch before they're needed. */
    otherNeuralVoices: List<CatalogVoice>,
    installedNeuralIds: Set<String>,
    downloading: DownloadProgress?,
    onDownloadVoice: (CatalogVoice) -> Unit,
    onDeleteVoice: (CatalogVoice) -> Unit,
    /** Minutes left on the sleep timer, or null; -1 means "until the chapter ends". */
    sleepMinutesLeft: Int?,
    onSleepTimer: (minutes: Int?, atChapterEnd: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSpeechRate: (Float) -> Unit,
    onFontScale: (Float) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    /** How this PDF is shown, or null when the book isn't a PDF. */
    pdfView: PdfView?,
    onPdfView: (PdfView) -> Unit,
    onSelectLanguage: (Locale) -> Unit,
    onInstallVoiceData: () -> Unit,
    onSelectDefaultVoice: () -> Unit,
    onSelectVoice: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val storedVoice = settings.voiceFor(activeLocale.language)
    val languageName = activeLocale.displayName()
    var languageMenuOpen by remember { mutableStateOf(false) }
    val declaredLocale = declaredLanguageTag
        ?.let { Locale.forLanguageTag(it) }
        ?.takeIf { it.language.isNotEmpty() }
    val isOverridden = declaredLocale != null && declaredLocale.language != activeLocale.language

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
                    MonoText(formatSpeed(settings.speechRate), size = 11f, tracking = 0.16f, color = Ink.Live)
                }
                SpeedSteps.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { step ->
                            ChoiceChip(
                                label = formatSpeed(step),
                                selected = kotlin.math.abs(settings.speechRate - step) < 0.01f,
                                onClick = { onSpeechRate(step) },
                            )
                        }
                    }
                }
            }
            if (pdfView != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MonoText("Show PDF as", size = 11f, tracking = 0.16f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeChip("Pages", selected = pdfView == PdfView.PAGES, onClick = { onPdfView(PdfView.PAGES) })
                        ThemeChip("Text", selected = pdfView == PdfView.TEXT, onClick = { onPdfView(PdfView.TEXT) })
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        declaredLocale != null -> "From the book's own metadata. Change it if it's wrong."
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
                        isSpeaking = false,
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
                            val current = option.locale.language == activeLocale.language
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

            MonoText("Sleep timer", size = 11f, tracking = 0.16f)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    val selected = when {
                        chapterEnd -> sleepMinutesLeft == CHAPTER_END
                        minutes == null -> sleepMinutesLeft == null
                        // The running timer counts down, so the chip that set it stays lit.
                        else -> sleepMinutesLeft != null && sleepMinutesLeft != CHAPTER_END &&
                            sleepMinutesLeft <= minutes && sleepMinutesLeft > minutes - 15
                    }
                    ChoiceChip(label = label, selected = selected) { onSleepTimer(minutes, chapterEnd) }
                }
            }

            Hairline(inset = 0.dp)

            val activeLabel = voices.firstOrNull { it.name == currentVoiceName }?.let { voiceTitle(it) }
                ?: currentVoiceName
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                MonoText("Voice — $languageName", size = 11f, tracking = 0.16f)
                if (activeLabel != null) MonoText("Now speaking", size = 10f, tracking = 0.12f, color = Ink.Live)
            }

            if (neuralVoices.isNotEmpty()) {
                MonoText(
                    "In this app · neural",
                    size = 10f,
                    tracking = 0.14f,
                    color = Ink.Dim,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                neuralVoices.forEach { voice ->
                    val installed = voice.id in installedNeuralIds
                    val busy = downloading?.takeIf { it.id == voice.id && !it.failed }
                    ChoiceRow(
                        title = voice.displayName,
                        subtitle = when {
                            busy != null -> "Downloading — ${busy.done} of ${voice.megabytes} MB"
                            downloading?.id == voice.id && downloading.failed -> "Download failed — tap to retry"
                            installed -> "Offline · in this app"
                            else -> "Tap to download · ${voice.megabytes} MB"
                        },
                        selected = storedVoice == voice.id,
                        isSpeaking = voice.id == currentVoiceName,
                        onClick = {
                            if (installed) onSelectVoice(voice.id) else onDownloadVoice(voice)
                        },
                        // A downloaded voice is 40 MB; hold it to get the space back.
                        onLongClick = { onDeleteVoice(voice) }.takeIf { installed && !voice.bundled },
                    )
                    if (busy != null) {
                        ThinProgress(
                            fraction = busy.fraction,
                            modifier = Modifier.padding(start = 3.dp, top = 2.dp, bottom = 6.dp),
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
                    color = Ink.Dim,
                    modifier = Modifier
                        .clickable { showOthers = !showOthers }
                        .padding(top = 14.dp, bottom = 4.dp),
                )
                if (showOthers) {
                    otherNeuralVoices.forEach { voice ->
                        val installed = voice.id in installedNeuralIds
                        val busy = downloading?.takeIf { it.id == voice.id && !it.failed }
                        ChoiceRow(
                            title = "${voice.displayName} · ${voice.locale.displayName()}",
                            subtitle = when {
                                busy != null -> "Downloading — ${busy.done} of ${voice.megabytes} MB"
                                downloading?.id == voice.id && downloading.failed ->
                                    "Download failed — tap to retry"
                                // Picking it here would read this book in the wrong language:
                                // it is offered so it is ready when you open one that needs it.
                                installed -> "Ready for when you read in ${voice.locale.displayName()}"
                                else -> "Tap to download · ${voice.megabytes} MB"
                            },
                            selected = false,
                            isSpeaking = false,
                            onClick = { if (!installed) onDownloadVoice(voice) },
                            onLongClick = { onDeleteVoice(voice) }.takeIf { installed && !voice.bundled },
                        )
                        if (busy != null) {
                            ThinProgress(
                                fraction = busy.fraction,
                                modifier = Modifier.padding(start = 3.dp, top = 2.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
            }

            Column {
                ChoiceRow(
                    title = "Device default",
                    subtitle = defaultVoiceName
                        ?.let { name -> voices.firstOrNull { it.name == name }?.let { voiceTitle(it) } ?: name }
                        ?: "The engine's built-in voice for this language",
                    selected = storedVoice == null,
                    isSpeaking = storedVoice == null && currentVoiceName != null,
                    onClick = onSelectDefaultVoice,
                )

                if (voices.isEmpty()) {
                    Text(
                        "This engine has no voices for $languageName. Install voice data in Android's " +
                            "Text-to-speech settings, or pick another language above.",
                        fontFamily = AppFonts.Grotesk,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Ink.Muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    voices
                        .filterNot { it.name in installedNeuralIds }
                        .groupBy { regionName(it.locale) }
                        .forEach { (region, list) ->
                        MonoText(
                            region,
                            size = 10f,
                            tracking = 0.14f,
                            color = Ink.Dim,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                        list.forEach { voice ->
                            ChoiceRow(
                                title = voiceTitle(voice),
                                subtitle = voice.name,
                                selected = storedVoice == voice.name,
                                isSpeaking = voice.name == currentVoiceName,
                                onClick = { onSelectVoice(voice.name) },
                            )
                        }
                    }
                }
            }
        }
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
    isSpeaking: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (selected) Ink.Well else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
            )
        }
        if (isSpeaking) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = "Currently speaking",
                tint = Ink.Live,
                modifier = Modifier.padding(end = 14.dp).size(18.dp),
            )
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
private fun Locale.displayName(): String =
    displayLanguage.replaceFirstChar { it.uppercase() }.ifBlank { language }

private fun regionName(locale: Locale): String {
    val country = locale.country
    if (country.isBlank()) return "Other"
    val name = locale.displayCountry.ifBlank { country }
    return "$name (${locale.language}-$country)"
}

/** A friendly, human-comparable label for a voice (quality, gender hint, online flag). */
private fun voiceTitle(voice: Voice): String {
    val name = voice.name.lowercase()
    val quality = when {
        voice.quality >= Voice.QUALITY_VERY_HIGH -> "Very high quality"
        voice.quality >= Voice.QUALITY_HIGH -> "High quality"
        voice.quality <= Voice.QUALITY_LOW -> "Low quality"
        else -> "Standard quality"
    }
    val gender = when {
        name.contains("female") -> "Female"
        name.contains("male") -> "Male"
        else -> null
    }
    val online = if (voice.isNetworkConnectionRequired) "Online" else "Offline"
    val download = if (voice.needsDownload()) "Downloads on first use" else null
    return listOfNotNull(gender, quality, online, download).joinToString(" · ")
}
