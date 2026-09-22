package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.ReadingPosition

data class PlaybackState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val author: String? = null,
    /** True when a book is loaded in the service. */
    val isActive: Boolean = false,
    val isSpeaking: Boolean = false,
    val position: ReadingPosition = ReadingPosition.START,
    /** Currently spoken sentence, as offsets into its block's text. */
    val sentenceRange: IntRange? = null,
    /** Currently spoken word, as offsets into its block's text. */
    val wordRange: IntRange? = null,
    val languageAvailable: Boolean = true,
    val error: String? = null,
    /**
     * The service's live speaking speed (chars/second at rate 1.0) for the voice in use, or
     * null before a book is open. Read this rather than the persisted setting: the setting is
     * written behind and would make the estimate step.
     */
    val charsPerSecond: Float? = null,
)
