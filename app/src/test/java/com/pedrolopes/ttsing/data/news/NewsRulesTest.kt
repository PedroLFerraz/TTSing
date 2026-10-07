package com.pedrolopes.ttsing.data.news

import com.pedrolopes.ttsing.data.news.db.ArticleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions in [NewsRepository] that don't need a database or a network: identity, groups, read state. */
class NewsRulesTest {

    // ---- feed identity ----

    @Test
    fun `one feed is one feed whatever scheme, www, host case, trailing slash or fragment`() {
        val same = listOf(
            "https://example.com/feed",
            "http://example.com/feed",
            "https://www.example.com/feed",
            "HTTPS://WWW.Example.COM/feed/",
            "  example.com/feed  ".let { NewsRepository.normalizeUrl(it)!! },
            "feed://example.com/feed",
            "https://example.com/feed#top",
        )
        val keys = same.map(NewsRepository::feedKey).toSet()
        assertEquals(keys.toString(), 1, keys.size)
    }

    @Test
    fun `different feeds keep different identities`() {
        val a = NewsRepository.feedKey("https://example.com/feed")
        assertNotEquals(a, NewsRepository.feedKey("https://example.com/rss"))
        assertNotEquals(a, NewsRepository.feedKey("https://blog.example.com/feed"))
        assertNotEquals(a, NewsRepository.feedKey("https://example.org/feed"))
        // The query string can be the whole feed (?format=rss vs ?format=atom).
        assertNotEquals(
            NewsRepository.feedKey("https://example.com/?feed=rss2"),
            NewsRepository.feedKey("https://example.com/?feed=atom"),
        )
        // Paths are case-sensitive on many servers.
        assertNotEquals(NewsRepository.feedKey("https://example.com/Feed"), NewsRepository.feedKey("https://example.com/feed"))
    }

    @Test
    fun `the catalogue and subscribing agree on identity`() {
        // Subscribing in Discover and then typing the address with www and a trailing slash must
        // be recognised as the same feed.
        val verge = FeedCatalog.feeds.first { it.title == "The Verge" }
        val typed = NewsRepository.normalizeUrl("www.theverge.com/rss/index.xml/")!!
        assertEquals(NewsRepository.feedKey(verge.url), NewsRepository.feedKey(typed))
        assertEquals(verge, FeedCatalog.find(typed))
    }

    // ---- groups ----

    @Test
    fun `a group name reuses the spelling of an existing group, ignoring case`() {
        val existing = listOf("Science", "Tech")
        assertEquals("Science", NewsRepository.canonicalGroup("science", existing))
        assertEquals("Science", NewsRepository.canonicalGroup("  SCIENCE ", existing))
        assertEquals("Tech", NewsRepository.canonicalGroup("Tech", existing))
    }

    @Test
    fun `a new group name is kept as typed, trimmed`() {
        assertEquals("Cooking", NewsRepository.canonicalGroup(" Cooking ", listOf("Science")))
        assertEquals("cooking", NewsRepository.canonicalGroup("cooking", emptyList()))
    }

    @Test
    fun `an exact spelling wins when old data holds two`() {
        assertEquals("science", NewsRepository.canonicalGroup("science", listOf("Science", "science")))
    }

    // ---- read state ----

    @Test
    fun `pausing early does not mark a story read`() {
        assertFalse(NewsRepository.isReadAt(wasRead = false, blockIndex = 1, blockCount = 10))
        assertFalse(NewsRepository.isReadAt(false, 6, 10))
    }

    @Test
    fun `a story is read once the listener is 80 percent of the way through`() {
        // Block index 7 of 10 is the eighth block: 8/10.
        assertTrue(NewsRepository.isReadAt(false, 7, 10))
        assertTrue(NewsRepository.isReadAt(false, 9, 10))
        assertFalse(NewsRepository.isReadAt(false, 6, 10))
        // The last block of a three-block story is far enough; the second is not.
        assertTrue(NewsRepository.isReadAt(false, 2, 3))
        assertFalse(NewsRepository.isReadAt(false, 1, 3))
    }

    @Test
    fun `pausing on the first sentence does not mark a story read`() {
        assertFalse(NewsRepository.isReadAt(false, 0, 10))
        assertFalse(NewsRepository.isReadAt(false, 0, 0))
    }

    @Test
    fun `an unknown length never counts as far enough`() {
        assertFalse(NewsRepository.isReadAt(false, 5, 0))
    }

    @Test
    fun `a story stays read when listened to again from the middle`() {
        assertTrue(NewsRepository.isReadAt(true, 1, 10))
    }

    // ---- why there is no full text ----

    @Test
    fun `full text that is there has no issue`() {
        assertNull(NewsRepository.fullTextIssue(NewsRepository.MIN_FULL_TEXT, downloadFailed = false))
        assertNull(NewsRepository.fullTextIssue(5_000, downloadFailed = true))
    }

    @Test
    fun `a failed download and a stub page are told apart`() {
        assertEquals(ArticleEntity.ISSUE_DOWNLOAD_FAILED, NewsRepository.fullTextIssue(0, downloadFailed = true))
        assertEquals(ArticleEntity.ISSUE_TOO_SHORT, NewsRepository.fullTextIssue(120, downloadFailed = false))
        assertEquals(ArticleEntity.ISSUE_TOO_SHORT, NewsRepository.fullTextIssue(NewsRepository.MIN_FULL_TEXT - 1, false))
    }

    // ---- refresh results ----

    @Test
    fun `refresh results add up across feeds`() {
        val total = RefreshResult(newStories = 3) +
            RefreshResult(failedFeeds = 1, firstError = "timeout") +
            RefreshResult(newStories = 2, failedFeeds = 1, firstError = "404")
        assertEquals(5, total.newStories)
        assertEquals(2, total.failedFeeds)
        assertEquals("the first failure is the one reported", "timeout", total.firstError)
    }

    @Test
    fun `a refresh says what happened`() {
        assertEquals("No new stories", RefreshResult().describe())
        assertEquals("1 new story", RefreshResult(newStories = 1).describe())
        assertEquals("4 new stories", RefreshResult(newStories = 4).describe())
        assertEquals("Couldn't reach 1 feed", RefreshResult(failedFeeds = 1).describe())
        assertEquals("Couldn't reach 3 feeds", RefreshResult(failedFeeds = 3).describe())
        assertEquals("2 new stories · couldn't reach 1 feed", RefreshResult(2, 1).describe())
    }
}
