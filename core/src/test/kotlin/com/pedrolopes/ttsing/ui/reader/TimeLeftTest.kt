package com.pedrolopes.ttsing.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeLeftTest {

    // A book of four chapters: 40, 25, 300 and 180 pages.
    private val pages = listOf(40, 25, 300, 180)

    @Test
    fun `every minute off the chapter is one minute off the book`() {
        val secondsPerPage = 33.7
        var previous: TimeLeft.Estimate? = null
        for (page in 0 until pages[1]) {
            val now = TimeLeft.byPages(pages, chapter = 1, pageInChapter = page, secondsPerPage = secondsPerPage)
            previous?.let { before ->
                assertEquals(
                    "page $page: chapter ${before.chapterMinutes}→${now.chapterMinutes}, book ${before.bookMinutes}→${now.bookMinutes}",
                    before.chapterMinutes - now.chapterMinutes,
                    before.bookMinutes - now.bookMinutes,
                )
            }
            previous = now
        }
    }

    @Test
    fun `time left is pages left times seconds per page`() {
        val estimate = TimeLeft.byPages(pages, chapter = 2, pageInChapter = 100, secondsPerPage = 30.0)
        assertEquals(200 * 30, estimate.chapterSeconds)
        assertEquals(200 * 30 / 60 + 180 * 30 / 60, estimate.bookMinutes)
    }

    @Test
    fun `the last page of the book is under a minute and nothing after it`() {
        val estimate = TimeLeft.byPages(pages, chapter = 3, pageInChapter = 179, secondsPerPage = 30.0)
        assertEquals(30, estimate.chapterSeconds)
        assertEquals(0, estimate.bookMinutes)
    }

    @Test
    fun `a new book runs at the voice's speed and its own pace takes over`() {
        // Nothing heard yet: exactly the voice's 15 characters a second.
        assertEquals(1.0 / 15, TimeLeft.secondsPerChar(0, 0, voiceCps = 15f), 1e-9)
        // An hour of this book at 12 cps: mostly the book's own pace now.
        val hourMs = 3_600_000L
        val learned = TimeLeft.secondsPerChar(hourMs, hourMs / 1000 * 12, voiceCps = 15f)
        assertTrue("learned ${1 / learned} cps", 1 / learned in 12.0..12.4)
    }

    @Test
    fun `seconds per page follow the book's characters per page and the speech rate`() {
        val atOne = TimeLeft.secondsPerPage(0, 0, 15f, bookTotalChars = 900_000, bookTotalPages = 1_000, rate = 1f)!!
        assertEquals(60.0, atOne, 1e-6) // 900 chars a page at 15 cps
        val atOneAndHalf = TimeLeft.secondsPerPage(0, 0, 15f, 900_000, 1_000, rate = 1.5f)!!
        assertEquals(40.0, atOneAndHalf, 1e-6)
        assertNull(TimeLeft.secondsPerPage(0, 0, 15f, bookTotalChars = 0, bookTotalPages = 10, rate = 1f))
    }

    @Test
    fun `while pages are counted the characters give the same coupled figures`() {
        val spc = 1.0 / 14
        val a = TimeLeft.byCharacters(14 * 600, 14 * 36_000, spc)
        val b = TimeLeft.byCharacters(14 * 540, 14 * 36_000, spc)
        assertEquals(10, a.chapterMinutes)
        assertEquals(610, a.bookMinutes)
        assertEquals(a.chapterMinutes - b.chapterMinutes, a.bookMinutes - b.bookMinutes)
    }
}
