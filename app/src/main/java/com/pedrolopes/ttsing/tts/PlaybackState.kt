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
    /**
     * This book's listening totals so far — time (at rate 1.0) and characters over every
     * sentence the voice has read in it — including what has not been saved yet. Zero for
     * articles. See [com.pedrolopes.ttsing.ui.reader.TimeLeft].
     */
    val bookListenedMs: Long = 0,
    val bookListenedChars: Long = 0,
    /**
     * When the sleep timer will stop the reading, as an
     * [android.os.SystemClock.elapsedRealtime] stamp, or null when none is set. A timer set
     * to the end of the chapter has no stamp: see [sleepAtChapterEnd].
     */
    val sleepAtElapsedMs: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    /**
     * Set when the service played on from one news story into the next by itself: the id of
     * the story that finished. Lets a reader screen still showing that story follow along.
     */
    val continuedFrom: String? = null,
)
