package com.pedrolopes.ttsing.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pedrolopes.ttsing.data.epub.TocEntry
import com.pedrolopes.ttsing.desktop.App
import com.pedrolopes.ttsing.desktop.ReaderTheme
import com.pedrolopes.ttsing.desktop.SettingsStore
import com.pedrolopes.ttsing.desktop.tts.Narration
import com.pedrolopes.ttsing.desktop.ui.Hairline
import com.pedrolopes.ttsing.desktop.ui.MonoText
import com.pedrolopes.ttsing.desktop.ui.theme.AppFonts
import com.pedrolopes.ttsing.desktop.ui.theme.Ink
import com.pedrolopes.ttsing.tts.piper.CatalogVoice
import com.pedrolopes.ttsing.tts.piper.PiperCatalog
import com.pedrolopes.ttsing.tts.piper.StartResult
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TocPanel(toc: List<TocEntry>, current: Int, palette: ReaderPalette, onPick: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (current > 3) list.scrollToItem(current - 3) }
    Column(Modifier.width(300.dp).fillMaxHeight().background(palette.background)) {
        MonoText("Contents", color = palette.secondaryText, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        if (toc.isEmpty()) {
            Text("This book has no table of contents.", color = palette.secondaryText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
        LazyColumn(state = list) {
            itemsIndexed(toc) { index, entry ->
                val selected = index == current
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(entry.spineIndex) }.padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(if (selected) palette.accent else Color.Transparent, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        entry.title,
                        color = if (selected) palette.text else palette.secondaryText,
                        fontFamily = AppFonts.Grotesk,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The page's colours and size, and the voice: which, how fast, and getting more. */
@Composable
fun SettingsPanel(app: App, model: ReaderModel, narration: Narration) {
    val settings by app.settings.state.collectAsState()
    val installed by app.downloads.installed.collectAsState()
    val progress by app.downloads.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    // Read here so the list follows a download finishing.
    val selected = installed.let { model.voice() }
    val locale = model.locale

    fun download(voice: CatalogVoice) {
        busy = when (app.downloads.start(voice)) {
            StartResult.BUSY -> "One voice downloads at a time"
            else -> null
        }
    }

    Column(
        modifier = Modifier.width(340.dp).fillMaxHeight().background(Ink.Surface).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
    ) {
        Section("Page") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    Chip(
                        label = when (theme) {
                            ReaderTheme.DARK -> "Black"
                            ReaderTheme.LIGHT -> "Light"
                            ReaderTheme.SEPIA -> "Sepia"
                        },
                        selected = settings.theme == theme,
                    ) { app.settings.update { it.copy(theme = theme) } }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip("A−", selected = false) { app.settings.update { it.copy(fontScale = SettingsStore.fontStep(it.fontScale - 0.05f)) } }
                MonoText("${(settings.fontScale * 100).roundToInt()}%", color = Ink.Text, size = 12f, modifier = Modifier.padding(horizontal = 16.dp))
                Chip("A+", selected = false) { app.settings.update { it.copy(fontScale = SettingsStore.fontStep(it.fontScale + 0.05f)) } }
            }
        }

        Section("Reading language") {
            LanguagePicker(locale) { model.selectLanguage(it) }
        }

        Section("Voice") {
            if (selected == null) {
                Text(
                    "No voice for ${locale.getDisplayLanguage(Locale.ENGLISH)} yet. Download one below; it stays on this computer.",
                    color = Ink.Live,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            PiperCatalog.forLanguage(locale).forEach { voice ->
                VoiceRow(
                    voice = voice,
                    installed = voice.id in installed,
                    selected = selected?.id == voice.id,
                    progress = progress?.takeIf { it.id == voice.id },
                    onSelect = { app.voiceFolder.voices().firstOrNull { it.id == voice.id }?.let(model::selectVoice) },
                    onDownload = { download(voice) },
                    onCancel = { app.downloads.cancel() },
                    onDelete = { scope.launch { app.downloads.delete(voice.id) } },
                )
            }
            busy?.let { Text(it, color = Ink.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            progress?.takeIf { it.failed }?.let {
                Text(it.reason ?: "The download failed", color = Color(0xFFFF6B57), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        var others by remember { mutableStateOf(false) }
        Section("Other languages") {
            if (!others) {
                TextButton(onClick = { others = true }) { MonoText("Show voices in other languages", color = Ink.Live) }
            } else {
                PiperCatalog.otherLanguages(locale).groupBy { it.locale.getDisplayLanguage(Locale.ENGLISH) }.forEach { (language, voices) ->
                    MonoText(language, color = Ink.Dim, size = 10f, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    voices.forEach { voice ->
                        VoiceRow(
                            voice = voice,
                            installed = voice.id in installed,
                            selected = false,
                            progress = progress?.takeIf { it.id == voice.id },
                            onSelect = null,
                            onDownload = { download(voice) },
                            onCancel = { app.downloads.cancel() },
                            onDelete = { scope.launch { app.downloads.delete(voice.id) } },
                        )
                    }
                }
            }
        }
        MonoText(
            "Voices: Piper (MIT), run on this computer by sherpa-onnx",
            color = Ink.Faint,
            size = 9f,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        MonoText(title, color = Ink.Dim, size = 10f, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
    Hairline(inset = 20.dp)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, if (selected) Ink.Live else Ink.Edge)
            .background(if (selected) Ink.Live else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        MonoText(label, color = if (selected) Ink.Surface else Ink.Text, size = 11f, weight = FontWeight.Medium)
    }
}

/** The languages there are voices for, plus the book's own; picking one sets the book's language. */
@Composable
private fun LanguagePicker(locale: Locale, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val languages = (PiperCatalog.voices.map { it.locale } + locale)
        .distinctBy { it.toLanguageTag() }
        .sortedBy { it.getDisplayName(Locale.ENGLISH) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().border(1.dp, Ink.Edge).clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(locale.getDisplayName(Locale.ENGLISH), color = Ink.Text, fontFamily = AppFonts.Grotesk, fontSize = 14.sp, modifier = Modifier.weight(1f))
            MonoText("Change", color = Ink.Live, size = 10f)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ink.Raised) {
            languages.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.getDisplayName(Locale.ENGLISH), color = if (option == locale) Ink.Live else Ink.Text) },
                    onClick = {
                        open = false
                        onPick(option.toLanguageTag())
                    },
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: CatalogVoice,
    installed: Boolean,
    selected: Boolean,
    progress: com.pedrolopes.ttsing.tts.piper.DownloadProgress?,
    onSelect: (() -> Unit)?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Ink.Well else Color.Transparent)
            .clickable(enabled = installed && onSelect != null) { onSelect?.invoke() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (selected) Ink.Live else if (installed) Ink.Muted else Color.Transparent, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(voice.displayName, color = if (installed) Ink.Text else Ink.Muted, fontFamily = AppFonts.Grotesk, fontSize = 14.sp)
                MonoText(
                    "${voice.locale.getDisplayCountry(Locale.ENGLISH).ifEmpty { voice.locale.getDisplayLanguage(Locale.ENGLISH) }} · neural",
                    color = Ink.Dim,
                    size = 9f,
                )
            }
            when {
                progress != null && !progress.failed -> TextButton(onClick = onCancel) { MonoText("Cancel", color = Ink.Muted, size = 10f) }
                installed -> TextButton(onClick = onDelete) { MonoText("Remove", color = Ink.Faint, size = 10f) }
                else -> TextButton(onClick = onDownload) { MonoText("Get · ${voice.megabytes} MB", color = Ink.Live, size = 10f) }
            }
        }
        if (progress != null && !progress.failed) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                color = Ink.Live,
                trackColor = Ink.Track,
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(top = 4.dp),
            )
        }
    }
}
