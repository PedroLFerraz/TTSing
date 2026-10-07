package com.pedrolopes.ttsing.ui.reader

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ichi2.anki.api.AddContentApi
import com.pedrolopes.ttsing.anki.CardDraft
import com.pedrolopes.ttsing.data.epub.WordSplitter
import java.util.Locale

/**
 * The "make a flashcard" sheet: pick the word you didn't know out of the sentence, say
 * what it means, and send it to AnkiDroid with the sentence read aloud.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CardSheet(
    draft: CardDraft,
    locale: Locale,
    isSubmitting: Boolean,
    onDraftChange: (CardDraft) -> Unit,
    onPreviewAudio: (CardDraft) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (CardDraft) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val meaningFocus = remember { FocusRequester() }
    val words = remember(draft.sentence, locale) { WordSplitter.split(draft.sentence, locale) }

    var permissionDenied by remember { mutableStateOf(false) }
    // The draft changes as the user types, so read the latest one when the permission
    // dialog returns rather than capturing the value this launcher was created with.
    val latestDraft by rememberUpdatedState(draft)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onSubmit(latestDraft) else permissionDenied = true
    }

    fun isAnkiInstalled(): Boolean {
        val pm = context.packageManager
        return listOf("com.ichi2.anki", "com.ichi2.anki.plus").any { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() != null
        }
    }

    fun openPlayStore(packageName: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                }
            )
        }
    }

    fun submit() {
        permissionDenied = false
        // Check AnkiDroid is installed BEFORE requesting its permission; the permission won't exist if the app is missing.
        if (!isAnkiInstalled()) {
            openPlayStore("com.ichi2.anki")
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, AddContentApi.READ_WRITE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onSubmit(draft) else permissionLauncher.launch(AddContentApi.READ_WRITE_PERMISSION)
    }

    // Focus the meaning field only once a word is picked — focusing on open would raise the
    // keyboard over the very word chips the user has to tap first.
    LaunchedEffect(draft.hasTarget) {
        if (draft.hasTarget) runCatching { meaningFocus.requestFocus() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("New flashcard", style = MaterialTheme.typography.titleLarge)
            Text(
                text = if (draft.hasTarget) {
                    "Tap another word to cover an expression, or the same one to clear it."
                } else {
                    "Tap the word you didn't know."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                words.forEach { span ->
                    val selected = draft.hasTarget &&
                        span.start >= draft.targetStart!! && span.end <= draft.targetEnd!!
                    WordChip(
                        text = draft.sentence.substring(span.start, span.end),
                        selected = selected,
                        onClick = { onDraftChange(draft.withWordAt(span.start, span.end)) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = draft.meaning,
                onValueChange = { onDraftChange(draft.copy(meaning = it)) },
                label = { Text("Back - what it means") },
                singleLine = false,
                minLines = 2,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(meaningFocus),
            )

            Spacer(Modifier.height(20.dp))

            CardPreview(
                draft = draft,
                isSubmitting = isSubmitting,
                onPlay = { onPreviewAudio(draft) },
            )

            if (permissionDenied) {
                Text(
                    "TTSing needs permission to write to your AnkiDroid collection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { submit() },
                enabled = draft.hasTarget && !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Adding…")
                } else {
                    Text("Add to Anki")
                }
            }
        }
    }
}

@Composable
private fun WordChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                this.selected = selected
            },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            // Padding ensures at least 48dp touch target in both dimensions
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }
}

/** Shows the card front exactly as Anki will render it: sentence, bold word, audio. */
@Composable
private fun CardPreview(draft: CardDraft, isSubmitting: Boolean, onPlay: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Front",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = boldedSentence(draft),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onPlay, enabled = !isSubmitting) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Hear the sentence")
            }
        }
    }
}

private fun boldedSentence(draft: CardDraft): AnnotatedString = buildAnnotatedString {
    append(draft.sentence)
    if (draft.hasTarget) {
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), draft.targetStart!!, draft.targetEnd!!)
    }
}
