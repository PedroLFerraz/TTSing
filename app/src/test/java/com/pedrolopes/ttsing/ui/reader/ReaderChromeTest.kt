package com.pedrolopes.ttsing.ui.reader

import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChromeTest {

    @Test
    fun `a phone on its side is compact, a phone upright and a tablet are not`() {
        assertTrue(isCompactHeight(360))
        assertTrue(isCompactHeight(411))
        assertFalse(isCompactHeight(800))
        assertFalse(isCompactHeight(480))
    }

    @Test
    fun `text is as wide as the column cap allows, less the margins`() {
        // Upright phone: the screen is narrower than the cap.
        assertEquals(1080 - 126, pageWidthPx(1080f, 1680f, 63f))
        // Landscape: the cap wins.
        assertEquals(1680 - 126, pageWidthPx(2400f, 1680f, 63f))
        assertEquals(1, pageWidthPx(10f, 1680f, 63f))
    }

    @Test
    fun `the margin beside a capped column is half of what is left over`() {
        assertEquals(360f, columnInsetPx(2400f, 1680f), 0f)
        assertEquals(0f, columnInsetPx(1080f, 1680f), 0f)
        // The padding the page draws and the width the text was laid out for agree.
        val available = 2400f
        val cap = 1680f
        val pad = 63f
        val laidOut = pageWidthPx(available, cap, pad)
        val drawn = available - 2 * (pad + columnInsetPx(available, cap))
        assertEquals(laidOut.toFloat(), drawn, 1f)
    }

    @Test
    fun `the summary banner says why the full text is missing`() {
        val download = summaryBannerMessage(ArticleEntity.ISSUE_DOWNLOAD_FAILED)
        val short = summaryBannerMessage(ArticleEntity.ISSUE_TOO_SHORT)
        assertTrue(download, "couldn't be downloaded" in download)
        assertTrue(short, "short summary (paywall?)" in short)
        assertNotEquals(download, short)
        assertNotEquals(download, summaryBannerMessage(null))
        assertNotEquals(short, summaryBannerMessage("something new"))
    }

    @Test
    fun `notifications are asked for on Android 13 and up, when missing, once`() {
        assertTrue(shouldAskNotificationPermission(sdkInt = 33, granted = false, askedThisProcess = false))
        assertTrue(shouldAskNotificationPermission(sdkInt = 36, granted = false, askedThisProcess = false))
        assertFalse(shouldAskNotificationPermission(sdkInt = 32, granted = false, askedThisProcess = false))
        assertFalse(shouldAskNotificationPermission(sdkInt = 34, granted = true, askedThisProcess = false))
        assertFalse(shouldAskNotificationPermission(sdkInt = 34, granted = false, askedThisProcess = true))
    }

    @Test
    fun `the question is put once per process however often Play is pressed`() {
        val ask = AskOnce()
        assertTrue(ask.shouldAskNow(sdkInt = 34, granted = false))
        assertFalse(ask.shouldAskNow(sdkInt = 34, granted = false))
        assertFalse(ask.shouldAskNow(sdkInt = 34, granted = false))
    }

    @Test
    fun `a granted permission or an old Android never uses up the question`() {
        val ask = AskOnce()
        assertFalse(ask.shouldAskNow(sdkInt = 30, granted = false))
        assertFalse(ask.shouldAskNow(sdkInt = 34, granted = true))
        // Revoked later in the same process: still gets its one ask.
        assertTrue(ask.shouldAskNow(sdkInt = 34, granted = false))
    }
}
