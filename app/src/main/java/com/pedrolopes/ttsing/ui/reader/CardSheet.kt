package com.pedrolopes.ttsing.ui.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ichi2.anki.api.AddContentApi
import com.pedrolopes.ttsing.anki.AnkiState
import com.pedrolopes.ttsing.anki.CardDraft
import com.pedrolopes.ttsing.anki.ankiState
import com.pedrolopes.ttsing.data.epub.WordSplitter
import kotlinx.coroutines.launch
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
    /** Why the last add failed, shown in the sheet itself: a snackbar can sit behind the modal. */
    error: String?,
    /** The failure was the audio: offer saving the card without it. */
    offerWithoutAudio: Boolean,
    isAnkiInstalled: () -> Boolean,
    onDraftChange: (CardDraft) -> Unit,
    onPreviewAudio: (CardDraft) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (CardDraft) -> Unit,
    onSubmitWithoutAudio: (CardDraft) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val meaningFocus = remember { FocusRequester() }
    val words = remember(draft.sentence, locale) { WordSplitter.split(draft.sentence, locale) }

    val scope = rememberCoroutineScope()
    var confirmDiscard by remember { mutableStateOf(false) }

    // Installed and permitted are re-read on resume: the user may come back from the Play
    // Store or the app settings with either fixed.
    var installed by remember { mutableStateOf(isAnkiInstalled()) }
    var granted by remember { mutableStateOf(hasAnkiPermission(context)) }
    var deniedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        installed = isAnkiInstalled()
        granted = hasAnkiPermission(context)
        onPauseOrDispose { }
    }
    val ankiState = ankiState(
        installed = installed,
        granted = granted,
        deniedOnce = deniedOnce,
        canAskAgain = context.findActivity()
            ?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, AddContentApi.READ_WRITE_PERMISSION) }
            ?: true,
    )

    // The draft changes as the user types, so read the latest one when the permission
    // dialog returns rather than capturing the value this launcher was created with.
    val latestDraft by rememberUpdatedState(draft)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        if (result) onSubmit(latestDraft) else deniedOnce = true
    }

    fun submit() {
        if (ankiState != AnkiState.Ready && ankiState != AnkiState.PermissionDenied) return
        if (hasAnkiPermission(context)) onSubmit(draft) else permissionLauncher.launch(AddContentApi.READ_WRITE_PERMISSION)
    }

    fun requestDismiss() {
        if (draft.hasUnsavedWork) confirmDiscard = true else onDismiss()
    }

    // Focus the meaning field only once a word is picked — focusing on open would raise the
    // keyboard over the very word chips the user has to tap first.
    LaunchedEffect(draft.hasTarget) {
        if (draft.hasTarget) runCatching { meaningFocus.requestFocus() }
    }

    ModalBottomSheet(onDismissRequest = ::requestDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("New flashcard", style = MaterialTheme.typography.titleLarge)
            Text(
                text = draft.selectionHint,
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
                label = { Text("Back — what it means") },
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

            when (ankiState) {
                AnkiState.NotInstalled -> AnkiProblem(
                    message = "AnkiDroid isn't installed",
                    action = "Get AnkiDroid",
                    onAction = { openAnkiPlayStore(context) },
                )
                AnkiState.PermissionBlocked -> AnkiProblem(
                    message = "TTSing needs permission to write to your AnkiDroid collection. " +
                        "Allow it in the app settings.",
                    action = "Open app settings",
                    onAction = { openAppSettings(context) },
                )
                AnkiState.PermissionDenied -> AnkiProblem(
                    message = "TTSing needs permission to write to your AnkiDroid collection.",
                )
                AnkiState.Ready -> Unit
            }

            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (offerWithoutAudio) {
                    OutlinedButton(
                        onClick = { onSubmitWithoutAudio(draft) },
                        enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text("Add without audio")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { submit() },
                enabled = draft.hasTarget && !isSubmitting &&
                    (ankiState == AnkiState.Ready || ankiState == AnkiState.PermissionDenied),
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

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this card?") },
            text = { Text("What you typed hasn't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDismiss()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    // A swipe or scrim tap may already have hidden the sheet.
                    scope.launch { sheetState.show() }
                }) { Text("Keep editing") }
            },
        )
    }
}

/** A line saying what stops the card reaching AnkiDroid, with the button that fixes it. */
@Composable
private fun AnkiProblem(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (action != null) {
            OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(action)
            }
        }
    }
}

private fun hasAnkiPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, AddContentApi.READ_WRITE_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The Play Store app if there is one, otherwise the web page. */
private fun openAnkiPlayStore(context: Context) {
    val id = "com.ichi2.anki"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")),
            )
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
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
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 12.dp),
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
            )
        }
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
