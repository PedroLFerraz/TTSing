package com.pedrolopes.ttsing.anki

/** What the card sheet should offer, decided before anything is sent to AnkiDroid. */
internal enum class AnkiState {
    /** Installed, and the permission is granted or can still be asked for. */
    Ready,

    NotInstalled,

    /** Asked and refused, but Android will still show the dialog again. */
    PermissionDenied,

    /** Refused with "don't ask again": only the app settings screen can grant it now. */
    PermissionBlocked,
}

/**
 * [deniedOnce] is whether our own request came back refused; [canAskAgain] is
 * `shouldShowRequestPermissionRationale`, which is false both before the first ask and after
 * a permanent denial, so it only means "blocked" once a denial has been seen.
 */
internal fun ankiState(installed: Boolean, granted: Boolean, deniedOnce: Boolean, canAskAgain: Boolean): AnkiState = when {
    !installed -> AnkiState.NotInstalled
    granted -> AnkiState.Ready
    deniedOnce && !canAskAgain -> AnkiState.PermissionBlocked
    deniedOnce -> AnkiState.PermissionDenied
    else -> AnkiState.Ready
}

/** What to do with the draft and what to tell the user once an add attempt finishes. */
internal data class CardOutcome(
    /** True on any failure, so the user's typing survives; false only when the card was added. */
    val keepDraft: Boolean,
    val message: String,
    /** True when the audio is what failed, so the sheet can offer adding the card without it. */
    val offerWithoutAudio: Boolean = false,
)

/** [result] is null when [audioFailed]: nothing was sent to AnkiDroid. */
internal fun cardOutcome(audioFailed: Boolean, result: AnkiExporter.Result?): CardOutcome = when {
    audioFailed -> CardOutcome(
        keepDraft = true,
        message = "Couldn't record the sentence audio.",
        offerWithoutAudio = true,
    )
    result is AnkiExporter.Result.Added -> CardOutcome(
        keepDraft = false,
        message = if (result.audioAttached) {
            "Card added to ${AnkiExporter.DECK_NAME}"
        } else {
            "Card added to ${AnkiExporter.DECK_NAME} (without audio)"
        },
    )
    result == AnkiExporter.Result.AnkiNotInstalled -> CardOutcome(true, "AnkiDroid isn't installed")
    result == AnkiExporter.Result.PermissionDenied -> CardOutcome(true, "AnkiDroid permission denied")
    result is AnkiExporter.Result.Failed -> CardOutcome(true, "Could not add the card: ${result.message}")
    else -> CardOutcome(true, "Could not add the card")
}
