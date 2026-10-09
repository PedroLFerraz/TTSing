package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.data.news.db.ArticleEntity

// Decisions behind the reader's chrome (header, footer, banners, permission), kept out of the
// composables so they can be tested without a device.

/** A window shorter than this (a phone on its side) starts with the compact chrome. */
private const val COMPACT_HEIGHT_DP = 480

internal fun isCompactHeight(heightDp: Int): Boolean = heightDp < COMPACT_HEIGHT_DP

/**
 * The width text is laid out in: the page area capped at [columnCapPx] (a line of text across a
 * tablet or a landscape phone is too long to follow), less the margin on both sides.
 */
internal fun pageWidthPx(availableWidthPx: Float, columnCapPx: Float, paddingPx: Float): Int =
    (minOf(availableWidthPx, columnCapPx) - paddingPx * 2).toInt().coerceAtLeast(1)

/** The margin each side of a page [availableWidthPx] wide whose text column is capped at [columnCapPx]. */
internal fun columnInsetPx(availableWidthPx: Float, columnCapPx: Float): Float =
    ((availableWidthPx - columnCapPx) / 2f).coerceAtLeast(0f)

/** What the "summary only" banner says, by why the full text isn't there. */
internal fun summaryBannerMessage(issue: String?): String = when (issue) {
    ArticleEntity.ISSUE_DOWNLOAD_FAILED -> "The page couldn't be downloaded — this is the feed's own summary."
    ArticleEntity.ISSUE_TOO_SHORT -> "The site only gave a short summary (paywall?) — this is all there is to read."
    else -> "The full article isn't available — this is the feed's own summary."
}

/**
 * Whether Play should now ask for the notification permission (Android 13+): once per app
 * process, and only while it hasn't been granted. Playback never waits for the answer.
 */
internal fun shouldAskNotificationPermission(sdkInt: Int, granted: Boolean, askedThisProcess: Boolean): Boolean =
    sdkInt >= 33 && !granted && !askedThisProcess

/** Remembers that the question was put. */
internal class AskOnce {
    private var asked = false

    /** True the first time it is called with the permission missing; false ever after. */
    fun shouldAskNow(sdkInt: Int, granted: Boolean): Boolean {
        val ask = shouldAskNotificationPermission(sdkInt, granted, asked)
        if (ask) asked = true
        return ask
    }
}

/** The one the reader uses: it lives as long as the process, so the question is put once per launch. */
internal val notificationAsk = AskOnce()
