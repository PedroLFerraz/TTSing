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
)
