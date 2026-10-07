package com.pedrolopes.ttsing.ui.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkifyTest {

    private fun urls(text: String) = urlRanges(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `finds addresses in a description`() {
        val text = "Subscribe: https://example.com/sub and follow http://x.org/a?b=1&c=2 today"
        assertEquals(listOf("https://example.com/sub", "http://x.org/a?b=1&c=2"), urls(text))
    }

    @Test
    fun `leaves the punctuation after an address out of it`() {
        assertEquals(listOf("https://example.com/a"), urls("See https://example.com/a."))
        assertEquals(listOf("https://example.com/a"), urls("(see https://example.com/a)"))
        assertEquals(listOf("https://example.com/a"), urls("https://example.com/a, then more"))
        assertEquals(listOf("https://example.com/a?x=1"), urls("Really? https://example.com/a?x=1!"))
    }

    @Test
    fun `keeps a bracket that belongs to the address`() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            urls("https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
    }

    @Test
    fun `text without an address, or a bare scheme, has none`() {
        assertTrue(urlRanges("no links here, just example.com").isEmpty())
        assertTrue(urlRanges("https:// alone").isEmpty())
    }

    @Test
    fun `ranges point at the address in the text`() {
        val text = "a https://e.com/x b"
        val range = urlRanges(text).single()
        assertEquals(2, range.first)
        assertEquals("https://e.com/x", text.substring(range.first, range.last + 1))
    }
}
