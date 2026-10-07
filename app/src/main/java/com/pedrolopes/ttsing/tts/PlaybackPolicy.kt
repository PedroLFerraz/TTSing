package com.pedrolopes.ttsing.tts

import com.pedrolopes.ttsing.data.epub.ReadingPosition

/** The service's small decisions, kept free of Android so they can be tested. */
internal object PlaybackPolicy {

    /**
     * Where a Play starts. A position asked for wins. Otherwise a book that has finished starts
     * over — its parked position is the last sentence, and playing that would speak one line
     * and stop again — and anything else carries on from where the narrator is, or the saved place.
     */
    fun startPosition(
        requested: ReadingPosition?,
        finished: Boolean,
        current: ReadingPosition?,
        restored: ReadingPosition,
    ): ReadingPosition = requested ?: if (finished) ReadingPosition.START else current ?: restored

    /**
     * What to save when playback ends. A finished article is saved at its start, so opening it
     * again reads it from the top rather than from its last sentence.
     */
    fun positionToSave(isArticle: Boolean, finishing: Boolean, position: ReadingPosition): ReadingPosition =
        if (isArticle && finishing) ReadingPosition.START else position

    /** The notification is built as playing while playback is starting, not only once audio runs. */
    fun notificationShowsPlaying(isSpeaking: Boolean, starting: Boolean): Boolean = isSpeaking || starting

    /** What a Play from outside the app (headset key, notification) has to play. */
    sealed interface ResumeTarget {
        data object Loaded : ResumeTarget
        data class Recent(val bookId: String) : ResumeTarget
        data object NothingToPlay : ResumeTarget
    }

    fun resumeTarget(loadedBookId: String?, mostRecentBookId: String?): ResumeTarget = when {
        loadedBookId != null -> ResumeTarget.Loaded
        mostRecentBookId != null -> ResumeTarget.Recent(mostRecentBookId)
        else -> ResumeTarget.NothingToPlay
    }

    /**
     * The sentence a skip from [current] lands on: the next one (null at the end of the book,
     * where there is nothing to do), or the previous one, which at the start is the current
     * sentence again.
     */
    suspend fun skipTarget(source: Narrator.ContentSource, current: SentenceRef, forward: Boolean): SentenceRef? =
        if (forward) source.next(current.position) else source.prev(current.position) ?: current
}
