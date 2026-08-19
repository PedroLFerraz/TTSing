package com.pedrolopes.ttsing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure helpers around feed subscription. The networked parts need a device. */
class NewsRepositoryTest {

    @Test
    fun `adds a scheme when the user pastes a bare host`() {
        assertEquals("https://example.com/feed", NewsRepository.normalizeUrl("example.com/feed"))
        assertEquals("https://example.com/feed", NewsRepository.normalizeUrl("  example.com/feed  "))
    }

    @Test
    fun `keeps an explicit scheme`() {
        assertEquals("http://example.com/rss", NewsRepository.normalizeUrl("http://example.com/rss"))
        assertEquals("https://example.com/rss", NewsRepository.normalizeUrl("https://example.com/rss"))
    }

    @Test
    fun `rewrites the feed scheme that readers hand out`() {
        assertEquals("https://example.com/rss", NewsRepository.normalizeUrl("feed://example.com/rss"))
    }

    @Test
    fun `rejects input that is not an address`() {
        assertNull(NewsRepository.normalizeUrl(""))
        assertNull(NewsRepository.normalizeUrl("   "))
        assertNull(NewsRepository.normalizeUrl("just some words"))
        // No dot means no host worth trying.
        assertNull(NewsRepository.normalizeUrl("localhostfeed"))
    }

    @Test
    fun `article ids are stable across refreshes`() {
        val first = NewsRepository.articleId("https://example.com/feed", "guid-1")
        val second = NewsRepository.articleId("https://example.com/feed", "guid-1")
        assertEquals("a story must keep its id so the reading position survives", first, second)
    }

    @Test
    fun `article ids differ per story and per feed`() {
        val a = NewsRepository.articleId("https://example.com/feed", "guid-1")
        val b = NewsRepository.articleId("https://example.com/feed", "guid-2")
        val c = NewsRepository.articleId("https://other.com/feed", "guid-1")
        assertNotEquals(a, b)
        assertNotEquals("same guid from another feed must not collide", a, c)
    }

    @Test
    fun `article ids are recognisable so the reader can tell them from books`() {
        val id = NewsRepository.articleId("https://example.com/feed", "guid-1")
        assertTrue(NewsRepository.isArticle(id))
        // Book ids are bare sha1 hex from BookRepository.
        assertFalse(NewsRepository.isArticle("da39a3ee5e6b4b0d3255bfef95601890afd80709"))
    }

    @Test
    fun `feed summaries are reduced to plain text`() {
        val html = "<p>A teaser with <b>bold</b> text and <a href='#'>a link</a>.</p>"
        assertEquals("A teaser with bold text and a link.", NewsRepository.stripHtml(html))
    }

    @Test
    fun `stripping html decodes entities`() {
        assertEquals("Tom & Jerry \"quoted\"", NewsRepository.stripHtml("Tom &amp; Jerry &quot;quoted&quot;"))
    }
}
