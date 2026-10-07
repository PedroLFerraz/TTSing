package com.pedrolopes.ttsing.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageTest {

    @Test
    fun `the same path in two books is two images`() {
        // Calibre names every EPUB's first picture the same.
        val a = bookImageKey("books/one.epub", "images/00001.jpeg")
        val b = bookImageKey("books/two.epub", "images/00001.jpeg")
        assertNotEquals(a, b)
        assertEquals(a, bookImageKey("books/one.epub", "images/00001.jpeg"))
        assertNotEquals(a, bookImageKey("books/one.epub", "images/00002.jpeg"))
    }

    @Test
    fun `the cache key carries the size asked for`() {
        assertNotEquals(cacheKeyFor("k", 100, 100), cacheKeyFor("k", 200, 200))
        assertEquals(cacheKeyFor("k", 100, 100), cacheKeyFor("k", 100, 100))
    }

    @Test
    fun `a picture is shrunk by powers of two while it stays at least as big as its space`() {
        assertEquals(1, sampleSizeFor(1000, 800, 1000, 800))
        assertEquals(1, sampleSizeFor(1000, 800, 600, 500)) // half would be 500x400: too small
        assertEquals(2, sampleSizeFor(1000, 800, 500, 400))
        assertEquals(4, sampleSizeFor(4000, 3000, 1000, 700))
        assertEquals(8, sampleSizeFor(4000, 3000, 500, 375))
    }

    @Test
    fun `the tighter side decides`() {
        // 4000 wide could go to 1/8, but the 3000 height must stay >= 1500: 1/2.
        assertEquals(2, sampleSizeFor(4000, 3000, 500, 1500))
    }

    @Test
    fun `a picture smaller than its space, or a space of unknown size, is left alone`() {
        assertEquals(1, sampleSizeFor(100, 100, 1000, 1000))
        assertEquals(1, sampleSizeFor(4000, 3000, 0, 0))
        assertEquals(1, sampleSizeFor(4000, 3000, 500, 0))
        assertEquals(1, sampleSizeFor(0, 0, 500, 500))
    }

    @Test
    fun `the sample size is a power of two and never leaves the picture smaller than asked`() {
        for (source in listOf(333 to 777, 1920 to 1080, 5000 to 5000, 64 to 4096)) {
            for (target in listOf(50 to 50, 300 to 200, 1000 to 1000)) {
                val sample = sampleSizeFor(source.first, source.second, target.first, target.second)
                assertTrue(sample >= 1 && (sample and (sample - 1)) == 0)
                if (sample > 1) {
                    assertTrue(source.first / sample >= target.first && source.second / sample >= target.second)
                }
            }
        }
    }

    @Test
    fun `the cache holds an eighth of the heap`() {
        assertEquals(32 * 1024 * 1024, imageCacheBytes(256L * 1024 * 1024))
        assertEquals(Int.MAX_VALUE, imageCacheBytes(Long.MAX_VALUE))
        assertEquals(1, imageCacheBytes(0))
    }
}
