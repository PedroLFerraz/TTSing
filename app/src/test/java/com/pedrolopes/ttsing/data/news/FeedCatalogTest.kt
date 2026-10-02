package com.pedrolopes.ttsing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The catalogue is hand-written data; these catch the typos a list like this invites. */
class FeedCatalogTest {

    @Test
    fun `no feed is listed twice`() {
        val duplicates = FeedCatalog.feeds.groupBy { it.url.lowercase().trimEnd('/') }.filterValues { it.size > 1 }
        assertTrue("listed more than once: ${duplicates.keys}", duplicates.isEmpty())
    }

    @Test
    fun `every address is stored exactly as subscribing would store it`() {
        // Subscribing normalises the URL; if that changed it, the Discover screen could no
        // longer tell the feed is already subscribed.
        for (feed in FeedCatalog.feeds) {
            assertEquals(feed.title, feed.url, NewsRepository.normalizeUrl(feed.url))
        }
    }

    @Test
    fun `every topic has sources in every language`() {
        for (topic in Topic.entries) {
            for (language in FeedCatalog.languages) {
                val outlets = FeedCatalog.group(topic, SourceKind.OUTLET, language)
                val newsletters = FeedCatalog.group(topic, SourceKind.NEWSLETTER, language)
                assertTrue("$topic/$language has no outlets", outlets.isNotEmpty())
                assertTrue("$topic/$language has no newsletters", newsletters.isNotEmpty())
            }
        }
    }

    @Test
    fun `languages are real tags the reading voice can use`() {
        for (feed in FeedCatalog.feeds) {
            assertTrue(feed.title, feed.language in FeedCatalog.languages)
            assertTrue(feed.title, Locale.forLanguageTag(feed.language).language.isNotEmpty())
        }
    }

    @Test
    fun `finds a feed whatever scheme or trailing slash it was pasted with`() {
        val verge = FeedCatalog.feeds.first { it.title == "The Verge" }
        assertEquals(verge, FeedCatalog.find("http://www.theverge.com/rss/index.xml"))
        assertEquals(verge, FeedCatalog.find("https://theverge.com/rss/index.xml/"))
        assertNotNull(FeedCatalog.find("https://tecnoblog.net/feed"))
        assertNull(FeedCatalog.find("https://example.com/feed"))
    }

    @Test
    fun `topic ids round-trip, since they are stored on feeds`() {
        for (topic in Topic.entries) assertEquals(topic, Topic.fromId(topic.id))
        assertNull(Topic.fromId(null))
        assertNull(Topic.fromId("cooking"))
    }
}
