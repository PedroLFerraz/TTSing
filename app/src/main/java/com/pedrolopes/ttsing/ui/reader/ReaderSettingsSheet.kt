package com.pedrolopes.ttsing.ui.reader

import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.pedrolopes.ttsing.data.settings.AppSettings
import com.pedrolopes.ttsing.data.settings.ReaderTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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
    availableLanguages: List<Locale>,
    onDismiss: () -> Unit,
    onSpeechRate: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onFontScale: (Float) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onSelectLanguage: (Locale) -> Unit,
    onSelectDefaultVoice: () -> Unit,
    onSelectVoice: (Voice) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val storedVoice = settings.voiceFor(activeLocale.language)
    val languageName = activeLocale.displayName()
    var languageMenuOpen by remember { mutableStateOf(false) }
    val declaredLocale = declaredLanguageTag
        ?.let { Locale.forLanguageTag(it) }
        ?.takeIf { it.language.isNotEmpty() }
    val isOverridden = declaredLocale != null && declaredLocale.language != activeLocale.language

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Reading settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            LabeledSlider("Speed", settings.speechRate, "%.1f×".format(settings.speechRate), 0.5f..2.5f, onSpeechRate)
            LabeledSlider("Pitch", settings.pitch, "%.1f".format(settings.pitch), 0.5f..2.0f, onPitch)
            LabeledSlider("Font size", settings.fontScale, "%.0f%%".format(settings.fontScale * 100), 0.8f..1.8f, onFontScale)

            Text("Theme", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = settings.readerTheme == theme,
                        onClick = { onTheme(theme) },
                        label = { Text(theme.displayName()) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text("Language", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = when {
                    isOverridden -> "Set by you. This book declares ${declaredLocale!!.displayName()}."
                    declaredLocale != null -> "From the book's own metadata. Change it if it's wrong."
                    else -> "This book doesn't say what language it's in."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box {
                VoiceRow(
                    title = languageName,
                    subtitle = "Tap to change the reading language",
                    selected = true,
                    isSpeaking = false,
                    onClick = { languageMenuOpen = true },
                )
                DropdownMenu(
                    expanded = languageMenuOpen,
                    onDismissRequest = { languageMenuOpen = false },
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    if (availableLanguages.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No languages available yet") },
                            onClick = { languageMenuOpen = false },
                        )
                    }
                    availableLanguages.forEach { locale ->
                        DropdownMenuItem(
                            text = { Text(locale.displayName()) },
                            leadingIcon = {
                                if (locale.language == activeLocale.language) {
                                    Icon(Icons.Filled.Check, contentDescription = "Current language")
                                }
                            },
                            onClick = {
                                languageMenuOpen = false
                                onSelectLanguage(locale)
                            },
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text("Voice — $languageName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val activeLabel = voices.firstOrNull { it.name == currentVoiceName }?.let { voiceTitle(it) }
                ?: currentVoiceName
            if (activeLabel != null) {
                Text(
                    "Now speaking: $activeLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            VoiceRow(
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
                    "No installed voices found for this language. Install voice data in Android's " +
                        "Text-to-speech settings to add more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                voices.groupBy { regionName(it.locale) }.forEach { (region, list) ->
                    Text(
                        region,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    list.forEach { voice ->
                        VoiceRow(
                            title = voiceTitle(voice),
                            subtitle = voice.name,
                            selected = storedVoice == voice.name,
                            isSpeaking = voice.name == currentVoiceName,
                            onClick = { onSelectVoice(voice) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    isSpeaking: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (isSpeaking) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = "Currently speaking",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

private fun ReaderTheme.displayName(): String = when (this) {
    ReaderTheme.SYSTEM -> "System"
    ReaderTheme.LIGHT -> "Light"
    ReaderTheme.SEPIA -> "Sepia"
    ReaderTheme.DARK -> "Dark"
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
    return listOfNotNull(gender, quality, online).joinToString(" · ")
}
