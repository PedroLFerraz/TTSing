package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.tts.piper.CatalogVoice
import com.pedrolopes.ttsing.tts.piper.DownloadProgress

/** The settings sheet's small decisions, pulled out so they can be tested without a screen. */
internal object VoiceRules {

    /** What, if anything, to say about the engine having nothing for this language. */
    enum class NoVoices { NONE, DEVICE_ONLY, ALL }

    /**
     * "This engine has no voices" is only the whole story when no neural voice is on offer
     * either: with neural rows listed the device is merely one source that came up empty.
     */
    fun noVoices(deviceVoiceCount: Int, neuralVoiceCount: Int): NoVoices = when {
        deviceVoiceCount > 0 -> NoVoices.NONE
        neuralVoiceCount > 0 -> NoVoices.DEVICE_ONLY
        else -> NoVoices.ALL
    }

    /**
     * The languages whose stored choice is [voiceId], which deleting it must clear — not just
     * the book's own language: a voice picked for another language is gone just the same.
     */
    fun languagesToClear(stored: Map<String, String>, voiceId: String): List<String> =
        stored.filterValues { it == voiceId }.keys.toList()

    fun deletePrompt(voice: CatalogVoice) = "Delete ${voice.displayName}? Frees about ${voice.megabytes} MB."

    fun meteredPrompt(voice: CatalogVoice) = "About ${voice.megabytes} MB on mobile data. Download ${voice.displayName} anyway?"

    /** The second line of a neural voice's row. */
    fun neuralSubtitle(
        voice: CatalogVoice,
        installed: Boolean,
        progress: DownloadProgress?,
        region: String,
        otherLanguage: Boolean,
    ): String {
        val mine = progress?.takeIf { it.id == voice.id }
        return when {
            mine != null && !mine.failed -> "Downloading — ${mine.done} of ${voice.megabytes} MB"
            mine != null -> "${mine.reason ?: "Download failed"} — tap to retry"
            // Picking it here would read this book in the wrong language: it is offered so it
            // is ready when you open one that needs it.
            installed && otherLanguage -> "Ready for when you read in $region"
            installed -> "$region · offline · in this app · neural"
            else -> "$region · tap to download · ${voice.megabytes} MB"
        }
    }

    /** What a tap on a voice that isn't installed should do, given what is downloading. */
    enum class DownloadTap { START, ASK_METERED, IGNORE, BUSY }

    fun downloadTap(voice: CatalogVoice, progress: DownloadProgress?, metered: Boolean): DownloadTap {
        val running = progress?.takeIf { !it.failed }
        return when {
            running != null && running.id == voice.id -> DownloadTap.IGNORE
            running != null -> DownloadTap.BUSY
            metered -> DownloadTap.ASK_METERED
            else -> DownloadTap.START
        }
    }
}

/** The sleep timer's chips and the line that says how long is left. */
internal object SleepTimerUi {

    /**
     * Whether a chip is lit. The chip that started the timer stays lit while it counts down
     * ([startedMinutes] is what it was set to), rather than a guess from the minutes left,
     * which lit "15" a quarter of an hour into a 30-minute timer.
     */
    fun chipSelected(chipMinutes: Int?, chapterChip: Boolean, startedMinutes: Int?, minutesLeft: Int?): Boolean = when {
        chapterChip -> minutesLeft == CHAPTER_END
        chipMinutes == null -> minutesLeft == null
        else -> minutesLeft != null && minutesLeft != CHAPTER_END && startedMinutes == chipMinutes
    }

    fun remaining(minutesLeft: Int?): String? = when (minutesLeft) {
        null -> null
        CHAPTER_END -> "Stops at the end of the chapter"
        else -> "Stops in $minutesLeft min"
    }
}
